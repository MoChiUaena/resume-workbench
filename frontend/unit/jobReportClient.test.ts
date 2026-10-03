import test from 'node:test';
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
const moduleUrl=new URL('../src/jobMatchApi.ts',import.meta.url).href;
const resumeId='a1111111-1111-4111-8111-000000000001',previewId='b1111111-1111-4111-8111-000000000002',reportId='c1111111-1111-4111-8111-000000000003';
const saved={id:reportId,resumeId,label:'岗位报告',sourceRevision:3,createdAt:'2026-10-03T00:00:00Z',profileName:'fixture',provider:'compatible',model:'qa-job-normal',sourceChanged:false,snapshot:{schemaVersion:1,sourceRevision:3,jobDescription:'具备 Java 项目经验',profileName:'fixture',provider:'compatible',model:'qa-job-normal',destination:'http://127.0.0.1:18770/v1/chat/completions',sources:[{id:'s1',sectionId:'project',entryId:'entry-1',paragraph:0,sectionTitle:'项目经历',entryTitle:'接口文档',text:'使用 Java 整理接口文档。'}],items:[{requirement:'Java 项目经验',status:'supported',evidence:[{sourceId:'s1',quote:'使用 Java'}],advice:''}],suggestions:[{sourceId:'s1',replacement:'使用 Java 整理接口文档，并记录联调问题。'}]}};
function check(script:string){
 const setup=`import assert from 'node:assert/strict';import {registerHooks} from 'node:module';registerHooks({resolve(specifier,context,nextResolve){try{return nextResolve(specifier,context);}catch(cause){if(specifier.startsWith('./')&&context.parentURL?.endsWith('.ts')&&!specifier.endsWith('.ts'))return nextResolve(specifier+'.ts',context);throw cause;}}});const {saveJobReport,savedJobReports,savedJobReport,deleteJobReport}=await import(${JSON.stringify(moduleUrl)});const saved=${JSON.stringify(saved)},resumeId=${JSON.stringify(resumeId)},previewId=${JSON.stringify(previewId)},reportId=${JSON.stringify(reportId)},collection='/api/resumes/'+resumeId+'/job-reports';const response=(body,status=200)=>new Response(JSON.stringify(body),{status});`;
 const result=spawnSync(process.execPath,['--experimental-transform-types','--input-type=module','-e',setup+script],{encoding:'utf8'});assert.equal(result.status,0,result.stderr||result.stdout);
}
test('saving reaches only the local report endpoint with the exact immutable report identity and label',()=>check(`
 globalThis.fetch=async(url,options)=>{const body=JSON.parse(options.body);return url===collection&&options.method==='POST'&&options.headers['X-Local-Resume']==='1'&&body.previewId===previewId&&body.reportId===reportId&&body.label==='岗位报告'&&Object.keys(body).length===3?response(saved):response({code:'WRONG_REQUEST',message:'Unexpected request'},422);};
 assert.deepEqual(await saveJobReport(resumeId,Object.freeze({previewId,reportId,label:'岗位报告'}),3),saved);
`));
test('a successful save body that mismatches its request remains uncertain',()=>check(`
 for(const changed of [{id:previewId},{resumeId:previewId},{label:'另一名称'},{sourceRevision:4},{snapshot:null}]){globalThis.fetch=async()=>response({...saved,...changed});await assert.rejects(saveJobReport(resumeId,{previewId,reportId,label:'岗位报告'},3),error=>error.code==='NETWORK_ERROR');}
`));
test('history reads the requested bounded page and an owned read-only snapshot',()=>check(`
 const {snapshot,...summary}=saved;const page={page:1,pageSize:20,total:21,pages:2,items:[summary]};
 globalThis.fetch=async(url,options)=>url===collection+'?page=1'&&options.method==='GET'?response(page):response({code:'WRONG_REQUEST',message:'Unexpected page'},422);assert.deepEqual(await savedJobReports(resumeId,1),page);
 globalThis.fetch=async(url,options)=>url===collection+'/'+reportId&&options.method==='GET'?response(saved):response({code:'WRONG_REQUEST',message:'Unexpected report'},422);assert.deepEqual(await savedJobReport(resumeId,reportId),saved);
 globalThis.fetch=async()=>response({...page,page:0});await assert.rejects(savedJobReports(resumeId,1),error=>error.code==='NETWORK_ERROR');
 globalThis.fetch=async()=>response({...saved,id:previewId});await assert.rejects(savedJobReport(resumeId,reportId),error=>error.code==='NETWORK_ERROR');
`));
test('bodyless report deletion sends local authorization and checks the exact deletion receipt',()=>check(`
 globalThis.fetch=async(url,options)=>url===collection+'/'+reportId&&options.method==='DELETE'&&options.headers['X-Local-Resume']==='1'?response({id:reportId,deleted:true}):response({code:'LOCAL_ONLY',message:'Missing local mutation header'},403);
 assert.deepEqual(await deleteJobReport(resumeId,reportId),{id:reportId,deleted:true});
 globalThis.fetch=async()=>response({id:previewId,deleted:true});await assert.rejects(deleteJobReport(resumeId,reportId),error=>error.code==='NETWORK_ERROR');
`));
test('saved-report business errors remain actionable rather than inviting a new generation',()=>check(`
 for(const code of ['REPORT_SAVE_CONFLICT','JOB_REPORT_SOURCE_INVALID','JOB_REPORT_EXPIRED','JOB_REPORT_DELETED','JOB_REPORT_LIMIT']){globalThis.fetch=async()=>response({code,message:'已确认的报告错误'},code==='REPORT_SAVE_CONFLICT'?409:410);await assert.rejects(saveJobReport(resumeId,{previewId,reportId,label:'岗位报告'},3),error=>error.code===code);}
 globalThis.fetch=async()=>response({code:'JOB_REPORT_NOT_FOUND',message:'报告不存在'},404);await assert.rejects(savedJobReport(resumeId,reportId),error=>error.code==='JOB_REPORT_NOT_FOUND');
`));
