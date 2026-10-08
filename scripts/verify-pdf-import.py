"""Verify reviewed text-PDF imports only in disposable, loopback CI workspaces."""
import argparse
import copy
import io
import json
from pathlib import Path
import runpy
from urllib.error import HTTPError
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
qa = runpy.run_path(str(ROOT / 'scripts/verify-backup.py'))
call, upload, archive = (qa[key] for key in ('call', 'upload', 'archive'))
STATE = ROOT / 'output/pdf-import-verification.json'


def pair(source, target):
    qa['isolated_pair'](source, target)


def assert_preview(preview, expected_pages):
    assert preview['format'] == 'pdf', 'PDF preview format differs'
    text = preview['sourceText']
    assert isinstance(text, str) and 0 < len(text) <= 40000 and '奶龙' in text, 'PDF source text missing or unbounded'
    document = preview['document']
    assert document['content']['name'] == '奶龙', 'Explicit or first-line name was not inferred for review'
    assert any(section['type'] == 'education' for section in document['content']['sections']), 'Education did not map to an editable section'
    stats = preview['statistics']
    assert isinstance(stats['pages'], int) and 1 <= stats['pages'] <= 20 and stats['pages'] == expected_pages, 'PDF page count wrong'
    assert isinstance(stats['paragraphs'], int) and 1 <= stats['paragraphs'] <= 600, 'PDF paragraph count wrong'


def assert_receipt(receipt, request, expected=None):
    assert receipt['mutationId'] == request['mutationId'], 'Import receipt has a different mutation identity'
    resume = receipt['resume']
    try:
        valid_id = str(uuid.UUID(resume['id'])) == resume['id']
    except (ValueError, TypeError, AttributeError):
        valid_id = False
    assert valid_id, 'Import receipt lacks a valid resume identity'
    if expected is not None:
        assert resume == expected, 'Retry replaced or changed the imported resume'
    else:
        assert resume['title'] == request['title'] and resume['document'] == request['document'], 'Created import differs from reviewed content'
        assert resume['revision'] == 1, 'New import must start at revision one'
    return resume


def assert_restored(original, restored):
    assert original['id'] != restored['id'], 'Restore must remap resume identity'
    for field in ('title', 'document', 'revision'):
        assert original[field] == restored[field], f'Restored import differs in {field}'


def assert_deleted_retry(source, request):
    before = call(source, '/api/resumes')
    try:
        call(source, '/api/imports/pdf/create', request)
    except HTTPError as error:
        assert error.code == 410 and json.loads(error.read())['code'] == 'PDF_IMPORT_DELETED', 'Deleted retry must remain a durable failure'
    else:
        raise AssertionError('Deleted PDF import was recreated')
    assert call(source, '/api/resumes') == before, 'Deleted retry changed resume membership'


def rejected_preview(source, name, file_name, code):
    before = call(source, '/api/resumes')
    try:
        upload(source, '/api/imports/pdf/preview', file_name, (ROOT / 'fixtures/pdf' / name).read_bytes())
    except HTTPError as error:
        assert error.code == 422 and json.loads(error.read())['code'] == code, f'{name} did not explain its unsupported content'
    else:
        raise AssertionError(f'{name} was accepted despite being unsupported')
    assert call(source, '/api/resumes') == before, 'Rejected preview created a resume'


