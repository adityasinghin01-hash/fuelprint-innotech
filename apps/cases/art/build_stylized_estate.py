"""Original static FuelPrint estate. Blender 4.5 LTS; instruments load separately.

Scene units are composition units, never readings or a surveyed real location.
The editable source and delivery GLB are created together; no external model is used.
"""
import bpy, math, random, json
from pathlib import Path
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
OUT=ROOT.parent/'public/cinematic/stylized'; OUT.mkdir(parents=True,exist_ok=True)
random.seed(26)
bpy.ops.object.select_all(action='SELECT'); bpy.ops.object.delete(use_global=False)
bpy.context.preferences.filepaths.save_version=0

def linear(v):
    return v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4
def mat(name,hex,rough=.8,metal=0):
    color=tuple(linear(int(hex[i:i+2],16)/255) for i in (1,3,5))
    m=bpy.data.materials.new(name); m.diffuse_color=(*color,1); m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF'); p.inputs['Base Color'].default_value=(*color,1)
    p.inputs['Roughness'].default_value=rough; p.inputs['Metallic'].default_value=metal
    return m
ivory=mat('Warm limestone','#EFEAD9'); sage=mat('Sage enamel','#AEBE98')
forest=mat('Forest roof','#3F6651'); glass=mat('Deep teal glazing','#244F45',.27,.18)
mint=mat('Mint ceramics','#C9D5B5'); road=mat('Green slate road','#627963')
path=mat('Pale stone paths','#D1CEAE'); sand=mat('Honey roof','#C5BA8D')
wood=mat('Tree bark','#786D4C'); crown=[mat('Leaf '+str(i),h) for i,h in enumerate(['#597A4F','#6F8D58','#839E68','#A2B67E'])]
hillmats=[mat('Hills '+str(i),h) for i,h in enumerate(['#6F8A60','#829B6C','#96AA7B','#ADC092'])]
detail=mat('Satin brass accents','#C1AB77',.5,.25)

def finish(o,name,material,bevel=0):
    o.name=name; o.data.materials.append(material)
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        mod=o.modifiers.new('Soft architectural edges','BEVEL');mod.width=bevel;mod.segments=3
        bpy.context.view_layer.objects.active=o;bpy.ops.object.modifier_apply(modifier=mod.name)
    for face in o.data.polygons: face.use_smooth=True
    mod=o.modifiers.new('Weighted normals','WEIGHTED_NORMAL');mod.keep_sharp=True
    try:bpy.ops.object.modifier_apply(modifier=mod.name)
    except RuntimeError:pass
    return o
def box(name,p,size,m,bevel=.15):
    bpy.ops.mesh.primitive_cube_add(size=1,location=p);o=bpy.context.object;o.dimensions=size
    return finish(o,name,m,bevel)
def cylinder(name,p,r,depth,m,vertices=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=r,depth=depth,location=p)
    return finish(bpy.context.object,name,m,.08)
def blob(name,p,scale,m,seed=0):
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=2,radius=1,location=p);o=bpy.context.object
    for v in o.data.vertices:
        f=1+.055*math.sin(v.co.x*8+seed)*math.sin(v.co.z*6+seed)
        v.co*=f
    o.scale=scale;return finish(o,name,m)
def label(words,p,size,m,rotation=(math.pi/2,0,0)):
    curve=bpy.data.curves.new(words,'FONT');curve.body=words;curve.size=size;curve.align_x='CENTER';curve.extrude=.003
    o=bpy.data.objects.new(words,curve);bpy.context.collection.objects.link(o);o.location=p;o.rotation_euler=rotation;o.data.materials.append(m)
    bpy.context.view_layer.objects.active=o;o.select_set(True);bpy.ops.object.convert(target='MESH');o.select_set(False)
