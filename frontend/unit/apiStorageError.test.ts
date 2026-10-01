import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const moduleUrl=new URL('../src/api.ts',import.meta.url).href;
// Transform types in a child because api.ts's existing parameter properties need it.
// fetch is stubbed in that process: every assertion executes the exported api, without network.
function check(script:string){
 const result=spawnSync(process.execPath,['--experimental-transform-types','--input-type=module','-e',`import assert from 'node:assert/strict';import {api,ApiFailure} from ${JSON.stringify(moduleUrl)};const reply=(body,status=503)=>{globalThis.fetch=async()=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});};${script}`],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr||result.stdout);
}
test('invalid storage error envelopes are uncertain rather than known application failures',()=>check(`
 const invalid=[{},null,[],{code:12,message:'broken'},{code:'BACKUP_FAILED',message:12},{code:'BACKUP_FAILED'},{message:'broken'},{code:'',message:'broken'},{code:'lowercase',message:'broken'},{code:'A'.repeat(81),message:'broken'},{code:'BACKUP_FAILED',message:''},{code:'BACKUP_FAILED',message:'   '},{code:'BACKUP_FAILED',message:'x'.repeat(1025)}];
 for(const url of ['/api/storage/quarantine','/api/storage/quarantine/11111111-1111-4111-8111-111111111111/restore'])for(const body of invalid){reply(body);await assert.rejects(api(url,{confirm:true}),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');}
`));
test('valid bounded storage errors retain the exact known code and message',()=>check(`
 for(const body of [{code:'QUARANTINE_CONFLICT',message:'原位置有文件，未覆盖。'},{code:'STORAGE_PREVIEW_CHANGED',message:'重新检查。'},{code:'A'.repeat(80),message:'x'.repeat(1024)}]){reply(body,409);await assert.rejects(api('/api/storage/quarantine',{confirm:true}),error=>error instanceof ApiFailure&&error.code===body.code&&error.message===body.message+'（'+body.code+'）');}
`));
test('unparseable storage error acknowledgements stay uncertain',()=>check(`
 globalThis.fetch=async()=>new Response('{',{status:503});await assert.rejects(api('/api/storage/quarantine',{confirm:true}),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');
`));
test('storage read errors use the same bounded envelope validation',()=>check(`
 reply(null);await assert.rejects(api('/api/storage/preview'),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');reply({code:'STORAGE_SCAN_FAILED',message:'已确认检查失败。'});await assert.rejects(api('/api/storage/preview'),error=>error instanceof ApiFailure&&error.code==='STORAGE_SCAN_FAILED');
`));
test('nonstorage error behavior and successful JSON responses stay unchanged',()=>check(`
 reply({code:'REVISION_CONFLICT',message:'known conflict'},409);await assert.rejects(api('/api/resumes/example',{}),error=>error instanceof ApiFailure&&error.code==='REVISION_CONFLICT');
 reply({code:12,message:'broken'});await assert.rejects(api('/api/resumes/example',{}),error=>error instanceof ApiFailure&&error.code===12);
 reply(null);await assert.rejects(api('/api/resumes/example',{}),error=>error instanceof TypeError);
 reply({id:'ok'},200);assert.deepEqual(await api('/api/storage/quarantine',{}),{id:'ok'});
`));
