"""Verify an already-purged browser fixture across isolated container recreation.

The before phase only reads evidence. The after phase makes one explicit terminal
retry with the fixture's original body; it never seeds or initially purges data.
"""
import argparse
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'output/purge-persistence.json'
REPORT = ROOT / 'output/purge-verification.json'
TEXT_ENTRY = '说明.txt'
LEGACY_TEXT_KEY = '\u02f5\ufffd\ufffd.txt'

spec = importlib.util.spec_from_file_location('quarantine_verifier', ROOT / 'scripts/verify-quarantine.py')
qa = importlib.util.module_from_spec(spec)
spec.loader.exec_module(qa)


def canonical_uuid(value):
    return isinstance(value, str) and qa.UUID.fullmatch(value) is not None


def canonical_sha(value):
    return isinstance(value, str) and qa.SHA.fullmatch(value) is not None


def plain_evidence_path(path, directory):
    """Check each path component without following symlinks or Windows reparse points."""
    for current in (*reversed(path.parents), path):
        entry = current.lstat()
        qa.require(not stat.S_ISLNK(entry.st_mode)
                   and not (getattr(entry, 'st_file_attributes', 0)
                            & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)),
                   'Purge evidence path contains a link or reparse point')
        qa.require(stat.S_ISDIR(entry.st_mode) if current != path or directory
                   else stat.S_ISREG(entry.st_mode),
                   'Purge evidence path is not a plain directory or regular file')
    return entry


