"""Reproducible offline Blender model authoring. No runtime simulation code.

Original FuelPrint drone/station, with actual bevelled surfaces, fasteners,
material slots, rotor pivots and instrument detail. Factory uses CC0 source meshes.
"""
import bpy
import math
import json
import random
from pathlib import Path
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / 'public' / 'cinematic'
OUT.mkdir(parents=True, exist_ok=True)
random.seed(22)

def reset():
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)

def material(name, color, metal=0, rough=.5):
    m = bpy.data.materials.new(name)
    m.diffuse_color = (*color, 1)
    m.use_nodes = True
    p = m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Base Color'].default_value = (*color, 1)
    p.inputs['Metallic'].default_value = metal
    p.inputs['Roughness'].default_value = rough
    return m

def finish(o, name, mat, bevel=0, smooth=True):
    o.name = name
    if mat: o.data.materials.append(mat)
    bpy.ops.object.transform_apply(location=False, rotation=False, scale=True)
    if bevel:
        mod=o.modifiers.new('Machined edge radius','BEVEL');mod.width=bevel;mod.segments=3
        bpy.context.view_layer.objects.active=o;bpy.ops.object.modifier_apply(modifier=mod.name)
    if smooth:
        for f in o.data.polygons: f.use_smooth=True
        mod=o.modifiers.new('Weighted surface normals','WEIGHTED_NORMAL');mod.keep_sharp=True
        bpy.context.view_layer.objects.active=o
        try:bpy.ops.object.modifier_apply(modifier=mod.name)
        except RuntimeError:pass
    return o

def box(name, p, size, mat, bevel=.02):
    bpy.ops.mesh.primitive_cube_add(size=1, location=p)
    o=bpy.context.object;o.dimensions=size
    return finish(o,name,mat,bevel)

def cylinder(name,p,r,depth,mat,vertices=32):
    bpy.ops.mesh.primitive_cylinder_add(vertices=vertices,radius=r,depth=depth,location=p)
    return finish(bpy.context.object,name,mat,.008 if r>.05 else 0)

def sphere(name,p,scale,mat):
    bpy.ops.mesh.primitive_uv_sphere_add(segments=32,ring_count=16,location=p)
    o=bpy.context.object;o.scale=scale;return finish(o,name,mat)

def tube(name,a,b,r,mat,vertices=20):
    a,b=Vector(a),Vector(b)
    o=cylinder(name,(a+b)/2,r,(b-a).length,mat,vertices)
    o.rotation_mode='QUATERNION';o.rotation_quaternion=(b-a).to_track_quat('Z','Y')
    return o

def text(name,words,p,size,mat,rotation=(math.pi/2,0,0)):
    curve=bpy.data.curves.new(name,'FONT');curve.body=words;curve.size=size;curve.extrude=.0003
    o=bpy.data.objects.new(name,curve);bpy.context.collection.objects.link(o);o.location=p;o.rotation_euler=rotation;o.data.materials.append(mat)
    bpy.context.view_layer.objects.active=o;o.select_set(True)
    bpy.ops.object.convert(target='MESH');o.select_set(False)
    return o

def join_materials(exclude=()):
    # Static objects merge by material; rotor groups retain named pivots.
    groups={}
    for o in list(bpy.context.scene.objects):
        if o.type!='MESH' or any(o.name.startswith(x) for x in exclude):continue
        if len(o.data.materials)==1:
            groups.setdefault(o.data.materials[0].name,[]).append(o)
    for key,objects in groups.items():
        bpy.ops.object.select_all(action='DESELECT')
        for o in objects:o.select_set(True)
        bpy.context.view_layer.objects.active=objects[0];bpy.ops.object.join();objects[0].name=key
    objects=[o for o in bpy.context.scene.objects if o.type=='MESH' and not any(o.name.startswith(x) for x in exclude)]
    if objects:
        bpy.ops.object.select_all(action='DESELECT')
        for o in objects:o.select_set(True)
        bpy.context.view_layer.objects.active=objects[0];bpy.ops.object.join();objects[0].name='StaticAssembly'

def export(name, exclude=()):
    join_materials(exclude)
    bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/(name+'.blend')))
    bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')),export_format='GLB',export_yup=True,export_animations=False,export_image_format='JPEG',export_jpeg_quality=82,export_draco_mesh_compression_enable=True,export_draco_mesh_compression_level=6)
    print('EXPORT',name,(OUT/(name+'.glb')).stat().st_size,flush=True)

