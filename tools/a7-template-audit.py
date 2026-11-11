#!/usr/bin/env python3
"""Read pinned vanilla village resources; never opens or changes a world.

Output is review evidence, not a production classifier or template generator.
"""
import collections
import gzip
import hashlib
import io
import json
from pathlib import Path
import struct
import zipfile

JAR = Path('build/moddev/artifacts/neoforge-21.1.252-client-extra-aka-minecraft-resources.jar')
INVENTORY = Path('.codex/A7/architecture/a7-template-inventory.md')
STYLES = ('plains', 'desert', 'savanna', 'snowy', 'taiga')
WORKSTATIONS = {'blast_furnace', 'smoker', 'cartography_table', 'barrel', 'fletching_table',
                'lectern', 'stonecutter', 'loom', 'cauldron', 'water_cauldron', 'brewing_stand', 'smithing_table', 'grindstone'}

class Nbt:
    def __init__(self, data): self.stream = io.BytesIO(gzip.decompress(data))
    def number(self, fmt): return struct.unpack('>' + fmt, self.stream.read(struct.calcsize(fmt)))[0]
    def string(self): return self.stream.read(self.number('H')).decode('utf-8')
    def value(self, tag):
        if tag in (1, 2, 3, 4, 5, 6): return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[tag])
        if tag == 7: return list(self.stream.read(self.number('i')))
        if tag == 8: return self.string()
        if tag == 9:
            child, size = self.number('B'), self.number('i')
            return [self.value(child) for _ in range(size)]
        if tag == 10:
            result = {}
            while (child := self.number('B')):
                name = self.string()
                result[name] = self.value(child)
            return result
        if tag in (11,12): return [self.number('i' if tag == 11 else 'q') for _ in range(self.number('i'))]
        raise ValueError(f'Unsupported NBT tag {tag}')
    def root(self):
        tag = self.number('B')
        self.string()
        return self.value(tag)

def digest(data): return hashlib.sha256(data).hexdigest()

def resource_path(identity, kind, suffix='.json'):
    namespace, name = identity.split(':', 1)
    return f'data/{namespace}/{kind}/{name}{suffix}'

def reachable_graph(jar, style, include, excluded):
    """Follow actual jigsaw pool targets/fallbacks from normal starts, not folders."""
    pending = [f'minecraft:village/{style}/town_centers']
    graph = {'pools': {}, 'templates': {}, 'processors': {}, 'features': {}}
    while pending:
        pool_id = pending.pop()
        if pool_id in graph['pools']: continue
        raw = jar.read(resource_path(pool_id, 'worldgen/template_pool'))
        pool = json.loads(raw)
        graph['pools'][pool_id] = {'sha256': digest(raw), 'fallback': pool['fallback'], 'elements': pool['elements']}
        pending.append(pool['fallback'])
        for entry in pool['elements']:
            element = entry['element']
            kind = element['element_type']
            if kind == 'minecraft:empty_pool_element': continue
            if kind == 'minecraft:feature_pool_element':
                feature = element['feature']
                if not isinstance(feature, str): raise ValueError(f'Inline feature: {element}')
                raw_feature = jar.read(resource_path(feature, 'worldgen/placed_feature'))
                placed = json.loads(raw_feature)
                feature_record = {'placed_sha256': digest(raw_feature), 'configured_feature': placed['feature'],
                                  'category': 'decoration_feature', 'count': 0}
                if isinstance(placed['feature'], str):
                    configured = jar.read(resource_path(placed['feature'], 'worldgen/configured_feature'))
                    feature_record['configured_sha256'] = digest(configured)
                else:
                    feature_record['inline_configured_sha256'] = digest(json.dumps(placed['feature'], sort_keys=True).encode())
                graph['features'][feature] = feature_record
                continue
            if kind not in ('minecraft:single_pool_element', 'minecraft:legacy_single_pool_element'):
                raise ValueError(f'Unreviewed composite/element in {pool_id}: {element}')
            identity = element['location']
            # Abandoned alternatives are fingerprinted in the start manifest but not traversed.
            if '/zombie/' in identity: continue
            processors = element.get('processors')
            if isinstance(processors, str):
                graph['processors'][processors] = digest(jar.read(resource_path(processors, 'worldgen/processor_list')))
            if identity in graph['templates']: continue
            raw_template = jar.read(resource_path(identity, 'structure', '.nbt'))
            nbt = Nbt(raw_template).root()
            blocks = collections.Counter(nbt['palette'][b['state']]['Name'] for b in nbt['blocks'])
            targets = sorted({b['nbt']['pool'] for b in nbt['blocks']
                              if nbt['palette'][b['state']]['Name'] == 'minecraft:jigsaw' and 'pool' in b.get('nbt', {})})
            pending.extend(targets)
            if identity in include: category = 'house_workplace'
            elif identity in excluded: category = 'approved_house_pool_exclusion'
            elif '/town_centers/' in identity: category = 'town_center'
            elif identity == 'minecraft:village/common/well_bottom': category = 'well'
            elif '/streets/' in identity: category = 'road'
            elif '/terminators/' in identity: category = 'terminator'
            elif '/villagers/' in identity or '/animals/' in identity or '/cats/' in identity or identity in ('minecraft:village/common/iron_golem', 'minecraft:village/desert/camel_spawn'): category = 'entity_spawn'
            elif pool_id.endswith('/decor') or '/decor/' in pool_id: category = 'decoration'
            else: raise ValueError(f'Unclassified reachable template {identity} in {pool_id}')
            graph['templates'][identity] = {'sha256': digest(raw_template), 'size': nbt['size'], 'category': category,
                                          'jigsaw_pools': targets, 'count': int(category == 'house_workplace'),
                                          'beds_and_workstations': {k:v for k,v in sorted(blocks.items()) if k.endswith('_bed') or k.removeprefix('minecraft:') in WORKSTATIONS},
                                          'entities': sorted(collections.Counter(e['nbt'].get('id', 'unknown') for e in nbt.get('entities', [])).items())}
    return graph

