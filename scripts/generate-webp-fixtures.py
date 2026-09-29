"""Create WebP test images from the project's original synthetic JPEG/PNG fixtures."""
from pathlib import Path
from PIL import Image, features

fixtures = Path(__file__).resolve().parents[1] / 'fixtures'
with Image.open(fixtures / 'university-logo.png') as logo:
    logo.save(fixtures / 'university-logo-lossless.webp', lossless=True, exact=True, method=6)
    logo.save(fixtures / 'university-logo-lossy.webp', quality=85, method=6)
with Image.open(fixtures / 'portrait-upright.jpg') as portrait:
    portrait.save(fixtures / 'portrait-lossy.webp', quality=85, method=6)
with Image.open(fixtures / 'portrait-exif-6.jpg') as portrait:
    portrait.save(fixtures / 'portrait-exif-6.webp', lossless=True, exact=True, method=6, exif=portrait.getexif().tobytes())
first = Image.new('RGBA', (16, 16), '#dd5b47')
second = Image.new('RGBA', (16, 16), '#438666')
first.save(fixtures / 'animated.webp', save_all=True, append_images=[second], duration=100, loop=0, lossless=True)
valid = (fixtures / 'portrait-lossy.webp').read_bytes()
(fixtures / 'corrupt.webp').write_bytes(valid[:32])
print('Pillow WebP codec:', features.version('webp'))
for path in sorted(fixtures.glob('*.webp')):
    print(path.name, path.stat().st_size)
