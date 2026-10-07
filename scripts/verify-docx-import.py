"""Verify editable DOCX import, backup restore and retries in disposable CI instances."""
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
helpers = runpy.run_path(str(ROOT / 'scripts/verify-backup.py'))
call, upload, archive = (helpers[key] for key in ('call', 'upload', 'archive'))
STATE = ROOT / 'output/docx-import-verification.json'


def pair(source, target):
    helpers['isolated_pair'](source, target)


def assert_receipt(receipt, request, expected=None):
    assert receipt['mutationId'] == request['mutationId'], 'Import receipt has a different mutation identity'
    resume = receipt['resume']
    try:
        valid_id = str(uuid.UUID(resume['id'])) == resume['id']
    except (ValueError, TypeError, AttributeError):
        valid_id = False
    assert valid_id, 'Import receipt lacks a valid resume identity'
    if expected is not None:
        assert resume == expected, 'Retry changed or replaced the imported resume'
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
        call(source, '/api/imports/docx/create', request)
    except HTTPError as error:
        assert error.code == 410 and json.loads(error.read())['code'] == 'DOCX_IMPORT_DELETED', 'Deleted retry must be a durable, explicit failure'
    else:
        raise AssertionError('Deleted import was recreated by a retry')
    assert call(source, '/api/resumes') == before, 'Deleted retry changed resume membership'


def run(source, target):
    pair(source, target)
    source_before, target_before = call(source, '/api/resumes'), call(target, '/api/resumes')
    preview = upload(source, '/api/imports/docx/preview', 'nailong.docx', (ROOT / 'fixtures/docx/basic.docx').read_bytes())
    assert call(source, '/api/resumes') == source_before, 'Preview created a resume'
    assert preview['format'] == 'docx' and '奶龙' in preview['sourceText'] and 'nailong@example.invalid' in preview['sourceText']
    assert any(section['type'] == 'education' for section in preview['document']['content']['sections'])
    table_preview = upload(source, '/api/imports/docx/preview', 'table.docx', (ROOT / 'fixtures/docx/table.docx').read_bytes())
    assert 'Word 表格' in table_preview['sourceText'] and '页眉补充' in table_preview['sourceText']
    assert table_preview['statistics']['tables'] >= 1 and table_preview['statistics']['images'] >= 1
    assert table_preview['warnings'], 'Skipped image needs review warning'
    assert call(source, '/api/resumes') == source_before, 'Table preview wrote persistent content'
    reviewed = copy.deepcopy(preview['document'])
    reviewed['content']['name'] = '奶龙'
    request = {'mutationId': str(uuid.uuid4()), 'title': 'DOCX 导入验收 ' + uuid.uuid4().hex,
               'document': reviewed}
    created = assert_receipt(call(source, '/api/imports/docx/create', request), request)
    assert_receipt(call(source, '/api/imports/docx/create', request), request, created)
    assert len(call(source, '/api/resumes')) == len(source_before) + 1, 'Create retry duplicated a resume'
    edited = copy.deepcopy(created)
    edited['document']['content']['headline'] = '导入后继续编辑 · Java 实习'
    edited = helpers['save'](source, edited)
    assert_receipt(call(source, '/api/imports/docx/create', request), request, edited)
    exported = call(source, f"/api/resumes/{edited['id']}/export", {'expectedRevision': edited['revision']})
    pdf = call(source, f"/api/exports/{exported['id']}/pdf", raw=True)
    from pypdf import PdfReader
    text = '\n'.join(page.extract_text() for page in PdfReader(io.BytesIO(pdf)).pages)
    assert all(value in text for value in ['奶龙', 'Java', '示例理工大学']), 'Imported PDF lost selectable Chinese content'
    (ROOT / 'output/pdf').mkdir(parents=True, exist_ok=True)
    (ROOT / 'output/pdf/docx-imported.pdf').write_bytes(pdf)
    delete_request = {**request, 'mutationId': str(uuid.uuid4()), 'title': request['title'] + ' · 删除重试'}
    deleted = assert_receipt(call(source, '/api/imports/docx/create', delete_request), delete_request)
    call(source, '/api/resumes/' + deleted['id'], {'expectedRevision': 1}, 'DELETE')
    assert_deleted_retry(source, delete_request)
    meta, complete = archive(source)
    with zipfile.ZipFile(io.BytesIO(complete)) as backup:
        workspace = json.loads(backup.read('workspace.json'))
        assert workspace['schemaVersion'] == 5, 'Import must not change portable backup schema'
        saved_import = next(row for row in workspace['resumes'] if row['id'] == edited['id'])
        assert saved_import['document'] == edited['document']
        assert not any(name.endswith('.docx') or 'docx_imports' in name for name in backup.namelist()), 'Original upload/instance receipt leaked into portable ZIP'
    restored = upload(target, '/api/backups/restore', 'docx-import.zip', complete)
    copies = [call(target, '/api/resumes/' + identifier) for identifier in restored['resumeIds']]
    imported = [row for row in copies if row['title'] == edited['title']]
    assert len(imported) == 1, 'Imported resume must map to one restored record'
    assert_restored(edited, imported[0])
    target_after = call(target, '/api/resumes')
    assert all(row in target_after for row in target_before), 'Restore altered original target records'
    state = {'source': source, 'target': target, 'request': request, 'deletedRequest': delete_request,
             'sourceResume': edited, 'sourceVersions': call(source, f"/api/resumes/{edited['id']}/versions"),
             'targetResume': imported[0], 'targetVersions': call(target, f"/api/resumes/{imported[0]['id']}/versions"),
             'backupId': meta['id'], 'previewDoesNotPersist': True,
             'createIsExplicitAndIdempotent': True, 'editedRetryPreserved': True,
             'deletedRetryNeverRecreates': True, 'selectableChinesePdf': True,
             'schema5BackupRestoresEditableImport': True}
    STATE.parent.mkdir(exist_ok=True)
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf8')
    print('DOCX preview, reviewed creation/retries, Chinese PDF and full backup restore verified.')


