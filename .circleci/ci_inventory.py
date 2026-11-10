#!/usr/bin/env python3
"""Shared, deterministic inventory for CircleCI assignments and coverage validation."""
from dataclasses import dataclass
from pathlib import Path
import re
import sys

NODES = 4


@dataclass(frozen=True)
class Phase:
    marker: str
    properties: tuple[str, ...]


@dataclass(frozen=True)
class Probe:
    name: str
    prefix: str
    phases: tuple[Phase, ...]


def single(prop, prefix, marker, *extra, name=None):
    return Probe(name or marker, prefix, (Phase(marker, (prop, *extra)),))


# Only autonomous integrated clients. Every phase must stop itself and write a pass marker.
PROBES = (
    single('bankingDashboardProbe', 'economy-a10-02-01-', 'banking-dashboard'),
    single('bankingHistoryProbe', 'economy-a10-02-02-', 'banking-history'),
    single('traderHookProbe', 'economy-a8-04-01-', 'trader-hook'),
    single('traderCustodyProbe', 'economy-a8-04-02-', 'trader-custody'),
    single('kioskUpgradeProbe', 'economy-a7-03-', 'kiosk-upgrade'),
    single('bankLocateProbe', 'economy-a7-04-', 'bank-locate'),
    single('villageGenerationProbe', 'economy-a7-04-', 'village-generation'),
    single('bankStructureProbe', 'economy-a7-04-', 'bank-structure'),
    single('bankerProbe', 'economy-a7-05-', 'banker'),
    Probe('village-bank-acceptance', 'economy-a7-06-', (
        Phase('village-bank-acceptance-phase1', ('villageBankAcceptance',)),
        Phase('village-bank-acceptance', ('villageBankAcceptance', 'villageBankAcceptanceRestart')),
    )),
    single('simulationProductionProbe', 'economy-a8-07-', 'simulation-production'),
    single('valuationProbe', 'economy-a9-02-', 'valuation'),
    single('recipeProbe', 'economy-a9-03-01-', 'recipes'),
    single('brewingProbe', 'economy-a9-03-02-', 'brewing'),
    single('variantProbe', 'economy-a9-03-03-', 'variants'),
    single('valuationApiProbe', 'economy-a9-04-', 'valuation-api'),
    single('referenceTraderProbe', 'economy-a9-06-', 'reference-trader'),
    single('layoutProbe', 'economy-a10-03-', 'layout', name='layout-base'),
    single('layoutProbe', 'economy-a10-03-', 'layout', 'layoutSupplement', name='layout-supplement'),
    single('layoutProbe', 'economy-a10-03-', 'layout', 'layoutTerminalCurrent', name='layout-current'),
    single('debtCompositeProbe', 'economy-a10-05-', 'debt-composite'),
    single('adminSettingsProbe', 'economy-a10-07-', 'admin-settings'),
)

# These client flags reopen server-prepared saves, rather than autonomous client fixtures.
PREPARED_CLIENTS = {'bankingProbe', 'bankingWorld', 'financeWorld'}


def assigned(items, node):
    if node not in range(NODES):
        raise ValueError('node must be 0..3')
    return tuple(items[node::NODES])


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


def validate_probes(build=Path('build.gradle')):
    """Fail when a new autonomous runClient fixture is added without a CI assignment."""
    source = build.read_text()
    discovered = set()
    pattern = r"if \(providers\.gradleProperty\('([^']+)'\)\.isPresent\(\)\) \{"
    for match in re.finditer(pattern, source):
        depth, end = 1, match.end()
        while depth and end < len(source):
            depth += (source[end] == '{') - (source[end] == '}')
            end += 1
        block = source[match.end():end]
        if "'runClient'" in block:
            discovered.add(match.group(1))
    configured = {p.phases[0].properties[0] for p in PROBES}
    missing = discovered - configured - PREPARED_CLIENTS
    if missing:
        raise ValueError('Automated client probes missing CI assignments: ' + ', '.join(sorted(missing)))
    if len({p.name for p in PROBES}) != len(PROBES):
        raise ValueError('Duplicate client scenario names')


if __name__ == '__main__':
    if sys.argv[1:] == ['junit']:
        print('\n'.join(junit_classes()))
    elif sys.argv[1:] == ['live']:
        validate_probes()
        print('\n'.join(p.name for p in PROBES))
    else:
        sys.exit('Usage: ci_inventory.py junit|live')
