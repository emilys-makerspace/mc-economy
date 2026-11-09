#!/usr/bin/env python3
"""Deterministic original A6-05 art. No financial IDs, entities or hardware are activated.
SPDX-License-Identifier: CC0-1.0
"""
from pathlib import Path
import hashlib, json
from art import ASSETS, ROOT, BANKS, NETWORKS, Canvas, PAPER, INK, color, contrast, mark, output, shade
HERE=Path(__file__).resolve().parent

def key_path(kind,key):return ASSETS/kind/(key+'.png')
def tier_badge(tier):
    p=Canvas(16,16);p.rect(0,1,16,15,PAPER);p.rect(1,2,15,14,INK)
    if tier==2:
        p.rect(4,4,6,12,PAPER);p.rect(10,4,12,12,PAPER)
    else:p.rect(7,4,9,12,PAPER)
    return p

def card(bank,product,network):
    p=Canvas(64,64);primary=color(bank['primary']);accent=color(bank['accent']);fg=contrast(primary)
    p.polygon([(4,12),(58,12),(62,16),(62,48),(58,52),(4,52),(2,50),(2,14)],fg)
    p.rect(5,15,59,49,primary)
    # Product shapes differ without relying on issuer or network color.
    if product=='debit':p.rect(7,17,23,24,accent)
    else:p.polygon([(7,17),(19,17),(24,20),(19,24),(7,24)],accent)
    p.text('DB' if product=='debit' else 'CR',9,18,contrast(accent))
    p.blit(mark(bank['key'],accent),7,26,22,22)
    # Original pixel lettering, no player names, numbers, limits or credentials.
    for i,label in enumerate(bank['label'][:2]):p.text(label,31,25+i*6,fg)
    p.text(bank['currency'],31,39,fg)
    if network:p.blit(mark(network,fg),46,35,14,14)
    else:
        p.rect(48,45,60,48,fg);p.line(48,44,54,40,fg);p.line(54,40,59,44,fg)
    return p

def icons():
    result=[]
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
        path='gui/sprites/banking/'+name;p.save(key_path('textures',path));p.svg(HERE/'sources/icons'/f'{name}.svg')
        result.append({'key':name,'sprite':'economy:banking/'+name,'texture':'economy:'+path})
    return result

def cards_and_marks():
    cards=[];issuers=[];networks=[];lang={};sheet=Canvas(864, len(BANKS)*84, '#192631')
    for idx,bank in enumerate(BANKS):
        key=bank['key'];primary=color(bank['primary']);accent=color(bank['accent'])
        logo=mark(key,accent);logo.save(key_path('textures','gui/sprites/banking/issuers/'+key));logo.svg(HERE/'sources/issuers'/f'{key}.svg')
        issuers.append({'key':key,'institution':'economy:bank/'+key,'name':bank['name'],'currency':bank['currency'],
                        'tier':bank['tier'],'networks':[n.lower() for n in bank['networks']],
                        'palette':{'primary':bank['primary'],'accent':bank['accent']},
                        'sprite':'economy:banking/issuers/'+key,'texture':'economy:gui/sprites/banking/issuers/'+key})
        lang['bank.economy.'+key]=bank['name']
        sheet.text(bank['name'].upper(),5,idx*84+5,PAPER);sheet.text(bank['currency']+' '+bank['tier'].replace('_',' '),5,idx*84+14,PAPER)
        x=210
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
            sheet.blit(art,x,idx*84+18,64,64);sheet.blit(art,x+43,idx*84+2,16,16);x+=78
    for network in NETWORKS:
        key=network['key'];logo=mark(key,network['accent']);logo.save(key_path('textures','gui/sprites/banking/networks/'+key));logo.svg(HERE/'sources/networks'/f'{key}.svg')
        networks.append({**network,'sprite':'economy:banking/networks/'+key,'texture':'economy:gui/sprites/banking/networks/'+key})
        lang['network.economy.'+key]=network['name']
    sheet.save(HERE/'previews/cards.png')
    for page in range(3):
        start=page*8*84;height=min(8*84,sheet.h-start)
        sheet.crop(0,start,sheet.w,height).save(HERE/'previews'/f'cards_{page+1}.png')
    # Monochrome/native-scale review supplements enlarged card/brand presentations.
    marks=Canvas(560,128,'#192631')
    for i,bank in enumerate(BANKS):
        x=(i%12)*46;y=(i//12)*44
        marks.blit(mark(bank['key'],PAPER),x+4,y+2,32,32);marks.text(str(i+1),x+12,y+36,PAPER)
    for i,network in enumerate(NETWORKS):marks.blit(mark(network['key'],PAPER),10+i*66,92,32,32)
    marks.save(HERE/'previews/marks.png')
    return issuers,networks,cards,lang

def generate():
    import hardware,agent
    issuers,networks,cards,lang=cards_and_marks()
    ui=icons();hardware_data=hardware.generate();agent_data=agent.generate()
    for bank in BANKS:
        for kind,title in [('atm','ATM'),('teller','Teller Station'),('kiosk','Branch Kiosk')]:
            lang['block.economy.'+bank['key']+'_'+kind]=bank['name']+' '+title+' ('+bank['currency']+')'
    lang.update({'art.economy.preview_only':'Art preview only; usable cards require server issuance.',
                 'entity.economy.collection_agent':'Collection Agent','art.economy.tier1':'Tier 1','art.economy.tier2':'Tier 2'})
    for icon in ui:lang['art.economy.action.'+icon['key']]=icon['key'].replace('_',' ').title()
    manifest={'version':1,'license':'CC0-1.0','source':'tools/banking-assets/generate.py','sources':['art.py','brands.json','hardware.py','agent.py'],
              'scope':'Artwork and model resources only. No issuer, facility, entity, hardware registration or world migration.',
              'issuers':issuers,'networks':networks,'cards':cards,'icons':ui,'hardware':hardware_data,'agent':agent_data,
              'localization':lang}
    output(ASSETS/'banking/art_manifest.json',manifest)
    existing=json.loads((ASSETS/'lang/en_us.json').read_text())
    for key,value in lang.items():
        if key in existing and existing[key]!=value:raise ValueError('Localization collision '+key)
        existing[key]=value
    output(ASSETS/'lang/en_us.json',existing)
    output(HERE/'resource_inventory.json',manifest)
    print(f'{len(issuers)} issuers, {len(networks)} networks, {len(cards)} card variants, {len(ui)} menu icons')

if __name__=='__main__':generate()
