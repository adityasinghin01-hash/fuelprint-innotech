"""Make delivery JPEGs; retain the checksum-verified originals in the local cache."""
from pathlib import Path
from PIL import Image
import shutil

ROOT = Path(__file__).resolve().parent
PUBLIC = ROOT.parent / 'public/cinematic/textures'
CACHE = ROOT / '.cache/texture-originals'
CACHE.mkdir(parents=True, exist_ok=True)
for path in PUBLIC.glob('*.jpg'):
    source = CACHE / path.name
    if not source.exists():
        shutil.copy2(path, source)
    image = Image.open(source)
    if 'normal' in path.name or 'roughness' in path.name:
        image.thumbnail((512,512), Image.Resampling.LANCZOS)
    image.save(path, quality=88 if 'normal' in path.name else 86, subsampling=0 if 'normal' in path.name else 2, optimize=True)
    print(path.name, source.stat().st_size, '->', path.stat().st_size)
