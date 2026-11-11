#!/usr/bin/env python3
"""Generate A7-03's original wide kiosk console and explicit legacy blockstates.

Wide console models, original console textures, header paths, item parents and
explicit blockstate variants are written.
Existing narrow models and existing raster surfaces are never changed. Standard
library plus the project's original CC0 pixel primitives; no external art/fonts.
"""
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/economy'
SOURCE = ROOT / 'tools/banking-assets/sources/hardware/wide-kiosk'
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'tools/banking-assets'))
from art import BANKS, Canvas, INK, PAPER, contrast, mark, shade

FACINGS = {'north': 0, 'east': 90, 'south': 180, 'west': 270}
# Match existing world geometry: NORTH cell1 is WEST of its anchor. The model's
# full console therefore has cell0 on the right, then cell1 on the left.
CELL_ORIGINS = ((16, 0), (0, 0), (16, 16), (0, 16))
SIDES = ('north', 'south', 'east', 'west', 'up', 'down')
# Full console coordinates (32 wide x32 high); all Z extents remain inside1..15.
# North front: substantial side cheeks, recessed foot and solid rear assembly,
# waist-height projecting counter, continuous display and institution header.
ELEMENTS = [
    # Recessed steel foot, powder-coated cabinet and walnut side cheeks.
    ([2, 0, 5], [30, 1, 14], 'dark'),
    ([2, 1, 4], [30, 2, 14], 'metal'),
    ([3, 2, 6], [29, 12, 14], 'shell'),
    ([2, 2, 12], [30, 30, 14.8], 'shell'),
    ([0, 2, 3], [2, 30, 15], 'wood'),
    ([30, 2, 3], [32, 30, 15], 'wood'),
    ([1.6, 2, 2.8], [2, 30, 3], 'metal'),
    ([30, 2, 2.8], [30.4, 30, 3], 'metal'),
    # Cash tray with a dark throat and a raised protective lip.
    ([4, 6.5, 5.3], [19, 10.5, 6.1], 'metal'),
    ([5, 7.4, 5.1], [18, 9.6, 5.35], 'cash'),
    ([5, 6.7, 3.5], [18, 7.2, 5.4], 'metal'),
    ([5, 7.2, 3.5], [18, 7.6, 3.9], 'trim'),
    ([21, 3.5, 5.8], [28, 10.5, 6.1], 'vent'),
    # Floating cream worktop with a bright metal edge and branded fascia.
    ([1, 12, 1], [31, 13, 14], 'metal'),
    ([1.5, 13, 1.5], [30.5, 14, 13.5], 'ivory'),
    ([2, 11, 2], [30, 12, 3], 'trim'),
    ([4, 14, 2.5], [15, 14.3, 8.5], 'dark'),
    # Card reader housing, recessed slot and small status indicator.
    ([20, 14, 4], [28, 15.5, 9], 'metal'),
    ([20.5, 14.1, 3.7], [27.5, 15.1, 4], 'reader'),
    # Align the physical throat with the printed slot, clear of the CARD label.
    ([21.2, 14.65, 3.5], [25, 14.85, 3.75], 'dark'),
    ([20.55, 14.4, 3.5], [21, 14.9, 3.75], 'indicator'),
    # Recess the entire monitor behind the worktop controls (which end at Z=9).
    # The lower bezel must clear the keypad too, not just the display surface.
    ([2, 16, 11.8], [30, 27, 12], 'shell'),
    ([3, 17, 10], [29, 19, 11.8], 'bezel'),
    ([3, 19, 10], [4, 25, 11.8], 'bezel'),
    ([28, 19, 10], [29, 25, 11.8], 'bezel'),
    ([3, 25, 10], [29, 26, 11.8], 'bezel'),
    ([4, 19, 10.7], [28, 25, 10.9], 'screen'),
    ([24, 17.7, 9.7], [26, 18.2, 10], 'metal'),
    ([26.7, 17.7, 9.7], [27.2, 18.2, 10], 'indicator'),
    # Horizontal bank nameplate and slender chrome crown.
    ([2, 27, 9.5], [30, 31.5, 12], 'trim'),
    ([3, 27.5, 9.3], [29, 31.5, 9.5], 'header'),
    ([2, 31.5, 9], [30, 32, 12.5], 'metal'),
    # Rear service door, frame, hinges and a separate lock escutcheon.
    ([4, 4, 14.8], [28, 27, 14.85], 'dark'),
    ([4.5, 4.5, 14.85], [27.5, 26.5, 14.9], 'service'),
    ([4.1, 7, 14.9], [5.1, 10, 15], 'metal'),
    ([4.1, 21, 14.9], [5.1, 24, 15], 'metal'),
    ([25, 14, 14.9], [26.5, 16, 15], 'lock'),
]
# Raised keys remain separate geometry, including the green enter/red cancel row.
for row in range(4):
    for column in range(3):
        label = str(row * 3 + column + 1) if row < 3 else ('cancel', '0', 'enter')[column]
        # North-facing users see +X on their left and +Z away from them.
        # Put 1..3 at the back and cancel/0/enter along the near edge.
        x, z = 11.2 - column * 3.2, 6.75 - row * 1.25
        ELEMENTS.append(([x, 14.3, z], [x + 2.5, 14.7, z + 0.95], 'key_' + label))