def curve_strip(name,points,width,m):
    c=bpy.data.curves.new(name,'CURVE');c.dimensions='3D';c.resolution_u=20
    s=c.splines.new('BEZIER');s.bezier_points.add(len(points)-1)
    for b,p in zip(s.bezier_points,points):b.co=p;b.handle_left_type=b.handle_right_type='AUTO'
    o=bpy.data.objects.new(name,c);bpy.context.collection.objects.link(o)
    # The strip is a flat, curved road, with authored smooth joins.
    profile=bpy.data.curves.new(name+' profile','CURVE');profile.dimensions='2D'
    line=profile.splines.new('POLY');line.points.add(1)
    line.points[0].co=(-width/2,0,0,1);line.points[1].co=(width/2,0,0,1)
    pr=bpy.data.objects.new(name+' profile',profile);bpy.context.collection.objects.link(pr)
    c.bevel_mode='OBJECT';c.bevel_object=pr;o.data.materials.append(m)
    bpy.ops.object.select_all(action='DESELECT');o.select_set(True);bpy.context.view_layer.objects.active=o;bpy.ops.object.convert(target='MESH')
    bpy.data.objects.remove(pr,do_unlink=True)

# Continuous rolling terrain, flattened around the fictional estate.
verts=[];faces=[];n=120;size=520
for iy in range(n+1):
    y=(iy/n-.5)*size
    for ix in range(n+1):
        x=(ix/n-.5)*size
        r=math.sqrt(x*x+(y-8)**2)
        edge=max(0,min(1,(r-65)/65));edge=edge*edge*(3-2*edge)
        z=(9+8*math.sin(x*.022+y*.01)+6*math.sin(y*.032-x*.017)+4*math.sin(x*.041-y*.026))*edge-.18
        verts.append((x,y,z))
for iy in range(n):
    for ix in range(n):
        a=iy*(n+1)+ix;faces.append((a,a+1,a+n+2,a+n+1))
mesh=bpy.data.meshes.new('Rolling terrain');mesh.from_pydata(verts,[],faces);mesh.update()
land=bpy.data.objects.new('Continuous green landscape',mesh);bpy.context.collection.objects.link(land)
meadow=mat('Painted meadow','#7D9767');mesh.materials.append(meadow)
colors=mesh.color_attributes.new(name='LandscapeTint',type='FLOAT_COLOR',domain='POINT')
for i,v in enumerate(mesh.vertices):
    x,y,z=v.co;t=.5+.5*math.sin(x*.038+y*.012)*math.sin(y*.042-x*.009)
    a=(.15,.25,.10);b=(.29,.38,.18)
    colors.data[i].color=(*(a[k]+(b[k]-a[k])*t for k in range(3)),1)
node=meadow.node_tree.nodes.new('ShaderNodeVertexColor');node.layer_name='LandscapeTint'
meadow.node_tree.links.new(node.outputs['Color'],meadow.node_tree.nodes.get('Principled BSDF').inputs['Base Color'])
for f in mesh.polygons:
    f.use_smooth=True
    f.material_index=0
# Broad organic meadow patches, never a flat presentation plinth.
for i,(x,y,s) in enumerate([(-80,90,25),(65,87,30),(-100,-40,30),(115,15,34),(-20,145,40),(80,150,48)]):
    blob('Distant rounded ridge',(x,y,0),(s,s*.8,10+i*1.7),hillmats[i%4],i)

curve_strip('Main estate avenue',[(-76,-28,.02),(-40,-12,.02),(-14,-10,.02),(10,-12,.02),(41,-7,.02),(70,22,.02)],5.5,road)
curve_strip('North service avenue',[(-48,-3,.02),(-42,22,.02),(-22,33,.02),(8,29,.02),(40,28,.02),(64,45,.02)],4.5,road)
curve_strip('HQ arrival path',[(0,-14,.06),(0,-10,.06),(0,-6,.06)],4,path)

