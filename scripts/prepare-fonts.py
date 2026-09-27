"""Derive static OFL fonts with unambiguous cmap for Chromium PDF text extraction.

Requires fonttools 4.60.1. Input is the verified upstream variable font in
.tools/fonts/NotoSansSC-variable.ttf. Derived binaries are checked in so ordinary
builds do not need Python, network access, or font generation.
"""
from pathlib import Path
import hashlib
import unicodedata
import json
import sys
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / '.tools/python'))
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

source = ROOT / '.tools/fonts/NotoSansSC-variable.ttf'
expected = 'a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da'
assert hashlib.sha256(source.read_bytes()).hexdigest() == expected
out = ROOT / 'src/main/resources/static/fonts'
manifest = {'source': 'https://raw.githubusercontent.com/google/fonts/main/ofl/notosanssc/NotoSansSC%5Bwght%5D.ttf', 'sourceSha256': expected, 'license': 'OFL-1.1', 'fonttools': '4.60.1', 'derived': []}
for weight, label in [(400, 'Regular'), (700, 'Bold')]:
    font = instantiateVariableFont(TTFont(source), {'wght': weight}, inplace=True)
    # Skia's reverse cmap may prefer U+2F8F (Kangxi radical) for ordinary 行.
    # Drop compatibility aliases sharing glyphs with their NFKC form, and
    # radicals sharing glyphs with unified CJK (some simplified radicals have
    # no NFKC mapping, or map to a traditional character with a different glyph).
    # Keep unified CJK code points intact; this is not a sample-text subset.
    removed = set()
    for table in font['cmap'].tables:
        if not table.isUnicode() or not hasattr(table, 'cmap'): continue
        cjk_glyphs = {g for cp, g in table.cmap.items() if 0x3400 <= cp <= 0x9FFF}
        for cp, glyph in list(table.cmap.items()):
            normal = unicodedata.normalize('NFKC', chr(cp))
            nfkc_alias = normal != chr(cp) and len(normal) == 1 and table.cmap.get(ord(normal)) == glyph
            radical_alias = 0x2E80 <= cp <= 0x2FFF and glyph in cjk_glyphs
            if nfkc_alias or radical_alias:
                del table.cmap[cp]; removed.add(cp)
    for record in font['name'].names:
        new = {1: 'Local Resume Sans', 2: label, 3: f'LocalResumeSans-{label}-StageA', 4: f'Local Resume Sans {label}', 6: f'LocalResumeSans-{label}', 16: 'Local Resume Sans', 17: label}.get(record.nameID)
        if new: record.string = new.encode(record.getEncoding(), errors='replace')
    destination = out / f'LocalResumeSans-{label}.ttf'
    font.save(destination)
    manifest['derived'].append({'file': destination.name, 'weight': weight, 'sha256': hashlib.sha256(destination.read_bytes()).hexdigest(), 'removedCompatibilityAliases': len(removed)})
    print(destination.name, destination.stat().st_size, 'bytes;', len(removed), 'ambiguous compatibility aliases removed')
(out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
