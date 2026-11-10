"""Runner contract checks; use temporary fixtures and fake Gradle, never Minecraft."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import ci_inventory as inventory

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


live = load('live_runner', 'run-live-probe.py')
coverage = load('coverage_verifier', 'verify-coverage-inputs.py')


@contextlib.contextmanager
def workspace():
    with tempfile.TemporaryDirectory(prefix='economy-ci-contract-') as directory:
        original = Path.cwd()
        os.chdir(directory)
        try:
            yield Path(directory)
        finally:
            os.chdir(original)


def fake_gradle(root):
    binary = root / 'gradlew'
    binary.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args = sys.argv[1:]
with open(os.environ['FAKE_ARGS'], 'a') as out:
    out.write(json.dumps(args) + '\\n')
if 'runClient' in args:
    root = pathlib.Path(next(a.split('=', 1)[1] for a in args if a.startswith('-PeconomyRunRoot=')))
    client = root / 'client'
    client.mkdir(exist_ok=True)
    if '-Pbad' in args:
        sys.exit(1)
    marker = 'second' if '-Prestart' in args else 'first'
    if '-Prestart' in args and not (client / 'first-passed.txt').exists():
        sys.exit(2)
    (client / (marker + '-passed.txt')).write_text('passed')
''')
    binary.chmod(0o755)
    xvfb = root / 'xvfb-run'
    xvfb.write_text('#!/usr/bin/env bash\nshift 3\nexec "$@"\n')
    xvfb.chmod(0o755)
    return {'FAKE_ARGS': str(root / 'calls.jsonl'), 'PATH': str(root) + os.pathsep + os.environ['PATH']}


class RunnerContracts(unittest.TestCase):
    def test_inventory_covers_current_tests_and_every_probe_once(self):
        inventory.validate_probes()
        classes = inventory.junit_classes()
        self.assertIn('ca.emilybelnavis.mcmods.economy.api.CurrencyConversionTest', classes)
        self.assertIn('ca.emilybelnavis.mcmods.economy.credit.CardServicingTest', classes)
        for items in (classes, inventory.PROBES):
            shards = [inventory.assigned(items, n) for n in range(inventory.NODES)]
            flattened = [item for shard in shards for item in shard]
            self.assertEqual(len(items), len(flattened))
            self.assertEqual(set(items), set(flattened))
        self.assertEqual(3, sum(p.name.startswith('layout-') for p in inventory.PROBES))

    def test_nonstandard_annotated_test_names_are_discovered(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / 'Unusual.java'
            source.write_text('package fixture;\nclass Unusual {\n @Test void checks() {}\n}')
            (Path(root) / 'Helper.java').write_text('class Helper {}')
            self.assertEqual(('fixture.Unusual',), inventory.junit_classes(Path(root)))

    def test_new_autonomous_probe_requires_explicit_assignment(self):
        with tempfile.TemporaryDirectory() as root:
            build = Path(root) / 'build.gradle'
            build.write_text("if (providers.gradleProperty('newProbe').isPresent()) {\n"
                             "tasks.named('runClient') { systemProperty 'economy.newProbe', 'true' }\n}")
            with self.assertRaisesRegex(ValueError, 'newProbe'):
                inventory.validate_probes(build)

    def test_batch_never_runs_an_unfiltered_suite_and_forces_assigned_tests(self):
        with workspace() as root:
            with patch.dict(os.environ, fake_gradle(root)):
                script = str(HERE / 'run-test-batch.sh')
                subprocess.run(['bash', script, 'junit'], check=True)
                self.assertFalse((root / 'calls.jsonl').exists())
                subprocess.run(['bash', script, 'junit', 'fixture.One', 'fixture.Two'], check=True)
                args = json.loads((root / 'calls.jsonl').read_text())
                self.assertIn('--rerun', args)
                self.assertEqual(['fixture.One', 'fixture.Two'],
                                 [args[n + 1] for n, arg in enumerate(args) if arg == '--tests'])

    def test_live_runner_continues_other_scenarios_after_failure(self):
        with workspace() as root:
            (root / 'build.gradle').write_text('')
            bad = inventory.single('bad', 'economy-ci-bad-', 'first', name='bad')
            good = inventory.single('good', 'economy-ci-good-', 'first', name='good')
            probes = (bad, good, good, good, good)
            with patch.dict(os.environ, fake_gradle(root)), patch.object(live, 'PROBES', probes), \
                    patch.object(sys, 'argv', ['runner', '--node', '0']), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, live.main())
            calls = [json.loads(line) for line in (root / 'calls.jsonl').read_text().splitlines()]
            self.assertEqual(2, len(calls))
            for args in calls:
                temporary = Path(next(a.split('=', 1)[1] for a in args if a.startswith('-PeconomyRunRoot=')))
                self.assertFalse(temporary.exists())
            report = ET.parse(root / 'build/circleci/junit/TEST-minecraft-live-good-1.xml').getroot()
            self.assertEqual('0', report.get('failures'))

    def test_restart_phases_keep_the_same_disposable_world(self):
        with workspace() as root:
            run_root = root / 'run'
            run_root.mkdir()
            phases = (inventory.Phase('first', ('good',)), inventory.Phase('second', ('good', 'restart')))
            probe = inventory.Probe('restart', 'economy-ci-', phases)
            with patch.dict(os.environ, fake_gradle(root)), contextlib.redirect_stdout(io.StringIO()):
                for n, phase in enumerate(phases, 1):
                    self.assertEqual(0, live.run_phase(probe, phase, n, run_root, 10))
            self.assertTrue((run_root / 'client/first-passed.txt').exists())
            self.assertTrue((run_root / 'client/second-passed.txt').exists())
            self.assertTrue((root / 'build/circleci/live/restart/phase-2/second-passed.txt').exists())

    def test_coverage_gate_rejects_a_missing_client_phase(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = inventory.junit_classes()
            for n in range(inventory.NODES):
                node = root / f'node-{n}'
                (node / 'junit').mkdir(parents=True)
                (node / 'all-tests.txt').write_text('\n'.join(classes))
                selected = inventory.assigned(classes, n)
                (node / 'selected-tests.txt').write_text('\n'.join(selected))
                (node / 'all-probes.txt').write_text('\n'.join(p.name for p in inventory.PROBES))
                probes = inventory.assigned(inventory.PROBES, n)
                (node / 'selected-probes.txt').write_text('\n'.join(p.name for p in probes))
                for phase in ('setup', 'junit', 'live'):
                    (node / f'{phase}.status').write_text('0')
                for name in selected:
                    suite = ET.Element('testsuite', name=name, tests='1')
                    ET.ElementTree(suite).write(node / 'junit' / (name + '.xml'))
                (node / 'batches').mkdir()
                (node / 'batches/jacoco.exec').write_bytes(b'fixture')
                for probe in probes:
                    for phase in range(1, len(probe.phases) + 1):
                        data = node / 'live' / probe.name / f'phase-{phase}' / 'jacoco.exec'
                        data.parent.mkdir(parents=True)
                        data.write_bytes(b'fixture')
                        live.junit(node / 'junit' / f'{probe.name}-{phase}.xml',
                                   f'{probe.name}/phase-{phase}', 1, '')
            with contextlib.redirect_stdout(io.StringIO()):
                coverage.verify(root)
            missing = next((root / 'node-0/live').glob('**/jacoco.exec'))
            missing.unlink()
            with self.assertRaisesRegex(ValueError, 'client execution data'):
                coverage.verify(root)


if __name__ == '__main__':
    unittest.main()
