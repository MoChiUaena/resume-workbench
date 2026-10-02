import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
const uuid=/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/;
export function requireIsolatedQuarantineWorkspace(){
 const container=process.env.RESUME_TEST_APP_CONTAINER,base=new URL(process.env.RESUME_TEST_BASE_URL||'http://127.0.0.1:18765');
 if(process.env.RESUME_TEST_ISOLATED!=='1'||!['resume-quarantine-qa-app-1','resume-ci-source-app-1'].includes(container||'')||base.hostname!=='127.0.0.1'||base.port!=='18767')throw Error('Quarantine fixture requires named isolated QA/CI container and port 18767');return container!;
}
function run(mode:string,id:string,operationId=''){
 const container=requireIsolatedQuarantineWorkspace();if(!uuid.test(id)||(operationId&&!uuid.test(operationId)))throw Error('Invalid test fixture identity');
 const script=`const fs=require('fs'),path=require('path'),crypto=require('crypto');const [mode,id,operation]=process.argv.slice(2);const root='/app/data/attachments',source=path.join(root,id);const hash=b=>crypto.createHash('sha256').update(b).digest('hex');const plain=p=>{const s=fs.lstatSync(p);if(s.isSymbolicLink())throw Error('Link fixture rejected');return s;};
 if(mode==='clone'){if(!plain(source).isDirectory())throw Error('Expected directory');const m=JSON.parse(fs.readFileSync(path.join(source,'metadata.json'),'utf8'));if(m.id!==id||!['PNG','JPEG','WEBP'].includes(m.format))throw Error('Invalid fixture metadata');const names=['metadata.json','image.png','original.'+m.format.toLowerCase()];if(JSON.stringify(fs.readdirSync(source).sort())!==JSON.stringify(names.sort()))throw Error('Unexpected fixture entries');const next=crypto.randomUUID(),target=path.join(root,next);fs.mkdirSync(target);for(const name of names){if(!plain(path.join(source,name)).isFile())throw Error('Invalid fixture file');fs.copyFileSync(path.join(source,name),path.join(target,name),fs.constants.COPYFILE_EXCL);}m.id=next;fs.writeFileSync(path.join(target,'metadata.json'),JSON.stringify(m));const old=new Date(Date.now()-31*86400000);for(const name of names)fs.utimesSync(path.join(target,name),old,old);fs.utimesSync(target,old,old);console.log(JSON.stringify({id:next}));}
 else if(mode==='inspect'){const target=operation?path.join('/app/data/quarantine',operation,'payload','attachments',id):source;if(!fs.existsSync(target)){console.log(JSON.stringify({exists:false}));}else{if(!plain(target).isDirectory())throw Error('Invalid directory');const files=Object.fromEntries(fs.readdirSync(target).sort().map(name=>{const file=path.join(target,name);if(!plain(file).isFile())throw Error('Invalid file');return[name,hash(fs.readFileSync(file))];}));console.log(JSON.stringify({exists:true,files,sha:hash(JSON.stringify(files))}));}}
 else if(mode==='create'){if(fs.existsSync(source))throw Error('Conflict target already exists');fs.mkdirSync(source);fs.writeFileSync(path.join(source,'fixture-conflict.txt'),'test-owned conflict',{flag:'wx'});console.log('{}');}
 else if(mode==='remove'){if(!plain(source).isDirectory()||JSON.stringify(fs.readdirSync(source))!=='["fixture-conflict.txt"]'||fs.readFileSync(path.join(source,'fixture-conflict.txt'),'utf8')!=='test-owned conflict')throw Error('Conflict ownership mismatch');fs.unlinkSync(path.join(source,'fixture-conflict.txt'));fs.rmdirSync(source);console.log('{}');}else throw Error('Invalid fixture mode');`;
 const result=spawnSync('docker',['exec','-i',container,'/ms-playwright-driver/node','-',mode,id,operationId],{input:script,encoding:'utf8'});if(result.status!==0)throw Error(result.stderr||'Fixture exec failed');return JSON.parse(result.stdout);
}
export const seedCandidate=(id:string)=>run('clone',id);
export const inspectFixture=(id:string,operationId='')=>run('inspect',id,operationId);
export const conflictFixture=(id:string,mode:'create'|'remove')=>run(mode,id);
/** Read only fingerprints in the same named synthetic workspace; never follows links. */
export function sourceCanaries(){
 const container=requireIsolatedQuarantineWorkspace();
 const script=`const fs=require('fs'),path=require('path'),crypto=require('crypto'),root='/app/data',files={};let count=0,total=0;const visit=p=>{const stat=fs.lstatSync(p);if(stat.isSymbolicLink())throw Error('Canary link rejected');if(stat.isDirectory()){for(const name of fs.readdirSync(p).sort())visit(path.join(p,name));}else{if(!stat.isFile()||++count>10000||(total+=stat.size)>1073741824)throw Error('Canary boundary');files[path.relative(root,p)]=crypto.createHash('sha256').update(fs.readFileSync(p)).digest('hex');}};for(const name of ['attachments','exports','backups','model-settings']){const p=path.join(root,name);if(fs.existsSync(p))visit(p);}console.log(JSON.stringify({files,sha256:crypto.createHash('sha256').update(JSON.stringify(files)).digest('hex')}));`;
 const result=spawnSync('docker',['exec','-i',container,'/ms-playwright-driver/node','-'],{input:script,encoding:'utf8'});if(result.status!==0)throw Error(result.stderr||'Canary read failed');
 const db=container==='resume-ci-source-app-1'?'resume-ci-source-db-1':'resume-quarantine-qa-db-1';
 const sql=['attachments','resumes','resume_versions','resume_assets','version_assets'].map(table=>`SELECT '${table}',coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text)::text,'[]') FROM ${table} t`).join(' UNION ALL ');
 const rows=spawnSync('docker',['exec',db,'psql','-U','local_resume','-d','local_resume','-At','-c',sql],{encoding:'utf8'});if(rows.status!==0)throw Error('Canary database read failed');
 return {...JSON.parse(result.stdout),databaseSHA256:createHash('sha256').update(rows.stdout).digest('hex')};
}
export function purgePayloadInventory(operationId:string){
 const container=requireIsolatedQuarantineWorkspace();if(!uuid.test(operationId))throw Error('Invalid cleanup fixture identity');
 const script=`const fs=require('fs'),path=require('path'),root=path.join('/app/data/quarantine',process.argv[2],'payload');let bytes=0,files=0,entries=0;const visit=p=>{const s=fs.lstatSync(p);if(s.isSymbolicLink()||++entries>10000)throw Error('Unsafe payload inspection');if(s.isDirectory()){for(const name of fs.readdirSync(p))visit(path.join(p,name));}else{if(!s.isFile())throw Error('Unknown payload entry');bytes+=s.size;files++;}};const exists=fs.existsSync(root);if(exists)visit(root);console.log(JSON.stringify({exists,bytes,files}));`;
 const result=spawnSync('docker',['exec','-i',container,'/ms-playwright-driver/node','-',operationId],{input:script,encoding:'utf8'});if(result.status!==0)throw Error(result.stderr||'Payload inspect failed');return JSON.parse(result.stdout);
}
