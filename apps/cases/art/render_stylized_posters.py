"""Static fallbacks rendered from the same original Blender estate, without UI text."""
import bpy, math, json
from pathlib import Path
from mathutils import Vector
ROOT=Path(__file__).resolve().parent;OUT=ROOT.parent/'public/cinematic/stylized'
bpy.ops.wm.open_mainfile(filepath=str(ROOT/'stylized-estate.blend'))
direction=json.loads((ROOT.parent/'src/cinematic/artDirection.json').read_text())
def linear(v):return v/12.92 if v<=.04045 else ((v+.055)/1.055)**2.4
def rgba(hex):return tuple(linear(int(hex[i:i+2],16)/255) for i in (1,3,5))+(1,)
# Match the runtime grade without rebuilding or modifying the original GLB geometry.
for material in bpy.data.materials:
    finish=direction['materials'].get(material.name)
    if not finish:continue
    material.diffuse_color=rgba(finish['color'])
    shader=material.node_tree.nodes.get('Principled BSDF') if material.use_nodes else None
    if shader:
        shader.inputs['Base Color'].default_value=rgba(finish['color'])
        shader.inputs['Roughness'].default_value=finish['roughness']
        shader.inputs['Metallic'].default_value=finish['metalness']
for obj in bpy.context.scene.objects:
    if obj.type!='MESH' or not any(m and m.name=='Painted meadow' for m in obj.data.materials):continue
    colors=obj.data.color_attributes.get('LandscapeTint')
    if not colors:continue
    dark,light=rgba(direction['meadowDark']),rgba(direction['meadowLight'])
    for item in colors.data:
        t=max(0,min(1,(item.color[1]-.25)/.13))
        item.color=tuple(dark[k]+(light[k]-dark[k])*t for k in range(3))+(1,)
def coord(p):return (p[0],-p[2],p[1])
world=bpy.context.scene.world;world.use_nodes=True
world.node_tree.nodes.get('Background').inputs['Color'].default_value=rgba(direction['atmosphere'])
world.node_tree.nodes.get('Background').inputs['Strength'].default_value=.8
light=bpy.data.lights.new('Soft daylight','SUN');light.energy=direction['sunIntensity'];light.angle=.12
sun=bpy.data.objects.new('Soft daylight',light);bpy.context.collection.objects.link(sun);sun.location=coord((-40,90,45));sun.rotation_euler=(-sun.location).to_track_quat('-Z','Y').to_euler()
cloud=bpy.data.materials.new('Cloud ivory');cloud.diffuse_color=rgba(direction['cloudLight']);cloud.use_nodes=True
cloud.node_tree.nodes.get('Principled BSDF').inputs['Base Color'].default_value=rgba(direction['cloudLight'])
cloud.node_tree.nodes.get('Principled BSDF').inputs['Roughness'].default_value=1
smoke=cloud.copy();smoke.name='Illustrative smoke';smoke.diffuse_color=rgba(direction['smoke'])
smoke.node_tree.nodes.get('Principled BSDF').inputs['Base Color'].default_value=rgba(direction['smoke'])
for x,y,z,sx,sy,sz in [(-74,17,20,27,9,18),(70,19,36,30,10,17),(7,19,-93,45,10,18),(-71,25,-58,33,10,18),(91,23,-46,31,9,15),(-29,12,76,26,7,15)]:
    for dx,dy,dz,s in [(-.36,-.1,0,.65),(.12,.13,0,.7),(.58,-.15,0,.4)]:
        bpy.ops.mesh.primitive_uv_sphere_add(segments=24,ring_count=12,radius=1,location=coord((x+dx*sx,y+dy*sy,z+dz*sz)))
        o=bpy.context.object;o.scale=(sx*s,sz*s*.85,sy*s);o.data.materials.append(cloud);o.visible_shadow=False
        for face in o.data.polygons:face.use_smooth=True
layout=json.loads((OUT/'estate.json').read_text())
for x,y,z in layout['emitters']:
    for i in range(5):
        age=i/5
        bpy.ops.mesh.primitive_uv_sphere_add(segments=16,ring_count=8,radius=1,location=coord((x+age*age*7,y+age*10,z+age*2)))
        o=bpy.context.object;o.scale=(.6+age*1.7,)*3;o.data.materials.append(smoke);o.visible_shadow=False
        for face in o.data.polygons:face.use_smooth=True
cam=bpy.data.cameras.new('Opening camera');camera=bpy.data.objects.new('Opening camera',cam);bpy.context.collection.objects.link(camera)
scene=bpy.context.scene;scene.camera=camera;scene.render.engine='CYCLES';scene.cycles.samples=12;scene.cycles.use_denoising=True
scene.render.threads_mode='FIXED';scene.render.threads=4;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='WEBP';scene.render.image_settings.quality=87;scene.view_settings.view_transform='AgX'
scene.view_settings.exposure=.1
for name,pos,target,w,h,fov in [('opening',(100,105,130),(0,20,-10),1440,900,36),('opening-mobile',(107,135,165),(0,24,-9),390,844,42)]:
    camera.location=coord(pos);camera.rotation_euler=(Vector(coord(target))-camera.location).to_track_quat('-Z','Y').to_euler()
    cam.type='PERSP';cam.sensor_fit='VERTICAL';cam.sensor_height=24;cam.lens=24/(2*math.tan(math.radians(fov/2)))
    scene.render.resolution_x=w;scene.render.resolution_y=h;scene.render.filepath=str(OUT/(name+'.webp'));bpy.ops.render.render(write_still=True)
