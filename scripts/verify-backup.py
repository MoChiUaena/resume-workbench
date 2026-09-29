"""Stage C: restore into a fresh instance and prove editable history survives.

Only accepts loopback URLs and requires TWO EMPTY, explicitly isolated workspaces.
Leaves its synthetic records in place for the separate persistence check.
"""
import argparse
import copy
import hashlib
import io
import json
from pathlib import Path
import uuid
import urllib.request
from urllib.parse import urlparse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output'


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def call(base, route, body=None, method=None, raw=False):
    headers = {'X-Local-Resume': '1'}
    if body is not None:
        body = json.dumps(body, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    request = urllib.request.Request(base + route, data=body, headers=headers, method=method)
    with urllib.request.urlopen(request, timeout=120) as response:
        result = response.read()
    return result if raw else json.loads(result)


def upload(base, route, filename, content):
    boundary = 'resume-' + uuid.uuid4().hex
    envelope = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; '
                f'filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n').encode()
    payload = envelope + content + f'\r\n--{boundary}--\r\n'.encode()
    request = urllib.request.Request(base + route, data=payload,
        headers={'X-Local-Resume': '1', 'Content-Type': 'multipart/form-data; boundary=' + boundary})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def save(base, resume):
    return call(base, '/api/resumes/' + resume['id'], {
        'title': resume['title'], 'document': resume['document'],
        'expectedRevision': resume['revision'], 'mutationId': str(uuid.uuid4())}, 'PUT')


def archive(base):
    meta = call(base, '/api/backups', {})
    return meta, call(base, '/api/backups/' + meta['id'] + '/download', raw=True)


def canonical_zip(content):
    with zipfile.ZipFile(io.BytesIO(content)) as archive_file:
        manifest = json.loads(archive_file.read('manifest.json'))
        assert manifest['format'] == 'resume-workbench-backup'
        assert manifest['formatVersion'] == 1
        for item in manifest['files']:
            payload = archive_file.read(item['path'])
            assert len(payload) == item['bytes']
            assert hashlib.sha256(payload).hexdigest() == item['sha256']
        workspace = json.loads(archive_file.read('workspace.json'))
        ids = {a['id']: a['sha256'] for a in workspace['attachments']}
        for resume in workspace['resumes'] + workspace['versions']:
            resume.pop('id')
            resume.pop('resumeId', None)
            for slot in ['photo', 'logo']:
                image = resume['document']['layout'][slot]
                if image['id']:
                    image['id'] = ids[image['id']]
        for asset in workspace['attachments']:
            asset.pop('id')
        for export in workspace['exports']:
            for field in ['id', 'resumeId', 'versionId']:
                export.pop(field)
        for group in ['resumes', 'versions', 'attachments', 'exports']:
            workspace[group].sort(key=digest)
        return workspace


