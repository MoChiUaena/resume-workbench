import test from 'node:test';
import assert from 'node:assert/strict';
import {isSavedJobReportDetail,isSavedJobReportPage,isJobReportDeleteReceipt,createJobReportSaveAttempt,rememberJobReportSave,pendingJobReportSave,resolveJobReportSave} from '../src/jobReportReceipt.ts';
const resumeId='a1111111-1111-4111-8111-000000000001',previewId='b1111111-1111-4111-8111-000000000002',reportId='c1111111-1111-4111-8111-000000000003';
const detail=()=>({id:reportId,resumeId,label:'Java 后端岗位',sourceRevision:7,createdAt:'2026-10-03T00:00:00.123456789Z',profileName:'本地测试模型',provider:'compatible',model:'qa-job-normal',sourceChanged:false,snapshot:{schemaVersion:1,sourceRevision:7,jobDescription:'具备 Java 项目经验',profileName:'本地测试模型',provider:'compatible',model:'qa-job-normal',destination:'http://127.0.0.1:18770/v1/chat/completions',sources:[{id:'s0',sectionId:resumeId,entryId:previewId,paragraph:0,sectionTitle:'项目经历',entryTitle:'接口文档',text:'使用 Java 整理接口文档。'}],items:[{requirement:'Java 项目经验',status:'supported',evidence:[{sourceId:'s0',quote:'使用 Java'}],advice:''}],suggestions:[{sourceId:'s0',replacement:'使用 Java 整理接口文档，并记录联调问题。'}]}});

test('saved acknowledgement must identify the exact immutable save request',()=>{
 const value=detail();assert.equal(isSavedJobReportDetail(value,resumeId,reportId,value.label,7),true);
 for(const changed of [{id:previewId},{resumeId:previewId},{label:'不同岗位'},{sourceRevision:8},{sourceChanged:'false'},{createdAt:'yesterday'},{snapshot:null}])assert.equal(isSavedJobReportDetail({...value,...changed},resumeId,reportId,value.label,7),false);
 assert.equal(isSavedJobReportDetail({...value,sourceChanged:true},resumeId,reportId,value.label,7),true);
});
test('archived evidence is checked against original material and has no live suggestion tokens',()=>{
 const value=detail();
 for(const changed of [{sourceRevision:8},{schemaVersion:2},{sources:[]},{items:[{...value.snapshot.items[0],status:'unknown'}]},{items:[{...value.snapshot.items[0],evidence:[{sourceId:'other',quote:'使用 Java'}]}]},{items:[{...value.snapshot.items[0],evidence:[{sourceId:'s0',quote:'并不存在的引用'}]}]},{suggestions:[{sourceId:'s0',replacement:'改写',id:reportId,expiresAt:'2026-10-03T00:00:00Z'}]},{suggestions:[{sourceId:'other',replacement:'改写'}]}])assert.equal(isSavedJobReportDetail({...value,snapshot:{...value.snapshot,...changed}},resumeId),false);
});
test('historical section and entry identifiers follow resume string identifiers rather than UUID-only storage ids',()=>{
 const value=detail();value.snapshot.sources[0].sectionId='imported-project';value.snapshot.sources[0].entryId='project-entry-1';
 assert.equal(isSavedJobReportDetail(value,resumeId),true);
});
test('history receipts must belong to their requested parent and bounded page',()=>{
 const {snapshot,...summary}=detail();const page={page:0,pageSize:20,total:1,pages:1,items:[summary]};
 assert.equal(isSavedJobReportPage(page,resumeId,0),true);
 assert.equal(isSavedJobReportPage({page:0,pageSize:20,total:0,pages:0,items:[]},resumeId,0),true);
 for(const changed of [{page:1},{pageSize:100},{total:101},{pages:2},{items:[{...summary,resumeId:previewId}]},{items:[summary,summary]},{items:null}])assert.equal(isSavedJobReportPage({...page,...changed},resumeId,0),false);
});
test('delete receipt cannot acknowledge another report or an incomplete deletion',()=>{
 assert.equal(isJobReportDeleteReceipt({id:reportId,deleted:true},reportId),true);
 for(const value of [null,{},[],{id:previewId,deleted:true},{id:reportId,deleted:false},{id:reportId}])assert.equal(isJobReportDeleteReceipt(value,reportId),false);
});
test('save retries keep exact ids and trimmed label even when an input object changes',()=>{
 const input={resumeId,previewId,reportId,label:'  Java 后端岗位  ',sourceRevision:7};const attempt=createJobReportSaveAttempt(input);
 input.label='不同岗位';input.reportId=previewId;
 assert.deepEqual(attempt.request,{previewId,reportId,label:'Java 后端岗位'});assert.equal(attempt.resumeId,resumeId);assert.equal(attempt.sourceRevision,7);
 assert.throws(()=>{(attempt.request as {label:string}).label='不同岗位';},TypeError);
 for(const label of ['', '  ', '字'.repeat(121)])assert.throws(()=>createJobReportSaveAttempt({...input,reportId,label}),RangeError);
});
test('an unconfirmed save survives dialog remount and cannot be replaced by another request',()=>{
 const attempt=createJobReportSaveAttempt({resumeId,previewId,reportId,label:'固定名称',sourceRevision:7});rememberJobReportSave(attempt);
 assert.deepEqual(pendingJobReportSave(resumeId),attempt);
 const different=createJobReportSaveAttempt({resumeId,previewId,reportId,label:'改变名称',sourceRevision:7});
 assert.throws(()=>rememberJobReportSave(different),RangeError);resolveJobReportSave(different);assert.deepEqual(pendingJobReportSave(resumeId),attempt);
 resolveJobReportSave(attempt);assert.equal(pendingJobReportSave(resumeId),undefined);
});
