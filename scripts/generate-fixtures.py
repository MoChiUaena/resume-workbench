"""Original synthetic fixtures; requires Pillow. Never uses a real person's image."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "fixtures"
OUT.mkdir(exist_ok=True)
FONT = ROOT / "src/main/resources/static/fonts/LocalResumeSans-Regular.ttf"
def font(n): return ImageFont.truetype(str(FONT), n)

logo = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
d = ImageDraw.Draw(logo)
d.ellipse((22, 22, 490, 490), outline="#244f63", width=12)
d.ellipse((44, 44, 468, 468), outline="#8caab5", width=3)
d.text((256, 115), "示例理工", font=font(49), fill="#244f63", anchor="mm")
d.polygon([(126, 232), (256, 166), (386, 232)], fill="#244f63")
d.rectangle((124, 246, 388, 257), fill="#244f63")
for x in (148, 214, 280, 346): d.rectangle((x, 268, x + 20, 339), fill="#244f63")
d.rectangle((124, 350, 388, 365), fill="#244f63")
d.text((256, 409), "SAMPLE · 2026", font=font(25), fill="#244f63", anchor="mm")
logo.save(OUT / "university-logo.png")

portrait = Image.new("RGB", (360, 480), "#dce9ee")
d = ImageDraw.Draw(portrait)
d.rectangle((16, 16, 58, 58), fill="#dd5b47")
d.rectangle((302, 16, 344, 58), fill="#438666")
d.text((180, 32), "TOP / 正向", font=font(18), fill="#47616d", anchor="mm")
d.ellipse((27, 288, 333, 590), fill="#2d5369")
d.polygon([(127, 307), (180, 349), (234, 307), (220, 440), (141, 440)], fill="#faf8ef")
d.rectangle((153, 245, 207, 314), fill="#e7b99b")
d.ellipse((98, 93, 262, 277), fill="#efcbb2")
d.pieslice((91, 67, 270, 213), 180, 360, fill="#304048")
d.polygon([(95, 139), (111, 92), (189, 78), (252, 107), (264, 145), (236, 126), (219, 103), (150, 124)], fill="#304048")
d.ellipse((134, 171, 143, 180), fill="#4f4340")
d.ellipse((217, 171, 226, 180), fill="#4f4340")
d.arc((159, 197, 207, 231), 10, 165, fill="#9a6555", width=3)
d.rectangle((0, 440, 360, 480), fill="#23485a")
d.text((180, 460), "合成证件照 · SYNTHETIC", font=font(16), fill="#ffffff", anchor="mm")
portrait.save(OUT / "portrait-upright.jpg", quality=93)
exif = Image.Exif(); exif[274] = 6
portrait.transpose(Image.Transpose.ROTATE_90).save(OUT / "portrait-exif-6.jpg", quality=93, exif=exif)
(OUT / "corrupt.png").write_bytes(b"\x89PNG\r\n\x1a\ncorrupted-file")
(OUT / "unsupported.svg").write_text('<svg xmlns="http://www.w3.org/2000/svg"><rect width="8" height="8"/></svg>', encoding="utf-8")
samples = ROOT / "src/main/resources/static/samples"
samples.mkdir(parents=True, exist_ok=True)
for name in ("university-logo.png", "portrait-exif-6.jpg"):
    shutil.copyfile(OUT / name, samples / name)
for f in sorted(OUT.iterdir()): print(f.name, f.stat().st_size)
