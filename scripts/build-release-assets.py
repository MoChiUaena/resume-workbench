"""Package version-matched public configuration and license materials, never .env/data."""
from pathlib import Path
import hashlib
import shutil
import zipfile
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'output/release'
OUT.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(OUT/'resume-workbench-config.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for name in ['compose.yml','.env.example']:archive.write(ROOT/name,name)
with zipfile.ZipFile(OUT/'dependency-notices.zip','w',zipfile.ZIP_DEFLATED) as archive:
    for name in ['LICENSE','THIRD_PARTY_NOTICES.md','docs/dependency-licenses.md']:
        archive.write(ROOT/name,name)
    for path in (ROOT/'src/main/resources/META-INF/third-party').rglob('*'):
        if path.is_file():archive.write(path,'third-party/'+path.relative_to(ROOT/'src/main/resources/META-INF/third-party').as_posix())
    for name in ['OFL.txt','OFL-Serif.txt']:
        archive.write(ROOT/'src/main/resources/static/fonts'/name,'fonts/'+name)
shutil.copy2(ROOT/'docs/demo.gif',OUT/'resume-workbench-demo.gif')
video=ROOT/'output/demo/resume-workbench-demo.webm'
if video.exists():shutil.copy2(video,OUT/video.name)
items=sorted(path for path in OUT.iterdir() if path.is_file() and path.name!='SHA256SUMS.txt')
(OUT/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n' for path in items),encoding='utf8')
print('Release assets:',', '.join(path.name for path in items))
