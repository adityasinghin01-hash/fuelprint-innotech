"""Fetch the approved CC0 source assets; run only during asset preparation."""
import concurrent.futures
import hashlib
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent / '.cache'
PUBLIC = Path(__file__).resolve().parents[1] / 'public' / 'cinematic'
HEADERS = {'User-Agent': 'FuelPrint-asset-preparation/1.0'}

def request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90).read()

def fetch(item):
    path, entry = item
    if path.exists() and hashlib.md5(path.read_bytes()).hexdigest() == entry.get('md5'):
        return
    data = request(entry['url'])
    if entry.get('md5') and hashlib.md5(data).hexdigest() != entry['md5']:
        raise ValueError('Source asset checksum mismatch: ' + path.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)

items = []
for asset in ['modular_factory_facade', 'modular_industrial_pipes_01', 'tree_small_02']:
    metadata = json.loads(request('https://api.polyhaven.com/files/' + asset))
    entry = metadata['gltf']['1k']['gltf']
    folder = ROOT / asset
    items.append((folder / (asset + '_1k.gltf'), entry))
    items.extend((folder / name, file) for name, file in entry['include'].items())
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'source.json').write_text(json.dumps(entry, indent=2))
    (folder / 'attribution.json').write_bytes(request('https://api.polyhaven.com/info/' + asset))
for asset, short in [('asphalt_02', 'asphalt'), ('concrete_floor_02', 'concrete'), ('grass_ground', 'grass')]:
    metadata=json.loads(request('https://api.polyhaven.com/files/' + asset))
    for key, suffix in [('Diffuse','color'),('nor_gl','normal'),('Rough','roughness')]:
        entry=metadata[key]['1k']['jpg']
        items.append((PUBLIC / 'textures' / (short+'-'+suffix+'.jpg'),entry))
sky = json.loads(request('https://api.polyhaven.com/files/kloofendal_48d_partly_cloudy_puresky'))
items.append((PUBLIC / 'daylight.hdr', sky['hdri']['1k']['hdr']))
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
    list(pool.map(fetch, items))
from PIL import Image, ImageOps
# Art-directed cast-concrete albedo, retaining the sourced surface detail.
source=ImageOps.autocontrast(ImageOps.grayscale(Image.open(PUBLIC/'textures/concrete-color.jpg')))
ImageOps.colorize(source,(150,155,147),(210,215,204)).save(PUBLIC/'textures/courtyard-color.jpg',quality=86)
print('Verified', len(items), 'CC0 asset resources.')