def run(source, target):
    pair(source, target)
    source_before, target_before = call(source, '/api/resumes'), call(target, '/api/resumes')
    model_before = call(source, '/api/models')
    preview = upload(source, '/api/imports/pdf/preview', 'nailong.pdf', (ROOT / 'fixtures/pdf/text.pdf').read_bytes())
    assert_preview(preview, 1)
    assert call(source, '/api/resumes') == source_before, 'Text preview created a resume'
    two = upload(source, '/api/imports/pdf/preview', 'two-pages.pdf', (ROOT / 'fixtures/pdf/two-pages.pdf').read_bytes())
    assert_preview(two, 2)
    assert '项目经历' in two['sourceText'] and '第二页' in two['sourceText'], 'Second-page text disappeared'
    assert call(source, '/api/resumes') == source_before, 'Two-page preview created a resume'
    with_images = upload(source, '/api/imports/pdf/preview', 'with-images.pdf', (ROOT / 'fixtures/pdf/with-images.pdf').read_bytes())
    assert_preview(with_images, 1)
    assert with_images['statistics']['images'] == 2 and len(with_images['images']) == 2, 'Synthetic photo and logo are not selectable'
    assert call(source, '/api/resumes') == source_before, 'Image preview persisted an unconfirmed PDF'
    rejected_preview(source, 'scanned.pdf', 'scan.pdf', 'PDF_NO_TEXT')
    rejected_preview(source, 'encrypted.pdf', 'protected.pdf', 'PDF_ENCRYPTED')
    request = {'mutationId': str(uuid.uuid4()), 'title': 'PDF 导入验收 ' + uuid.uuid4().hex,
               'document': copy.deepcopy(preview['document'])}
    request['document']['content']['name'] = '奶龙已核对'
    created = assert_receipt(call(source, '/api/imports/pdf/create', request), request)
    assert_receipt(call(source, '/api/imports/pdf/create', request), request, created)
    assert len(call(source, '/api/resumes')) == len(source_before) + 1, 'Exact retry duplicated a PDF import'
    edited = copy.deepcopy(created)
    edited['document']['content']['headline'] = 'PDF 导入后继续编辑 · Java 实习'
    edited = qa['save'](source, edited)
    assert_receipt(call(source, '/api/imports/pdf/create', request), request, edited)
    export = call(source, f"/api/resumes/{edited['id']}/export", {'expectedRevision': edited['revision']})
    pdf = call(source, f"/api/exports/{export['id']}/pdf", raw=True)
    from pypdf import PdfReader
    rendered_text = '\n'.join(page.extract_text() for page in PdfReader(io.BytesIO(pdf)).pages)
    assert all(value in rendered_text for value in ('奶龙已核对', '示例理工大学', 'Java')), 'Editable import lost selectable Chinese PDF text'
    (ROOT / 'output/pdf').mkdir(parents=True, exist_ok=True)
    (ROOT / 'output/pdf/pdf-imported.pdf').write_bytes(pdf)
    image_request = {'mutationId': str(uuid.uuid4()), 'title': 'PDF 图片导入验收 ' + uuid.uuid4().hex,
                     'document': copy.deepcopy(with_images['document'])}
    for slot, candidate in zip(('photo', 'logo'), with_images['images']):
        image_request[slot] = {'mimeType': candidate['mimeType'], 'base64': candidate['base64']}
    image_created = call(source, '/api/imports/pdf/create', image_request)['resume']
    assert image_created['revision'] == 1, 'Image import did not start at revision one'
    source_ids = {slot: image_created['document']['layout'][slot]['id'] for slot in ('photo', 'logo')}
    assert all(source_ids.values()) and source_ids['photo'] != source_ids['logo'], 'Photo and logo were not assigned independently'
    assert call(source, '/api/imports/pdf/create', image_request)['resume'] == image_created, 'Image retry duplicated or changed import'
    source_images = {slot: call(source, f"/api/assets/{asset_id}/image", raw=True) for slot, asset_id in source_ids.items()}
    image_versions = call(source, f"/api/resumes/{image_created['id']}/versions")
    assert len(image_versions) == 1, 'Selected images must belong to the initial version'
    assert call(source, f"/api/resumes/{image_created['id']}/versions/{image_versions[0]['id']}")['document'] == image_created['document']
    deleted_request = {**request, 'mutationId': str(uuid.uuid4()), 'title': request['title'] + ' · 删除验证'}
    deleted = assert_receipt(call(source, '/api/imports/pdf/create', deleted_request), deleted_request)
    call(source, '/api/resumes/' + deleted['id'], {'expectedRevision': 1}, 'DELETE')
    assert_deleted_retry(source, deleted_request)
    meta, complete = archive(source)
    with zipfile.ZipFile(io.BytesIO(complete)) as backup:
        workspace = json.loads(backup.read('workspace.json'))
        assert workspace['schemaVersion'] == 5, 'PDF import changed portable backup schema'
        row = next(entry for entry in workspace['resumes'] if entry['id'] == edited['id'])
        assert row['document'] == edited['document']
        image_row = next(entry for entry in workspace['resumes'] if entry['id'] == image_created['id'])
        assert image_row['document'] == image_created['document'], 'Image import is missing from workspace backup'
        assert not any(name.startswith('imports/') or name.endswith(('text.pdf', 'with-images.pdf')) for name in backup.namelist()), 'Original PDF or receipt was copied into ZIP'
    restored = upload(target, '/api/backups/restore', 'pdf-import.zip', complete)
    copies = [call(target, '/api/resumes/' + identity) for identity in restored['resumeIds']]
    imported = [row for row in copies if row['title'] == edited['title']]
    assert len(imported) == 1, 'Imported PDF resume must map to one restored record'
    assert_restored(edited, imported[0])
    image_copies = [row for row in copies if row['title'] == image_created['title']]
    assert len(image_copies) == 1, 'Selected PDF images did not restore with their resume'
    image_restored = image_copies[0]
    target_ids = {slot: image_restored['document']['layout'][slot]['id'] for slot in ('photo', 'logo')}
    assert all(target_ids.values()) and all(target_ids[slot] != source_ids[slot] for slot in source_ids), 'Restored images were not remapped'
    assert all(call(target, f"/api/assets/{target_ids[slot]}/image", raw=True) == source_images[slot] for slot in source_ids), 'Restored image bytes differ'
    expected_image_document = copy.deepcopy(image_created['document'])
    for slot in source_ids:
        expected_image_document['layout'][slot]['id'] = target_ids[slot]
    assert image_restored['document'] == expected_image_document, 'Restored image resume changed other fields'
    restored_image_versions = call(target, f"/api/resumes/{image_restored['id']}/versions")
    assert len(restored_image_versions) == 1, 'Restored selected images lost the initial version'
    assert call(target, f"/api/resumes/{image_restored['id']}/versions/{restored_image_versions[0]['id']}")['document'] == image_restored['document'], 'Restored initial version lost selected images'
    target_after = call(target, '/api/resumes')
    assert all(item in target_after for item in target_before), 'Restore altered original target resumes'
    assert call(source, '/api/models') == model_before, 'PDF import changed model settings'
    state = {'source': source, 'target': target, 'request': request, 'deletedRequest': deleted_request,
             'sourceResume': edited, 'targetResume': imported[0],
             'sourceImageResume': image_created, 'targetImageResume': image_restored,
             'sourceVersions': call(source, f"/api/resumes/{edited['id']}/versions"),
             'targetVersions': call(target, f"/api/resumes/{imported[0]['id']}/versions"),
             'sourceImageVersions': image_versions,
             'targetImageVersions': restored_image_versions,
             'backupId': meta['id'], 'previewDoesNotPersist': True,
             'scannedAndEncryptedRejected': True, 'createIsExplicitAndIdempotent': True,
             'editedRetryPreserved': True, 'deletedRetryNeverRecreates': True,
             'selectableChinesePdf': True, 'selectedImagesRestoreWithInitialVersion': True,
             'schema5BackupRestoresEditableImport': True,
             'instanceModelSettingsUnchanged': True}
    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf8')
    print('Text PDF preview, reviewed creation/retries, new Chinese PDF and full backup restore verified.')


