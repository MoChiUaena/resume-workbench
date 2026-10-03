"""Verify saved report backup/remapping and persistence in disposable CI workspaces."""
import argparse
import io
import json
from pathlib import Path
import runpy
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
helpers = runpy.run_path(str(ROOT / 'scripts/verify-backup.py'))
call, archive, upload, isolated_port = (helpers[name] for name in ['call', 'archive', 'upload', 'isolated_port'])
REPORT = ROOT / 'output/job-report-history-verification.json'


def run(source, target):
    original_settings = call(source, '/api/models')
    config = call(source, '/api/models/profiles', {
        'expectedRevision': original_settings['revision'], 'name': '报告持久化 fixture',
        'provider': 'compatible', 'baseUrl': 'http://127.0.0.1:18770/v1',
        'model': 'qa-job-normal', 'apiKey': 'report-persistence-fixture-only', 'clearKey': False})
    profile_id = next(item['id'] for item in config['profiles']
                      if item['id'] not in {p['id'] for p in original_settings['profiles']})
    try:
        if not config['enabled']:
            config = call(source, '/api/models/enabled', {'expectedRevision': config['revision'], 'enabled': True}, 'PUT')
        resume = call(source, '/api/resumes', {'title': '保存报告持久化 QA ' + uuid.uuid4().hex, 'sample': 'one'})
        project = next(section for section in resume['document']['content']['sections'] if section['type'] == 'project')
        project['entries'][0]['bullets'][0] = 'PROJECT-BODY-CANARY 使用 Java 整理接口文档并记录联调问题。'
        resume = call(source, '/api/resumes/' + resume['id'], {
            'title': resume['title'], 'document': resume['document'],
            'expectedRevision': resume['revision'], 'mutationId': str(uuid.uuid4())}, 'PUT')
        preview = call(source, '/api/ai/job-matches/preview', {
            'resumeId': resume['id'], 'expectedRevision': resume['revision'], 'sectionIds': [project['id']],
            'jobDescription': '具备 Java 项目经验；具备云平台部署经验',
            'profileId': profile_id, 'settingsRevision': config['revision']})
        generated = call(source, '/api/ai/job-matches', {'previewId': preview['id'], 'confirmSend': True})
        request = {'previewId': preview['id'], 'reportId': generated['id'], 'label': 'Java 岗位 · 持久化验证'}
        route = '/api/resumes/' + resume['id'] + '/job-reports'
        saved = call(source, route, request)
        assert saved == call(source, route, request), 'Save retry duplicated or changed the archive'
        assert call(source, '/api/resumes/' + resume['id']) == resume, 'Saving changed the resume'
        assert saved['snapshot']['suggestions'] and all(set(s) == {'sourceId', 'replacement'} for s in saved['snapshot']['suggestions'])
    finally:
        config = call(source, '/api/models')
        if config['enabled'] != original_settings['enabled']:
            config = call(source, '/api/models/enabled', {'expectedRevision': config['revision'], 'enabled': original_settings['enabled']}, 'PUT')
        call(source, '/api/models/profiles/' + profile_id, {'expectedRevision': config['revision']}, 'DELETE')

    target_models = call(target, '/api/models')
    backup, content = archive(source)
    with zipfile.ZipFile(io.BytesIO(content)) as bundle:
        assert json.loads(bundle.read('workspace.json'))['schemaVersion'] == 5
        payload = bundle.read('job-reports/' + saved['id'] + '.json')
        assert json.loads(payload) == saved['snapshot']
        for forbidden in [b'report-persistence-fixture-only', b'encryptedKey', b'expiresAt', b'sendToken']:
            assert forbidden not in payload
    restored = upload(target, '/api/backups/restore', 'job-history.zip', content)
    copies = [call(target, '/api/resumes/' + identifier) for identifier in restored['resumeIds']]
    copy = next(item for item in copies if item['title'] == resume['title'])
    route = '/api/resumes/' + copy['id'] + '/job-reports'
    history = call(target, route)
    assert history['total'] == 1
    restored_report = call(target, route + '/' + history['items'][0]['id'])
    assert restored_report['id'] != saved['id'] and restored_report['resumeId'] == copy['id']
    assert restored_report['snapshot'] == saved['snapshot']
    assert call(target, '/api/models') == target_models, 'Restore changed instance model settings'
    assert call(source, '/api/resumes/' + resume['id']) == resume
    result = {'source': source, 'target': target, 'sourceResume': resume,
              'sourceReport': saved, 'saveRequest': request, 'targetResume': copy, 'targetReport': restored_report,
              'backupId': backup['id'], 'restoreRemapsOwnershipAndPreservesSnapshots': True,
              'saveIsExplicitAndIdempotent': True, 'portableReportExcludesLiveTokensAndCredentials': True}
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Saved report: explicit save, remapped backup restore and immutable snapshot verified.')


def persistence(source, target):
    result = json.loads(REPORT.read_text(encoding='utf-8'))
    assert result['source'] == source and result['target'] == target
    for side in ['source', 'target']:
        resume, saved = result[side + 'Resume'], result[side + 'Report']
        base = result[side]
        route = '/api/resumes/' + resume['id'] + '/job-reports'
        assert call(base, '/api/resumes/' + resume['id']) == resume
        assert call(base, route)['total'] == 1
        assert call(base, route + '/' + saved['id']) == saved
    resume = result['sourceResume']
    route = '/api/resumes/' + resume['id'] + '/job-reports'
    assert call(source, route, result['saveRequest']) == result['sourceReport'], 'Lost save receipt failed after cache reset'
    assert call(source, route)['total'] == 1
    result['containerRecreationPreservesReportsAndSaveRetries'] = True
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print('Recreation preserved both report histories; retry worked with the generation cache gone.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', default='http://127.0.0.1:18767')
    parser.add_argument('--target', default='http://127.0.0.1:18769')
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--check-persistence', action='store_true')
    args = parser.parse_args()
    source, target = args.source.rstrip('/'), args.target.rstrip('/')
    assert args.isolated and isolated_port(source) != isolated_port(target), 'Use two disposable workspaces'
    (persistence if args.check_persistence else run)(source, target)
