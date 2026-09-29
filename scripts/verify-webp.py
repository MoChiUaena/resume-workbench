"""Verify synthetic WebP images, Chinese PDFs and restore in isolated workspaces."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import runpy
import uuid
from urllib.parse import urlparse
import zipfile
from pypdf import PdfReader

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output'
REPORT = OUT / 'webp-verification.json'
helpers = runpy.run_path(str(ROOT / 'scripts/verify-backup.py'))
call, upload, save, archive, digest = (helpers[name] for name in ['call', 'upload', 'save', 'archive', 'digest'])

def pdf_checks(content, pages, font):
    reader = PdfReader(io.BytesIO(content))
    assert len(reader.pages) == pages
    first = reader.pages[0].extract_text()
    assert all(word in first for word in ['奶龙', '教育背景', '专业技能', '项目经历'])
    for page in reader.pages:
        text = page.extract_text()
        assert '\ufffd' not in text and not any(0x2e80 <= ord(c) <= 0x2fff for c in text)
        assert abs(float(page.mediabox.width) - 595.276) < 1 and abs(float(page.mediabox.height) - 841.89) < 1
    fonts = [str(value.get_object()['/BaseFont']) for value in reader.pages[0]['/Resources']['/Font'].get_object().values()]
    assert any(font in value for value in fonts)
    images = [item.image.convert('RGBA') for item in reader.pages[0].images]
    logo = next(image for image in images if image.size == (512, 512))
    assert logo.getpixel((0, 0))[3] == 0 and logo.getpixel((256, 232))[3] == 255
    photo = next(image for image in images if image.size == (360, 480))
    red = photo.getpixel((30, 30))
    assert red[0] > red[1] + 80, 'WebP photo orientation changed in PDF'
    return {'pages': pages, 'a4': True, 'literalChinese': True, 'transparentLogo': True, 'uprightPhoto': True}

def run(source, target):
    (OUT / 'pdf').mkdir(parents=True, exist_ok=True)
    existing = {row['id']: digest(call(target, '/api/resumes/' + row['id'])) for row in call(target, '/api/resumes')}
    token = uuid.uuid4().hex[:8]
    records, pdfs = [], []
    logo = upload(source, '/api/assets', 'logo.jpg', (ROOT / 'fixtures/university-logo-lossless.webp').read_bytes())
    assert logo['format'] == 'WEBP'
    for sample in ['one', 'two']:
        filename = 'portrait-lossy.webp' if sample == 'one' else 'portrait-exif-6.webp'
        photo = upload(source, '/api/assets', 'photo.webp', (ROOT / 'fixtures' / filename).read_bytes())
        assert photo['format'] == 'WEBP' and photo['exifOrientation'] == (1 if sample == 'one' else 6)
        resume = call(source, '/api/resumes', {'title': f'WebP {token} · {sample}', 'sample': sample})
        resume['document']['layout']['photo'].update(id=photo['id'], fit='contain')
        resume['document']['layout']['logo']['id'] = logo['id']
        for template in ['classic', 'banner', 'card', 'rail']:
            resume['document']['layout']['template'] = template
            resume['document']['layout']['font'] = 'serif' if template == 'banner' else 'sans'
            resume = save(source, resume)
            exported = call(source, '/api/resumes/' + resume['id'] + '/export', {'expectedRevision': resume['revision']})
            content = call(source, '/api/exports/' + exported['id'] + '/pdf', raw=True)
            assert hashlib.sha256(content).hexdigest() == exported['sha256']
            checked = pdf_checks(content, 1 if sample == 'one' else 2, 'LocalResumeSerif' if template == 'banner' else 'LocalResumeSans')
            path = OUT / 'pdf' / f'webp-{template}-{sample}.pdf'
            path.write_bytes(content)
            pdfs.append({'file': path.name, 'sha256': exported['sha256'], **checked})
        call(source, '/api/resumes/' + resume['id'] + '/versions', {'expectedRevision': resume['revision'], 'title': 'WebP 图片基线'})
        records.append({'sourceId': resume['id'], 'sourceDigest': digest(resume), 'title': resume['title'], 'photo': photo, 'logo': logo})
    original = call(source, '/api/resumes/' + records[-1]['sourceId'])
    options = {name: True for name in ['name', 'phone', 'email', 'location', 'photo', 'logo', 'matchingText']}
    preview = call(source, '/api/resumes/' + original['id'] + '/export/preview', {'expectedRevision': original['revision'], 'redaction': options})
    redacted = call(source, '/api/resumes/' + original['id'] + '/export', {'expectedRevision': original['revision'], 'redaction': options, 'previewDigest': preview['digest']})
    redacted_bytes = call(source, '/api/exports/' + redacted['id'] + '/pdf', raw=True)
    redacted_pdf = PdfReader(io.BytesIO(redacted_bytes))
    assert len(redacted_pdf.pages) == 2 and all(len(page.images) == 0 for page in redacted_pdf.pages)
    assert '奶龙' not in '\n'.join(page.extract_text() for page in redacted_pdf.pages)
    (OUT / 'pdf/webp-redacted.pdf').write_bytes(redacted_bytes)
    backup, zipped = archive(source)
    (OUT / 'webp-cross-instance.zip').write_bytes(zipped)
    with zipfile.ZipFile(io.BytesIO(zipped)) as bundle:
        for record in records:
            for slot in ['photo', 'logo']:
                asset = record[slot]
                assert hashlib.sha256(bundle.read('attachments/' + asset['id'] + '/original.webp')).hexdigest() == asset['sha256']
    restored = upload(target, '/api/backups/restore', 'webp.zip', zipped)
    imported = [call(target, '/api/resumes/' + identity) for identity in restored['resumeIds']]
    for identity, fingerprint in existing.items():
        assert digest(call(target, '/api/resumes/' + identity)) == fingerprint, 'Existing target record changed'
    for record in records:
        recovered = next(row for row in imported if row['title'] == record['title'])
        current = call(source, '/api/resumes/' + record['sourceId'])
        assert digest(current) == record['sourceDigest']
        assert recovered['document']['content'] == current['document']['content'] and recovered['revision'] == current['revision']
        record['targetId'] = recovered['id']
        record['targetDigest'] = digest(recovered)
        for slot in ['photo', 'logo']:
            asset_id = recovered['document']['layout'][slot]['id']
            asset = call(target, '/api/assets/' + asset_id)
            expected = record[slot]
            assert asset_id != expected['id'] and asset['format'] == 'WEBP'
            assert all(asset[key] == expected[key] for key in ['sha256', 'normalizedSha256', 'exifOrientation', 'width', 'height'])
            assert hashlib.sha256(call(target, '/api/assets/' + asset_id + '/image', raw=True)).hexdigest() == expected['normalizedSha256']
            record['target' + slot.title()] = asset_id
        versions = call(target, '/api/resumes/' + recovered['id'] + '/versions')
        assert any(version['label'] == 'WebP 图片基线' for version in versions)
    _, target_zip = archive(target)
    with zipfile.ZipFile(io.BytesIO(target_zip)) as bundle:
        for record in records:
            for slot in ['photo', 'logo']:
                assert hashlib.sha256(bundle.read('attachments/' + record['target' + slot.title()] + '/original.webp')).hexdigest() == record[slot]['sha256']
    result = {'source': source, 'target': target, 'records': records, 'pdfs': pdfs, 'backupId': backup['id'], 'backupSha256': hashlib.sha256(zipped).hexdigest(),
              'originalWebpBytesPreserved': True, 'normalizedPngBytesPreserved': True, 'existingTargetPreserved': True, 'historicalWebpPreserved': True, 'redactedPdfContainsNoImages': True}
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('WebP: 8 Chinese PDFs, alpha/orientation, redacted export, original bytes and cross-instance history passed.')

def persistence(source, target):
    report = json.loads(REPORT.read_text(encoding='utf-8'))
    assert report['source'] == source and report['target'] == target
    for record in report['records']:
        for side in ['source', 'target']:
            resume = call(report[side], '/api/resumes/' + record[side + 'Id'])
            assert digest(resume) == record[side + 'Digest']
            for slot in ['photo', 'logo']:
                asset_id = resume['document']['layout'][slot]['id']
                assert hashlib.sha256(call(report[side], '/api/assets/' + asset_id + '/image', raw=True)).hexdigest() == record[slot]['normalizedSha256']
    assert hashlib.sha256(call(source, '/api/backups/' + report['backupId'] + '/download', raw=True)).hexdigest() == report['backupSha256']
    report['containerRecreationPreservesWebp'] = True
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Container recreation preserved WebP originals, normalized images, history and ZIP bytes.')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='http://127.0.0.1:18767')
    parser.add_argument('--target', default='http://127.0.0.1:18769')
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--check-persistence', action='store_true')
    args = parser.parse_args()
    assert args.isolated and args.source != args.target, 'Use two explicitly isolated workspaces'
    for base in [args.source, args.target]:
        parsed = urlparse(base)
        assert parsed.scheme == 'http' and parsed.hostname in ['127.0.0.1', 'localhost'] and parsed.port and parsed.port != 18765
    if args.check_persistence:
        persistence(args.source.rstrip('/'), args.target.rstrip('/'))
    else:
        run(args.source.rstrip('/'), args.target.rstrip('/'))