# The HQ silhouette is deliberately larger, layered and more identifiable than its neighbours.
hq_before=set(bpy.data.objects)
box('HQ landscaped foundation',(0,0,.25),(27,20,.8),path,2)
box('HQ lower podium',(0,0,1),(22,16,1.2),forest,1.4)
box('HQ main building',(0,0,4.7),(19,13,6.7),ivory,1.25)
box('HQ panoramic glazing',(0,-6.52,4.7),(15.8,.18,3.25),glass,.6)
for x in [-6,-3,0,3,6]:box('HQ window mullion',(x,-6.67,4.7),(.17,.15,3.35),ivory,.04)
for side in [-1,1]:
    box('HQ side ribbon',(side*9.5,0,4.7),(.14,9,2.8),glass,.3)
    for y in [-3,0,3]:box('HQ side mullion',(side*9.6,y,4.7),(.15,.15,2.9),ivory,.03)
box('HQ floating roof',(0,0,8.35),(20.7,14.6,.75),mint,1.1)
box('HQ clerestory',(-3,1.6,9.3),(9,7.5,1.45),forest,.65)
box('HQ upper roof',(-3,1.6,10.1),(9.8,8.2,.3),ivory,.5)
box('HQ entry canopy',(0,-8.3,2.95),(9,4,.35),mint,.45)
for x in [-3.8,3.8]:cylinder('Canopy support',(x,-9.2,1.65),.11,2.7,forest,16)
for i in range(3):box('HQ arrival step',(0,-10.25-i*.65,.56-i*.14),(9+i*.5,.8,.22),ivory,.08)
label('FUELPRINT',(0,-6.76,6.73),.82,forest)
label('COMMAND CENTRE',(0,-6.77,6.1),.28,forest)
# Launch terrace; the separately authored drone is placed here by StoryScene.
cylinder('Launch terrace',(5,1,8.87),3.25,.28,forest,64)
cylinder('Terrace inlay',(5,1,9.03),2.9,.05,ivory,64)
label('FP',(5,1,9.08),1.25,forest,(0,0,0))
cylinder('HQ communications mast',(-6,3,11.8),.11,3.3,ivory)
for z in [12.4,13]:box('Mast crossbar',(-6,3,z),(1.8,.12,.12),ivory,.06)

emitters=[];sensors=[]
def sensor(x,y,z):
    box('Inactive rooftop sensor',(x,y,z+.36),(.9,.7,.7),ivory,.16)
    box('Sensor dark face',(x,y-.36,z+.39),(.58,.035,.27),glass,.04)
    # HQ gets a separately authored animated instrument over this mounting footprint.
    if x!=-6 or y!=-3.8:
        cylinder('Sensor inlet',(x+.23,y,z+.88),.07,.4,mint,16)
        cylinder('Sensor inlet cap',(x+.23,y,z+1.11),.16,.06,ivory,20)
    sensors.append([x,z+.36,-y])
sensor(-6,-3.8,8.8)
for o in set(bpy.data.objects)-hq_before:
    if o.type=='MESH':
        o.location.x*=1.12;o.location.y*=1.12;o.location.z*=1.18
        o.scale.x*=1.12;o.scale.y*=1.12;o.scale.z*=1.18
