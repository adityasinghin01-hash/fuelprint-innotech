"""Prepare web delivery copies; editable original Blender sources are preserved."""
import bpy
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parent
OUT=ROOT.parent/'public/cinematic'
names=[] if '--sky-only' in sys.argv else ['tree-mobile'] if '--mobile-only' in sys.argv else ['factory','pipe-kit','tree','tree-mobile']
for name in names:
    bpy.ops.wm.open_mainfile(filepath=str(ROOT/(('tree' if name=='tree-mobile' else name)+'.blend')))
    if name.startswith('tree'):
        for o in bpy.context.scene.objects:
            if o.type!='MESH':continue
            bpy.context.view_layer.objects.active=o
            modifier=o.modifiers.new('Web foliage topology','DECIMATE')
            modifier.ratio=.045 if name=='tree-mobile' else .12
            bpy.ops.object.modifier_apply(modifier=modifier.name)
    limit=512 if name=='tree-mobile' else 1024
    for image in bpy.data.images:
        if image.size[0]>limit or image.size[1]>limit:image.scale(limit,limit)
    bpy.ops.export_scene.gltf(filepath=str(OUT/(name+'.glb')),export_format='GLB',export_yup=True,export_animations=False,export_image_format='WEBP',export_image_quality=82,export_draco_mesh_compression_enable=True,export_draco_mesh_compression_level=7)
    print('OPTIMIZED',name,(OUT/(name+'.glb')).stat().st_size,flush=True)
sky=bpy.data.images.load(str(OUT/'daylight.hdr'))
sky.scale(512,256);sky.file_format='HDR';sky.filepath_raw=str(OUT/'daylight-mobile.hdr');sky.save()
