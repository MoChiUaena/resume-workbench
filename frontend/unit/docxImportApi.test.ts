import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const moduleUrl=new URL('../src/api.ts',import.meta.url).href;
function check(script:string){const result=spawnSync(process.execPath,['--experimental-transform-types','--input-type=module','-e',`import assert from 'node:assert/strict';import {api,ApiFailure} from ${JSON.stringify(moduleUrl)};const url='/api/imports/docx/create';${script}`],{encoding:'utf8'});assert.equal(result.status,0,result.stderr||result.stdout);}
test('malformed DOCX create errors and success acknowledgements stay uncertain',()=>check(`
 for(const [status,body] of [[503,'{'],[422,'{}'],[200,'{']]){globalThis.fetch=async()=>new Response(body,{status});await assert.rejects(api(url,{mutationId:'x'}),e=>e instanceof ApiFailure&&e.code==='NETWORK_ERROR');}
`));
test('well formed DOCX business error keeps its code for review unlock',()=>check(`
 globalThis.fetch=async()=>new Response(JSON.stringify({code:'DOCX_IMPORT_DELETED',message:'已删除'}),{status:410});await assert.rejects(api(url,{mutationId:'x'}),e=>e instanceof ApiFailure&&e.code==='DOCX_IMPORT_DELETED'&&e.status===410);
`));
test('HTTP 503 with syntactically valid INVALID_INPUT remains uncertain',()=>check(`
 globalThis.fetch=async()=>new Response(JSON.stringify({code:'INVALID_INPUT',message:'错误'}),{status:503});await assert.rejects(api(url,{mutationId:'x'}),e=>e instanceof ApiFailure&&e.code==='INVALID_INPUT'&&e.status===503);
`));
test('PDF create error envelope is validated and exposes its status',()=>check(`
 const pdf='/api/imports/pdf/create';globalThis.fetch=async()=>new Response('{}',{status:422});await assert.rejects(api(pdf,{mutationId:'x'}),e=>e instanceof ApiFailure&&e.code==='NETWORK_ERROR');
 globalThis.fetch=async()=>new Response(JSON.stringify({code:'PDF_NO_TEXT',message:'无文字'}),{status:422});await assert.rejects(api(pdf,{mutationId:'x'}),e=>e instanceof ApiFailure&&e.code==='PDF_NO_TEXT'&&e.status===422);
`));
