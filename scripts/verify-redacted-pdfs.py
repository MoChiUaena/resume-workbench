"""Check literal text, removed/kept image streams, metadata, A4 and render export-only projections."""
from pathlib import Path
import json,re,subprocess
from pypdf import PdfReader

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'output/pdf'
report=[]
for template in ['classic','banner','card','rail']:
    for sample,count in [('one',1),('two',2)]:
        name=f'redacted-{template}-{sample}'
        reader=PdfReader(OUT/f'{name}.pdf')
        assert len(reader.pages)==count, name
        text='\n'.join(p.extract_text() for p in reader.pages)
        literal=re.sub(r'\s+','',text)
        for hidden in ['奶龙','13800000000','nailong@example.invalid','杭州·2027届']:
            assert hidden not in literal, f'{name}: identifier still present'
            assert hidden not in str(reader.metadata), f'{name}: identifier in metadata'
        assert all(value in literal for value in ['候选人','教育背景','专业技能','项目经历','（电话已隐藏）','（邮箱已隐藏）','（位置已隐藏）']), name
        for page in reader.pages:
            assert len(page.images)==0, f'{name}: hidden image bytes present'
            assert abs(float(page.mediabox.width)-595.276)<1 and abs(float(page.mediabox.height)-841.89)<1
            assert '候选人' in page.extract_text(), f'{name}: continuation/footer placeholder missing'
        positions=[literal.index(value) for value in ['教育背景','专业技能','项目经历','实践与学习','补充信息']]
        assert positions==sorted(positions), f'{name}: reading order changed'
        assert '\ufffd' not in text and not any(0x2E80<=ord(c)<=0x2FFF for c in text)
        fonts=subprocess.check_output(['pdffonts',str(OUT/f'{name}.pdf')]).decode(errors='replace')
        assert 'LocalResumeSans' in fonts and 'Type 3' not in fonts
        subprocess.run(['pdftoppm','-scale-to','1500','-png',str(OUT/f'{name}.pdf'),str(OUT/name)],check=True,capture_output=True)
        report.append({'file':name+'.pdf','pages':count,'knownTextIdentifiersRemoved':True,'imageStreamsRemoved':True,'a4':True,'readingOrder':True})
name='redacted-selective';reader=PdfReader(OUT/f'{name}.pdf');text='\n'.join(p.extract_text() for p in reader.pages)
assert len(reader.pages)==1 and '138 0000 0000' in text and '候选人' in text
assert '奶龙' not in text and 'nailong@example.invalid' not in text
assert len(reader.pages[0].images)==1, 'Only the deliberately kept portrait may remain'
subprocess.run(['pdftoppm','-scale-to','1500','-png',str(OUT/f'{name}.pdf'),str(OUT/name)],check=True,capture_output=True)
report.append({'file':name+'.pdf','pages':1,'selectedPhoneAndPortraitKept':True,'otherChosenFieldsRemoved':True})
name='redacted-restored';reader=PdfReader(OUT/f'{name}.pdf');text='\n'.join(p.extract_text() for p in reader.pages)
assert len(reader.pages)==2 and '候选人' in text and '奶龙' not in text and 'nailong@example.invalid' not in text
assert all(len(page.images)==0 for page in reader.pages)
subprocess.run(['pdftoppm','-scale-to','1500','-png',str(OUT/f'{name}.pdf'),str(OUT/name)],check=True,capture_output=True)
report.append({'file':name+'.pdf','pages':2,'restoredRedactionRetained':True,'imageStreamsRemoved':True})
(ROOT/'output/redacted-pdf-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
print(json.dumps(report,ensure_ascii=False,indent=2))
