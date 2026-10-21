"""Original FuelPrint drone and sensor; Blender 4.5.3 LTS, no external assets.

Authored enamel shells, recessed optics, sampling inlet, landing skids, protected
rotors and separate animation pivots. Composition units are not engineering specs.
"""
import bpy, math
from pathlib import Path
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
OUT=ROOT.parent/'public/cinematic/stylized'
bpy.context.preferences.filepaths.save_version=0
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)

def material(name,color,rough=.55,metal=0):
    c=[int(color[i:i+2],16)/255 for i in (1,3,5)]
    c=[v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4 for v in c]
    m=bpy.data.materials.new(name);m.diffuse_color=(*c,1);m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Base Color'].default_value=(*c,1)
    p.inputs['Roughness'].default_value=rough;p.inputs['Metallic'].default_value=metal
    return m
cream=material('Instrument porcelain','#EFEAD9');green=material('Instrument forest','#3F6651')
dark=material('Recessed optics','#183C36',.24,.2);mint=material('Instrument sage','#AEBE98')
brass=material('Inlet satin brass','#BEA16A',.35,.45);lens=material('PowerLens','#B1BE9A',.3)
signal=material('SignalLens','#B1BE9A',.3)
def finish(o,name,m,bevel=.07):
    o.name=name;o.data.materials.append(m)
    bpy.ops.object.transform_apply(location=False,rotation=False,scale=True)
    if bevel:
        mod=o.modifiers.new('Soft manufactured edges','BEVEL');mod.width=bevel;mod.segments=4
        bpy.context.view_layer.objects.active=o;bpy.ops.object.modifier_apply(modifier=mod.name)
    for p in o.data.polygons:p.use_smooth=True
    n=o.modifiers.new('Weighted normals','WEIGHTED_NORMAL');bpy.ops.object.modifier_apply(modifier=n.name)
    return o
def box(name,p,size,m,bevel=.07):
    bpy.ops.mesh.primitive_cube_add(size=1,location=p);o=bpy.context.object;o.dimensions=size
    return finish(o,name,m,bevel)
def cyl(name,p,r,h,m):
    bpy.ops.mesh.primitive_cylinder_add(vertices=40,radius=r,depth=h,location=p)
    return finish(bpy.context.object,name,m,min(.035,h*.2))
def rod(name,a,b,r,m):
    mid=(Vector(a)+Vector(b))/2;o=cyl(name,mid,r,(Vector(b)-Vector(a)).length,m)
    o.rotation_euler=(Vector(b)-Vector(a)).to_track_quat('Z','Y').to_euler();return o
def ring(name,p,r,m):
    bpy.ops.mesh.primitive_torus_add(major_segments=48,minor_segments=10,location=p,major_radius=r,minor_radius=.045)
    return finish(bpy.context.object,name,m,0)
def export(name):
    # Batch the authored static parts, retaining four independent rotor pivots.
    for m in list(bpy.data.materials):
        objects=[o for o in bpy.context.scene.objects if o.type=='MESH' and o.parent is None and len(o.data.materials)==1 and o.data.materials[0]==m]
        if not objects:continue
        bpy.ops.object.select_all(action='DESELECT')
        for o in objects:o.select_set(True)
        bpy.context.view_layer.objects.active=objects[0];bpy.ops.object.join();objects[0].name=m.name
    bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/('stylized-'+name+'.blend')))
    bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')),export_format='GLB',export_yup=True,export_animations=False,export_draco_mesh_compression_enable=True)

# Drone origin is its body centre; skids rest 1.03 units below it.
box('Sculpted upper shell',(0,0,.12),(1.9,2.25,.62),cream,.28)
box('Forest underbody',(0,0,-.23),(1.65,1.95,.32),green,.15)
box('Battery spine',(0,.17,.49),(.9,1.45,.2),mint,.1)
for x in [-.6,.6]:
    for y in [.15,.36,.57]:box('Cooling slot',(x,y,.435),(.22,.055,.025),dark,.014)
box('Front power lens',(0,-1.13,.13),(.65,.04,.14),lens,.035)
box('Camera gimbal',(0,-.83,-.49),(.74,.58,.42),green,.14)
opt=cyl('Optical barrel',(0,-1.15,-.49),.22,.25,dark);opt.rotation_euler.x=math.pi/2
opt=cyl('Recessed front glass',(0,-1.285,-.49),.16,.025,mint);opt.rotation_euler.x=math.pi/2
cyl('Air screening inlet',(.58,.2,-.62),.17,.64,brass)
cyl('Inlet perforated crown',(.58,.2,-.95),.24,.1,green)
for i in range(8):
    a=i*math.pi/4;cyl('Inlet perforation',(.58+math.cos(a)*.15,.2+math.sin(a)*.15,-1.012),.025,.02,dark)
for x in [-.78,.78]:
    rod('Landing strut',(x,-.64,-.27),(x,-.64,-.92),.065,green)
    rod('Landing strut',(x,.69,-.27),(x,.69,-.92),.065,green)
    box('Landing skid',(x,0,-.97),(.18,2.6,.13),cream,.06)
for i,(x,y) in enumerate([(-1.8,-1.65),(1.8,-1.65),(-1.8,1.65),(1.8,1.65)]):
    rod('Swept rotor arm',(x*.35,y*.35,-.07),(x,y,.09),.135,green)
    cyl('Motor nacelle',(x,y,.2),.25,.42,cream)
    ring('Protective rotor rim',(x,y,.36),.9,green)
    for a in [0,math.pi/2,math.pi,3*math.pi/2]:
        rod('Rotor guard stay',(x,y,.04),(x+math.cos(a)*.88,y+math.sin(a)*.88,.36),.025,mint)
    pivot=bpy.data.objects.new('Rotor_'+str(i),None);bpy.context.collection.objects.link(pivot);pivot.location=(x,y,.43)
    blade=box('Shaped propeller',(x,y,.43),(1.63,.16,.045),dark,.07)
    blade.parent=pivot;blade.location=(0,0,0)
    cyl('Rotor spindle',(x,y,.47),.105,.14,brass)
export('drone')

bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
box('Sensor foot',(0,0,-.43),(1.45,1.15,.15),green,.06)
box('Weatherproof sensor shell',(0,0,0),(1.25,1,.88),cream,.17)
box('Recessed instrument face',(0,-.51,.02),(.98,.035,.53),dark,.07)
box('Sustained change indicator',(0,-.536,.1),(.65,.025,.1),signal,.025)
for x in [-.3,-.15,0,.15,.3]:box('Sensor grille',(x,-.54,-.12),(.045,.025,.12),mint,.01)
cyl('Sensor inlet',(.3,.15,.62),.075,.4,brass)
cyl('Sensor weather cap',(.3,.15,.85),.2,.08,cream)
export('sensor')
print('STYLIZED INSTRUMENTS EXPORTED',flush=True)
