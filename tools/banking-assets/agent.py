"""Original Waystone Recovery civilian art and humanoid geometry.

SPDX-License-Identifier: CC0-1.0
No downloaded images, external fonts, real marks, entity registration or gameplay.
"""
from pathlib import Path
from art import ASSETS, ROOT, Canvas, PAPER, INK, color, shade, output

HERE = Path(__file__).parent
TEAL = color('#355E63')
TEAL_DARK = color('#29474F')
STONE = color('#C4BBA4')
BRASS = color('#BB9560')
SKIN = color('#C89574')
HAIR = color('#4D3C37')
TROUSER = color('#525B62')
BOOT = color('#333B43')

# Minecraft cube UV layout: right, front, left and back below top/bottom.
PARTS = [
    dict(name='head', parent='root', pivot=[0,0,0], box=[-4,-8,-4,8,8,8], uv=[0,0], material='skin'),
    dict(name='hat', parent='root', pivot=[0,0,0], box=[-4,-8,-4,8,8,8], uv=[32,0], deformation=0.25, material='cap'),
    dict(name='body', parent='root', pivot=[0,0,0], box=[-4,0,-2,8,12,4], uv=[16,16], material='coat'),
    dict(name='right_arm', parent='root', pivot=[-5,2,0], box=[-3,-2,-2,4,12,4], uv=[40,16], material='sleeve'),
    dict(name='left_arm', parent='root', pivot=[5,2,0], box=[-1,-2,-2,4,12,4], uv=[32,48], material='sleeve'),
    dict(name='right_leg', parent='root', pivot=[-1.9,12,0], box=[-2,0,-2,4,12,4], uv=[0,32], material='trousers'),
    dict(name='left_leg', parent='root', pivot=[1.9,12,0], box=[-2,0,-2,4,12,4], uv=[16,32], material='trousers'),
    dict(name='satchel', parent='body', pivot=[0,0,0], box=[3,7,1,4,5,2], uv=[48,34], material='satchel'),
    dict(name='folio', parent='body', pivot=[0,0,0], box=[-3,4,-3,6,5,1], uv=[32,32], material='paper'),
]


def cube_faces(part):
    u,v=part['uv']; _,_,_,w,h,d=part['box']
    return dict(right=(u,v+d,d,h),front=(u+d,v+d,w,h),left=(u+d+w,v+d,d,h),
                back=(u+d+w+d,v+d,w,h),top=(u+d,v,w,d),bottom=(u+d+w,v,w,d))


def fill_cube(p, part, base):
    for side,(x,y,w,h) in cube_faces(part).items():
        delta={'front':0,'right':-12,'left':-5,'back':-10,'top':12,'bottom':-20}[side]
        p.rect(x,y,x+w,y+h,shade(base,delta))


