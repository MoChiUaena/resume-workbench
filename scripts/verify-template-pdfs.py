"""Verify and render the new layouts' real Chromium PDF exports, including page boundaries."""
from pathlib import Path
from pypdf import PdfReader
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/pdf'
report = []
for template in ['card', 'rail']:
    for sample, count in [('one', 1), ('two', 2), ('edge', None)]:
        name = f'template-{template}-{sample}'
        pdf = OUT / f'{name}.pdf'
        reader = PdfReader(pdf)
        texts = [page.extract_text() for page in reader.pages]
        # A narrow title rail can wrap headings; remove layout whitespace, never normalize glyphs.
        logical = [re.sub(r'\s+', '', text) for text in texts]
        if count is None:
            assert 2 < len(reader.pages) <= 10, f'{name}: unsafe page count'
            combined = '\n'.join(texts)
            for i in range(30):
                assert combined.count(f'模板条目{i:02d}：') == 1, f'{name}: missing/duplicated paragraph {i}'
        else:
            assert len(reader.pages) == count, f'{name}: expected {count} pages'
            first = logical[0]
            ordered = ['奶龙', '教育背景', '专业技能', '项目经历', '实践与学习', '补充信息']
            assert all(text in first for text in ordered), f'{name}: missing literal Chinese'
            positions = [first.index(text) for text in ordered]
            assert positions == sorted(positions), f'{name}: wrong reading order'
            if count == 2:
                assert all(text in logical[1] for text in ['项目细节', '接口与错误处理', '测试与验证', '后续计划', '长中文段落样本']), f'{name}: missing continuation headings'
        assert len(reader.pages[0].images) >= 2, f'{name}: missing photo/logo'
        for page, text in zip(reader.pages, texts):
            assert abs(float(page.mediabox.width) - 595.276) < 1
            assert abs(float(page.mediabox.height) - 841.89) < 1
            assert '\ufffd' not in text and not any(0x2E80 <= ord(c) <= 0x2FFF for c in text)
        fonts = subprocess.check_output(['pdffonts', str(pdf)]).decode(errors='replace')
        assert 'LocalResumeSans' in fonts and 'Type 3' not in fonts
        subprocess.run(['pdftoppm', '-scale-to', '1500', '-png', str(pdf), str(OUT / name)], check=True, capture_output=True)
        report.append({'file': pdf.name, 'pages': len(reader.pages), 'literalChinese': True, 'readingOrder': True if count is not None else None, 'paragraphCountsVerified': True if count is None else None, 'a4': True, 'photoAndLogo': True, 'fonts': fonts})
(ROOT / 'output/template-pdf-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps([{k: v for k, v in item.items() if k != 'fonts'} for item in report], ensure_ascii=False, indent=2))