def main():
    text = INVENTORY.read_text()
    # Existing reviewed identity manifest; explicit exclusions remain explicit.
    include = set()
    section = False
    for line in text.splitlines():
        if line.startswith('### '): section = 'house/workplace' in line.lower()
        if section and line.startswith('- `minecraft:'): include.add(line.split('`')[1])
    result = {'jar': str(JAR), 'jar_sha256': digest(JAR.read_bytes()), 'styles': {}}
    with zipfile.ZipFile(JAR) as jar:
        for style in STYLES:
            style_result = {'buildings': [], 'excluded_house_pool': [], 'starts': [], 'empty_house_entries': 0}
            for pool, target in [('houses','buildings'), ('town_centers','starts')]:
                path = f'data/minecraft/worldgen/template_pool/village/{style}/{pool}.json'
                raw_pool = jar.read(path)
                style_result[pool + '_pool_sha256'] = digest(raw_pool)
                for entry in json.loads(raw_pool)['elements']:
                    element = entry['element']
                    if element['element_type'] == 'minecraft:empty_pool_element':
                        style_result['empty_house_entries'] += pool == 'houses'
                        continue
                    if element['element_type'] not in ('minecraft:single_pool_element','minecraft:legacy_single_pool_element'):
                        raise ValueError(f'Unreviewed element: {element}')
                    identity = element['location']
                    resource = 'data/' + identity.replace(':','/structure/',1) + '.nbt'
                    raw = jar.read(resource)
                    nbt = Nbt(raw).root()
                    states = nbt['palette']
                    blocks = collections.Counter(states[b['state']]['Name'] for b in nbt['blocks'])
                    signals = {k:v for k,v in sorted(blocks.items()) if k.endswith('_bed') or k.removeprefix('minecraft:') in WORKSTATIONS}
                    record = {'id': identity, 'sha256': digest(raw), 'size': nbt['size'],
                              'beds_and_workstations': signals,
                              'door_blocks': sum(v for k,v in blocks.items() if k.endswith('_door')),
                              'entities': sorted(collections.Counter(e['nbt'].get('id','unknown') for e in nbt.get('entities',[])).items())}
                    if pool == 'town_centers':
                        record['abandoned'] = '/zombie/' in identity
                        record['processors'] = element.get('processors')
                        processors = element.get('processors')
                        if isinstance(processors, str):
                            processor_path = 'data/' + processors.replace(':','/worldgen/processor_list/',1) + '.json'
                            record['processor_sha256'] = digest(jar.read(processor_path))
                        style_result['starts'].append(record)
                    elif identity in include: style_result['buildings'].append(record)
                    else: style_result['excluded_house_pool'].append(record)
            style_result['normal_reachable_graph'] = reachable_graph(jar, style, include, {b['id'] for b in style_result['excluded_house_pool']})
            result['styles'][style] = style_result
    buildings = [b for v in result['styles'].values() for b in v['buildings']]
    excluded = [b for v in result['styles'].values() for b in v['excluded_house_pool']]
    if {b['id'] for b in buildings} != include: raise ValueError('Manifest ID missing from pinned house pools')
    if len(buildings) != 123 or not all(b['beds_and_workstations'] for b in buildings):
        raise ValueError('Included content audit failed')
    if len(excluded) != 29 or any(b['beds_and_workstations'] for b in excluded):
        raise ValueError('Excluded content audit failed')
    print(json.dumps(result, indent=2, sort_keys=True))

if __name__ == '__main__': main()