reset()
carbon=material('Carbon composite',(.045,.065,.061),.45,.35)
# Original seamless woven composite surface, authored as a material rather than painted lighting.
weave=bpy.data.images.new('FuelPrint carbon weave',width=128,height=128)
pixels=[]
for y in range(128):
    for x in range(128):
        warp=(x//8+y//8)%2
        value=.06+.018*math.sin((y if warp else x)*math.pi/4)+(.012 if warp else 0)
        pixels.extend((value*.82,value,value*.91,1))
weave.pixels=pixels
nodes=carbon.node_tree.nodes;links=carbon.node_tree.links
image_node=nodes.new('ShaderNodeTexImage');image_node.image=weave
coords=nodes.new('ShaderNodeTexCoord');mapping=nodes.new('ShaderNodeVectorMath');mapping.operation='SCALE';mapping.inputs[3].default_value=4
links.new(coords.outputs['UV'],mapping.inputs[0]);links.new(mapping.outputs[0],image_node.inputs['Vector'])
links.new(image_node.outputs['Color'],nodes.get('Principled BSDF').inputs['Base Color'])
bump=nodes.new('ShaderNodeBump');bump.inputs['Strength'].default_value=.12;bump.inputs['Distance'].default_value=.0006
links.new(image_node.outputs['Color'],bump.inputs['Height']);links.new(bump.outputs['Normal'],nodes.get('Principled BSDF').inputs['Normal'])
paint=material('Warm ceramic shell',(.77,.80,.72),.18,.32)
alloy=material('Brushed aluminium',(.48,.53,.52),.85,.28)
rubber=material('Rubber isolation',(.018,.027,.025),0,.8)
leaf=material('FuelPrint enamel',(.18,.32,.20),.25,.36)
glass=material('Optical glass',(.022,.085,.09),.7,.12)
white=material('Markings',(.86,.88,.79),0,.55)

# Engineering-sized drone: 1.55 m rotor span. All bodywork is original.
box('Lower carbon chassis',(0,0,0),(.57,.41,.1),carbon,.045)
box('Upper instrument fairing',(0,0,.095),(.54,.37,.15),paint,.065)
box('Service seam',(0,0,.052),(.548,.38,.008),rubber,.025)
box('Battery cartridge',(0,-.055,.21),(.28,.23,.1),carbon,.02)
box('Battery pull tab',(0,-.19,.21),(.12,.028,.04),rubber,.008)
box('Enamel identification panel',(0,.048,.177),(.19,.19,.007),leaf,.012)
text('FP body marking','FUELPRINT',(-.082,.075,.183),.027,white,(0,0,0))
text('Instrument marking','AIR SCREENING',(-.09,.022,.183),.016,white,(0,0,0))
for sx in [-1,1]:
    for sy in [-1,1]:
        x,y=sx*.51,sy*.43
        tube('Carbon outrigger',(sx*.19,sy*.13,.02),(x,y,.075),.032,carbon)
        tube('Arm reinforcement',(sx*.22,sy*.09,-.015),(x*.9,y,.035),.014,alloy)
        box('Arm clamp',(sx*.3,sy*.23,.055),(.095,.065,.06),alloy,.012)
        cylinder('Motor casing',(x,y,.11),.067,.09,carbon)
        cylinder('Motor stator',(x,y,.16),.059,.025,alloy)
        for j in range(10):
            a=j*math.tau/10
            box('Motor cooling fin',(x+math.cos(a)*.065,y+math.sin(a)*.065,.11),(.012,.012,.052),alloy,.002)
        cylinder('Motor shaft',(x,y,.19),.02,.04,alloy)
        rotor=bpy.data.objects.new('Rotor_%s_%s'%(sx,sy),None);bpy.context.collection.objects.link(rotor);rotor.location=(x,y,.185)
        for angle in [0,math.pi]:
            blade=box('Blade_%s_%s_%s'%(sx,sy,angle),(x+math.cos(angle)*.13,y+math.sin(angle)*.13,.19),(.29,.045,.008),carbon,.016)
            blade.rotation_euler.z=angle+.1;blade.parent=rotor;blade.matrix_parent_inverse=rotor.matrix_world.inverted()
        for xx in [-.024,.024]:
            cylinder('Motor fastener',(x+xx,y,.183),.004,.006,alloy,12)
for sx in [-1,1]:
    for sy in [-1,1]:
        tube('Landing strut',(sx*.19,sy*.12,-.04),(sx*.25,sy*.19,-.3),.012,alloy)
    tube('Landing rail',(sx*.25,-.31,-.31),(sx*.25,.3,-.31),.019,carbon)
    for sy in [-1,1]:sphere('Landing foot',(sx*.25,sy*.25,-.325),(.038,.07,.018),rubber)
box('Sampling payload',(0,0,-.15),(.22,.25,.19),paint,.025)
box('Filter service cover',(0,-.132,-.145),(.17,.012,.12),carbon,.008)
for x in [-.07,.07]:
    for z in [-.185,-.105]:
        bolt=cylinder('Payload cover screw',(x,-.143,z),.005,.006,alloy,12);bolt.rotation_euler.x=math.pi/2
cylinder('Inlet throat',(0,0,-.28),.033,.11,alloy)
cylinder('Inlet lip',(0,0,-.342),.046,.014,carbon)
for i in range(8):
    a=i*math.tau/8;tube('Inlet guard',(math.cos(a)*.037,math.sin(a)*.037,-.345),(0,0,-.367),.0025,alloy,8)
tube('Payload cable',(.115,-.04,-.17),(.23,-.05,-.02),.007,rubber)
sphere('Gimbal joint',(0,.23,-.07),(.055,.055,.055),alloy)
box('Gimbal camera',(0,.275,-.13),(.13,.09,.09),carbon,.02)
lens=cylinder('Camera lens',(0,.327,-.13),.032,.027,glass);lens.rotation_euler.x=math.pi/2
for sx in [-1,1]:
    for j in range(8):box('Shell ventilation',(sx*.269,-.10+j*.026,.08),(.006,.012,.045),carbon,.002)
    for sy in [-1,1]:cylinder('Shell fastener',(sx*.22,sy*.135,.166),.005,.005,alloy,12)
cylinder('GNSS antenna',(0,-.15,.28),.042,.025,white)
export('monitoring-drone',('Blade_',))

reset()
# Purpose-built environmental station with construction and service detail.
plinth=material('Cast concrete',(.53,.55,.48),0,.91)
cabinet=material('Powder coated instrument cabinet',(.78,.80,.73),.12,.47)
trim=material('Station graphite',(.10,.17,.15),.5,.43)
steel=material('Station stainless steel',(.53,.59,.57),.85,.3)
label=material('Station label',(.17,.30,.21),.2,.45)
box('Concrete footing',(0,0,.1),(1.05,.85,.2),plinth,.025)
for sx in [-1,1]:
    for sy in [-1,1]:
        cylinder('Anchor bolt',(sx*.4,sy*.3,.23),.018,.12,steel,12)
box('Lower mounting frame',(0,0,.39),(.7,.57,.38),trim,.015)
box('Instrument cabinet',(0,0,1.04),(.78,.62,1.13),cabinet,.035)
box('Door perimeter seal',(0,-.32,1.06),(.7,.025,1.02),trim,.022)
box('Service door',(0,-.34,1.06),(.67,.026,.99),cabinet,.02)
box('Door handle',(.25,-.37,1.02),(.027,.045,.18),steel,.008)
for z in [.72,1.37]:box('Door hinge',(-.33,-.36,z),(.035,.045,.11),steel,.006)
for i in range(8):box('Ventilation louvre',(.398,0,.82+i*.045),(.012,.36,.018),trim,.004)
box('Pitched weather cap',(0,0,1.64),(.9,.73,.055),cabinet,.015)
box('Station nameplate',(0,-.36,1.35),(.42,.012,.15),label,.006)
text('Station name','FUELPRINT',(-.165,-.371,1.345),.047,cabinet)
text('Station caption','MONITORING STATION',(-.19,-.373,1.30),.021,cabinet)
text('Teaching label','ILLUSTRATIVE HARDWARE',(-.20,-.373,.77),.019,trim)
cylinder('Inlet mast',(.19,.1,2.2),.025,1.1,steel)
cylinder('Inlet shield',(.19,.1,2.8),.115,.045,cabinet)
cylinder('Inlet screen',(.19,.1,2.75),.065,.075,trim)
for i in range(4):
    z=2.68+i*.05;cylinder('Radiation shield disc',(-.19,.1,z),.105,.016,cabinet)
tube('Instrument service conduit',(.31,.18,.39),(.31,.18,1.7),.022,trim)
export('monitoring-station')

reset()
bpy.ops.import_scene.gltf(filepath=str(ROOT/'.cache/modular_factory_facade/modular_factory_facade_1k.gltf'))
# Capture source modules in their applied world basis, then arrange a real building.
sources={}
for o in list(bpy.context.scene.objects):
    if o.type=='MESH':
        sources[o.name]=o.copy();sources[o.name].data=o.data.copy()
        sources[o.name].data.transform(o.matrix_world)
for o in list(bpy.context.scene.objects):bpy.data.objects.remove(o,do_unlink=True)
(ROOT/'.cache/factory-source-names.json').write_text(json.dumps(list(sources),indent=2))
def module(name, pos, width=4, height=4, rot=0):
    src=next((o for key,o in sources.items() if key==name),None)
    if src is None:raise RuntimeError('Missing licensed module '+name)
    o=src.copy();o.data=src.data.copy();bpy.context.collection.objects.link(o)
    mins=Vector(tuple(min(v.co[i] for v in o.data.vertices) for i in range(3)))
    maxs=Vector(tuple(max(v.co[i] for v in o.data.vertices) for i in range(3)))
    dims=maxs-mins
    for v in o.data.vertices:
        v.co-=mins
        v.co.x*=width/max(dims.x,.001)
        v.co.z*=height/max(dims.z,.001)
    o.rotation_mode='XYZ';o.scale=(1,1,1);o.location=pos;o.rotation_euler=(0,0,rot)
    return o
for row in range(2):
    for i in range(6):
        module('window_centered_large_01',(i*4,0,row*4),4,4)
        module('window_centered_large_01',(24-i*4,16,row*4),4,4,math.pi)
    for i in range(4):
        module('window_centered_medium_01',(24,i*4,row*4),4,4,math.pi/2)
        module('window_centered_medium_01',(0,16-i*4,row*4),4,4,-math.pi/2)
concrete=material('Architectural concrete',(.52,.55,.48),0,.87)
roof=material('Standing seam sage roof',(.26,.36,.31),.5,.52)
zinc=material('Roof zinc',(.52,.59,.56),.8,.4)
dark=material('Deep window glass',(.075,.13,.13),.35,.28)
box('Foundation',(12,8,-.22),(24.7,16.7,.44),concrete,.06)
box('Roof structure',(12,8,8.06),(24.6,16.6,.18),roof,.04)
for i in range(61):box('Standing seam',(i*.4,8,8.20),(.035,16.6,.085),zinc,.005)
for i in range(3):
    box('Roof monitor body',(4+i*7,8,8.7),(4.3,9,1),roof,.06)
    box('Roof monitor glazing',(4+i*7,3.45,8.7),(3.9,.05,.65),dark,.01)
    box('Roof monitor flashing',(4+i*7,8,9.25),(4.6,9.5,.1),zinc,.02)
    for x in [-1.4,-.7,0,.7,1.4]:box('Monitor mullion',(4+i*7+x,3.39,8.7),(.045,.1,.71),zinc,.005)
    cylinder('Roof exhaust',(4+i*7,12,9.5),.32,1.1,zinc)
    cylinder('Exhaust cowl',(4+i*7,12,10.1),.48,.12,roof)
for i in range(7):
    box('Front structural pier',(i*4,-.09,4),(.19,.22,8.1),concrete,.025)
for x in [.2,23.8]:
    tube('Downpipe',(x,-.2,.2),(x,-.2,8.1),.055,zinc)
    for z in [1,3,5,7]:box('Pipe bracket',(x,-.12,z),(.16,.19,.045),zinc,.005)
box('Service annex',(27,9,2.1),(5.8,11,4.2),concrete,.06)
box('Annex roof',(27,9,4.28),(6.2,11.5,.13),roof,.03)
for i in range(3):
    box('Annex louvre',(27,3.45,1.3+i*.52),(3.5,.08,.2),dark,.02)
box('Loading canopy',(13,-2,3.2),(8,4,.14),roof,.03)
for x in [9.2,16.8]:tube('Canopy column',(x,-3.8,.1),(x,-3.8,3.2),.055,zinc)
export('factory')

reset()
bpy.ops.import_scene.gltf(filepath=str(ROOT/'.cache/modular_industrial_pipes_01/modular_industrial_pipes_01_1k.gltf'))
export('pipe-kit')
reset()
bpy.ops.import_scene.gltf(filepath=str(ROOT/'.cache/tree_small_02/tree_small_02_1k.gltf'))
export('tree')
print('FuelPrint asset production complete',flush=True)