def validate_fixture(value, container):
    qa.require(isinstance(value, dict) and set(value) == {
        'schemaVersion', 'container', 'operationId', 'recoveryToken', 'imageId',
        'originalBatchBytes', 'exportId', 'archiveSHA256', 'archiveBytes',
        'purgeBody', 'receipt', 'originalFilesSHA256', 'zipEntrySHA256',
        'zipEntryBytes', 'zipPath', 'extractionPath', 'sourceCanaries',
        'modelPublicSHA256', 'sourceResumeId', 'sourceAssetId', 'earlyPurge',
        'remainingPayloadBytes', 'payloadInventory', 'originalPathAbsent'},
        'Invalid purge fixture fields')
    qa.require(type(value['schemaVersion']) is int and value['schemaVersion'] == 1,
               'Invalid purge fixture schema')
    qa.require(value['container'] == container, 'Purge fixture container mismatch')
    for name in ('operationId', 'imageId', 'exportId', 'sourceResumeId', 'sourceAssetId'):
        qa.require(canonical_uuid(value[name]), 'Invalid purge fixture UUID: ' + name)
    for name in ('recoveryToken', 'archiveSHA256', 'modelPublicSHA256'):
        qa.require(canonical_sha(value[name]), 'Invalid purge fixture SHA-256: ' + name)
    for name in ('originalBatchBytes', 'archiveBytes'):
        qa.require(type(value[name]) is int and value[name] > 0, 'Invalid purge fixture bytes: ' + name)
    body = value['purgeBody']
    qa.require(isinstance(body, dict) and set(body) == {
        'expectedDigest', 'exportId', 'archiveSha256', 'confirm', 'backupSaved', 'confirmation'},
        'Invalid purge retry body')
    qa.require(body == {
        'expectedDigest': value['recoveryToken'], 'exportId': value['exportId'],
        'archiveSha256': value['archiveSHA256'], 'confirm': True,
        'backupSaved': True, 'confirmation': value['operationId'][-6:]},
        'Purge retry body differs from completed operation')
    row = value['receipt']
    qa.require(isinstance(row, dict) and set(row) == {
        'id', 'state', 'createdAt', 'updatedAt', 'items', 'bytes',
        'backupId', 'digest', 'errorCode', 'cleanup'}, 'Invalid purged receipt fields')
    qa.require(row['id'] == value['operationId'] and row['state'] == 'purged'
               and row['digest'] == value['recoveryToken'] and row['errorCode'] is None
               and row['items'] == [{'kind': 'image', 'id': value['imageId'],
                                     'bytes': value['originalBatchBytes']}]
               and row['bytes'] == value['originalBatchBytes']
               and canonical_uuid(row['backupId'])
               and all(isinstance(row[name], str) and row[name] for name in ('createdAt', 'updatedAt')),
               'Invalid purged receipt identity, selection or proof')
    qa.require(row['cleanup'] == {
        'exportId': value['exportId'], 'sha256': value['archiveSHA256'],
        'bytes': value['archiveBytes']}, 'Purge cleanup proof mismatch')
    files = value['originalFilesSHA256']
    qa.require(isinstance(files, dict) and set(files) == qa.FILES
               and all(canonical_sha(hash_value) for hash_value in files.values()),
               'Invalid original file hashes')
    archive_files = value['zipEntrySHA256']
    archive_bytes = value['zipEntryBytes']
    prefix = 'files/attachments/' + value['imageId'] + '/'
    qa.require(isinstance(archive_files, dict) and isinstance(archive_bytes, dict)
               and set(archive_files) == set(archive_bytes) and len(archive_files) == 5
               and 'manifest.json' in archive_files
               and len([name for name in archive_files if name in {TEXT_ENTRY, LEGACY_TEXT_KEY}]) == 1
               and set(archive_files) == {'manifest.json',
                   *[name for name in archive_files if name in {TEXT_ENTRY, LEGACY_TEXT_KEY}],
                   *[prefix + name for name in qa.FILES]}
               and all(archive_files.get(prefix + name) == file_hash for name, file_hash in files.items())
               and all(canonical_sha(file_hash) for file_hash in archive_files.values())
               and all(type(size) is int and size > 0 for size in archive_bytes.values()),
               'Invalid ZIP entry proof')
    qa.require(value['earlyPurge'] == {'status': 409, 'code': 'QUARANTINE_DOWNLOAD_REQUIRED'}
               and value['remainingPayloadBytes'] == 0
               and value['payloadInventory'] == {'exists': False, 'bytes': 0, 'files': 0}
               and value['originalPathAbsent'] is True, 'Fixture does not prove a completed purge')
    canaries = value['sourceCanaries']
    qa.require(isinstance(canaries, dict) and set(canaries) == {'files', 'sha256', 'databaseSHA256'}
               and isinstance(canaries['files'], dict) and 0 < len(canaries['files']) <= 10000
               and all(isinstance(name, str) and name.split('/')[0] in
                       {'attachments', 'exports', 'backups', 'model-settings'}
                       and all(part not in {'', '.', '..'} for part in name.split('/'))
                       and '\\' not in name for name in canaries['files'])
               and all(canonical_sha(item) for item in canaries['files'].values())
               and canonical_sha(canaries['sha256']) and canonical_sha(canaries['databaseSHA256']),
               'Invalid source canary proof')
    qa.require(qa.hash_bytes(json.dumps(canaries['files'], separators=(',', ':')).encode()) == canaries['sha256'],
               'Source canary composite hash mismatch')
    qa.require(any(name.startswith('attachments/' + value['sourceAssetId'] + '/')
                   for name in canaries['files']), 'Unrelated source attachment proof missing')
    for name, expected in [('zipPath', 'purge-files-' + value['exportId'] + '.zip'),
                           ('extractionPath', 'purge-unpacked-' + value['exportId'])]:
        path = value[name]
        expected_path = ROOT / 'output' / expected
        qa.require(isinstance(path, str) and Path(path).is_absolute()
                   and Path(path) == expected_path,
                   'Purge evidence path escapes expected output location: ' + name)
        plain_evidence_path(expected_path, directory=name == 'extractionPath')
    return value


def archive_snapshot(path):
    expected = plain_evidence_path(path, directory=False)
    flags = os.O_RDONLY | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, 'rb') as stream:
        opened = os.fstat(stream.fileno())
        qa.require(stat.S_ISREG(opened.st_mode)
                   and not (getattr(opened, 'st_file_attributes', 0)
                            & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0))
                   and (opened.st_dev, opened.st_ino) == (expected.st_dev, expected.st_ino),
                   'Saved ZIP changed to a link or different file before read')
        content = stream.read()
    current = plain_evidence_path(path, directory=False)
    qa.require((current.st_dev, current.st_ino) == (expected.st_dev, expected.st_ino),
               'Saved ZIP changed during read')
    return content