def sheet():
    p=Canvas(64,64)
    materials={'skin':SKIN,'coat':TEAL,'sleeve':TEAL,'trousers':TROUSER,'satchel':STONE,'paper':PAPER}
    for part in PARTS:
        if part['material']!='cap':fill_cube(p,part,materials[part['material']])
    # Cropped short hair follows the head's UV faces. The front is warm and open.
    p.rect(8,0,16,8,HAIR)
    p.rect(0,8,8,11,HAIR);p.rect(16,8,24,11,HAIR);p.rect(24,8,32,14,HAIR)
    p.rect(8,8,16,10,HAIR);p.rect(8,10,10,11,HAIR)
    p.pixel(10,11,INK);p.pixel(13,11,INK)
    p.pixel(12,12,shade(SKIN,-22));p.rect(11,14,13,15,shade(SKIN,-38))
    p.pixel(10,13,shade(SKIN,12));p.pixel(14,13,shade(SKIN,12))
    # Cap overlay is transparent below its two-pixel band: it cannot obscure eyes.
    p.rect(40,0,48,8,TEAL_DARK)
    for x,w in [(32,8),(40,8),(48,8),(56,8)]:p.rect(x,8,x+w,10,TEAL_DARK)
    p.rect(43,8,45,10,BRASS)
    # Stone lapels, paper shirt and narrow seam. This is not a uniform insignia.
    p.rect(23,20,25,24,PAPER)
    p.polygon([(20,20),(23,20),(23,25)],STONE)
    p.polygon([(25,20),(28,20),(25,25)],shade(STONE,-15))
    p.rect(23,25,24,32,TEAL_DARK)
    for y in (26,29):p.pixel(24,y,BRASS)
    # Original ledger badge: a folded rectangle and two short rule lines.
    p.rect(26,23,28,26,BRASS);p.pixel(27,23,PAPER);p.pixel(26,24,INK)
    # Back yoke and a diagonal satchel strap, continued over the shoulder.
    p.rect(32,20,40,22,shade(TEAL,14))
    p.line(33,21,39,29,STONE)
    p.line(21,21,27,29,shade(STONE,-10))
    for part in PARTS:
        if part['material']=='sleeve':
            for x,y,w,h in cube_faces(part).values():
                if h==12:p.rect(x,y+9,x+w,y+10,STONE);p.rect(x,y+10,x+w,y+12,SKIN)
        if part['material']=='trousers':
            for x,y,w,h in cube_faces(part).values():
                if h==12:p.rect(x,y+9,x+w,y+12,BOOT);p.rect(x,y+9,x+w,y+10,shade(BOOT,15))
    # Satchel flap and clasp, paper leaves in the separate torso-mounted folio.
    p.rect(50,36,54,38,shade(STONE,-28));p.rect(51,38,53,39,BRASS)
    p.rect(56,36,60,38,shade(STONE,-28));p.rect(57,38,59,39,BRASS)
    p.rect(33,33,39,34,STONE);p.rect(34,34,38,35,shade(INK,45));p.rect(34,36,37,37,shade(INK,45))
    p.pixel(38,33,BRASS)
    return p


def face(p, name, direction):
    part=next(x for x in PARTS if x['name']==name)
    x,y,w,h=cube_faces(part)[direction]
    return p.crop(x,y,w,h)


def figure(texture, direction):
    p=Canvas(32,38)
    if direction in ('front','back'):
        p.blit(face(texture,'right_leg',direction),12,22)
        p.blit(face(texture,'left_leg',direction),16,22)
        p.blit(face(texture,'body',direction),12,10)
        p.blit(face(texture,'right_arm',direction),8,10)
        p.blit(face(texture,'left_arm',direction),20,10)
        p.blit(face(texture,'head',direction),12,2)
        p.blit(face(texture,'hat',direction),12,2)
        # The satchel sits behind the left hip; folio is mounted only at front.
        if direction=='back':p.blit(face(texture,'satchel','back'),19,17)
        else:
            p.blit(face(texture,'satchel','right'),20,17)
            p.blit(face(texture,'folio','front'),13,14)
    else:
        p.blit(face(texture,'right_leg','right'),13,22)
        p.blit(face(texture,'body','right'),13,10)
        p.blit(face(texture,'right_arm','right'),13,10)
        p.blit(face(texture,'head','right'),11,2)
        p.blit(face(texture,'hat','right'),11,2)
        p.blit(face(texture,'folio','right'),12,14)
        p.blit(face(texture,'satchel','left'),17,17)
    return p


