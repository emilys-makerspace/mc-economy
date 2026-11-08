#!/usr/bin/env python3
"""Reject a partial or duplicated four-node suite before publishing aggregate coverage."""
from collections import Counter
from pathlib import Path
import sys
import xml.etree.ElementTree as ET


def verify(root: Path) -> None:
    expected = None
    assigned = Counter()
    for index in range(4):
        node = root / f"node-{index}"
        discovery = set((node / "all-tests.txt").read_text().splitlines())
        selected = (node / "selected-tests.txt").read_text().splitlines()
        if not discovery or any(not name.strip() for name in selected):
            raise ValueError(f"Invalid test discovery or assignment: {node}")
        if expected is None:
            expected = discovery
        elif discovery != expected:
            raise ValueError(f"Nodes discovered different suites: {node}")
        assigned.update(selected)
        results = set()
        for report in (node / "junit").glob("*.xml"):
            suite = ET.parse(report).getroot()
            if suite.tag == "testsuite" and suite.get("name") in discovery:
                results.add(suite.get("name"))
                if int(suite.get("skipped", "0")):
                    raise ValueError(f"Suite contains skipped tests: {report}")
        if results != set(selected):
            raise ValueError(f"Assigned classes are missing JUnit results: {node}")
        for kind in ("batches", "live"):
            files = list((node / kind).glob("**/jacoco.exec"))
            if not files or any(path.stat().st_size == 0 for path in files):
                raise ValueError(f"Missing or empty {kind} execution data: {node}")
    if set(assigned) != expected or any(count != 1 for count in assigned.values()):
        raise ValueError("Full suite assignments must contain each discovered class exactly once")
    print(f"Verified all {len(expected)} test classes across four nodes, with JUnit and live coverage on each")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]))
    except (OSError, ValueError, IndexError, ET.ParseError) as error:
        sys.exit(f"Incomplete aggregate coverage: {error}")
