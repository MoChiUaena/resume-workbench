"""Check actual Chromium PDF outputs, and render every page via Poppler.

Requires pypdf and Poppler on PATH. Run after npm run test:e2e.
"""
from pathlib import Path
from pypdf import PdfReader
import subprocess
import json
import hashlib
import sys

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/pdf'
report = []
cases = [(f'resume-{n}-page', c, 'LocalResumeSans') for n,c in [('one',1),('two',2)]] if '--stage-a' in sys.argv else [(f'stage-b-{template}-{n}', c, 'LocalResumeSerif' if template=='banner' else 'LocalResumeSans') for template in ['classic','banner'] for n,c in [('one',1),('two',2)]]
for name, count, font_name in cases:
    path = OUT / f'{name}.pdf'
    reader = PdfReader(path)
    assert len(reader.pages) == count, f'{name}: unexpected page count'
    texts = [page.extract_text() for page in reader.pages]
    first = texts[0]
    # No NFKC normalization: literal ordinary Chinese must survive extraction.
    for text in ['奶龙', '教育背景', '专业技能', '项目经历', '本地简历工作台', '补充信息']:
        assert text in first, f'{name}: literal text missing: {text!r}'
    positions = [first.index(text) for text in ['奶龙', '教育背景', '专业技能', '项目经历', '实践与学习', '补充信息']]
    assert positions == sorted(positions), 'Unexpected reading order'
    if count == 2:
        for text in ['项目细节', '接口与错误处理', '测试与验证', '后续计划', '长中文段落样本']:
            assert text in texts[1], f'Missing continuation text: {text}'
    for page, text in zip(reader.pages, texts):
        assert abs(float(page.mediabox.width) - 595.276) < 1
        assert abs(float(page.mediabox.height) - 841.89) < 1
        assert '\ufffd' not in text
        assert not any(0x2E80 <= ord(c) <= 0x2FFF for c in text), 'Compatibility radicals replaced normal Chinese'
    font_info = subprocess.run(['pdffonts', str(path)], capture_output=True, check=True).stdout.decode('utf-8', errors='replace')
    assert font_name in font_info
    assert 'Type 3' not in font_info, 'Expected embedded TrueType, not per-glyph Type 3 fonts'
    subprocess.run(['pdftoppm', '-scale-to', '1500', '-png', str(path), str(OUT / name)], check=True, capture_output=True)
    (OUT / f'{name}-text.txt').write_text('\n\f\n'.join(texts), encoding='utf-8')
    report.append({'file': path.name, 'pages': count, 'bytes': path.stat().st_size, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'literalChinese': True, 'readingOrder': True, 'a4': True, 'textCharactersPerPage': [len(t) for t in texts], 'fonts': font_info})
(ROOT / ('output/pdf-verification.json' if '--stage-a' in sys.argv else 'output/pdf-verification-stage-b.json')).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps([{k:v for k,v in r.items() if k != 'fonts'} for r in report], indent=2))