def java_source():
    lines=['package ca.emilybelnavis.mcmods.economy.client;','',
        'import net.minecraft.client.model.geom.PartPose;',
        'import net.minecraft.client.model.geom.builders.CubeDeformation;',
        'import net.minecraft.client.model.geom.builders.CubeListBuilder;',
        'import net.minecraft.client.model.geom.builders.LayerDefinition;',
        'import net.minecraft.client.model.geom.builders.MeshDefinition;',
        'import net.minecraft.client.model.geom.builders.PartDefinition;','',
        '/** Original Waystone civilian mesh. A6-11 owns its renderer, entity and all behavior. */',
        'public final class CollectionAgentGeometry {',
        '    public static final int TEXTURE_WIDTH = 64, TEXTURE_HEIGHT = 64;',
        '    private CollectionAgentGeometry() {}',
        '    public static LayerDefinition create() {',
        '        var mesh = new MeshDefinition();',
        '        PartDefinition root = mesh.getRoot();']
    def f(v):return str(float(v))+'F'
    for part in PARTS:
        u,v=part['uv']; box=', '.join(f(n) for n in part['box'])
        deform=', new CubeDeformation('+f(part['deformation'])+')' if 'deformation' in part else ''
        pivot=', '.join(f(n) for n in part['pivot'])
        decl='PartDefinition body = ' if part['name']=='body' else ''
        lines.append(f'        {decl}{part["parent"]}.addOrReplaceChild("{part["name"]}", CubeListBuilder.create().texOffs({u}, {v})')
        lines.append(f'                .addBox({box}{deform}), PartPose.offset({pivot}));')
    lines += ['        return LayerDefinition.create(mesh, TEXTURE_WIDTH, TEXTURE_HEIGHT);','    }','}','']
    return '\n'.join(lines)


def generate():
    texture=sheet()
    texture_path=ASSETS/'textures/entity/banking/collection_agent.png'
    texture.save(texture_path)
    source_path=HERE/'sources/agent/collection_agent_uv.svg'
    texture.svg(source_path)
    blueprint_path=HERE/'sources/agent/collection_agent_model.json'
    blueprint={'schema':'economy:humanoid-art-v1','version':1,'license':'CC0-1.0',
        'name':'Waystone Recovery Collection Agent','texture':'economy:entity/banking/collection_agent',
        'texture_width':64,'texture_height':64,'parts':PARTS,
        'authority':'Presentation only. No entity, item, spawning, interaction or financial registration.',
        'future_owner':'A6-11','geometry_factory':'ca.emilybelnavis.mcmods.economy.client.CollectionAgentGeometry.create',
        'uv_layout':'Minecraft CubeListBuilder box UV layout; transparent cap overlay below top band.'}
    output(blueprint_path,blueprint)
    java_path=ROOT/'src/main/java/ca/emilybelnavis/mcmods/economy/client/CollectionAgentGeometry.java'
    java_path.write_text(java_source())
    preview=Canvas(544,272,(231,228,216,255))
    preview.text('WAYSTONE RECOVERY',12,10,INK,2)
    preview.text('COLLECTION AGENT ART ONLY',12,28,INK)
    for i,direction in enumerate(('front','back','right')):
        rendered=figure(texture,direction)
        preview.blit(rendered,10+i*126,48,128,152)
        preview.text(direction,40+i*126,211,INK)
        preview.blit(rendered,53+i*126,225,32,38)
    preview.blit(texture,400,56,128,128)
    preview.text('64 X 64 UV',410,194,INK)
    preview.text('NO ENTITY REGISTERED',390,211,INK)
    preview_path=HERE/'previews/agent_views.png'
    preview.save(preview_path)
    preview.svg(HERE/'sources/agent/collection_agent_views.svg')
    return {'texture':'economy:entity/banking/collection_agent',
        'textureFile':str(texture_path.relative_to(ROOT)),
        'blueprintFile':str(blueprint_path.relative_to(ROOT)),
        'geometryJava':str(java_path.relative_to(ROOT)),
        'source':str(source_path.relative_to(ROOT)),
        'preview':str(preview_path.relative_to(ROOT)),
        'textureSize':[64,64], 'parts':len(PARTS), 'registeredEntity':False,
        'provenance':'Original project-authored pixel layout and cube geometry; CC0-1.0; no external art/fonts.'}

if __name__=='__main__':
    import json
    print(json.dumps(generate(),indent=2))
