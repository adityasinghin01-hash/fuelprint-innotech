"""Offline composition/fallback renders, not a substitute for browser verification."""
import bpy, math, sys
from pathlib import Path
from mathutils import Vector
ROOT=Path(__file__).resolve().parent
OUT=ROOT.parent/'public/cinematic/posters';OUT.mkdir(parents=True,exist_ok=True)
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
def coord(v):return (v[0],-v[2],v[1])
def model(name,position,scale=1):
    before=set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(ROOT.parent/'public/cinematic'/(name+'.glb')))
    loaded=[o for o in bpy.data.objects if o not in before]
    root=bpy.data.objects.new(name+'-root',None);bpy.context.collection.objects.link(root)
    for o in loaded:
        if not o.parent:o.parent=root
    root.location=coord(position);root.scale=(scale,)*3
    return root
def mat(name,color):
    m=bpy.data.materials.new(name);m.diffuse_color=(*color,1);m.use_nodes=True
    m.node_tree.nodes.get('Principled BSDF').inputs['Base Color'].default_value=(*color,1)
    m.node_tree.nodes.get('Principled BSDF').inputs['Roughness'].default_value=.85
    return m
def box(name,pos,size,material):
    bpy.ops.mesh.primitive_cube_add(size=1,location=coord(pos));o=bpy.context.object;o.name=name;o.dimensions=(size[0],size[2],size[1]);o.data.materials.append(material);return o
def texmat(name):
    m=mat(name,(.5,.5,.5));nodes=m.node_tree.nodes;bsdf=nodes.get('Principled BSDF')
    tex=nodes.new('ShaderNodeTexImage');tex.image=bpy.data.images.load(str(ROOT.parent/'public/cinematic/textures'/(('courtyard' if name=='concrete' else name)+'-color.jpg')))
    coords=nodes.new('ShaderNodeTexCoord');mapping=nodes.new('ShaderNodeVectorMath');mapping.operation='SCALE';mapping.inputs[3].default_value=1200 if name=='grass' else 30
    m.node_tree.links.new(coords.outputs['Generated'],mapping.inputs[0]);m.node_tree.links.new(mapping.outputs[0],tex.inputs['Vector']);m.node_tree.links.new(tex.outputs['Color'],bsdf.inputs['Base Color']);return m
model('factory',(0,.06,-2))
for x,z,s in [(51,-37,.8),(-35,-60,.65),(55,-86,.65)]:model('factory',(x,0,z),s)
station=model('monitoring-station',(-3,.18,3.4));station.rotation_euler.z=-.1
drone=model('monitoring-drone',(28,5.4,-2));drone.rotation_euler.z=.35
model('pipe-kit',(26,.2,-13),1.2)
ground=mat('Estate ground',(.54,.57,.43));curb=mat('Road kerb',(.64,.65,.56));line=mat('Road markings',(.8,.8,.68))
box('Ground',(0,-.2,-15),(5000,.3,5000),texmat('grass'))
box('Service court',(12,-.025,-7),(50,.12,42),texmat('concrete'))
box('Access road',(0,.015,9),(260,.1,8.5),texmat('asphalt'))
box('Cross road',(40,.01,-25),(8.5,.1,180),bpy.data.materials['asphalt'])
for z in [4.4,13.6]:box('Kerb',(0,.17,z),(240,.28,.24),curb)
for x in range(-90,100,8):box('Centre mark',(x,.075,9),(3.4,.012,.1),line)
for i,(x,z) in enumerate([(-20,0),(-28,-14),(-24,-28),(-33,-41),(35,-7),(35,-22),(35,-40),(50,22),(66,22),(-40,19),(-57,19)]):
    t=model('tree',(x,0,z))
    meshes=[o for o in t.children_recursive if o.type=='MESH']
    bounds=[o.matrix_world@Vector(p) for o in meshes for p in o.bound_box]
    h=max(p.z for p in bounds)-min(p.z for p in bounds)
    t.scale=( (6+i%3*.7)/max(h,.1),)*3;t.rotation_euler.z=i*1.7
world=bpy.context.scene.world;world.use_nodes=True;nodes=world.node_tree.nodes
env=nodes.new('ShaderNodeTexEnvironment');env.image=bpy.data.images.load(str(ROOT.parent/'public/cinematic/daylight.hdr'))
world.node_tree.links.new(env.outputs['Color'],nodes.get('Background').inputs['Color']);nodes.get('Background').inputs['Strength'].default_value=.5
sky=nodes.new('ShaderNodeBackground');sky.inputs['Color'].default_value=(.72,.80,.78,1);sky.inputs['Strength'].default_value=.8
path=nodes.new('ShaderNodeLightPath');mix=nodes.new('ShaderNodeMixShader')
world.node_tree.links.new(path.outputs['Is Camera Ray'],mix.inputs[0]);world.node_tree.links.new(nodes.get('Background').outputs[0],mix.inputs[1]);world.node_tree.links.new(sky.outputs[0],mix.inputs[2]);world.node_tree.links.new(mix.outputs[0],nodes.get('World Output').inputs['Surface'])
light=bpy.data.lights.new('Daylight','SUN');light.energy=2.7;light.angle=.08
sun=bpy.data.objects.new('Daylight',light);bpy.context.collection.objects.link(sun);sun.location=coord((-35,58,30));sun.rotation_euler=(Vector(coord((10,0,-5)))-sun.location).to_track_quat('-Z','Y').to_euler()
cam=bpy.data.cameras.new('Cinematic camera');camera=bpy.data.objects.new('Cinematic camera',cam);bpy.context.collection.objects.link(camera);bpy.context.scene.camera=camera
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=8;scene.cycles.use_denoising=True
scene.render.threads_mode='FIXED';scene.render.threads=4
scene.render.resolution_percentage=100;scene.render.image_settings.file_format='WEBP';scene.render.image_settings.quality=86
scene.view_settings.view_transform='AgX';scene.view_settings.look='AgX - Medium High Contrast'
shots=[('estate',(52,30,56),(0,5,-5),1280,800,36),('estate-mobile',(46,35,64),(12,13,-4),390,764,38)]
for name,pos,target,w,h,fov in shots:
    if name=='drone':drone.location=coord((13,4.2,14));drone.rotation_euler.z=-.4
    camera.location=coord(pos);camera.rotation_euler=(Vector(coord(target))-camera.location).to_track_quat('-Z','Y').to_euler()
    cam.type='PERSP';cam.sensor_fit='VERTICAL';cam.sensor_height=24;cam.lens=24/(2*math.tan(math.radians(fov/2)))
    scene.render.resolution_x=w;scene.render.resolution_y=h;scene.render.filepath=str(OUT/(name+'.webp'))
    bpy.ops.render.render(write_still=True)
    print('RENDERED',name,flush=True)