# Physical louvers finish both the lower front and rear exhaust.
for row in range(4):
    y = 4.5 + row * 1.25
    ELEMENTS.append(([22, y, 5.5], [27, y + 0.35, 5.85], 'metal'))
    ELEMENTS.append(([8, 6 + row * 1.2, 14.9], [23, 6.3 + row * 1.2, 15], 'dark'))

DISPLAY = {
    'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [0.78, 0.78, 0.78]},
    'ground': {'translation': [0, 3, 0], 'scale': [0.25, 0.25, 0.25]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [0.5, 0.5, 0.5]},
    'thirdperson_righthand': {'rotation': [75, 45, 0], 'translation': [0, 2.5, 0], 'scale': [0.375, 0.375, 0.375]},
    'firstperson_righthand': {'rotation': [0, 45, 0], 'translation': [0, 0, 0], 'scale': [0.4, 0.4, 0.4]},
}


def output(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def header(bank=None):
    background = bank['primary'] if bank else '#263B50'
    accent = bank['accent'] if bank else '#77C9B1'
    c = Canvas(64, 16, background)
    c.rect(0, 0, 64, 1, accent)
    c.rect(0, 15, 64, 16, shade(background, -30))
    if bank:
        c.blit(mark(bank['key'], accent), 1, 2, 12, 12)
        fg = contrast(background)
        for i, line in enumerate(bank['label']):
            assert len(line) <= 8, (bank['key'], line)
            c.text(line, 16, 2 + i * 7, fg)
        c.rect(49, 2, 50, 14, accent)
        c.text(bank['currency'], 51, 9, fg)
    else:
        c.text('ECONOMY', 5, 2, PAPER)
        c.text('KIOSK', 5, 9, PAPER)
        c.text('INFO', 43, 9, accent)
    return c


def clipped_box(start, end, texture, cell=None):
    """Clip a global cuboid to one cell and preserve continuous front/back UVs."""
    low, high = list(start), list(end)
    dx = dy = 0
    if cell is not None:
        dx, dy = CELL_ORIGINS[cell]
        low[0], high[0] = max(low[0], dx), min(high[0], dx + 16)
        low[1], high[1] = max(low[1], dy), min(high[1], dy + 16)
        if any(low[i] >= high[i] for i in range(3)):
            return None
    width, height, depth = [end[i] - start[i] for i in range(3)]
    u = [(low[0] - start[0]) / width * 16, (high[0] - start[0]) / width * 16]
    v = [(end[1] - high[1]) / height * 16, (end[1] - low[1]) / height * 16]
    z = [(low[2] - start[2]) / depth * 16, (high[2] - start[2]) / depth * 16]
    faces = {}
    for side in SIDES:
        # Do not emit artificial internal cut faces between adjacent cell models.
        axis, endpoint = {'west': (0, 0), 'east': (0, 1), 'down': (1, 0),
                          'up': (1, 1), 'north': (2, 0), 'south': (2, 1)}[side]
        if (low if endpoint == 0 else high)[axis] != (start if endpoint == 0 else end)[axis]:
            continue
        if side == 'north': uv = [16 - u[1], v[0], 16 - u[0], v[1]]
        elif side == 'south': uv = [u[0], v[0], u[1], v[1]]
        elif side == 'east': uv = [16 - z[1], v[0], 16 - z[0], v[1]]
        elif side == 'west': uv = [z[0], v[0], z[1], v[1]]
        else: uv = [u[0], z[0], u[1], z[1]]
        faces[side] = {'texture': '#' + texture, 'uv': uv}
        if texture == 'reader' and side != 'north':
            # The label and slot belong only on the front fascia.
            faces[side]['texture'] = '#metal'
        elif texture.startswith('key_'):
            if side == 'up':
                faces[side]['rotation'] = 180
            else:
                # Sample the key's plain rim rather than repeating its glyph.
                faces[side]['uv'] = [2, 2, 3, 3]
    return {'from': [low[0] - dx, low[1] - dy, low[2]],
            'to': [high[0] - dx, high[1] - dy, high[2]], 'faces': faces}


def console_surfaces():
    """Original pixel surfaces. Drawn with the project's CC0 Canvas primitives."""
    directory = ASSETS / 'textures/block/banking/console'
    surfaces = {}
    metal = Canvas(64, 64, '#AAB4B3')
    for y in range(64):
        metal.rect(0, y, 64, y + 1, shade('#AAB4B3', (y % 4) * 3 - 5))
    metal.rect(0, 0, 64, 2, '#E3E8DC')
    metal.rect(0, 62, 64, 64, '#62736F')
    surfaces['metal'] = metal
    ivory = Canvas(64, 64, '#DCD9C7')
    for y in range(0, 64, 8):
        for x in range(y % 3, 64, 11):
            ivory.pixel(x, y, '#C4C6B5')
    ivory.rect(0, 0, 64, 1, '#F4EEDB')
    ivory.rect(0, 63, 64, 64, '#A0A595')
    surfaces['ivory'] = ivory
    wood = Canvas(64, 64, '#775138')
    for x in range(64):
        for y in range(64):
            grain = ((x * 17 + y // 16) % 13) - 6
            wood.pixel(x, y, shade('#775138', grain * 2))
    for x in (3, 30, 59):
        wood.rect(x, 0, x + 1, 64, '#513C2D')
        wood.rect(x + 1, 0, x + 2, 64, '#95704A')
    surfaces['wood'] = wood
    bezel = Canvas(64, 64, '#273C3B')
    bezel.rect(0, 0, 64, 2, '#627975')
    bezel.rect(0, 2, 2, 64, '#425B58')
    bezel.rect(0, 62, 64, 64, '#142727')
    surfaces['bezel'] = bezel
    surfaces['dark'] = Canvas(16, 16, '#17282B')
    screen = Canvas(128, 32, '#183B36')
    for y in range(2, 31, 3):
        screen.rect(2, y, 126, y + 1, '#1D453E')
    screen.rect(1, 1, 127, 2, '#678F7D')
    screen.rect(2, 2, 3, 30, '#416C5D')
    screen.text('BANKING', 8, 5, '#D5E5B2')
    screen.text('SELECT SERVICE', 8, 16, '#8DBFA6')
    for x in (85, 103):
        screen.rect(x, 5, x + 13, 11, '#376757')
        screen.rect(x + 2, 7, x + 10, 8, '#A6CBB0')
        screen.rect(x, 15, x + 13, 21, '#376757')
        screen.rect(x + 2, 17, x + 10, 18, '#A6CBB0')
    screen.rect(8, 26, 119, 27, '#436F5A')
    surfaces['screen'] = screen
    cash = Canvas(64, 16, '#142324')
    cash.rect(2, 2, 62, 3, '#687A71')
    cash.rect(4, 5, 60, 11, '#080F12')
    cash.rect(4, 11, 60, 13, '#425C51')
    surfaces['cash'] = cash
    reader = Canvas(64, 16, '#50645F')
    reader.text('CARD', 3, 3, '#E1E4CE')
    reader.rect(23, 4, 58, 7, '#132626')
    reader.rect(23, 8, 58, 9, '#A4B8A4')
    surfaces['reader'] = reader
    vent = Canvas(32, 32, '#34453E')
    for y in range(3, 30, 5):
        vent.rect(3, y, 29, y + 2, '#102423')
        vent.rect(3, y + 2, 29, y + 3, '#687A63')
    surfaces['vent'] = vent
    service = Canvas(64, 64, '#516359')
    service.rect(1, 1, 63, 2, '#90A18A')
    service.rect(1, 2, 2, 63, '#768C75')
    service.rect(2, 62, 63, 63, '#2E433A')
    for x in (4, 59):
        for y in (4, 59):
            service.rect(x - 1, y - 1, x + 2, y + 2, '#A3ADA0')
            service.rect(x - 1, y, x + 2, y + 1, '#304339')
    service.rect(12, 10, 52, 22, '#273F38')
    service.text('SERVICE', 18, 13, '#BAC7AD')
    for y in range(37, 55, 4):
        service.rect(10, y, 54, y + 1, '#263C34')
        service.rect(10, y + 1, 54, y + 2, '#7F927A')
    surfaces['service'] = service
    lock = Canvas(16, 16, '#B5BAA7')
    lock.rect(6, 3, 10, 10, '#233730')
    lock.rect(7, 9, 9, 13, '#233730')
    surfaces['lock'] = lock
    indicator = Canvas(16, 16, '#6FBB95')
    indicator.rect(2, 2, 14, 5, '#C4ECD1')
    indicator.rect(0, 14, 16, 16, '#2D7155')
    surfaces['indicator'] = indicator
    for label in [str(n) for n in range(10)] + ['cancel', 'enter']:
        background = '#B5BEA8' if label.isdigit() else '#9A6056' if label == 'cancel' else '#63896B'
        key = Canvas(16, 16, background)
        key.rect(0, 0, 16, 2, shade(background, 25))
        key.rect(0, 2, 2, 14, shade(background, 12))
        key.rect(0, 14, 16, 16, shade(background, -35))
        key.rect(14, 2, 16, 14, shade(background, -20))
        key.text(label if label.isdigit() else 'X' if label == 'cancel' else 'O', 6, 5, '#20332D')
        surfaces['key_' + label] = key
    for name, art in surfaces.items():
        art.save(directory / (name + '.png'))
    return {name: 'economy:block/banking/console/' + name for name in surfaces}


def material_map(shared, bank=None):
    textures = dict(shared)
    primary = bank['primary'] if bank else '#263B50'
    accent = bank['accent'] if bank else '#77C9B1'
    stem = bank['key'] if bank else 'generic'
    shell = Canvas(64, 64, primary)
    # Enamel seams, subtle vertical changes and screw heads finish every cabinet face.
    for x in range(0, 64, 8):
        shell.rect(x, 0, x + 1, 64, shade(primary, 5))
    for y in (2, 61):
        shell.rect(2, y, 62, y + 1, shade(primary, -22))
    for x in (3, 60):
        for y in (4, 59):
            shell.pixel(x, y, shade(primary, 55))
            shell.pixel(x + 1, y, shade(primary, -35))
    trim = Canvas(64, 16, accent)
    trim.rect(0, 0, 64, 2, shade(accent, 26))
    trim.rect(0, 13, 64, 16, shade(accent, -32))
    directory = ASSETS / 'textures/block/banking/console'
    shell.save(directory / (stem + '_shell.png'))
    trim.save(directory / (stem + '_trim.png'))
    textures['shell'] = 'economy:block/banking/console/' + stem + '_shell'
    textures['trim'] = 'economy:block/banking/console/' + stem + '_trim'
    textures['particle'] = textures['shell']
    textures['header'] = ('economy:block/banking/' + bank['key'] + '_kiosk_wide_header'
                          if bank else 'economy:block/terminal_wide_kiosk_header')
    return textures


def generate():
    shared = console_surfaces()
    # Geometry-only sources are separate from every legacy narrow model.
    for cell in range(4):
        elements = [part for start, end, texture in ELEMENTS
                    if (part := clipped_box(start, end, texture, cell)) is not None]
        model = {'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout', 'elements': elements}
        output(ASSETS / f'models/block/branch_kiosk_wide_shape_{cell}.json', model)
    output(SOURCE / 'geometry.json', {'version': 2, 'license': 'CC0-1.0',
            'cell_origins': CELL_ORIGINS, 'elements': ELEMENTS,
            'notes': 'Original mid-century 2x2 console with walnut cheeks, enamel cabinet, recessed monitor, raised keys, card reader, cash tray and rear service door. North front, cell0 right and cell1 left.'})
    preview = Canvas(6 * 104, 4 * 116, '#D8D4C9')
    for appearance_index, bank in enumerate([None] + BANKS):
        stem = bank['key'] + '_kiosk_wide' if bank else 'branch_kiosk_wide'
        prefix = 'banking/' if bank else ''
        header_art = header(bank)
        header_art.save(ASSETS / ('textures/block/banking/' + bank['key'] + '_kiosk_wide_header.png'
                                  if bank else 'textures/block/terminal_wide_kiosk_header.png'))
        header_art.svg(SOURCE / f'{stem}_header.svg')
        px, py = (appearance_index % 6) * 104 + 4, (appearance_index // 6) * 116 + 4
        primary = bank['primary'] if bank else '#263B50'
        accent = bank['accent'] if bank else '#77C9B1'
        preview.rect(px + 6, py + 92, px + 90, py + 98, '#A7ADB3')
        preview.rect(px, py + 8, px + 6, py + 92, accent)
        preview.rect(px + 90, py + 8, px + 96, py + 92, accent)
        preview.rect(px + 6, py + 8, px + 90, py + 92, primary)
        preview.rect(px + 6, py + 22, px + 90, py + 46, INK)
        preview.rect(px + 9, py + 25, px + 87, py + 43, '#427C7B')
        preview.rect(px + 2, py + 48, px + 94, py + 58, '#A7ADB3')
        preview.rect(px + 2, py + 48, px + 94, py + 52, accent)
        preview.blit(header_art, px + 6, py + 8, 84, 14)
        preview.text(bank['currency'] if bank else 'INFO', px + 4, py + 104, INK)
        textures = material_map(shared, bank)
        for cell in range(4):
            output(ASSETS / f'models/block/{prefix}{stem}_{cell}.json', {
                'parent': f'economy:block/branch_kiosk_wide_shape_{cell}', 'textures': textures,
                'render_type': 'minecraft:cutout'})
        inventory_elements = [clipped_box(*element) for element in ELEMENTS]
        for element in inventory_elements:
            for bound in ('from', 'to'):
                element[bound] = [value / 2 for value in element[bound]]
        inventory = {'parent': 'minecraft:block/block', 'render_type': 'minecraft:cutout',
                     'textures': textures, 'elements': inventory_elements, 'display': DISPLAY}
        # Inventory model bounds must remain within the documented -16..32 format.
        output(ASSETS / f'models/block/{prefix}{stem}_inventory.json', inventory)
        if bank:
            output(ASSETS / f'models/item/banking/{bank["key"]}_kiosk.json', {
                'parent': f'economy:block/banking/{stem}_inventory'})
    preview.save(ROOT / 'tools/banking-assets/previews/wide-kiosk-fronts.png')
    variants = {}
    for index, bank in enumerate([None] + BANKS):
        old = ('banking/' + bank['key'] + '_kiosk') if bank else 'branch_kiosk'
        wide = ('banking/' + bank['key'] + '_kiosk_wide') if bank else 'branch_kiosk_wide'
        for cell in range(4):
            for facing, rotation in FACINGS.items():
                for expanded in (False, True):
                    # Invalid narrow2/3 are visibly safe legacy models; Java rejects
                    # their integrity rather than silently expanding their geometry.
                    model = wide + '_' + str(cell) if expanded else old + '_' + str(min(cell, 1))
                    variants[f'bank={index},cell={cell},facing={facing},wide={str(expanded).lower()}'] = {
                        'model': 'economy:block/' + model, 'y': rotation}
    output(ASSETS / 'blockstates/branch_kiosk.json', {'variants': variants})
    item_path = ASSETS / 'models/item/branch_kiosk.json'
    item = json.loads(item_path.read_text())
    item['parent'] = 'economy:block/branch_kiosk_wide_inventory'
    output(item_path, item)
    validate()
    print('Generated 24 original wide kiosk appearances, 96 cell models, 24 inventory models, 24 headers and 768 explicit blockstate variants; legacy narrow models unchanged.')


def validate():
    variants = json.loads((ASSETS / 'blockstates/branch_kiosk.json').read_text())['variants']
    assert len(variants) == 24 * 4 * 4 * 2
    model_paths = list((ASSETS / 'models/block').glob('branch_kiosk_wide*.json'))
    model_paths += list((ASSETS / 'models/block/banking').glob('*_kiosk_wide*.json'))
    for path in model_paths:
        model = json.loads(path.read_text())
        parent = model.get('parent', '')
        if parent.startswith('economy:'):
            assert (ASSETS / 'models' / (parent.split(':', 1)[1] + '.json')).exists(), (path, parent)
        for texture in model.get('textures', {}).values():
            if texture.startswith('economy:'):
                assert (ASSETS / 'textures' / (texture.split(':', 1)[1] + '.png')).exists(), (path, texture)
        for element in model.get('elements', []):
            assert all(-16 <= number <= 32 for key in ('from', 'to') for number in element[key]), path
            assert all(element['from'][i] < element['to'][i] for i in range(3)), path
            if path.name.startswith('branch_kiosk_wide_shape_'):
                assert all(0 <= element[bound][axis] <= 16
                           for bound in ('from', 'to') for axis in (0, 1)), path
                assert 1 <= element['from'][2] < element['to'][2] <= 15, path
            for face in element['faces'].values():
                assert all(0 <= n <= 16 for n in face['uv']), path
    required_materials = {texture for _, _, texture in ELEMENTS}
    for path in model_paths:
        model = json.loads(path.read_text())
        if 'textures' in model:
            assert required_materials <= model['textures'].keys(), path
    for variant in variants.values():
        assert (ASSETS / 'models' / (variant['model'].split(':', 1)[1] + '.json')).exists()


if __name__ == '__main__':
    generate()
