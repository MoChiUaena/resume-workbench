import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const moduleUrl=new URL('../src/api.ts',import.meta.url).href;
function check(script:string){
 const result=spawnSync(process.execPath,['--experimental-transform-types','--input-type=module','-e',`import assert from 'node:assert/strict';import {api,ApiFailure} from ${JSON.stringify(moduleUrl)};const reply=(body,status=503)=>{globalThis.fetch=async()=>new Response(JSON.stringify(body),{status});};${script}`],{encoding:'utf8'});
 assert.equal(result.status,0,result.stderr||result.stdout);
}
test('unreadable successful job generation acknowledgements are uncertain',()=>check(`
 globalThis.fetch=async()=>new Response('{',{status:200});
 await assert.rejects(api('/api/ai/job-matches',{previewId:'same-preview',confirmSend:true}),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');
`));
test('invalid job generation error envelopes are uncertain',()=>check(`
 const invalid=[{},null,[],{code:12,message:'broken'},{code:'MODEL_BUSY',message:12},{code:'MODEL_BUSY'},{message:'broken'},{code:'',message:'broken'},{code:'lowercase',message:'broken'},{code:'A'.repeat(81),message:'broken'},{code:'MODEL_BUSY',message:''},{code:'MODEL_BUSY',message:'   '},{code:'MODEL_BUSY',message:'x'.repeat(1025)}];
 for(const body of invalid){reply(body);await assert.rejects(api('/api/ai/job-matches',{previewId:'same-preview',confirmSend:true}),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');}
`));
test('job generation retains known business errors and valid responses',()=>check(`
 for(const body of [{code:'MODEL_BUSY',message:'稍后重试。'},{code:'JOB_MATCH_PREVIEW_EXPIRED',message:'已过期。'},{code:'MODEL_AUTH_FAILED',message:'鉴权失败。'}]){reply(body,423);await assert.rejects(api('/api/ai/job-matches',{}),error=>error instanceof ApiFailure&&error.code===body.code&&error.message===body.message+'（'+body.code+'）');}
 globalThis.fetch=async()=>{throw new TypeError('lost connection');};await assert.rejects(api('/api/ai/job-matches',{}),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');
 globalThis.fetch=async()=>new Response('{',{status:503});await assert.rejects(api('/api/ai/job-matches',{}),error=>error instanceof ApiFailure&&error.code==='NETWORK_ERROR');
 reply({id:'cached-report'},200);assert.deepEqual(await api('/api/ai/job-matches',{}),{id:'cached-report'});
`));