def validate_downloaded_archive(fixture):
    archive_path = Path(fixture['zipPath'])
    qa.require(archive_path == ROOT / 'output' / ('purge-files-' + fixture['exportId'] + '.zip'),
               'Saved ZIP is outside expected output path')
    content = archive_snapshot(archive_path)
    qa.require(len(content) == fixture['archiveBytes']
               and qa.hash_bytes(content) == fixture['archiveSHA256'],
               'Saved file ZIP bytes or SHA-256 changed')
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        actual_names = set(archive.namelist())
        fixture_names = set(fixture['zipEntrySHA256'])
        fixture_text = [name for name in fixture_names if name in {TEXT_ENTRY, LEGACY_TEXT_KEY}]
        # The Windows browser runner can decode Python's GBK stdout as UTF-8,
        # corrupting only this display name in its JSON fixture. Its ZIP bytes,
        # extracted file, size and SHA-256 remain independently checkable.
        qa.require(len(fixture_text) == 1
                   and actual_names == (fixture_names - set(fixture_text)) | {TEXT_ENTRY}
                   and archive.testzip() is None,
                   'Saved file ZIP entries or CRC changed')
        for name, expected_hash in fixture['zipEntrySHA256'].items():
            actual_name = TEXT_ENTRY if name == fixture_text[0] else name
            data = archive.read(actual_name)
            qa.require(qa.hash_bytes(data) == expected_hash
                       and len(data) == fixture['zipEntryBytes'][name],
                       'Saved file ZIP entry changed: ' + name)
            extracted = Path(fixture['extractionPath']) / actual_name
            qa.require(qa.hash_bytes(archive_snapshot(extracted)) == expected_hash,
                       'Extracted file ZIP entry changed: ' + name)


INSPECT = r"""
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const [image,operation]=process.argv.slice(2),root='/app/data';
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const plain=p=>{const s=fs.lstatSync(p);if(s.isSymbolicLink())throw Error('Link rejected');return s;};
const exists=p=>{try{plain(p);return true;}catch(e){if(e.code==='ENOENT')return false;throw e;}};
const visit=p=>{const s=plain(p);if(s.isDirectory()){for(const name of fs.readdirSync(p).sort())visit(path.join(p,name));}
 else{if(!s.isFile()||++count>10000||(total+=s.size)>1073741824)throw Error('Canary boundary');files[path.relative(root,p).replaceAll('\\','/')]=hash(fs.readFileSync(p));}};
plain(root);let count=0,total=0;const files={};
for(const name of ['attachments','exports','backups','model-settings']){const p=path.join(root,name);if(exists(p))visit(p);}
const safeAbsent=p=>{let current='/';for(const part of p.split('/').filter(Boolean)){current=path.join(current,part);if(!exists(current))return true;plain(current);}return false;};
const absent={original:safeAbsent(path.join(root,'attachments',image)),payload:safeAbsent(path.join(root,'quarantine',operation,'payload')),discard:safeAbsent(path.join(root,'quarantine',operation,'discard'))};
console.log(JSON.stringify({files,sha256:hash(JSON.stringify(files)),absent}));
"""


def inspect(container, fixture):
    result = subprocess.run(['docker', 'exec', '-i', container, '/ms-playwright-driver/node', '-',
                             fixture['imageId'], fixture['operationId']], input=INSPECT,
                            capture_output=True, text=True, encoding='utf-8', check=True)
    snapshot = json.loads(result.stdout)
    qa.require(snapshot['absent'] == {'original': True, 'payload': True, 'discard': True},
               'Purged original, payload or discard still exists')
    qa.require(snapshot['files'].get('attachments/' + fixture['imageId'] + '/image.png') is None,
               'Purged original appeared in source canaries')
    qa.require(all(snapshot['files'].get(name) == file_hash
                   for name, file_hash in fixture['sourceCanaries']['files'].items()
                   if name.startswith('attachments/' + fixture['sourceAssetId'] + '/')),
               'Unrelated fixture source attachment changed')
    return snapshot


def database_hash(container):
    database = {'resume-ci-source-app-1': 'resume-ci-source-db-1',
                'resume-quarantine-qa-app-1': 'resume-quarantine-qa-db-1'}[container]
    sql = ' UNION ALL '.join(
        "SELECT '%s',coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]') FROM %s t" % (table, table)
        for table in ('attachments', 'resumes', 'resume_versions', 'resume_assets', 'version_assets'))
    result = subprocess.run(['docker', 'exec', database, 'psql', '-U', 'local_resume',
                             '-d', 'local_resume', '-At', '-c', sql],
                            capture_output=True, text=True, encoding='utf-8', check=True)
    return qa.hash_bytes(result.stdout.encode())


