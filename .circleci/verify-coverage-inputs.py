#!/usr/bin/env python3
"""Reject a partial or duplicated four-node suite before publishing aggregate coverage."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

from ci_inventory import NODES, PROBES, assigned, junit_classes, validate_probes


def verify(root: Path) -> None:
    validate_probes()
    expected = set(junit_classes())
    assignments = Counter()
    probe_assignments = Counter()
    all_probes = {p.name for p in PROBES}
    for index in range(NODES):
        node = root / f"node-{index}"
        discovery = set((node / "all-tests.txt").read_text().splitlines())
        selected = (node / "selected-tests.txt").read_text().splitlines()
        if not discovery or any(not name.strip() for name in selected):
            raise ValueError(f"Invalid test discovery or assignment: {node}")
        if discovery != expected:
            raise ValueError(f"Nodes discovered different suites: {node}")
        assignments.update(selected)
        for phase in ('setup', 'junit', 'live'):
            if (node / f'{phase}.status').read_text().strip() != '0':
                raise ValueError(f'Failed {phase} phase: {node}')
        discovered_probes = set((node / 'all-probes.txt').read_text().splitlines())
        selected_probes = (node / 'selected-probes.txt').read_text().splitlines()
        expected_probes = assigned(PROBES, index)
        if discovered_probes != all_probes or selected_probes != [p.name for p in expected_probes]:
            raise ValueError(f'Incomplete client probe assignment: {node}')
        probe_assignments.update(selected_probes)
        live_results = Counter()
        results = set()
        for report in (node / "junit").glob("*.xml"):
            suite = ET.parse(report).getroot()
            if suite.tag != 'testsuite':
                continue
            if any(int(suite.get(field, '0')) for field in ('failures', 'errors', 'skipped')):
                raise ValueError(f'Failed or skipped tests: {report}')
            class_name = suite.get('name', '').split('$')[0]
            if class_name in discovery and int(suite.get('tests', '0')) > 0:
                results.add(class_name)
            if suite.get('name') == 'minecraft.live':
                live_results.update(case.get('name') for case in suite.findall('testcase'))
        if results != set(selected):
            raise ValueError(f"Assigned classes are missing JUnit results: {node}")
        files = list((node / 'batches').glob('**/jacoco.exec'))
        if not files or any(path.stat().st_size == 0 for path in files):
            raise ValueError(f'Missing or empty JUnit execution data: {node}')
        expected_live = set()
        for probe in expected_probes:
            for phase in range(1, len(probe.phases) + 1):
                expected_live.add(f'{probe.name}/phase-{phase}')
                data = node / 'live' / probe.name / f'phase-{phase}' / 'jacoco.exec'
                if not data.is_file() or data.stat().st_size == 0:
                    raise ValueError(f'Missing or empty client execution data: {data}')
        if set(live_results) != expected_live or any(n != 1 for n in live_results.values()):
            raise ValueError(f'Missing or duplicated client phase JUnit results: {node}')
    if set(assignments) != expected or any(count != 1 for count in assignments.values()):
        raise ValueError("Full suite assignments must contain each discovered class exactly once")
    if set(probe_assignments) != all_probes or any(n != 1 for n in probe_assignments.values()):
        raise ValueError('Each automated client scenario must run exactly once')
    print(f'Verified all {len(expected)} JUnit classes and {len(PROBES)} client scenarios across four nodes')


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        sys.exit(f"Incomplete aggregate coverage: {error}")
