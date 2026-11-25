#!/usr/bin/env python3
"""Original CC0 64px banker skin with 64-unit humanoid UV coordinates."""
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
sys.path.insert(0, str(ROOT / 'tools/banking-assets'))
from art import Canvas, shade

SCALE = 1
skin = Canvas(64, 64)
SUIT = '#596452'
TROUSERS = '#414C40'
SKIN = '#BC8B6B'
HAIR = '#483C34'
SHIRT = '#ECE6D3'


def rect(x, y, width, height, pigment):
    left, top = round(x * SCALE), round(y * SCALE)
    right = max(left + 1, round((x + width) * SCALE))
    bottom = max(top + 1, round((y + height) * SCALE))
    skin.rect(left, top, right, bottom, pigment)


def cloth(x, y, width, height, base):
    for py in range(y * SCALE, (y + height) * SCALE):
        for px in range(x * SCALE, (x + width) * SCALE):
            # The paired diagonals read as a few woven clusters at skin scale.
            warp = (px - x) % 6
            stitch = ((py - y) + min(warp, 5 - warp)) % 5
            skin.pixel(px, py, shade(base, 7 if stitch == 0 else -3 if stitch == 1 else 0))


def face():
    rect(0, 0, 32, 16, SKIN)
    # Broad color patches keep the face readable at Minecraft's native skin scale.
    rect(8, 0, 8, 8, HAIR)
    rect(24, 8, 8, 8, HAIR)
    rect(0, 8, 8, 6, HAIR)
    rect(16, 8, 8, 6, HAIR)
    rect(6, 9, 1, 3, '#939080')
    rect(17, 9, 1, 3, '#939080')
    rect(7, 11, 1, 3, SKIN)
    rect(16, 11, 1, 3, SKIN)
    rect(8, 8, 8, 1, HAIR)
    rect(8, 9, 1, 2, HAIR)
    rect(15, 9, 1, 2, HAIR)
    for eye, pupil in ((9, 10), (13, 13)):
        rect(eye, 10, 2, 1, '#514739')
        rect(eye, 11, 2, 1, '#DBD9C7')
        rect(pupil, 11, 1, 1, '#425B43')
    rect(11, 14, 2, 1, '#8B604A')
    rect(9, 15, 6, 1, '#AE7C5D')
    # Close-fitting hair and a compact cuboid nose use separate UV islands.
    rect(32, 0, 32, 16, HAIR)
    rect(40, 0, 1, 8, '#5D4D3D')
    rect(0, 48, 8, 6, SKIN)
    rect(0, 48, 8, 1, '#C69370')
    rect(1, 50, 3, 1, '#AC795B')


def jacket():
    cloth(16, 16, 24, 16, SUIT)
    rect(20, 20, 8, 1, '#6D7662')
    rect(22, 20, 4, 7.5, SHIRT)
    rect(22, 20, .5, 6, '#C8C3AF')
    rect(25.5, 20, .5, 6, '#FAF3DE')
    rect(23.5, 21, 1, 5, '#74383D')
    rect(23.5, 21.5, .5, 4, '#96565A')
    # Lapels, collar, badge and pocket square are painted onto the torso.
    rect(21, 21, 1, 3, '#78816B')
    rect(22, 24, 1, 2, '#78816B')
    rect(26, 21, 1, 3, '#78816B')
    rect(25, 24, 1, 2, '#78816B')
    rect(22, 20, 1, 1, '#FFF7E3')
    rect(25, 20, 1, 1, '#FFF7E3')
    rect(20, 24, 2, 1, '#D6C596')
    rect(26, 23, 1, 1, SHIRT)
    rect(26, 24, 2, 1, '#394735')
    rect(20.5, 25.5, 1.5, .5, '#343F34')
    rect(26, 25.5, 1.5, .5, '#343F34')
    rect(20.5, 26, 1.5, .5, '#77816B')
    rect(26, 26, 1.5, .5, '#77816B')
    rect(23.5, 26, .5, 6, '#3E4B3C')
    rect(23.5, 27.5, .5, .5, '#D1BF8C')
    rect(23.5, 29.5, .5, .5, '#D1BF8C')
    rect(20, 31, 8, 1, '#414D3D')
    # Shoulder seams, tailored side seams and a center back vent.
    for x in (16, 28):
        rect(x, 20, .5, 12, '#444F40')
    rect(32, 20, 8, .5, '#6D7761')
    rect(35.5, 21, .5, 11, '#46513F')
    rect(36, 29, .5, 3, '#75806B')
    rect(32, 31, 8, 1, '#414D3D')
    # A shallow stepped shoulder yoke and split tails on the rear panel.
    rect(32, 21, 2, 1, '#69735F')
    rect(34, 22, 4, 1, '#646F5B')
    rect(38, 21, 2, 1, '#69735F')
    rect(34, 30, 1, 2, '#46513F')


