"""Exercise instance-local policies and complete automatic archives, only in isolated loopback workspaces."""
import argparse,hashlib,importlib.util,json
from pathlib import Path
from urllib.parse import urlparse
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('backup_qa',ROOT/'scripts/verify-backup.py');qa=importlib.util.module_from_spec(spec);spec.loader.exec_module(qa)
REPORT=ROOT/'output/automatic-backup-verification.json'

def run(source,target):
    before={resume['id']:qa.call(source,'/api/resumes/'+resume['id']) for resume in qa.call(source,'/api/resumes')}
    target_before={resume['id']:qa.call(target,'/api/resumes/'+resume['id']) for resume in qa.call(target,'/api/resumes')}
    assert not qa.call(target,'/api/backups/automatic')['state']['enabled'], 'Target policy must start disabled'
    owned_source=[];owned_target=[]
    try:
        record=qa.call(source,'/api/resumes',{'title':'自动备份重启 · 奶龙合成示例','sample':'one'});owned_source.append(record['id'])
        configured=qa.call(source,'/api/backups/automatic',{'enabled':True,'frequency':'weekly'},'PUT');assert configured['state']['enabled']
        first=qa.call(source,'/api/backups/automatic/check',{})
        # A background due check may already have created the same baseline.
        assert first['state']['outcome'] in ['created','unchanged'] and first['state']['lastBackup']
        last=first['state']['lastBackup'];content=qa.call(source,'/api/backups/'+last['id']+'/download',raw=True)
        sha=hashlib.sha256(content).hexdigest();qa.canonical_zip(content)
        unchanged=qa.call(source,'/api/backups/automatic/check',{})
        assert unchanged['state']['outcome']=='unchanged' and unchanged['state']['lastBackup']['id']==last['id']
        assert qa.call(source,'/api/resumes/'+record['id'])==record
        restored=qa.upload(target,'/api/backups/restore','automatic.zip',content);owned_target.extend(restored['resumeIds'])
        imported=next(qa.call(target,'/api/resumes/'+identifier) for identifier in owned_target if qa.call(target,'/api/resumes/'+identifier)['title']==record['title'])
        assert imported['document']['content']==record['document']['content'] and imported['revision']==record['revision']
        for kind in ['photo','logo']:
            original=qa.call(source,'/api/assets/'+record['document']['layout'][kind]['id'])
            recovered=qa.call(target,'/api/assets/'+imported['document']['layout'][kind]['id'])
            assert original['sha256']==recovered['sha256'] and original['normalizedSha256']==recovered['normalizedSha256']
        assert not qa.call(target,'/api/backups/automatic')['state']['enabled'], 'Import must not enable another instance policy'
        policy=qa.call(source,'/api/backups/automatic')['state']
        report={'source':source,'target':target,'backupId':last['id'],'backupSha256':sha,'nextCheck':policy['nextCheck'],
                'completeAutomaticArchiveRestored':True,'unchangedDataSkipped':True,'sourceUnaffected':True,'targetOriginalsPreserved':True,'policyNotMigrated':True}
        REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
        print(json.dumps(report,ensure_ascii=False,indent=2))
    finally:
        for base,ids in [(target,owned_target),(source,owned_source)]:
            for identifier in ids:
                current=qa.call(base,'/api/resumes/'+identifier);qa.call(base,'/api/resumes/'+identifier,{'expectedRevision':current['revision']},'DELETE')
    for identifier,original in before.items():assert qa.call(source,'/api/resumes/'+identifier)==original
    for identifier,original in target_before.items():assert qa.call(target,'/api/resumes/'+identifier)==original

def persistence():
    report=json.loads(REPORT.read_text(encoding='utf8'));policy=qa.call(report['source'],'/api/backups/automatic')
    assert policy['state']['enabled'] and policy['state']['frequency']=='weekly' and policy['settingsReadable']
    assert policy['state']['nextCheck']==report['nextCheck'] and policy['state']['lastBackup']['id']==report['backupId']
    assert hashlib.sha256(qa.call(report['source'],'/api/backups/'+report['backupId']+'/download',raw=True)).hexdigest()==report['backupSha256']
    history=qa.call(report['source'],'/api/backups')['items'];assert any(item['backup']['id']==report['backupId'] and item['kind']=='automatic' for item in history)
    assert not qa.call(report['target'],'/api/backups/automatic')['state']['enabled']
    report['policyAndBackupSurviveContainerRecreation']=True;REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    print('Automatic policy, due time, archive bytes and catalog survive recreation; target remains disabled.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',default='http://127.0.0.1:18767');parser.add_argument('--target',default='http://127.0.0.1:18769');parser.add_argument('--isolated',action='store_true');parser.add_argument('--check-persistence',action='store_true');args=parser.parse_args()
    assert args.isolated
    for base in [args.source,args.target]:
        url=urlparse(base);assert url.scheme=='http' and url.hostname in ['127.0.0.1','localhost'] and url.port and url.port!=18765
    if args.check_persistence:persistence()
    else:assert args.source!=args.target;run(args.source.rstrip('/'),args.target.rstrip('/'))
