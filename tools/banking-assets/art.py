"""Original, dependency-free pixel primitives and marks. SPDX-License-Identifier: CC0-1.0"""
from pathlib import Path
import json, math, struct, zlib
ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / 'src/main/resources/assets/economy'
DATA = json.loads((Path(__file__).parent / 'brands.json').read_text())
BANKS = DATA['banks']
NETWORKS = DATA['networks']
INK = (23, 31, 42, 255)
PAPER = (243, 238, 222, 255)
# Hand-authored three-by-five alphabet; no external font or image source.
FONT = dict(zip('ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789', [
'010101111101101','110101110101110','011100100100011','110101101101110','111100110100111','111100110100100',
'011100101101011','101101111101101','111010010010111','001001001101010','101101110101101','100100100100111',
'101111111101101','101111111111101','010101101101010','110101110100100','010101101111011','110101110101101',
'011100010001110','111010010010010','101101101101111','101101101101010','101101111111101','101101010101101',
'101101010010010','111001010100111','111101101101111','010110010010111','110001010100111','110001010001110',
'101101111001001','111100110001110','011100111101111','111001010010010','111101111101111','111101111001110']))
FONT.update({' ': '000000000000000', '-': '000000111000000', '/': '001001010100100', '.': '000000000000010', ':':'000010000010000'})

# Seven-row lettering for printed cards, signage and instrument panels.
# Each hexadecimal digit pair is one row, with bit zero at the right edge.
LETTER_ROWS = {
    'A': '0e1111111f1111', 'B': '1e11111e11111e', 'C': '0e11101010110e',
    'D': '1e11111111111e', 'E': '1f10101e10101f', 'F': '1f10101e101010',
    'G': '0e11101013110f', 'H': '1111111f111111', 'I': '07020202020207',
    'J': '0702020202120c', 'K': '11121418141211', 'L': '1010101010101f',
    'M': '111b1515111111', 'N': '11191915131311', 'O': '0e11111111110e',
    'P': '1e11111e101010', 'Q': '0e11111115120d', 'R': '1e11111e141211',
    'S': '0f10100e01011e', 'T': '1f040404040404', 'U': '1111111111110e',
    'V': '111111110a0a04', 'W': '1111111515150a', 'X': '11110a040a1111',
    'Y': '11110a04040404', 'Z': '1f01020408101f',
    '0': '0e11131519110e', '1': '02060202020207', '2': '0e11010204081f',
    '3': '1e01010e01011e', '4': '02060a121f0202', '5': '1f10101e01011e',
    '6': '0608101e11110e', '7': '1f010204080808', '8': '0e11110e11110e',
    '9': '0e11110f01020c', ' ': '00000000000000',
}
LETTERS = {letter: bytes.fromhex(rows) for letter, rows in LETTER_ROWS.items()}
LETTER_WIDTHS = {letter: max(3, max(rows).bit_length()) for letter, rows in LETTERS.items()}


def lettering_width(label, scale=1):
    return max(0, sum(LETTER_WIDTHS[ch] + 1 for ch in label.upper()) - 1) * scale


def color(c):
    if isinstance(c, str): return tuple(bytes.fromhex(c.lstrip('#'))) + (255,)
    return tuple(c) if len(c) == 4 else tuple(c) + (255,)

def shade(c, delta): return tuple(max(0, min(255, v + delta)) for v in color(c)[:3]) + (color(c)[3],)
def contrast(c):
    r,g,b,_=color(c)
    return INK if (r*299+g*587+b*114)/1000 > 146 else PAPER

def output(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False)+'\n')

