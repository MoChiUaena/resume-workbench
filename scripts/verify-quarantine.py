"""Verify a browser-owned synthetic quarantine across external container recreation.

Before is read-only. After explicitly restores that exact synthetic operation using
the advertised recovery token. Never seeds, deletes or moves fixture files itself.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
from urllib.parse import urlparse
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / 'output/quarantine-persistence.json'
REPORT = ROOT / 'output/quarantine-verification.json'
CONTAINERS = {'resume-quarantine-qa-app-1', 'resume-ci-source-app-1'}
UUID = re.compile(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}')
SHA = re.compile(r'[0-9a-f]{64}')
FILES = {'image.png', 'metadata.json', 'original.png'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def hash_bytes(content):
    return hashlib.sha256(content).hexdigest()


def digest(value):
    return hash_bytes(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                separators=(',', ':')).encode('utf-8'))


def guard(args):
    require(args.isolated, 'Quarantine verification requires --isolated')
    require(args.container in CONTAINERS, 'Quarantine verification requires an allowed isolated container')
    parsed = urlparse(args.source)
    try:
        valid_port = parsed.port == 18767
    except ValueError:
        valid_port = False
    require(parsed.scheme == 'http' and parsed.hostname in {'127.0.0.1', 'localhost'}
            and valid_port and parsed.username is None and parsed.password is None
            and parsed.path in {'', '/'} and not parsed.query and not parsed.fragment,
            'Quarantine verification requires http loopback port 18767 without credentials, path, query or fragment')
    return args.source.rstrip('/')


def validate_fixture(value, container):
    require(isinstance(value, dict) and set(value) == {
        'operationId', 'recoveryToken', 'imageId', 'normalizedSHA', 'files', 'container'},
        'Invalid browser fixture fields')
    for name in ['operationId', 'imageId']:
        require(isinstance(value[name], str) and UUID.fullmatch(value[name]),
                'Invalid browser fixture UUID')
    for name in ['recoveryToken', 'normalizedSHA']:
        require(isinstance(value[name], str) and SHA.fullmatch(value[name]),
                'Invalid browser fixture SHA-256')
    require(value['container'] == container, 'Invalid browser fixture container')
    require(isinstance(value['files'], dict) and set(value['files']) == FILES
            and all(isinstance(v, str) and SHA.fullmatch(v) for v in value['files'].values()),
            'Invalid browser fixture file names or SHA-256')
    # This composite is the browser fixture helper's compact, sorted file map hash.
    composite = hash_bytes(json.dumps(dict(sorted(value['files'].items())), separators=(',', ':')).encode())
    require(composite == value['normalizedSHA'], 'Invalid browser fixture composite SHA-256')
    return value


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Quarantine verification refuses redirects')


def call(base, route, body=None, raw=False):
    headers = {'X-Local-Resume': '1'}
    content = None
    if body is not None:
        headers['Content-Type'] = 'application/json'
        content = json.dumps(body).encode()
    request = urllib.request.Request(base + route, data=content, headers=headers)
    # Loopback requests must not be forwarded through an environment proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=120) as response:
        content = response.read()
    return content if raw else json.loads(content)


def container_identity(container):
    result = subprocess.run(['docker', 'inspect', container], check=True, capture_output=True,
                            text=True, encoding='utf-8')
    rows = json.loads(result.stdout)
    require(len(rows) == 1 and rows[0]['Name'] == '/' + container and rows[0]['State']['Running'],
            'Allowed container is not running')
    ports = rows[0]['NetworkSettings']['Ports'].get('18765/tcp')
    require(ports == [{'HostIp': '127.0.0.1', 'HostPort': '18767'}],
            'Allowed container must expose only loopback port 18767')
    return rows[0]['Id']


INSPECT = r"""
const fs=require('fs'),path=require('path'),crypto=require('crypto');
const [id,operation]=process.argv.slice(2),root='/app/data';
const hash=b=>crypto.createHash('sha256').update(b).digest('hex');
const plain=p=>{const s=fs.lstatSync(p);if(s.isSymbolicLink())throw Error('Link rejected');return s;};
const directory=p=>{if(!plain(p).isDirectory())throw Error('Plain directory required');};
const exists=p=>{try{plain(p);return true;}catch(e){if(e.code==='ENOENT')return false;throw e;}};
// Reject links in every traversed ancestor, including a dangling link.
const ancestors=p=>{let current='/';for(const part of p.split('/').filter(Boolean)){current=path.join(current,part);directory(current);}};
ancestors(root);const originals=path.join(root,'attachments');ancestors(originals);
const inspect=target=>{if(!exists(target))return {exists:false};ancestors(target);
 const names=fs.readdirSync(target).sort(),expected=['image.png','metadata.json','original.png'];
 if(JSON.stringify(names)!==JSON.stringify(expected))throw Error('Unexpected fixture entries');
 const files={},bytes={},modified={};for(const name of names){const file=path.join(target,name),s=plain(file);
  if(!s.isFile())throw Error('Plain file required');const content=fs.readFileSync(file);
  if(content.length!==s.size)throw Error('Unstable fixture file');files[name]=hash(content);bytes[name]=s.size;modified[name]=s.mtimeMs;
 }
 const metadata=JSON.parse(fs.readFileSync(path.join(target,'metadata.json'),'utf8'));
 if(metadata.id!==id||metadata.format!=='PNG')throw Error('Unexpected fixture metadata');
 return {exists:true,files,bytes,modified,normalizedSHA:hash(JSON.stringify(files))};};
