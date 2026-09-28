"""Inspect actual PDF outputs from the layout-control browser tests."""
from pathlib import Path
from pypdf import PdfReader
import subprocess
import json

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/pdf'
report = []
for name in ['layout-styled', 'layout-reset', 'layout-extreme']:
    pdf = OUT / (name + '.pdf')
    reader = PdfReader(pdf)
    texts = [page.extract_text() for page in reader.pages]
    assert '奶龙' in texts[0] and '项目经历' in texts[0]
    assert all('\ufffd' not in text for text in texts)
    assert all(not any(0x2E80 <= ord(c) <= 0x2FFF for c in text) for text in texts)
    for page in reader.pages:
        assert abs(float(page.mediabox.width) - 595.276) < 1
        assert abs(float(page.mediabox.height) - 841.89) < 1
    if name == 'layout-extreme':
        assert 2 < len(reader.pages) <= 10
        combined = '\n'.join(texts)
        for i in range(30):
            assert combined.count(f'排版条目{i:02d}：') == 1
    else:
        assert len(reader.pages) == 1
        for text in ['教育背景', '专业技能', '实践与学习', '补充信息']:
            assert text in texts[0]
    fonts = subprocess.check_output(['pdffonts', str(pdf)]).decode(errors='replace')
    assert ('LocalResumeSans' if name == 'layout-extreme' else 'LocalResumeSerif') in fonts
    assert 'Type 3' not in fonts
    subprocess.run(['pdftoppm', '-scale-to', '1500', '-png', str(pdf), str(OUT / name)], check=True, capture_output=True)
    report.append({'file': pdf.name, 'pages': len(reader.pages), 'chineseText': True, 'a4': True, 'fonts': fonts})
(ROOT / 'output/layout-pdf-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps([{k:v for k,v in item.items() if k != 'fonts'} for item in report], ensure_ascii=False, indent=2))
