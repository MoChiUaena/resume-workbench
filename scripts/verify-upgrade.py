"""Upgrade schema 2/3/4 to the current release using retained, disposable volumes."""
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
SUFFIX='schema-2'

def before(base, source_schema=2, future_backup=None, automatic_enabled=False, webp_backup=None):
    assert qa.call(base,'/api/resumes')==[], 'Old instance must be empty and isolated'
    resume=qa.call(base,'/api/resumes',{'title':'版本升级 · 奶龙合成简历','sample':'one'})
    assert resume['document']['schemaVersion']==source_schema, 'Must start with the actual old schema'
    if source_schema in [3,4]:
        resume['document']['layout']['template']='banner'
        resume['document']['layout']['font']='serif'
        resume['document']['layout']['presentation'].update(accentColor='#7c3aed',marginTopMm=20,contactStyle='icons')
        resume=qa.save(base,resume)
    qa.call(base,'/api/resumes/'+resume['id']+'/versions',{'title':'旧版基线','expectedRevision':resume['revision']})
    exported=qa.call(base,'/api/resumes/'+resume['id']+'/export',{'expectedRevision':resume['revision']})
    assets={slot:qa.call(base,'/api/assets/'+resume['document']['layout'][slot]['id']) for slot in ['photo','logo']}
    meta,backup=qa.archive(base)
    ROOT.joinpath('output').mkdir(exist_ok=True)
    (ROOT/f'output/upgrade-{SUFFIX}.zip').write_bytes(backup)
    rejected=False
    if future_backup:
        previous=qa.call(base,'/api/resumes')
        try:qa.upload(base,'/api/backups/restore','future.zip',future_backup.read_bytes())
        except HTTPError as error:
            assert error.code==422 and json.loads(error.read())['code']=='BACKUP_VERSION_UNSUPPORTED'
            rejected=True
        assert rejected and qa.call(base,'/api/resumes')==previous, 'Old app must reject future backup without changes'
    webp_rejected=False
    if webp_backup:
        previous=qa.call(base,'/api/resumes')
        try:qa.upload(base,'/api/backups/restore','webp.zip',webp_backup.read_bytes())
        except HTTPError as error:
            assert error.code==422 and json.loads(error.read())['code']=='BACKUP_INVALID'
            webp_rejected=True
        assert webp_rejected and qa.call(base,'/api/resumes')==previous
    policy=None;automatic_hash=None
    if automatic_enabled:
        qa.call(base,'/api/backups/automatic',{'enabled':True,'frequency':'weekly'},'PUT')
        status=qa.call(base,'/api/backups/automatic/check',{})
        assert not status['running'] and status['state']['lastBackup']
        policy=status['state']
        automatic_hash=hashlib.sha256(qa.call(base,'/api/backups/'+policy['lastBackup']['id']+'/download',raw=True)).hexdigest()
    data={'base':base,'sourceSchema':source_schema,'futureBackupRejected':rejected,'webpBackupRejected':webp_rejected,'automaticState':policy,'automaticArchiveSha256':automatic_hash,'backupKind':'manual' if automatic_enabled else 'legacy','backupId':meta['id'],'resume':resume,'assets':assets,'export':exported,'versions':qa.call(base,'/api/resumes/'+resume['id']+'/versions')}
    STATE.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf8')
    print(f'Old schema {source_schema} resume, images, versions, PDF and complete backup prepared.')

def after(base):
    data=json.loads(STATE.read_text(encoding='utf8'));old=data['resume'];assert data['base']==base
    source_schema=data['sourceSchema']
    current=qa.call(base,'/api/resumes/'+old['id'])
    history=qa.call(base,'/api/backups')['items']
    assert any(item['backup']['id']==data['backupId'] and item['kind']==data.get('backupKind','legacy') for item in history), 'Pre-upgrade ZIP must remain available'
    automatic=qa.call(base,'/api/backups/automatic')['state']
    if data.get('automaticState'):
        assert automatic==data['automaticState'], 'Existing automatic backup policy changed'
        assert hashlib.sha256(qa.call(base,'/api/backups/'+automatic['lastBackup']['id']+'/download',raw=True)).hexdigest()==data['automaticArchiveSha256']
    else:
        assert not automatic['enabled'], 'Upgrade must not enable background backups'
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
    restored=qa.upload(base,'/api/backups/restore','old.zip',(ROOT/f'output/upgrade-{SUFFIX}.zip').read_bytes())
    imported=qa.call(base,'/api/resumes/'+restored['resumeIds'][0]);assert imported['document']['content']==old['document']['content']
    imported['document']['layout']['presentation']['accentColor']='#c65c19'
    imported['document']['layout']['presentation']['marginTopMm']=22
    imported['document']['layout']['template']='rail'
    imported=qa.save(base,imported)
    exported=qa.call(base,'/api/resumes/'+imported['id']+'/export',{'expectedRevision':imported['revision']})
    (ROOT/'output/pdf').mkdir(exist_ok=True)
    (ROOT/f'output/pdf/release-upgraded-{SUFFIX}.pdf').write_bytes(qa.call(base,'/api/exports/'+exported['id']+'/pdf',raw=True))
    options={name:True for name in ['name','phone','email','location','photo','logo','matchingText']}
    projected=qa.call(base,'/api/resumes/'+imported['id']+'/export/preview',{'expectedRevision':imported['revision'],'redaction':options})
    redacted=qa.call(base,'/api/resumes/'+imported['id']+'/export',{'expectedRevision':imported['revision'],'redaction':options,'previewDigest':projected['digest']})
    (ROOT/f'output/pdf/release-upgraded-redacted-{SUFFIX}.pdf').write_bytes(qa.call(base,'/api/exports/'+redacted['id']+'/pdf',raw=True))
    assert qa.call(base,'/api/resumes/'+imported['id'])==imported
    assert qa.call(base,'/api/resumes/'+old['id'])==current
    report={'upgrade':f'schema {source_schema} -> 4','sameVolumes':True,'oldContentRevisionDatesPreserved':True,'oldImagesVersionsPdfPreserved':True,'oldBackupRestored':True,'oldBackupVisibleInHistory':True,'automaticBackupsRemainDisabled':not automatic['enabled'],'automaticPolicyPreserved':True,'newTemplatesEditableAndExportable':True,'redactedExportLeavesSourceUnchanged':True,'oldAppRejectedFutureBackup':data['futureBackupRejected'],'oldAppRejectedWebpBackup':data.get('webpBackupRejected',False)}
    (ROOT/f'output/upgrade-verification-{SUFFIX}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('step',choices=['before','after']);parser.add_argument('--base',default='http://127.0.0.1:18769');parser.add_argument('--isolated',action='store_true');parser.add_argument('--source-schema',type=int,choices=[2,3,4],default=2);parser.add_argument('--future-backup',type=Path);parser.add_argument('--source-version');parser.add_argument('--automatic-enabled',action='store_true');parser.add_argument('--unsupported-webp-backup',type=Path);args=parser.parse_args()
    target=urlparse(args.base);assert args.isolated and target.hostname in ['127.0.0.1','localhost'] and target.port and target.port!=18765
    if args.source_version:
        import re
        assert re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+',args.source_version)
    SUFFIX=f'schema-{args.source_schema}'+('-'+args.source_version if args.source_version else '')
    STATE=ROOT/f'output/upgrade-before-{SUFFIX}.json'
    if args.step=='before':before(args.base.rstrip('/'),args.source_schema,args.future_backup,args.automatic_enabled,args.unsupported_webp_backup)
    else:after(args.base.rstrip('/'))
