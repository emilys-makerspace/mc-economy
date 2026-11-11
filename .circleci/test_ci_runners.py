"""Runner contract checks; use temporary fixtures and fake Gradle, never Minecraft."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
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
''')
    binary.chmod(0o755)
    return {'FAKE_ARGS': str(root / 'calls.jsonl')}


class RunnerContracts(unittest.TestCase):
    def test_inventory_covers_current_junit_tests(self):
        classes = inventory.junit_classes()
        self.assertIn('ca.emilybelnavis.mcmods.economy.api.CurrencyConversionTest', classes)
        self.assertIn('ca.emilybelnavis.mcmods.economy.credit.CardServicingTest', classes)

    def test_nonstandard_annotated_test_names_are_discovered(self):
        with tempfile.TemporaryDirectory() as root:
            source = Path(root) / 'Unusual.java'
            source.write_text('package fixture;\nclass Unusual {\n @Test void checks() {}\n}')
            (Path(root) / 'Helper.java').write_text('class Helper {}')
            self.assertEqual(('fixture.Unusual',), inventory.junit_classes(Path(root)))

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

    def test_coverage_gate_rejects_missing_junit_execution_data(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = inventory.junit_classes()
            for n in range(inventory.NODES):
                node = root / f'node-{n}'
                (node / 'junit').mkdir(parents=True)
                (node / 'all-tests.txt').write_text('\n'.join(classes))
                selected = classes[n::inventory.NODES]
                (node / 'selected-tests.txt').write_text('\n'.join(selected))
                (node / 'junit.status').write_text('0')
                for name in selected:
                    suite = ET.Element('testsuite', name=name, tests='1')
                    ET.ElementTree(suite).write(node / 'junit' / (name + '.xml'))
                (node / 'batches').mkdir()
                (node / 'batches/jacoco.exec').write_bytes(b'fixture')
            with contextlib.redirect_stdout(io.StringIO()):
                coverage.verify(root)
            (root / 'node-0/batches/jacoco.exec').unlink()
            with self.assertRaisesRegex(ValueError, 'JUnit execution data'):
                coverage.verify(root)


if __name__ == '__main__':
    unittest.main()
