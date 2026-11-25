#!/usr/bin/env python3
"""Original CC0 mid-century desk models and pixel materials, using project art primitives."""
import copy
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/economy'
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'tools/banking-assets'))
from art import Canvas, output, shade

TEXTURES = ASSETS / 'textures/block/banker_desk'
WOODS = {
    'oak': '#A7804D',
    'jungle': '#A06F50',
    'acacia': '#AC693F',
    'spruce': '#755638',
}
SIDES = ('north', 'south', 'east', 'west', 'up', 'down')


def materials():
    grain_bands = (-12, -8, -2, 1, 2, 4, 5, 7, 7, 5, 4, 3, 1, 0, -2, -4)
    for wood, base in WOODS.items():
        veneer = Canvas(32, 32, base)
        for y in range(32):
            for x in range(32):
                # A repeating cathedral-grain arch with paired early/latewood bands.
                arch = (1 - math.cos(x * math.tau / 32)) * 2
                ring = int((y + arch + math.sin((y + arch) * math.tau / 16)) * 2) % 16
                veneer.pixel(x, y, shade(base, grain_bands[ring]))
        veneer.save(TEXTURES / f'{wood}_veneer.png')

    metal = Canvas(32, 32, '#B7ADA0')
    for y in range(32):
        for x in range(32):
            sheen = (0, 5, 12, 16, 9, 2, -5, -4)[((x - y // 2) % 32) // 4]
            metal.pixel(x, y, shade('#B7ADA0', sheen + (y % 4) * 2 - 3))
    metal.save(TEXTURES / 'metal.png')

    brass = Canvas(16, 16, '#B39A62')
    for y, tone in enumerate((18, 30, 40, 28, 17, 10, 6, 0, -4, -6, -9, -13, -17)):
        brass.rect(0, y, 16, y + 1, shade('#B39A62', tone))
    brass.rect(0, 13, 16, 16, '#806A42')
    brass.save(TEXTURES / 'brass.png')

    hardware = Canvas(32, 32, '#303A3D')
    hardware.rect(1, 1, 31, 31, '#414D50')
    hardware.rect(2, 2, 30, 3, '#596568')
    for y in range(8, 25, 3):
        hardware.rect(8, y, 24, y + 1, '#20292C')
        hardware.rect(9, y + 1, 24, y + 2, '#566164')
    for x in (4, 27):
        for y in (4, 27):
            hardware.rect(x, y, x + 2, y + 2, '#263234')
            hardware.pixel(x, y, '#A5AFAB')
    hardware.rect(4, 28, 10, 30, '#ADAA97')
    hardware.save(TEXTURES / 'hardware.png')

    screen = Canvas(64, 32, '#203C40')
    for y in range(1, 31):
        for x in range(1, 63):
            glow = max(0, 10 - (abs(x - 32) + abs(y - 14)) // 4)
            screen.pixel(x, y, shade('#203C40', glow - (2 if y % 3 == 0 else 0)))
    screen.rect(1, 1, 63, 10, '#42676B')
    screen.lettering('BRANCH', 4, 2, '#E2EADF')
    screen.rect(49, 3, 59, 5, '#95BCAE')
    screen.lettering('ACCOUNTS', 4, 12, '#BBD6CD')
    for y, width in ((20, 27), (25, 20)):
        screen.rect(4, y, 4 + width, y + 2, '#709D98')
        screen.rect(45, y, 59, y + 2, '#ABC8B4')
    screen.rect(1, 30, 63, 31, '#527F7C')
    screen.save(TEXTURES / 'screen.png')

    keyboard = Canvas(64, 32, '#566163')
    keyboard.rect(1, 1, 63, 31, '#727C7C')
    keyboard.rect(3, 3, 61, 29, '#293639')
    for y in (5, 11, 17):
        for x in range(5, 58, 6):
            keyboard.rect(x + 1, y + 1, x + 5, y + 5, '#182527')
            keyboard.rect(x, y, x + 4, y + 4, '#AAB3AA')
            keyboard.pixel(x + 1, y + 2, '#748482')
            keyboard.rect(x, y, x + 4, y + 1, '#D2D4C7')
    keyboard.rect(16, 23, 46, 27, '#B8BDB1')
    keyboard.rect(51, 23, 59, 27, '#78998A')
    keyboard.save(TEXTURES / 'keyboard.png')

    paper = Canvas(64, 64, '#E9DFC4')
    paper.rect(2, 62, 64, 64, '#AD9E81')
    paper.rect(62, 2, 64, 62, '#CABD9F')
    paper.rect(4, 4, 60, 17, '#A8B6A0')
    paper.lettering('LEDGER', 8, 7, '#354B48')
    paper.rect(4, 19, 60, 21, '#9F9C83')
    paper.rect(40, 19, 42, 60, '#BBAE8B')
    for y in range(28, 60, 8):
        paper.rect(4, y, 60, y + 2, '#C2B89B')
        paper.rect(8, y - 4, 24 + y % 9, y - 2, '#6E8278')
        paper.rect(48, y - 4, 56, y - 2, '#6E8278')
    paper.save(TEXTURES / 'paper.png')

    leather = Canvas(32, 32, '#3D584B')
    leather.rect(1, 1, 31, 31, '#65806A')
    leather.rect(2, 2, 30, 30, '#3D584B')
    for y in range(5, 27, 4):
        for x in range(5 + y % 3, 27, 6):
            leather.rect(x, y, x + 2, y + 1, '#425D50')
    for position in range(4, 29, 3):
        leather.pixel(position, 3, '#B7AA7F')
        leather.pixel(position, 28, '#B7AA7F')
    leather.save(TEXTURES / 'leather.png')


def box(low, high, texture, face_textures=None):
    faces = {side: {'texture': '#' + texture, 'uv': [0, 0, 16, 16]} for side in SIDES}
    for side, material in (face_textures or {}).items():
        faces[side]['texture'] = '#' + material
    return {'from': low, 'to': high, 'faces': faces}


def parts(cell):
    if cell < 2:
        elements = [
            box([0, 14, 0], [16, 16, 16], 'wood'),
            box([0, 13.5, .5], [16, 14, 15.5], 'brass'),
            box([0, 4, 12.5], [16, 13.5, 14], 'wood'),
        ]
        if cell == 0:
            # Stepped tapered legs and metal shoes under the open knee bay.
            for z in (2, 12):
                elements.extend([
                    box([1.5, 0, z], [4, 1.5, z + 2], 'metal'),
                    box([1.75, 1.5, z], [3.75, 7, z + 2], 'wood'),
                    box([1.25, 7, z], [4.25, 14, z + 2], 'wood'),
                ])
            elements.append(box([2, 3, 3.5], [3.5, 4.5, 12.5], 'wood'))
        else:
            elements.extend([
                box([3, 1, 4], [14, 13.5, 14], 'wood'),
                box([4, 0, 5], [13, 1, 13], 'metal'),
            ])
            for bottom, top in ((2, 5), (5.4, 8.5), (8.9, 12.5)):
                elements.extend([
                    box([3.5, bottom, 3.6], [13.5, top, 4], 'wood'),
                    box([6, top - 1.1, 3], [11, top - .7, 3.6], 'brass'),
                ])
        return elements
    if cell == 2:
        return [
            box([4, 0, 9], [12, .7, 14], 'metal'),
            box([7, .7, 11], [9, 3.5, 13], 'hardware'),
            box([2, 3, 9], [14, 11, 13.5], 'hardware'),
            box([2, 3, 8.8], [14, 11, 9], 'metal'),
            box([2.5, 3.5, 8.6], [13.5, 10.5, 8.8], 'hardware'),
            box([3, 4, 8.55], [13, 10, 8.6], 'hardware', {'north': 'screen'}),
            box([11.5, 3.2, 8.5], [12.5, 3.5, 8.6], 'brass'),
            box([3, 0, 2], [13, .65, 6.5], 'hardware', {'up': 'keyboard'}),
            box([13.2, 0, 4], [14.5, .7, 6], 'metal'),
        ]
    return [
        box([2, 0, 4], [11.5, .5, 13.5], 'leather'),
        box([2.5, .5, 4.5], [11, 1.1, 13], 'paper'),
        box([2.8, 1.1, 4.7], [10.8, 1.2, 12.8], 'paper'),
        box([2, .5, 4], [2.6, 1.3, 13.5], 'leather'),
        box([12, 0, 10], [14, 3, 12], 'metal'),
        box([12.4, 3, 10.4], [12.8, 6, 10.8], 'hardware'),
        box([13.1, 3, 11], [13.5, 5.5, 11.4], 'brass'),
        box([12, 0, 4], [12.5, .4, 8], 'hardware'),
    ]


def generate():
    materials()
    variants = {}
    for wood in WOODS:
        textures = {material: 'economy:block/banker_desk/' + material
                    for material in ('metal', 'brass', 'hardware', 'screen', 'keyboard', 'paper', 'leather')}
        textures['wood'] = f'economy:block/banker_desk/{wood}_veneer'
        textures['particle'] = textures['wood']
        for cell in range(4):
            output(ASSETS / f'models/block/banker_desk/{wood}_{cell}.json',
                   {'textures': textures, 'elements': parts(cell)})
            for facing, rotation in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
                variants[f'cell={cell},facing={facing},wood={wood}'] = {
                    'model': f'economy:block/banker_desk/{wood}_{cell}', 'y': rotation, 'uvlock': True}
    output(ASSETS / 'blockstates/banker_desk.json', {'variants': variants})
    inventory_parts = []
    for cell in range(4):
        for element in copy.deepcopy(parts(cell)):
            for endpoint in ('from', 'to'):
                element[endpoint][0] += (cell % 2) * 16
                element[endpoint][1] += (cell // 2) * 16
            inventory_parts.append(element)
    output(ASSETS / 'models/item/banker_desk.json', {
        'textures': textures | {'wood': 'economy:block/banker_desk/oak_veneer',
                                'particle': 'economy:block/banker_desk/oak_veneer'},
        'elements': inventory_parts,
        'display': {
            # Center the rotated two-wide model about the inventory slot, with padding.
            'gui': {'rotation': [25, 225, 0], 'translation': [2.26, -1.25, 0], 'scale': [.4, .4, .4]},
            'ground': {'translation': [0, 2, 0], 'scale': [.25, .25, .25]},
            'fixed': {'scale': [.5, .5, .5]},
        },
    })
    for cell in range(4):
        for element in parts(cell):
            assert all(0 <= element['from'][axis] < element['to'][axis] <= 16 for axis in range(3))
    assert len(variants) == 64
    print('Generated refined desk models and 11 original material textures across four wood palettes.')


if __name__ == '__main__':
    generate()
