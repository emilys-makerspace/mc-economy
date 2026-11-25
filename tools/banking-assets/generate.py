#!/usr/bin/env python3
"""Deterministic original A6-05 art. No financial IDs, entities or hardware are activated.
SPDX-License-Identifier: CC0-1.0
"""
from pathlib import Path
import hashlib, json
from art import ASSETS, ROOT, BANKS, NETWORKS, Canvas, PAPER, INK, color, contrast, lettering_width, mark, output, shade
HERE=Path(__file__).resolve().parent

def key_path(kind,key):return ASSETS/kind/(key+'.png')
def tier_badge(tier):
    p=Canvas(16,16);p.rect(0,1,16,15,PAPER);p.rect(1,2,15,14,INK)
    if tier==2:
        p.rect(4,4,6,12,PAPER);p.rect(10,4,12,12,PAPER)
    else:p.rect(7,4,9,12,PAPER)
    return p

NETWORK_PALETTES = {n['key']: (color(n['primary']), color(n['accent'])) for n in NETWORKS}


def face_panel(x, y, network):
    """Positive distances are inside the product's large coloured panel."""
    if network == 'orbit':
        return 34 - int(((x - 58) ** 2 + (y - 36) ** 2) ** 0.5)
    if network == 'keystone':
        return x - (41 - 6 * ((y - 12) // 12))
    if network == 'wayline':
        return x - (49 - (y - 12) * 3 // 4)
    if network == 'ember':
        return 31 - abs(x - 56) - abs(y - 33) // 2
    return y - 40  # Debit signature band


def card(bank, product, network, corporate=False):
    p = Canvas(64, 64)
    primary, accent = color(bank['primary']), color(bank['accent'])
    base, trim = (accent, primary) if corporate else (primary, accent)
    panel, foil = NETWORK_PALETTES[network] if network else (trim, base)
    if network and corporate:
        panel, foil = foil, panel

    # Cut corners, a polished upper edge and a shadow along the lower lip.
    for y in range(12, 52):
        inset = max(0, 3 - min(y - 12, 51 - y))
        for x in range(2 + inset, 62 - inset):
            depth = face_panel(x, y, network)
            field = panel if depth >= 0 else base
            tone = (3 - (x + 2 * (y - 12)) // 17) * 3
            if depth in (0, 1):
                pigment = foil if network else trim
            elif network and depth in (4, 9):
                pigment = shade(panel, 18)
            else:
                pigment = shade(field, tone)
            if y == 12 or x == 2 + inset:
                pigment = shade(field, 45)
            elif y >= 50 or x == 61 - inset:
                pigment = shade(field, -38)
            p.pixel(x, y, pigment)

    # Issuer lockup and a segmented contact chip occupy the quiet left field.
    label_width = max(map(lettering_width, bank['label']))
    p.rect(4, 13, 6 + label_width, 29, base)
    for i, label in enumerate(bank['label'][:2]):
        p.lettering(label, 5, 14 + 8 * i, contrast(base))
    if corporate:
        p.rect(52, 14, 61, 25, base)
        p.blit(mark(bank['key'], trim), 52, 15, 9, 9)
    else:
        p.blit(mark(bank['key'], trim), 19, 29, 12, 12)
    p.rect(7, 31, 18, 38, '#74583F')
    p.rect(7, 30, 17, 37, '#E1BC76')
    p.rect(8, 30, 16, 31, '#FFDEA0')
    for x in (10, 14):
        p.rect(x, 31, x + 1, 37, '#947447')
    p.rect(7, 33, 11, 34, '#947447')
    p.rect(14, 33, 17, 34, '#947447')

    # A solid nameplate keeps the tiny letters clear of the engraved pattern.
    label = 'DEBIT' if product == 'debit' else 'CREDIT'
    p.rect(5, 41, 8 + lettering_width(label), 50, trim)
    p.lettering(label, 7, 42, contrast(trim))
    if network:
        p.blit(mark(network, foil), 45, 28, 15, 15)
    currency = bank['currency']
    p.lettering(currency, 60 - lettering_width(currency), 43, contrast(panel))
    if corporate:
        p.rect(19, 30, 44, 40, INK)
        p.lettering('CORP', 20, 32, PAPER)
    return p

def write_icon_sources():
    for name in ['accounts','debit','credit','loan','replace','deposit','withdraw','fx','history','tier2','collections','branch']:
        p=Canvas(16,16);c=PAPER
        if name=='accounts':
            p.rect(2,4,14,14,c);p.rect(4,6,12,12,INK);p.rect(4,1,12,3,c)
        elif name in ('debit','credit'):
            p.rect(1,3,15,13,c);p.rect(2,4,14,12,INK)
            if name=='debit':p.rect(3,6,7,10,c);p.rect(9,8,13,10,c)
            else:p.polygon([(3,5),(8,5),(11,8),(8,11),(3,11)],c)
        elif name=='loan':
            p.polygon([(2,7),(8,1),(14,7)],c);p.rect(3,8,5,13,c);p.rect(7,8,9,13,c);p.rect(11,8,13,13,c);p.rect(2,14,14,16,c)
        elif name=='replace':
            p.line(2,4,12,4,c,2);p.line(12,4,12,8,c,2);p.polygon([(9,6),(15,6),(12,10)],c)
            p.line(13,12,3,12,c,2);p.line(3,12,3,8,c,2);p.polygon([(0,9),(6,9),(3,5)],c)
        elif name in ('deposit','withdraw'):
            p.rect(2,11,14,15,c);p.rect(4,12,12,14,INK)
            if name=='deposit':p.rect(7,1,9,8,c);p.polygon([(3,5),(13,5),(8,10)],c)
            else:p.rect(7,4,9,10,c);p.polygon([(3,5),(13,5),(8,0)],c)
        elif name=='fx':
            p.line(1,5,13,5,c,2);p.polygon([(10,1),(15,6),(10,9)],c);p.line(3,11,14,11,c,2);p.polygon([(5,7),(0,12),(5,15)],c)
        elif name=='history':
            p.rect(2,1,14,15,c);p.rect(4,3,12,13,INK)
            for y in (4,7,10):p.rect(5,y,11,y+1,c)
        elif name=='tier2':p=tier_badge(2)
        elif name=='collections':
            for x,y in [(1,10),(6,6),(11,2)]:p.rect(x,y,x+4,y+4,c)
            p.line(2,14,14,14,c)
        else:
            p.rect(4,1,12,15,c);p.rect(5,3,11,8,INK);p.rect(6,10,10,12,INK);p.rect(2,14,14,16,c)
        p.svg(HERE/'sources/icons'/f'{name}.svg')

def cards_and_marks():
    cards=[];issuers=[];lang={}
    row_height = 84
    label_width = 150
    product_width = 184
    columns = 1 + max(len(bank['networks']) for bank in BANKS)
    sheet = Canvas(label_width + columns * product_width, len(BANKS) * row_height, '#192631')
    for idx,bank in enumerate(BANKS):
        key=bank['key'];primary=color(bank['primary']);accent=color(bank['accent'])
        logo=mark(key,accent);logo.save(key_path('textures','gui/sprites/banking/issuers/'+key));logo.svg(HERE/'sources/issuers'/f'{key}.svg')
        issuers.append({'key':key,'institution':'economy:bank/'+key,'name':bank['name'],'currency':bank['currency'],
                        'tier':bank['tier'],'networks':[n.lower() for n in bank['networks']],
                        'palette':{'primary':bank['primary'],'accent':bank['accent']},
                        'sprite':'economy:banking/issuers/'+key,'texture':'economy:gui/sprites/banking/issuers/'+key})
        lang['bank.economy.'+key]=bank['name']
        top = idx * row_height
        sheet.rect(0, top, sheet.w, top + 1, '#42525E')
        for i, word in enumerate(bank['name'].split()):
            sheet.lettering(word, 6, top + 6 + i * 16, PAPER, scale=2)
        sheet.lettering(bank['currency'], 6, top + 70, PAPER)
        x = label_width
        for product,network in [('debit',None)]+[('credit',n.lower()) for n in bank['networks']]:
            item=key+'_'+product+('_'+network if network else '')
            art=card(bank,product,network);art.save(key_path('textures','item/banking/'+item));art.svg(HERE/'sources/cards'/f'{item}.svg')
            model='item/banking/'+item;texture='item/banking/'+item
            output(ASSETS/'models'/f'{model}.json',{'parent':'minecraft:item/generated','textures':{'layer0':'economy:'+texture}})
            label=bank['name']+' '+(network.title()+' Credit Card' if network else 'Debit Card')
            lang['item.economy.'+item]=label
            cards.append({'key':item,'institution':'economy:bank/'+key,'product':product,'network':network,
                          'texture':'economy:'+texture,'model':'economy:'+model,'translation':'item.economy.'+item,
                          'registered':False,'financial_authority':False})
            sheet.lettering((network + ' credit') if network else 'Debit', x, top + 5, PAPER)
            for offset, title, face in (
                (0, 'Personal', art),
                (92, 'Corporate', card(bank, product, network, corporate=True)),
            ):
                sheet.lettering(title, x + offset, top + 16, '#B8C9D2')
                sheet.blit(face, x + offset, top + 20)
                sheet.blit(face, x + offset + 66, top + 44, 16, 16)
            x += product_width
    for network in NETWORKS:
        key=network['key'];logo=mark(key,network['accent']);logo.svg(HERE/'sources/networks'/f'{key}.svg')
        lang['network.economy.'+key]=network['name']
    sheet.save(HERE/'previews/cards.png')
    for page in range(3):
        start=page*8*row_height;height=min(8*row_height,sheet.h-start)
        sheet.crop(0,start,sheet.w,height).save(HERE/'previews'/f'cards_{page+1}.png')
    # Monochrome/native-scale review supplements enlarged card/brand presentations.
    marks=Canvas(560,128,'#192631')
    for i,bank in enumerate(BANKS):
        x=(i%12)*46;y=(i//12)*44
        marks.blit(mark(bank['key'],PAPER),x+4,y+2,32,32);marks.text(str(i+1),x+12,y+36,PAPER)
    for i,network in enumerate(NETWORKS):marks.blit(mark(network['key'],PAPER),10+i*66,92,32,32)
    marks.save(HERE/'previews/marks.png')
    return issuers,cards,lang

def generate():
    import hardware,agent
    issuers,cards,lang=cards_and_marks()
    write_icon_sources()
    hardware_data=hardware.generate();agent_data=agent.generate()
    for bank in BANKS:
        for kind,title in [('atm','ATM'),('teller','Teller Station'),('kiosk','Branch Kiosk')]:
            lang['block.economy.'+bank['key']+'_'+kind]=bank['name']+' '+title+' ('+bank['currency']+')'
    lang.update({'art.economy.preview_only':'Art preview only; usable cards require server issuance.',
                 'entity.economy.collection_agent':'Collection Agent','art.economy.tier1':'Tier 1','art.economy.tier2':'Tier 2'})
    manifest={'version':1,'license':'CC0-1.0','source':'tools/banking-assets/generate.py','sources':['art.py','brands.json','hardware.py','agent.py'],
              'scope':'Artwork and model resources only. No issuer, facility, entity, hardware registration or world migration.',
              'issuers':issuers,'networks':NETWORKS,'cards':cards,'hardware':hardware_data,'agent':agent_data,
              'localization':lang}
    output(ASSETS/'banking/art_manifest.json',manifest)
    existing=json.loads((ASSETS/'lang/en_us.json').read_text())
    for key,value in lang.items():
        if key in existing and existing[key]!=value:raise ValueError('Localization collision '+key)
        existing[key]=value
    output(ASSETS/'lang/en_us.json',existing)
    output(HERE/'resource_inventory.json',manifest)
    print(f'{len(issuers)} issuers, {len(NETWORKS)} networks, {len(cards)} card variants')

if __name__=='__main__':generate()
