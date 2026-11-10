#!/usr/bin/env python3
"""Shared, deterministic inventory for CircleCI assignments and coverage validation."""
from pathlib import Path
import re
import sys

NODES = 4


def junit_classes(root=Path('src/test/java')):
    """Include annotated tests regardless of file suffix, plus inherited conventional tests."""
    result = []
    annotation = re.compile(r'^\s*@(?:org\.junit\.[\w.]+\.)?'
                            r'(?:Test|TestFactory|TestTemplate|ParameterizedTest|RepeatedTest)\b', re.M)
    for path in sorted(root.rglob('*.java')):
        source = re.sub(r'/\*.*?\*/|//[^\n]*', '', path.read_text(), flags=re.S)
        if not annotation.search(source) and not re.search(r'(?:Test|Tests|IT)$', path.stem):
            continue
        if re.search(r'\babstract\s+class\s+' + re.escape(path.stem) + r'\b', source):
            continue
        package = re.search(r'^\s*package\s+([\w.]+)\s*;', source, re.M)
        result.append((package.group(1) + '.' if package else '') + path.stem)
    if not result or len(result) != len(set(result)):
        raise ValueError('Empty or duplicate JUnit discovery')
    return tuple(sorted(result))

if __name__ == '__main__':
    if sys.argv[1:] != ['junit']:
        sys.exit('Usage: ci_inventory.py junit')
    print('\n'.join(junit_classes()))
