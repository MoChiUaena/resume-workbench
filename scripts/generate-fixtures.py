"""Original synthetic fixtures; requires Pillow. Never uses a person's image."""
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
# Small red/green orientation stamps are also used by the EXIF test.
d.rectangle((17, 17, 49, 49), fill="#dd5b47")
d.rectangle((311, 17, 343, 49), fill="#438666")
d.text((180, 34), "奶龙 · 正向", font=font(18), fill="#47616d", anchor="mm")

# A new, source-controlled cartoon drawing: yellow dragon, round face, little
# horns and cream belly. It contains no photo or image copied from the web.
d.ellipse((43, 284, 317, 581), fill="#f3bf4f", outline="#ca8b31", width=4)
d.ellipse((105, 319, 255, 517), fill="#ffe8ab")
d.ellipse((28, 330, 112, 440), fill="#efb543")
d.ellipse((248, 330, 332, 440), fill="#efb543")
d.polygon([(112, 141), (125, 85), (153, 132)], fill="#f1b64a")
d.polygon([(207, 132), (235, 85), (248, 141)], fill="#f1b64a")
d.ellipse((71, 187, 118, 247), fill="#edaa3e")
d.ellipse((242, 187, 289, 247), fill="#edaa3e")
d.ellipse((83, 112, 277, 336), fill="#f7c657", outline="#d9a13d", width=3)
d.ellipse((117, 214, 243, 301), fill="#ffe1a0")
d.ellipse((129, 181, 146, 202), fill="#342f2d")
d.ellipse((214, 181, 231, 202), fill="#342f2d")
d.ellipse((133, 185, 138, 191), fill="#fffef7")
d.ellipse((218, 185, 223, 191), fill="#fffef7")
d.ellipse((105, 222, 127, 239), fill="#ed9276")
d.ellipse((233, 222, 255, 239), fill="#ed9276")
d.ellipse((165, 243, 172, 250), fill="#bd8a48")
d.ellipse((188, 243, 195, 250), fill="#bd8a48")
d.arc((162, 245, 198, 274), 12, 170, fill="#9c6647", width=3)
d.rectangle((0, 440, 360, 480), fill="#23485a")
d.text((180, 460), "奶龙 · 合成示例", font=font(18), fill="#ffffff", anchor="mm")
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