def run(source, target):
    assert call(source, '/api/resumes') == [], 'Source must be an empty isolated workspace'
    assert call(target, '/api/resumes') == [], 'Target must be a fresh empty workspace'
    resume = call(source, '/api/resumes', {'title': '阶段 C · 全新实例恢复', 'sample': 'two'})
    original = copy.deepcopy(resume['document']['layout']['photo'])
    old_photo = upload(source, '/api/assets', 'exif.jpg', (ROOT / 'fixtures/portrait-exif-6.jpg').read_bytes())
    assert old_photo['exifOrientation'] == 6
    resume['document']['layout']['photo']['id'] = old_photo['id']
    resume = save(source, resume)
    call(source, '/api/resumes/' + resume['id'] + '/versions', {
        'title': '历史 EXIF 照片', 'expectedRevision': resume['revision']})
    resume['document']['layout']['photo'] = original
    resume = save(source, resume)
    call(source, '/api/resumes/' + resume['id'] + '/export', {'expectedRevision': resume['revision']})
    options = {name: True for name in ['name', 'phone', 'email', 'location', 'photo', 'logo', 'matchingText']}
    projected = call(source, '/api/resumes/' + resume['id'] + '/export/preview', {'expectedRevision': resume['revision'], 'redaction': options})
    redacted = call(source, '/api/resumes/' + resume['id'] + '/export', {'expectedRevision': resume['revision'], 'redaction': options, 'previewDigest': projected['digest']})
    meta, content = archive(source)
    OUT.mkdir(exist_ok=True)
    (OUT / 'stage-c-fresh-instance.zip').write_bytes(content)
    restored = upload(target, '/api/backups/restore', 'workspace.zip', content)
    assert restored['resumes'] == 1 and restored['attachments'] == 3
    imported_id = restored['resumeIds'][0]
    assert imported_id != resume['id']
    imported = call(target, '/api/resumes/' + imported_id)
    assert imported['document']['content'] == resume['document']['content']
    assert imported['revision'] == resume['revision']
    _, recovered = archive(target)
    assert canonical_zip(content) == canonical_zip(recovered), 'Structure, originals, PNGs, history or PDF changed'
    with zipfile.ZipFile(io.BytesIO(recovered)) as recovered_archive:
        recovered_workspace = json.loads(recovered_archive.read('workspace.json'))
        recovered_redacted = next(item for item in recovered_workspace['exports'] if item['sha256'] == redacted['sha256'])
    redacted_pdf = call(target, '/api/exports/' + recovered_redacted['id'] + '/pdf', raw=True)
    assert hashlib.sha256(redacted_pdf).hexdigest() == redacted['sha256']
    (OUT / 'pdf').mkdir(exist_ok=True)
    (OUT / 'pdf/redacted-restored.pdf').write_bytes(redacted_pdf)
    versions = call(target, '/api/resumes/' + imported_id + '/versions')
    historical = next(v for v in versions if v['label'] == '历史 EXIF 照片')
    imported = call(target, f'/api/resumes/{imported_id}/versions/{historical["id"]}/restore',
                    {'expectedRevision': imported['revision']})
    asset = call(target, '/api/assets/' + imported['document']['layout']['photo']['id'])
    assert asset['sha256'] == old_photo['sha256']
    assert asset['normalizedSha256'] == old_photo['normalizedSha256']
    imported['document']['content']['headline'] = '全新实例恢复后继续编辑 · Java 后端'
    imported = save(target, imported)
    exported = call(target, '/api/resumes/' + imported_id + '/export', {'expectedRevision': imported['revision']})
    pdf = call(target, '/api/exports/' + exported['id'] + '/pdf', raw=True)
    assert pdf.startswith(b'%PDF-')
    assert hashlib.sha256(pdf).hexdigest() == exported['sha256']
    (OUT / 'pdf').mkdir(exist_ok=True)
    (OUT / 'pdf/restored-new-instance.pdf').write_bytes(pdf)
    assert call(source, '/api/resumes/' + resume['id']) == resume, 'Source was modified by restore'
    result = {'source': source, 'target': target, 'counts': {k: meta[k] for k in ['resumes', 'versions', 'attachments', 'exports']},
        'sourceId': resume['id'], 'targetId': imported_id,
        'sourceDigest': digest(resume), 'targetDigest': digest(imported),
        'structureAndAllFileHashesMatch': True, 'historicalExifPhotoRestored': True,
        'restoredRecordEditable': True, 'redactedPdfHashPreserved': True, 'redactedPdfSha256': redacted['sha256'], 'pdfSha256': exported['sha256']}
    (OUT / 'stage-c-backup-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))


def persistence():
    report_path = OUT / 'stage-c-backup-verification.json'
    result = json.loads(report_path.read_text(encoding='utf-8'))
    for side in ['source', 'target']:
        current = call(result[side], '/api/resumes/' + result[side + 'Id'])
        assert digest(current) == result[side + 'Digest'], 'Resume changed after container recreation'
        for slot in ['photo', 'logo']:
            asset_id = current['document']['layout'][slot]['id']
            assert call(result[side], '/api/assets/' + asset_id + '/image', raw=True).startswith(b'\x89PNG')
        assert len(call(result[side], '/api/resumes/' + current['id'] + '/versions')) >= 3
    result['containerRecreationPreservesData'] = True
    report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Container recreation preserved both instances, images, revisions and versions.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='http://127.0.0.1:18767')
    parser.add_argument('--target', default='http://127.0.0.1:18769')
    parser.add_argument('--isolated', action='store_true', help='Confirm both instances are disposable test workspaces')
    parser.add_argument('--check-persistence', action='store_true')
    args = parser.parse_args()
    assert args.isolated, 'Run only in explicitly isolated test instances; supply --isolated'
    for base in [args.source, args.target]:
        url = urlparse(base)
        assert url.scheme == 'http' and url.hostname in ['127.0.0.1', 'localhost'] and url.port
    if args.check_persistence:
        persistence()
    else:
        assert args.source != args.target
        run(args.source.rstrip('/'), args.target.rstrip('/'))
