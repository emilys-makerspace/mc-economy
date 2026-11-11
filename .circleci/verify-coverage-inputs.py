#!/usr/bin/env python3
"""Reject a partial or duplicated four-node suite before publishing aggregate coverage."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

from ci_inventory import NODES, junit_classes


def verify(root: Path) -> None:
    expected = set(junit_classes())
    assignments = Counter()
    for index in range(NODES):
        node = root / f"node-{index}"
        discovery = set((node / "all-tests.txt").read_text().splitlines())
        selected = (node / "selected-tests.txt").read_text().splitlines()
        if not discovery or any(not name.strip() for name in selected):
            raise ValueError(f"Invalid test discovery or assignment: {node}")
        if discovery != expected:
            raise ValueError(f"Nodes discovered different suites: {node}")
        assignments.update(selected)
        if (node / 'junit.status').read_text().strip() != '0':
            raise ValueError(f'Failed JUnit phase: {node}')
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
        if results != set(selected):
            raise ValueError(f"Assigned classes are missing JUnit results: {node}")
        files = list((node / 'batches').glob('**/jacoco.exec'))
        if not files or any(path.stat().st_size == 0 for path in files):
            raise ValueError(f'Missing or empty JUnit execution data: {node}')
    if set(assignments) != expected or any(count != 1 for count in assignments.values()):
        raise ValueError("Full suite assignments must contain each discovered class exactly once")
    print(f'Verified all {len(expected)} JUnit classes across {NODES} nodes')


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        sys.exit(f"Incomplete aggregate coverage: {error}")
