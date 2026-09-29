"""Upgrade schema 2/3 to the current release using retained, disposable volumes."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from urllib.parse import urlparse
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('backup_qa',ROOT/'scripts/verify-backup.py')
qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
STATE=ROOT/'output/upgrade-before.json'

def before(base, source_schema=2, future_backup=None):
    assert qa.call(base,'/api/resumes')==[], 'Old instance must be empty and isolated'
    resume=qa.call(base,'/api/resumes',{'title':'版本升级 · 奶龙合成简历','sample':'one'})
    assert resume['document']['schemaVersion']==source_schema, 'Must start with the actual old schema'
    if source_schema==3:
        resume['document']['layout']['template']='banner'
        resume['document']['layout']['font']='serif'
        resume['document']['layout']['presentation'].update(accentColor='#7c3aed',marginTopMm=20,contactStyle='icons')
        resume=qa.save(base,resume)
    qa.call(base,'/api/resumes/'+resume['id']+'/versions',{'title':'旧版基线','expectedRevision':resume['revision']})
    exported=qa.call(base,'/api/resumes/'+resume['id']+'/export',{'expectedRevision':resume['revision']})
    assets={slot:qa.call(base,'/api/assets/'+resume['document']['layout'][slot]['id']) for slot in ['photo','logo']}
    meta,backup=qa.archive(base)
    ROOT.joinpath('output').mkdir(exist_ok=True)
    (ROOT/f'output/upgrade-schema-{source_schema}.zip').write_bytes(backup)
    rejected=False
    if future_backup:
        previous=qa.call(base,'/api/resumes')
        try:qa.upload(base,'/api/backups/restore','future.zip',future_backup.read_bytes())
        except HTTPError as error:
            assert error.code==422 and json.loads(error.read())['code']=='BACKUP_VERSION_UNSUPPORTED'
            rejected=True
        assert rejected and qa.call(base,'/api/resumes')==previous, 'Old app must reject future backup without changes'
    data={'base':base,'sourceSchema':source_schema,'futureBackupRejected':rejected,'resume':resume,'assets':assets,'export':exported,'versions':qa.call(base,'/api/resumes/'+resume['id']+'/versions')}
    STATE.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf8')
    print(f'Old schema {source_schema} resume, images, versions, PDF and complete backup prepared.')

def after(base):
    data=json.loads(STATE.read_text(encoding='utf8'));old=data['resume'];assert data['base']==base
    source_schema=data['sourceSchema']
    current=qa.call(base,'/api/resumes/'+old['id'])
    assert current['revision']==old['revision'] and current['updatedAt']==old['updatedAt']
    assert current['document']['content']==old['document']['content']
    assert current['document']['schemaVersion']==4
    for name,value in old['document']['layout'].items():assert current['document']['layout'][name]==value
    if source_schema==2:
        for side in ['marginHorizontalMm','marginTopMm','marginBottomMm']:assert current['document']['layout']['presentation'][side]==old['document']['layout']['marginMm']
    for slot,asset in data['assets'].items():assert qa.call(base,'/api/assets/'+asset['id'])==asset
    assert qa.call(base,'/api/resumes/'+old['id']+'/versions')==data['versions']
    pdf=qa.call(base,'/api/exports/'+data['export']['id']+'/pdf',raw=True)
    assert hashlib.sha256(pdf).hexdigest()==data['export']['sha256']
    restored=qa.upload(base,'/api/backups/restore','old.zip',(ROOT/f'output/upgrade-schema-{source_schema}.zip').read_bytes())
    imported=qa.call(base,'/api/resumes/'+restored['resumeIds'][0]);assert imported['document']['content']==old['document']['content']
    imported['document']['layout']['presentation']['accentColor']='#c65c19'
    imported['document']['layout']['presentation']['marginTopMm']=22
    imported['document']['layout']['template']='rail'
    imported=qa.save(base,imported)
    exported=qa.call(base,'/api/resumes/'+imported['id']+'/export',{'expectedRevision':imported['revision']})
    (ROOT/'output/pdf').mkdir(exist_ok=True)
    (ROOT/f'output/pdf/release-upgraded-schema-{source_schema}.pdf').write_bytes(qa.call(base,'/api/exports/'+exported['id']+'/pdf',raw=True))
    assert qa.call(base,'/api/resumes/'+old['id'])==current
    report={'upgrade':f'schema {source_schema} -> 4','sameVolumes':True,'oldContentRevisionDatesPreserved':True,'oldImagesVersionsPdfPreserved':True,'oldBackupRestored':True,'newTemplatesEditableAndExportable':True,'oldAppRejectedFutureBackup':data['futureBackupRejected']}
    (ROOT/f'output/upgrade-verification-schema-{source_schema}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('step',choices=['before','after']);parser.add_argument('--base',default='http://127.0.0.1:18769');parser.add_argument('--isolated',action='store_true');parser.add_argument('--source-schema',type=int,choices=[2,3],default=2);parser.add_argument('--future-backup',type=Path);args=parser.parse_args()
    target=urlparse(args.base);assert args.isolated and target.hostname in ['127.0.0.1','localhost'] and target.port and target.port!=18765
    STATE=ROOT/f'output/upgrade-before-schema-{args.source_schema}.json'
    if args.step=='before':before(args.base.rstrip('/'),args.source_schema,args.future_backup)
    else:after(args.base.rstrip('/'))