const original=inspect(path.join(originals,id));
const parent=path.join(root,'quarantine',operation,'payload','attachments');ancestors(parent);
const held=inspect(path.join(parent,id));
const modelDirectory=path.join(root,'model-settings'),modelFiles={};
if(exists(modelDirectory)){ancestors(modelDirectory);for(const name of fs.readdirSync(modelDirectory).sort()){
 if(!['profiles.json','master.key'].includes(name))throw Error('Unexpected model settings entry');
 const file=path.join(modelDirectory,name),s=plain(file);if(!s.isFile())throw Error('Plain model file required');
 modelFiles[name]={bytes:s.size,sha256:hash(fs.readFileSync(file))};
}}
console.log(JSON.stringify({original,held,modelFiles}));
"""


def inspect(container, fixture):
    result = subprocess.run(['docker', 'exec', '-i', container, '/ms-playwright-driver/node', '-',
                             fixture['imageId'], fixture['operationId']], input=INSPECT,
                            capture_output=True, text=True, encoding='utf-8', check=True)
    return json.loads(result.stdout)


def receipt(base, fixture):
    for page in range(10000):
        history = call(base, '/api/storage/quarantine?page=' + str(page))
        require(isinstance(history, dict) and history.get('page') == page
                and isinstance(history.get('items'), list) and isinstance(history.get('hasMore'), bool),
                'Malformed quarantine history')
        matches = [row for row in history['items'] if row.get('id') == fixture['operationId']]
        require(len(matches) <= 1, 'Duplicate quarantine receipt')
        if matches:
            return matches[0]
        if not history['hasMore']:
            break
    raise ValueError('Browser fixture quarantine receipt not found')


def check_receipt(row, fixture, state, byte_count):
    require(row.get('id') == fixture['operationId'] and row.get('state') == state
            and row.get('digest') == fixture['recoveryToken'] and row.get('errorCode') is None,
            'Quarantine receipt identity, state or advertised token changed')
    require(row.get('items') == [{'kind': 'image', 'id': fixture['imageId'], 'bytes': byte_count}]
            and row.get('bytes') == byte_count, 'Quarantine receipt selection or byte count changed')
    require(isinstance(row.get('backupId'), str) and UUID.fullmatch(row['backupId']),
            'Quarantine receipt has no canonical backup ID')
    require(isinstance(row.get('createdAt'), str) and isinstance(row.get('updatedAt'), str),
            'Quarantine receipt timestamps missing')


def backup_digest(base, row):
    content = call(base, '/api/backups/' + row['backupId'] + '/download', raw=True)
    require(content.startswith(b'PK'), 'Quarantine pre-move backup is unavailable')
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        require(archive.testzip() is None and {'manifest.json', 'workspace.json'} <= set(archive.namelist()),
                'Quarantine pre-move backup is not a valid workspace ZIP')
    return hash_bytes(content)


def workspace_snapshot(base):
    listing = call(base, '/api/resumes')
    require(isinstance(listing, list), 'Malformed saved-resume listing')
    records = {}
    for row in listing:
        identity = row.get('id')
        require(isinstance(identity, str) and UUID.fullmatch(identity) and identity not in records,
                'Invalid or duplicate saved-resume ID')
        records[identity] = {
            'resume': digest(call(base, '/api/resumes/' + identity)),
            'versions': digest(call(base, '/api/resumes/' + identity + '/versions')),
        }
    return {'listing': digest(listing), 'records': records, 'models': digest(call(base, '/api/models'))}


def check_files(actual, fixture):
    require(actual.get('exists') is True and actual.get('files') == fixture['files']
            and actual.get('normalizedSHA') == fixture['normalizedSHA'],
            'Synthetic fixture exact file bytes or composite SHA-256 changed')
    require(set(actual.get('bytes', {})) == FILES
            and all(type(size) is int and size > 0 for size in actual['bytes'].values()),
            'Synthetic fixture byte evidence missing')
    return sum(actual['bytes'].values())


def write_report(result):
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def before(base, container, fixture):
    identity = container_identity(container)
    files = inspect(container, fixture)
    require(files['original'] == {'exists': False}, 'Held synthetic fixture still has an original directory')
    byte_count = check_files(files['held'], fixture)
    row = receipt(base, fixture)
    check_receipt(row, fixture, 'quarantined', byte_count)
    zipped = backup_digest(base, row)
    snapshot = workspace_snapshot(base)
    require(inspect(container, fixture) == files, 'GET checks changed the held synthetic fixture or model files')
    write_report({'source': base, 'container': container, 'containerIdBefore': identity,
        'fixture': fixture, 'receiptBefore': row, 'backupSha256': zipped, 'filesBefore': files,
        'workspaceBefore': snapshot, 'beforePassed': True})
    print('Quarantine before: frozen payload hashes, absent originals, receipt/token, backup and workspace fingerprints verified.')


def after(base, container, fixture):
    result = json.loads(REPORT.read_text(encoding='utf-8'))
    require(result.get('beforePassed') is True and result.get('source') == base
            and result.get('container') == container and result.get('fixture') == fixture,
            'Before evidence does not match the latest browser fixture and isolated workspace')
    identity = container_identity(container)
    require(identity != result['containerIdBefore'], 'After verification requires actual external container recreation')
    files = inspect(container, fixture)
    require(files == result['filesBefore'], 'Container recreation changed held files or model fingerprints')
    byte_count = check_files(files['held'], fixture)
    row = receipt(base, fixture)
    check_receipt(row, fixture, 'quarantined', byte_count)
    require(row == result['receiptBefore'], 'Container recreation changed the held receipt')
    require(backup_digest(base, row) == result['backupSha256'], 'Container recreation changed the pre-move backup')
    require(workspace_snapshot(base) == result['workspaceBefore'], 'Container recreation changed saved resumes, versions or models')
    require(inspect(container, fixture) == files, 'Read-only GET checks moved files')
    restored = call(base, '/api/storage/quarantine/' + fixture['operationId'] + '/restore',
                    {'expectedDigest': row['digest'], 'confirm': True})
    check_receipt(restored, fixture, 'restored', byte_count)
    require(restored['backupId'] == row['backupId'] and restored['createdAt'] == row['createdAt'],
            'Restore changed the original receipt identity or backup')
    actual = inspect(container, fixture)
    check_files(actual['original'], fixture)
    require(actual['original']['bytes'] == files['held']['bytes'] and actual['held'] == {'exists': False},
            'Explicit restore did not preserve all exact bytes or left held files')
    require(all(actual['original']['modified'][name] > files['held']['modified'][name] for name in FILES),
            'Restore did not renew all file protection timestamps')
    require(actual['modelFiles'] == files['modelFiles'], 'Restore changed private model file fingerprints')
    downloaded = call(base, '/api/assets/' + fixture['imageId'] + '/image', raw=True)
    require(hash_bytes(downloaded) == fixture['files']['image.png'], 'Restored image download bytes changed')
    require(receipt(base, fixture) == restored, 'Original operation history does not contain the restored receipt')
    require(workspace_snapshot(base) == result['workspaceBefore'], 'Restore changed saved resumes, versions or model configuration')
    require(backup_digest(base, restored) == result['backupSha256'], 'Restore changed the pre-move backup')
    result.update(containerIdAfter=identity, receiptAfter=restored, filesAfter=actual,
        containerRecreationPreservesQuarantine=True, explicitRestorePreservesExactBytes=True,
        imageDownloadSha256=hash_bytes(downloaded), protectionTimestampsRenewed=True,
        savedResumesAndModelsUnchanged=True, afterPassed=True)
    write_report(result)
    print('Quarantine after: recreation preserved held evidence; explicit restore preserved all hashes, download bytes and workspace/model fingerprints.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['before', 'after'])
    parser.add_argument('--source', default=os.environ.get('RESUME_TEST_BASE_URL', 'http://127.0.0.1:18767'))
    parser.add_argument('--container', default=os.environ.get('RESUME_TEST_APP_CONTAINER'))
    parser.add_argument('--isolated', action='store_true')
    parser.add_argument('--fixture', type=Path, default=FIXTURE)
    args = parser.parse_args()
    base = guard(args)
    fixture = validate_fixture(json.loads(args.fixture.read_text(encoding='utf-8')), args.container)
    (before if args.phase == 'before' else after)(base, args.container, fixture)