def persistence(source, target):
    pair(source, target)
    state = json.loads(STATE.read_text(encoding='utf8'))
    assert state['source'] == source and state['target'] == target, 'Wrong persistence endpoints'
    original, restored = state['sourceResume'], state['targetResume']
    assert call(source, '/api/resumes/' + original['id']) == original, 'Source import changed during recreation'
    assert call(target, '/api/resumes/' + restored['id']) == restored, 'Restored import changed during recreation'
    assert call(source, f"/api/resumes/{original['id']}/versions") == state['sourceVersions']
    assert call(target, f"/api/resumes/{restored['id']}/versions") == state['targetVersions']
    assert_receipt(call(source, '/api/imports/docx/create', state['request']), state['request'], original)
    assert_deleted_retry(source, state['deletedRequest'])
    state['containerRecreationPreservesImportedDataAndRetryReceipts'] = True
    STATE.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding='utf8')
    print('DOCX import and create/deleted-target receipts survive container recreation.')


def after_upgrade(base):
    helpers['isolated_port'](base)
    before = call(base, '/api/resumes')
    preview = upload(base, '/api/imports/docx/preview', 'upgrade.docx', (ROOT / 'fixtures/docx/basic.docx').read_bytes())
    assert call(base, '/api/resumes') == before, 'Upgraded preview changed existing resumes'
    request = {'mutationId': str(uuid.uuid4()), 'title': '升级后 Word 导入验证', 'document': preview['document']}
    created = assert_receipt(call(base, '/api/imports/docx/create', request), request)
    assert_receipt(call(base, '/api/imports/docx/create', request), request, created)
    after = call(base, '/api/resumes')
    assert len(after) == len(before) + 1 and all(row in after for row in before), 'Upgrade/import changed original records'
    report = {'sourceVersion': '0.8.0', 'additiveReceiptMigrationUsable': True,
              'previewDoesNotPersist': True, 'oneCreatedRecordAcrossRetries': True,
              'preUpgradeRecordsPreserved': True}
    (ROOT / 'output/docx-upgrade-verification.json').write_text(json.dumps(report, indent=2), encoding='utf8')
    print('DOCX import and receipt migration work after the retained-volume 0.8 upgrade.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='http://127.0.0.1:18767')
    parser.add_argument('--target', default='http://127.0.0.1:18769')
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--check-persistence', action='store_true')
    parser.add_argument('--after-upgrade', action='store_true')
    args = parser.parse_args()
    assert args.isolated, 'Explicit disposable-workspace authorization is required'
    if args.after_upgrade:
        assert not args.check_persistence, 'Choose one verification phase'
        after_upgrade(args.source.rstrip('/'))
    else:
        (persistence if args.check_persistence else run)(args.source.rstrip('/'), args.target.rstrip('/'))