sensors[0]=[sensors[0][0]*1.12,sensors[0][1]*1.18,sensors[0][2]*1.12]
factories=[(-29,14,0),(29,21,1),(-7,42,2),(-43,45,1),(43,-7,2),(-42,-17,0)]
for i,(x,y,kind) in enumerate(factories):
    color=[sage,mint,sand][kind];w=13 if kind!=1 else 15;depth=11 if kind!=2 else 14;h=4.6+kind*.55
    box('S%02d court'%(i+1),(x,y,.12),(w+5,depth+6,.35),path,.9)
    box('S%02d factory'%(i+1),(x,y,h/2+.3),(w,depth,h),color,.5)
    box('Factory lower trim',(x,y,.55),(w+.2,depth+.2,.65),forest,.18)
    if kind==0:
        for j in range(3):
            roof=box('Sawtooth roof',(x-w/2+w/6+j*w/3,y,h+.65),(w/3+.35,depth+.6,.7),forest,.12)
            roof.rotation_euler.y=-.12
            box('Sawtooth clerestory',(x-w/2+w/6+j*w/3,y-depth/2-.1,h+.6),(w/3-.4,.12,.5),glass,.05)
    elif kind==1:
        box('Tiered industrial roof',(x,y,h+.65),(w+.6,depth+.6,.6),forest,.28)
        box('Roof monitor',(x,y,h+1.35),(w*.65,depth*.65,1),sage,.25)
        box('Roof monitor cap',(x,y,h+1.95),(w*.7,depth*.7,.3),ivory,.15)
    else:
        for j in [-1,1]:
            cylinder('Rounded process tank',(x+j*3.5,y,h+1.05),2.25,1.5,ivory)
            cylinder('Tank rim',(x+j*3.5,y,h+1.8),2.35,.16,forest)
        box('Factory roof fascia',(x,y,h+.4),(w+.5,depth+.5,.35),forest,.2)
    for j in range(4):
        xx=x-w*.34+j*w*.225
        box('Factory window',(xx,y-depth/2-.03,h*.66),(w*.14,.12,1.35),glass,.12)
        box('Window sill',(xx,y-depth/2-.13,h*.66-.8),(w*.17,.3,.18),ivory,.06)
    box('Factory loading door',(x,y-depth/2-.1,1.5),(2.9,.14,2.5),forest,.15)
    for z in [.7,1.1,1.5,1.9,2.3]:box('Door slat',(x,y-depth/2-.19,z),(2.5,.04,.045),sage,.01)
    label('S%02d'%(i+1),(x-w*.36,y-depth/2-.21,.9),.5,forest)
    sensor(x+w*.31,y-depth*.1,h+1.4)
    for j in range(1 if kind==2 else 2):
        xx=x-w*.32+j*2.25;yy=y+depth*.28;top=h+5+j*.6
        cylinder('Ceramic chimney',(xx,yy,(top+h)/2),.62,top-h,ivory)
        for z in [h+.3,top-1.25,top-.13]:cylinder('Chimney band',(xx,yy,z),.71,.21,forest)
        cylinder('Chimney dark opening',(xx,yy,top+.02),.44,.025,glass)
        emitters.append([xx,top+.1,-yy])

# Trees are authored irregular crowns with a trunk/branch hierarchy, not generic cones.
for i in range(88):
    a=i*2.39996;r=55+random.random()*27
    x=math.cos(a)*r;y=math.sin(a)*r+5
    if x>45 and y<-10:continue
    s=.85+random.random()*.65
    cylinder('Tree trunk',(x,y,1.1*s),.18*s,2.2*s,wood,10)
    for j,(dx,dy,dz) in enumerate([(-.7,0,2.7),(.9,.3,3),(0,-.6,3.8)]):
        blob('Organic tree crown',(x+dx*s,y+dy*s,dz*s),(1.9*s,1.6*s,2*s),crown[(i+j)%4],i+j)
for x,y in [(-13,-10),(13,-10),(-13,10),(13,10)]:
    box('HQ planted bed',(x,y,.5),(2.7,3,.7),forest,.5)
    blob('HQ ornamental planting',(x,y,1.2),(1.3,1.4,1.1),crown[2],int(x))

# Join by material for a small draw-call count, preserving named sensor/asset provenance in source.
for m in list(bpy.data.materials):
    objects=[o for o in bpy.context.scene.objects if o.type=='MESH' and len(o.data.materials)==1 and o.data.materials[0]==m]
    if not objects:continue
    bpy.ops.object.select_all(action='DESELECT')
    for o in objects:o.select_set(True)
    bpy.context.view_layer.objects.active=objects[0];bpy.ops.object.join();objects[0].name=m.name
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/'stylized-estate.blend'))
bpy.ops.export_scene.gltf(filepath=str(OUT/'estate.glb'),export_format='GLB',export_yup=True,export_animations=False,export_draco_mesh_compression_enable=True,export_draco_mesh_compression_level=7)
(OUT/'estate.json').write_text(json.dumps({'emitters':emitters,'sensors':sensors,'hq':[0,5,0],'scope':'Illustrative opening only; no detection, mission, drone or sampling state.'},indent=2))
print('STYLIZED ESTATE',len(emitters),'chimneys;', (OUT/'estate.glb').stat().st_size,'bytes',flush=True)
