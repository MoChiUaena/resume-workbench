"""Build a short README demonstration from real application screenshots."""
from pathlib import Path
import json
from PIL import Image,ImageOps,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
source=ROOT/'output/demo'
font=ImageFont.truetype(str(ROOT/'src/main/resources/static/fonts/LocalResumeSans-Regular.ttf'),22)
frames=[]
for index,item in enumerate(json.loads((source/'frames.json').read_text(encoding='utf-8'))):
    screenshot=Image.open(source/item['file']).convert('RGB')
    picture=Image.new('RGB',(1080,775),'#ffffff');picture.paste(ImageOps.contain(screenshot,(1080,720)),(0,0))
    draw=ImageDraw.Draw(picture);draw.rectangle((0,720,1080,775),fill='#f4f6f9')
    draw.text((25,736),f'{index+1} / 6   '+item['caption'],font=font,fill='#1e2d43')
    frames.append(picture.quantize(colors=128))
destination=ROOT/'docs/demo.gif'
frames[0].save(destination,save_all=True,append_images=frames[1:],duration=[3200]*6,loop=0,optimize=True)
print(destination.name,destination.stat().st_size,'bytes; 6 real steps, 19.2 seconds')