def observe(base, container, fixture):
    files = inspect(container, fixture)
    database = database_hash(container)
    row = qa.receipt(base, fixture)
    qa.require(row == fixture['receipt'], 'Purged receipt, token or cleanup proof changed')
    preview = qa.call(base, '/api/storage/preview')
    qa.require(isinstance(preview, dict) and isinstance(preview.get('items'), list)
               and any(isinstance(item, dict) and item.get('id') == fixture['imageId']
                       and item.get('status') == 'purged' and item.get('reason') == 'purged_file'
                       and item.get('bytes') == 0 for item in preview['items']),
               'Purged file tombstone missing from storage preview')
    source = qa.call(base, '/api/resumes/' + fixture['sourceResumeId'])
    asset = qa.call(base, '/api/assets/' + fixture['sourceAssetId'])
    qa.require(source.get('id') == fixture['sourceResumeId']
               and asset.get('id') == fixture['sourceAssetId'], 'Unrelated source document or asset missing')
    workspace = qa.workspace_snapshot(base)
    qa.require(files == inspect(container, fixture) and database == database_hash(container),
               'Read-only GET changed source files or database')
    return {'files': files, 'databaseSHA256': database, 'receipt': row,
            'sourceResumeSHA256': qa.digest(source), 'sourceAssetSHA256': qa.digest(asset),
            'workspace': workspace}


def write_report(report):
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def before(base, container, fixture, fixture_sha):
    identity = qa.container_identity(container)
    validate_downloaded_archive(fixture)
    observed = observe(base, container, fixture)
    report = {'source': base, 'container': container, 'containerIdBefore': identity,
              'fixtureSHA256': fixture_sha, 'operationId': fixture['operationId'],
              'before': observed, 'beforePassed': True}
    write_report(report)
    print('Purge before: exact purged receipt/proof, absent paths, saved ZIP, source canaries, document and models frozen.')


def after(base, container, fixture, fixture_sha):
    report = json.loads(REPORT.read_text(encoding='utf-8'))
    qa.require(report.get('beforePassed') is True and report.get('source') == base
               and report.get('container') == container
               and report.get('operationId') == fixture['operationId']
               and report.get('fixtureSHA256') == fixture_sha,
               'Before evidence does not match current purge fixture and isolated workspace')
    identity = qa.container_identity(container)
    qa.require(identity != report['containerIdBefore'],
               'After verification requires actual external container recreation')
    validate_downloaded_archive(fixture)
    observed = observe(base, container, fixture)
    qa.require(observed == report['before'], 'Container recreation changed purge or source evidence')
    retried = qa.call(base, '/api/storage/quarantine/' + fixture['operationId'] + '/purge',
                      fixture['purgeBody'])
    qa.require(retried == fixture['receipt'], 'Terminal identical-body retry changed purged receipt')
    after_retry = observe(base, container, fixture)
    qa.require(after_retry == observed, 'Terminal identical-body retry mutated source or purge evidence')
    report.update(containerIdAfter=identity, after=after_retry,
                  containerRecreationPreservesPurge=True, terminalRetryNeedsNoNewTicket=True,
                  terminalRetryMakesNoMutation=True, afterPassed=True)
    write_report(report)
    print('Purge after: new container preserved receipt/proof and all fingerprints; identical-body retry made no mutation.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['before', 'after'])
    parser.add_argument('--source', default=os.environ.get('RESUME_TEST_BASE_URL', 'http://127.0.0.1:18767'))
    parser.add_argument('--container', default=os.environ.get('RESUME_TEST_APP_CONTAINER'))
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--fixture', type=Path, default=FIXTURE)
    args = parser.parse_args()
    base = qa.guard(args)
    fixture_bytes = args.fixture.read_bytes()
    fixture = validate_fixture(json.loads(fixture_bytes), args.container)
    (before if args.phase == 'before' else after)(base, args.container, fixture,
                                                  qa.hash_bytes(fixture_bytes))
