"""Prove instance credentials survive recreation and never enter a portable workspace ZIP."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import runpy
from urllib.parse import urlparse
import zipfile

ROOT=Path(__file__).resolve().parents[1]
helpers=runpy.run_path(str(ROOT/'scripts/verify-backup.py'))
call,archive,upload=(helpers[name] for name in ['call','archive','upload'])
REPORT=ROOT/'output/model-settings-verification.json'

def create(base,name,secret):
    state=call(base,'/api/models')
    return call(base,'/api/models/profiles',{'expectedRevision':state['revision'],'name':name,'provider':'compatible','baseUrl':'http://127.0.0.1:18770/v1','model':'qa-model','apiKey':secret,'clearKey':False})

def run(source,target):
    source_state=create(source,'本机模型持久化验证','source-key-canary')
    source_state=call(source,'/api/models/enabled',{'expectedRevision':source_state['revision'],'enabled':True},'PUT')
    target_state=create(target,'目标实例原有配置','target-key-canary')
    assert 'source-key-canary' not in json.dumps(source_state) and 'target-key-canary' not in json.dumps(target_state)
    backup,content=archive(source)
    with zipfile.ZipFile(io.BytesIO(content)) as bundle:
        assert not any('model-settings' in name or 'master.key' in name for name in bundle.namelist())
        for name in bundle.namelist():
            payload=bundle.read(name)
            assert b'source-key-canary' not in payload and b'target-key-canary' not in payload
    upload(target,'/api/backups/restore','settings-exclusion.zip',content)
    assert call(source,'/api/models')==source_state and call(target,'/api/models')==target_state
    REPORT.parent.mkdir(exist_ok=True)
    REPORT.write_text(json.dumps({'source':source,'target':target,'sourceState':source_state,'targetState':target_state,'backupId':backup['id'],'backupSha256':hashlib.sha256(content).hexdigest(),'credentialsExcludedFromPortableZip':True,'targetSettingsUnaffectedByRestore':True},ensure_ascii=False,indent=2),encoding='utf-8')
    print('Model settings: credentials excluded from ZIP; source/target instance policies preserved.')

def persistence(source,target):
    result=json.loads(REPORT.read_text(encoding='utf-8'))
    assert result['source']==source and result['target']==target
    for side in ['source','target']:
        assert call(result[side],'/api/models')==result[side+'State']
    checked=call(source,'/api/models/profiles/'+result['sourceState']['defaultId']+'/test',{'expectedRevision':result['sourceState']['revision']})
    assert checked['connected'], 'Stored key must remain decryptable after restart'
    assert hashlib.sha256(call(source,'/api/backups/'+result['backupId']+'/download',raw=True)).hexdigest()==result['backupSha256']
    result['containerRecreationPreservesModelsAndDecryptableCredentials']=True
    REPORT.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('Recreation preserved model configuration and decryptable credentials without exposing keys.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',default='http://127.0.0.1:18767');parser.add_argument('--target',default='http://127.0.0.1:18769');parser.add_argument('--isolated',action='store_true');parser.add_argument('--check-persistence',action='store_true');args=parser.parse_args()
    assert args.isolated and args.source!=args.target
    for base in [args.source,args.target]:
        parsed=urlparse(base);assert parsed.scheme=='http' and parsed.hostname in ['127.0.0.1','localhost'] and parsed.port and parsed.port!=18765
    (persistence if args.check_persistence else run)(args.source.rstrip('/'),args.target.rstrip('/'))
