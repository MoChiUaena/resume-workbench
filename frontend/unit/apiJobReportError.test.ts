import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const moduleUrl=new URL('../src/api.ts',import.meta.url).href;
function check(script:string){const result=spawnSync(process.execPath,['--experimental-transform-types','--input-type=module','-e',`import assert from 'node:assert/strict';import {api,ApiFailure} from ${JSON.stringify(moduleUrl)};const base='/api/resumes/11111111-1111-4111-8111-111111111111/job-reports';${script}`],{encoding:'utf8'});assert.equal(result.status,0,result.stderr||result.stdout);}
test('unreadable report save and deletion success acknowledgements remain uncertain',()=>check(`
 for(const [url,method] of [[base,'POST'],[base+'/22222222-2222-4222-8222-222222222222','DELETE']]){globalThis.fetch=async()=>new Response('{',{status:200});await assert.rejects(api(url,{},method),e=>e instanceof ApiFailure&&e.code==='NETWORK_ERROR');}
`));
test('invalid report mutation error envelopes do not authorize a new save or delete',()=>check(`
 for(const body of [null,{},[],{code:12,message:'bad'},{code:'JOB_REPORT_EXPIRED',message:''},{code:'JOB_REPORT_EXPIRED',message:12}]){globalThis.fetch=async()=>new Response(JSON.stringify(body),{status:503});await assert.rejects(api(base,{},'POST'),e=>e instanceof ApiFailure&&e.code==='NETWORK_ERROR');}
`));
test('known report business errors and well formed save responses are preserved',()=>check(`
 globalThis.fetch=async()=>new Response(JSON.stringify({code:'JOB_REPORT_DELETED',message:'已删除'}),{status:410});await assert.rejects(api(base,{},'POST'),e=>e instanceof ApiFailure&&e.code==='JOB_REPORT_DELETED');
 const payload={id:'saved-report'};globalThis.fetch=async()=>new Response(JSON.stringify(payload),{status:200});assert.deepEqual(await api(base,{},'POST'),payload);
`));
