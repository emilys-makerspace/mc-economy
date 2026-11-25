"""Original CC0 bank hardware surfaces and future hardware model resources.

Run through the asset package generator; no runtime registrations are created.
"""
import copy
import json
from pathlib import Path
from art import ASSETS, BANKS, Canvas, INK, PAPER, color, contrast, lettering_width, mark, output, shade

HERE = Path(__file__).resolve().parent
KINDS = ('atm', 'teller', 'kiosk')
# Cell order follows the actual existing blockstates, not old tooling comments.
SOURCES = {
    'atm': ('atm_right', 'atm', 'atm_upper_right', 'atm_upper'),
    'teller': ('bank_teller_station_right', 'bank_teller_station',
               'bank_teller_station_upper_right', 'bank_teller_station_upper'),
}


def enamel(width, height, base):
    """Broad stepped bands give lacquer a directional reflection at game scale."""
    c = Canvas(width, height)
    for y in range(height):
        for x in range(width):
            band = (x * 8 // width + y * 5 // height)
            lift = (2, 4, 7, 12, 18, 14, 8, 4, 0, -3, -6, -9, -10)[band]
            c.pixel(x, y, shade(base, lift))
    c.rect(0, 0, width, 1, shade(base, 38))
    c.rect(0, 1, 1, height - 1, shade(base, 19))
    c.rect(width - 1, 1, width, height, shade(base, -20))
    c.rect(0, height - 1, width, height, shade(base, -36))
    return c


def surface(bank, kind):
    w = 64
    primary, accent = bank['primary'], bank['accent']
    fg = contrast(primary)
    c = enamel(w, 64, primary)
    c.rect(2, 2, w - 2, 22, shade(primary, -22))
    c.rect(3, 3, w - 3, 21, shade(primary, -10))
    # Twin inlaid rules flank the issuer crest.
    center = w // 2
    for y in (10, 13):
        c.rect(4, y, center - 9, y + 1, accent)
        c.rect(center + 9, y, w - 4, y + 1, accent)
    c.blit(mark(bank['key'], accent), center - 8, 4)
    for i, line in enumerate(bank['label']):
        c.lettering(line, (w - lettering_width(line)) // 2, 24 + i * 8, fg)
    c.rect(2, 41, w - 2, 54, shade(primary, -38))
    c.rect(3, 42, w - 3, 52, accent)
    c.rect(4, 42, w - 4, 43, shade(accent, 30))
    c.lettering(bank['currency'], (w - lettering_width(bank['currency'])) // 2, 44, contrast(accent))
    caption = {'atm': 'ATM', 'teller': 'TELLER', 'kiosk': 'INFO'}[kind]
    c.lettering(caption, (w - lettering_width(caption)) // 2, 55, fg)
    return c


def material(bank, role):
    primary, accent = bank['primary'], bank['accent']
    base = {'shell': primary, 'trim': accent, 'metal': '#AAB3B2',
            'dark': shade(primary, -35), 'keypad': '#34434A',
            'slot': '#38464B', 'vent': shade(primary, -12)}[role]
    c = enamel(32, 32, base)
    if role == 'metal':
        for x, y, length in ((3, 5, 11), (14, 8, 14), (1, 16, 12), (12, 23, 15), (6, 27, 9)):
            c.rect(x, y, x + length, y + 1, shade(base, 16))
    elif role == 'shell':
        c.rect(2, 27, 30, 28, shade(base, -18))
        c.rect(2, 28, 30, 29, shade(base, 7))
        for x in (3, 27):
            c.rect(x, 3, x + 2, 5, shade(base, -26))
            c.pixel(x, 3, shade(base, 41))
    elif role == 'keypad':
        for row in range(4):
            for col in range(3):
                x, y = 3 + 9 * col, 2 + 7 * row
                cap = '#5B9583' if (row, col) == (3, 2) else '#B2BBB6'
                c.rect(x, y, x + 7, y + 6, '#18272C')
                c.rect(x, y, x + 6, y + 5, cap)
                c.rect(x, y, x + 6, y + 1, shade(cap, 32))
                c.rect(x + 2, y + 2, x + 4, y + 3, '#536364')
    elif role == 'slot':
        c.rect(2, 9, 30, 22, '#263438')
        c.rect(3, 10, 29, 12, '#92A7A3')
        c.rect(4, 12, 28, 18, '#0D181C')
        c.rect(4, 18, 28, 20, '#566A66')
        c.rect(24, 6, 28, 7, '#8AD4AD')
    elif role == 'vent':
        for y in range(4, 29, 5):
            c.rect(3, y, 29, y + 1, shade(base, 27))
            c.rect(4, y + 1, 28, y + 3, shade(base, -46))
    elif role == 'trim':
        c.rect(0, 6, 32, 8, shade(base, 24))
        c.rect(0, 24, 32, 26, shade(base, -14))
    return c


DYE_TRIM = {
    'black': '#202329', 'blue': '#3550AD', 'brown': '#7B553A', 'cyan': '#22929F',
    'gray': '#474D50', 'green': '#49793B', 'light_blue': '#5DB0DA',
    'light_gray': '#9DA4A5', 'lime': '#87C035', 'magenta': '#B945BE',
    'orange': '#EA7824', 'pink': '#E389A8', 'purple': '#7945B0', 'red': '#B73733',
    'white': '#E5E8E7', 'yellow': '#F3CD3A'
}


def generic_surfaces():
    directory = ASSETS / 'textures/block'
    bank = {'primary': '#313A41', 'accent': '#257D79'}
    for role in ('shell', 'dark', 'metal', 'keypad', 'trim', 'slot', 'vent'):
        material(bank, role).save(directory / f'terminal_{role}.png')
    for dye, accent in DYE_TRIM.items():
        material(dict(bank, accent=accent), 'trim').save(directory / f'terminal_trim_{dye}.png')

    glass = Canvas(32, 32)
    # A cutout pane reveals the banker and monitor behind the taller counter.
    glass.rect(0, 0, 32, 1, '#71959A')
    glass.rect(0, 31, 32, 32, '#476F78')
    glass.line(3, 9, 8, 4, '#ABC9C8')
    glass.line(7, 9, 10, 6, '#739CA3')
    glass.line(24, 26, 27, 23, '#739CA3')
    glass.save(directory / 'teller_glass.png')

    screen = enamel(32, 32, '#123B43')
    screen.rect(2, 3, 30, 12, '#235958')
    screen.lettering('BANK', (32 - lettering_width('BANK')) // 2, 4, '#D5EDDA')
    for y, length in ((14, 13), (18, 19), (22, 10)):
        screen.rect(5, y, 7, y + 2, '#6FD1AF')
        screen.rect(10, y, 10 + length, y + 1, '#519E92')
    screen.rect(4, 27, 28, 28, '#34746E')
    screen.save(directory / 'terminal_screen.png')

    chart = enamel(32, 32, '#182E38')
    for i in range(6, 30, 6):
        chart.rect(i, 3, i + 1, 29, '#28404B')
        chart.rect(3, i, 29, i + 1, '#28404B')
    for x, top, bottom, rising in ((5, 18, 26, True), (10, 13, 23, True),
                                    (15, 11, 21, False), (20, 8, 19, True), (25, 4, 13, True)):
        ink = '#79B99B' if rising else '#CB8275'
        chart.rect(x, top, x + 1, bottom, ink)
        chart.rect(x - 1, top + 2, x + 2, bottom - 2, ink)
    chart.save(directory / 'trading_chart.png')
    return glass


def hardware_svg(art, path):
    """Keep editable master swatches compact by coalescing solid pixel spans."""
    lines = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {art.w} {art.h}" shape-rendering="crispEdges">']
    for y in range(art.h):
        x = 0
        while x < art.w:
            value = art.p[y * art.w + x]
            end = x + 1
            while end < art.w and art.p[y * art.w + end] == value:
                end += 1
            fill = bytes(value[:3]).hex()
            lines.append(f'<rect x="{x}" y="{y}" width="{end-x}" height="1" fill="#{fill}"/>')
            x = end
    path.write_text('\n'.join(lines + ['</svg>']) + '\n')


def box(bounds, texture, uv=None):
    return {'from': bounds[:3], 'to': bounds[3:], 'faces': {
        side: {'uv': uv or [0,0,16,16], 'texture': '#'+texture}
        for side in ('north','south','east','west','up','down')}}


def kiosk_cell(upper):
    # A slender information totem: pedestal, rear spine, angled-width display bezel.
    if upper:
        elements = [box([4,0,7,12,16,11],'shell'),
                    box([2,2,3,14,15,8],'trim'),
                    box([3,3,2.8,13,14,3],'front', [0,0,16,16])]
    else:
        elements = [box([2,0,3,14,2,13],'metal'),
                    box([5,2,7,11,16,11],'shell'),
                    box([4,13,6,12,16,12],'trim')]
    return {'parent':'minecraft:block/block', 'elements':elements}


def generate(textures_only=False):
    glass = generic_surfaces()
    records = []
    preview = Canvas(23*76, 208, '#D8D4C9')
    for index, bank in enumerate(BANKS):
        record = {'key':bank['key'], 'currency':bank['currency']}
        for ki, kind in enumerate(KINDS):
            stem = bank['key']+'_'+kind
            front = surface(bank,kind)
            textures = {}
            for role in ('front','shell','trim','metal','dark','keypad','slot','vent'):
                art = front if role=='front' else material(bank,role)
                path = ASSETS/'textures/block/banking'/f'{stem}_{role}.png'
                art.save(path)
                hardware_svg(art, HERE/'sources/hardware'/f'{stem}_{role}.svg')
                textures[role] = 'economy:block/banking/'+stem+'_'+role
            if not textures_only:
                # Existing screen/glass UV splits retain their exact original orientation.
                textures.update(screen=textures['front'],glass=textures['front'],
                                chart=textures['front'],particle=textures['shell'])
                if kind == 'teller':
                    textures.update(glass='economy:block/teller_glass', chart='economy:block/trading_chart')
                cells = []
                all_elements = []
                for cell in range(2 if kind=='kiosk' else 4):
                    model = (kiosk_cell(cell==1) if kind=='kiosk' else
                             json.loads((ASSETS/'models/block'/f'{SOURCES[kind][cell]}.json').read_text()))
                    model['textures'] = textures
                    model['render_type'] = 'minecraft:cutout'
                    model_id = 'economy:block/banking/'+stem+'_'+str(cell)
                    output(ASSETS/'models/block/banking'/f'{stem}_{cell}.json',model)
                    cells.append(model_id)
                    for element in copy.deepcopy(model['elements']):
                        dx = 0 if kind=='kiosk' else (16 if cell%2==0 else 0)
                        dy = cell*16 if kind=='kiosk' else (16 if cell>=2 else 0)
                        for end in ('from','to'):
                            element[end][0] += dx
                            element[end][1] += dy
                        all_elements.append(element)
                inventory = {'parent':'minecraft:block/block','textures':textures,
                    'render_type':'minecraft:cutout','elements':all_elements,
                    'display': {'gui':{'rotation':[25,225,0], 'translation':[0,-2,0],
                                        'scale':[0.35,0.35,0.35]},
                                'ground':{'translation':[0,3,0],'scale':[0.2,0.2,0.2]},
                                'fixed':{'scale':[0.4,0.4,0.4]}}}
                inv_id = 'economy:block/banking/'+stem+'_inventory'
                output(ASSETS/'models/block/banking'/f'{stem}_inventory.json',inventory)
                output(ASSETS/'models/item/banking'/f'{stem}.json',{'parent':inv_id})
                record[kind] = {'textures': textures, 'models':cells,
                    'inventory_model':inv_id, 'item_model':'economy:item/banking/'+stem,
                    'cells':len(cells), 'facing':'north-front; rotate all cells together with horizontal facing',
                    'geometry_sources': list(SOURCES[kind]) if kind!='kiosk' else ['original slender information totem'],
                    'placement_notes': ('lower anchor and upper satellite; one wide, two high, one deep' if kind=='kiosk'
                        else 'existing detailed 2x2 geometry; cell order right, base, upper_right, upper; preserve existing support and single-drop semantics'),
                    'label':bank['label'], 'display_currency':bank['currency']}
            # Contact sheet conveys intended distinct silhouettes at small pixel scale.
            x=index*76+6; y=ki*68+2
            shell, trim = material(bank, 'shell'), material(bank, 'trim')
            if kind == 'kiosk':
                preview.blit(shell, x + 21, y + 40, 14, 21)
                preview.blit(material(bank, 'metal'), x + 13, y + 59, 30, 4)
                preview.blit(front, x + 14, y, 28, 48)
            elif kind == 'atm':
                preview.blit(shell, x + 2, y + 2, 56, 62)
                preview.blit(front, x + 9, y + 5, 42, 42)
                preview.blit(material(bank, 'keypad'), x + 9, y + 47, 18, 11)
                preview.blit(material(bank, 'slot'), x + 29, y + 49, 22, 7)
            else:
                preview.blit(material(bank, 'dark'), x, y + 40, 60, 24)
                preview.blit(trim, x, y + 37, 60, 4)
                preview.blit(glass, x + 2, y + 1, 56, 23)
                preview.blit(glass, x + 2, y + 24, 15, 13)
                preview.blit(glass, x + 43, y + 24, 15, 13)
                preview.blit(front, x + 38, y + 19, 17, 18)
        records.append(record)
    preview.save(HERE/'previews/hardware-contact.png')
    return {'banks':records}

if __name__ == '__main__':
    generate()