class Canvas:
    def __init__(self, width, height, background=(0,0,0,0)):
        self.w,self.h=width,height
        self.p=[color(background)]*(width*height)
    def pixel(self,x,y,c):
        x,y=int(x),int(y)
        if 0<=x<self.w and 0<=y<self.h:self.p[y*self.w+x]=color(c)
    def rect(self,x1,y1,x2,y2,c):
        for y in range(max(0,int(y1)),min(self.h,int(y2))):
            for x in range(max(0,int(x1)),min(self.w,int(x2))):self.pixel(x,y,c)
    def line(self,x1,y1,x2,y2,c,width=1):
        steps=max(abs(x2-x1),abs(y2-y1),1)
        for s in range(steps+1):
            x=round(x1+(x2-x1)*s/steps);y=round(y1+(y2-y1)*s/steps)
            self.rect(x,y,x+width,y+width,c)
    def polygon(self,points,c):
        for y in range(max(0,int(min(p[1] for p in points))), min(self.h,int(max(p[1] for p in points))+1)):
            xs=[]
            for (ax,ay),(bx,by) in zip(points,points[1:]+points[:1]):
                if ay!=by and min(ay,by)<=y+0.5<max(ay,by):xs.append(ax+(y+0.5-ay)*(bx-ax)/(by-ay))
            xs.sort()
            for i in range(0,len(xs)-1,2):self.rect(math.ceil(xs[i]),y,math.ceil(xs[i+1]),y+1,c)
    def text(self,label,x,y,c,scale=1):
        for ch in label.upper():
            glyph=FONT[ch]
            for gy in range(5):
                for gx in range(3):
                    if glyph[gy*3+gx]=='1':self.rect(x+gx*scale,y+gy*scale,x+(gx+1)*scale,y+(gy+1)*scale,c)
            x+=4*scale
    def lettering(self, label, x, y, c, scale=1):
        for ch in label.upper():
            width = LETTER_WIDTHS[ch]
            for row, bits in enumerate(LETTERS[ch]):
                for col in range(width):
                    if bits & (1 << (width - col - 1)):
                        left, top = x + col * scale, y + row * scale
                        self.rect(left, top, left + scale, top + scale, c)
            x += (width + 1) * scale

    def blit(self,source,x,y,width=None,height=None):
        width=width or source.w;height=height or source.h
        for dy in range(height):
            for dx in range(width):
                c=source.p[(dy*source.h//height)*source.w+dx*source.w//width]
                if c[3]:self.pixel(x+dx,y+dy,c)
    def crop(self,x,y,w,h):
        out=Canvas(w,h)
        for dy in range(h):
            for dx in range(w):out.pixel(dx,dy,self.p[(y+dy)*self.w+x+dx])
        return out
    def save(self,path):
        def chunk(kind,data):return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data))
        raw=b''.join(b'\0'+bytes(v for p in self.p[y*self.w:(y+1)*self.w] for v in p) for y in range(self.h))
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',self.w,self.h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b''))
    def svg(self,path):
        lines=[f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" shape-rendering="crispEdges">']
        for y in range(self.h):
            for x in range(self.w):
                r,g,b,a=self.p[y*self.w+x]
                if a:lines.append(f'<rect x="{x}" y="{y}" width="1" height="1" fill="#{r:02x}{g:02x}{b:02x}"/>')
        path.parent.mkdir(parents=True,exist_ok=True);path.write_text('\n'.join(lines+['</svg>'])+'\n')

def mark(key,c=PAPER):
    p=Canvas(16,16)
    if key=='crownstone':
        p.polygon([(1,13),(1,10),(4,10),(4,7),(7,7),(7,3),(10,3),(10,8),(13,8),(13,11),(15,11),(15,14)],c)
        p.rect(7,8,10,14,(0,0,0,0))
    elif key=='orchard_direct':
        for x,y in [(2,3),(7,5),(12,2)]:p.rect(x,y,x+2,y+3,c);p.line(x,y+3,x,y+10,c)
        p.line(1,14,14,11,c)
    elif key=='tidecrest':
        for y,dx in [(3,0),(7,2),(11,0)]:p.polygon([(1+dx,y+2),(5+dx,y),(10+dx,y+1),(14,y),(14,y+2),(10+dx,y+3),(5+dx,y+2),(1+dx,y+4)],c)
    elif key=='crosswind':
        p.polygon([(1,3),(4,2),(15,10),(13,13)],c);p.polygon([(3,14),(1,11),(11,1),(14,3)],c)
        p.rect(6,6,9,9,(0,0,0,0))
    elif key=='lantern_union':
        p.line(3,3,12,3,c,2);p.line(3,3,3,12,c,2);p.line(12,3,12,12,c,2);p.line(3,12,12,12,c,2)
        p.rect(7,1,9,15,c);p.rect(1,7,15,9,c)
    elif key=='mesa_trail':
        p.rect(1,11,15,14,c);p.rect(3,7,12,9,c);p.rect(6,3,10,5,c)
    elif key=='asterbridge':
        p.line(1,13,1,8,c,2);p.line(13,13,13,8,c,2);p.line(1,8,7,2,c,2);p.line(7,2,13,8,c,2)
        p.line(5,13,5,9,c,2);p.line(5,9,8,6,c,2);p.line(8,6,10,9,c,2);p.line(10,9,10,13,c,2)
    elif key=='greenhaven':
        for x,y in [(1,2),(8,1),(2,9),(9,8)]:p.polygon([(x,y+4),(x+1,y),(x+5,y+1),(x+4,y+5)],c)
        p.line(4,14,12,3,c)
    elif key=='meridian_grove':
        p.line(7,14,7,3,c,2);p.line(7,8,2,4,c,2);p.line(7,10,13,5,c,2);p.rect(1,2,4,4,c);p.rect(11,2,14,4,c);p.rect(6,1,9,3,c)
    elif key=='slate_harbor':
        for x,h in [(1,6),(6,10),(11,8)]:p.rect(x,14-h,x+3,13,c)
        p.line(0,14,15,14,c)
    elif key=='fjordlight':
        p.polygon([(1,2),(6,2),(6,6),(9,6),(9,10),(14,10),(14,14),(1,14),(1,11),(5,11),(5,8),(3,8),(3,5),(1,5)],c)
    elif key=='harbor_meridian':
        for angle in range(0,285,8):
            a=math.radians(angle);p.rect(round(7+6*math.cos(a)),round(8+5*math.sin(a)),round(7+6*math.cos(a))+2,round(8+5*math.sin(a))+2,c)
        p.rect(7,1,9,11,c);p.rect(1,7,6,9,c)
    elif key=='jade_terrace':
        p.rect(1,12,15,15,c);p.rect(3,7,13,10,c);p.rect(5,2,11,5,c)
    elif key=='southern_arc':
        for off in (0,4):
            for angle in range(190,352,8):
                a=math.radians(angle);x=round(8+6*math.cos(a));y=round(5+off+6*math.sin(a));p.rect(x,y,x+2,y+2,c)
        p.rect(11,10,14,14,c)
    elif key=='fernreach':
        p.line(4,14,10,1,c,2)
        for y,x in [(3,8),(6,7),(9,5)]:p.line(x,y,2,y-1,c,2);p.line(x,y+1,13,y-1,c,2)
    elif key=='cedar_parcel':
        p.polygon([(2,2),(14,2),(14,14),(2,14)],c);p.polygon([(4,4),(11,4),(4,11)],(0,0,0,0));p.polygon([(6,12),(12,6),(12,12)],(0,0,0,0))
    elif key=='lakeward_cantonal':
        p.polygon([(1,2),(8,2),(8,5),(12,5),(12,8),(15,8),(15,11),(6,11),(6,8),(3,8),(3,5),(1,5)],c);p.rect(1,13,15,15,c)
    elif key=='newhaven':
        for x,y in [(1,10),(5,6),(9,2)]:p.rect(x,y,x+5,y+5,c);p.rect(x+1,y+1,x+4,y+4,(0,0,0,0))
    elif key=='atlas_reserve':
        for x,y in [(1,1),(8,2),(2,8),(9,9)]:p.polygon([(x,y),(x+5,y+1),(x+4,y+5),(x,y+4)],c);p.rect(x+1,y+2,x+3,y+4,(0,0,0,0))
    elif key=='obsidian_capital':
        p.polygon([(2,2),(7,1),(7,14),(2,12)],c);p.polygon([(9,2),(14,4),(14,14),(9,15)],c)
    elif key=='alpine_vault':
        p.polygon([(0,13),(5,3),(9,13)],c);p.polygon([(7,13),(11,1),(16,13)],c);p.rect(1,14,15,16,c)
    elif key=='kintsugi_trust':
        for a,b in [((1,2),(6,7)),((6,7),(4,14)),((6,7),(13,4)),((13,4),(15,10)),((9,9),(13,15))]:p.line(*a,*b,c,2)
    elif key=='mariner_crest':
        p.polygon([(2,12),(7,1),(7,12)],c);p.polygon([(9,3),(15,12),(9,12)],c);p.line(1,14,14,14,c,2)
    elif key=='keystone':
        p.rect(1,2,10,5,c);p.rect(5,7,15,10,c);p.rect(2,12,11,15,c)
    elif key=='orbit':
        for angle in range(35,325,5):
            a=math.radians(angle);x=round(7+6*math.cos(a));y=round(7+5*math.sin(a));p.rect(x,y,x+2,y+2,c)
        p.rect(11,2,15,5,c)
    elif key=='wayline':
        p.line(3,12,8,8,c,2);p.line(8,8,12,3,c,2)
        for x,y in [(1,10),(6,6),(10,1)]:p.rect(x,y,x+5,y+5,c);p.rect(x+1,y+1,x+4,y+4,(0,0,0,0))
    elif key=='ember':
        for x,y,r in [(4,4,3),(12,6,2),(7,12,3)]:p.polygon([(x,y-r),(x+r,y),(x,y+r),(x-r,y)],c)
        p.line(5,4,11,6,c);p.line(11,7,8,11,c)
    else:raise ValueError(key)
    return p