def persistence(source, target):
    pair(source, target)
    state = json.loads(STATE.read_text(encoding='utf8'))
    assert state['source'] == source and state['target'] == target, 'Wrong persistence endpoints'
    original, restored = state['sourceResume'], state['targetResume']
    assert call(source, '/api/resumes/' + original['id']) == original, 'Source PDF import changed on recreation'
    assert call(target, '/api/resumes/' + restored['id']) == restored, 'Restored PDF import changed on recreation'
    assert call(source, f"/api/resumes/{original['id']}/versions") == state['sourceVersions']
    assert call(target, f"/api/resumes/{restored['id']}/versions") == state['targetVersions']
    for side in ('source', 'target'):
        image=state[side+'ImageResume'];version=state[side+'ImageVersions']
        assert call(state[side], '/api/resumes/' + image['id']) == image, 'Selected PDF images changed on recreation'
        assert call(state[side], f"/api/resumes/{image['id']}/versions") == version
    assert_receipt(call(source, '/api/imports/pdf/create', state['request']), state['request'], original)
    assert_deleted_retry(source, state['deletedRequest'])
    state['containerRecreationPreservesImportedDataAndRetryReceipts'] = True
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf8')
    print('PDF import data and both receipt states survive container recreation.')


def after_upgrade(base):
    qa['isolated_port'](base)
    before = call(base, '/api/resumes')
    preview = upload(base, '/api/imports/pdf/preview', 'upgrade.pdf', (ROOT / 'fixtures/pdf/text.pdf').read_bytes())
    assert_preview(preview, 1)
    assert call(base, '/api/resumes') == before, 'Upgraded preview changed original resumes'
    request = {'mutationId': str(uuid.uuid4()), 'title': '0.8 升级后 PDF 导入验证', 'document': preview['document']}
    created = assert_receipt(call(base, '/api/imports/pdf/create', request), request)
    assert_receipt(call(base, '/api/imports/pdf/create', request), request, created)
    after = call(base, '/api/resumes')
    assert len(after) == len(before) + 1 and all(item in after for item in before), 'Upgrade/import changed original data'
    report = {'sourceVersion': '0.8.0', 'schema4ReceiptMigrationUsable': True,
              'previewDoesNotPersist': True, 'oneCreatedRecordAcrossRetries': True,
              'preUpgradeRecordsPreserved': True}
    (ROOT / 'output/pdf-upgrade-verification.json').write_text(json.dumps(report, indent=2), encoding='utf8')
    print('PDF import works after a retained-volume upgrade from published 0.8.0.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='http://127.0.0.1:18767')
    parser.add_argument('--target', default='http://127.0.0.1:18769')
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--check-persistence', action='store_true')
    parser.add_argument('--after-upgrade', action='store_true')
    args = parser.parse_args()
    assert args.isolated, 'Use explicitly disposable CI workspaces'
    if args.after_upgrade:
        assert not args.check_persistence, 'Choose exactly one verification phase'
        after_upgrade(args.source.rstrip('/'))
    else:
        (persistence if args.check_persistence else run)(args.source.rstrip('/'), args.target.rstrip('/'))