def limbs():
    for x, y in ((40, 16), (32, 48)):
        cloth(x, y, 16, 16, SUIT)
        rect(x + 4, y + 4, 4, 1, '#727B67')
        rect(x + 12, y + 7, 2, 1, '#4C5748')
        rect(x + 13, y + 8, 2, 1, '#65705D')
        # Wrapped shirt cuffs and hands on every side of both arms.
        rect(x, y + 11, 16, 1, '#394735')
        rect(x, y + 12, 16, 1, SHIRT)
        rect(x, y + 13, 16, 3, SKIN)
        rect(x + 4, y + 15, 4, 1, '#AB795B')
        rect(x + 8, y, 4, 4, SKIN)
        rect(x + 4, y + 10, 1, .5, '#B9A778')
        rect(x + 6, y + 10, 1, .5, '#B9A778')
    for x, y in ((0, 16), (16, 48)):
        cloth(x, y, 16, 16, TROUSERS)
        rect(x, y + 4, 16, 1, '#373F35')
        rect(x + 5, y + 5, 1, 6, '#54604F')
        rect(x + 6, y + 6, 1, 7, '#384334')
        rect(x + 4, y + 11, 2, 1, '#4D5848')
        rect(x, y + 13, 16, 2, '#49392E')
        rect(x, y + 15, 16, 1, '#282925')
        rect(x + 8, y, 4, 4, '#282925')
        rect(x + 4.5, y + 13.5, 3, .5, '#7C6147')


def preview():
    sheet = Canvas(288, 180, '#E7E4D8')
    sheet.text('BANKER', 12, 8, '#29362E', 2)
    for index, back in enumerate((False, True)):
        figure = Canvas(16, 32)
        # Orthographic UV swatches in model proportions.
        arm_offset = 12 if back else 4
        figure.blit(skin.crop(24 if back else 8, 8, 8, 8), 4, 0)
        figure.blit(skin.crop(32 if back else 20, 20, 8, 12), 4, 8)
        left = 0 if back else 12
        figure.blit(skin.crop(32 + arm_offset, 52, 4, 12), left, 8)
        figure.blit(skin.crop(40 + arm_offset, 20, 4, 12), 12 - left, 8)
        figure.blit(skin.crop(16 + arm_offset, 52, 4, 12), 4 if back else 8, 20)
        figure.blit(skin.crop(arm_offset, 20, 4, 12), 8 if back else 4, 20)
        sheet.blit(figure, 16 + 78 * index, 26, 64, 128)
        sheet.text('BACK' if back else 'FRONT', 36 + 78 * index, 162, '#29362E')
    sheet.blit(skin, 196, 56)
    sheet.text('64 X 64 UV', 208, 132, '#29362E')
    sheet.save(Path(__file__).with_name('banker_views.png'))


face()
jacket()
limbs()
target = ROOT / 'src/main/resources/assets/economy/textures/entity/banker.png'
skin.save(target)
preview()
print(f'Generated {target.name}, 64x64 with blocky facial features and tailored clothing.')
