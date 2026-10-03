import type {JobSource,SaveJobReportRequest,SavedJobReportDetail,SavedJobReportPage,SavedJobReportSummary} from './jobMatchApi';
type RecordValue=Record<string,unknown>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function object(value:unknown):value is RecordValue{return typeof value==='object'&&value!==null&&!Array.isArray(value);}
function text(value:unknown,max:number,nonempty=true):value is string{return typeof value==='string'&&value.length<=max&&(!nonempty||value.trim().length>0);}
function integer(value:unknown,min=0,max=Number.MAX_SAFE_INTEGER):value is number{return Number.isSafeInteger(value)&&Number(value)>=min&&Number(value)<=max;}
function id(value:unknown):value is string{return typeof value==='string'&&uuid.test(value);}
function summary(value:unknown,resumeId:string):value is SavedJobReportSummary{
 return object(value)&&id(value.id)&&value.resumeId===resumeId&&text(value.label,120)&&integer(value.sourceRevision)&&text(value.createdAt,40)&&/^\d{4}-\d{2}-\d{2}T.*Z$/.test(value.createdAt)&&Number.isFinite(Date.parse(value.createdAt))&&text(value.profileName,256)&&text(value.provider,80)&&text(value.model,256)&&typeof value.sourceChanged==='boolean';
}
function source(value:unknown):value is JobSource{
 return object(value)&&text(value.id,120)&&text(value.sectionId,50)&&text(value.entryId,50)&&integer(value.paragraph,-1,29)&&text(value.sectionTitle,120,false)&&text(value.entryTitle,160,false)&&text(value.text,12000);
}
export function isSavedJobReportDetail(value:unknown,resumeId:string,reportId?:string,label?:string,sourceRevision?:number):value is SavedJobReportDetail{
 if(!summary(value,resumeId)||(reportId!==undefined&&value.id!==reportId)||(label!==undefined&&value.label!==label)||(sourceRevision!==undefined&&value.sourceRevision!==sourceRevision))return false;
 const snapshot=(value as unknown as RecordValue).snapshot;
 if(!object(snapshot)||snapshot.schemaVersion!==1||snapshot.sourceRevision!==value.sourceRevision||snapshot.profileName!==value.profileName||snapshot.provider!==value.provider||snapshot.model!==value.model||!text(snapshot.jobDescription,6000)||!text(snapshot.destination,2048))return false;
 if(!Array.isArray(snapshot.sources)||snapshot.sources.length<1||snapshot.sources.length>60||!snapshot.sources.every(source))return false;
 const sources=new Map(snapshot.sources.map(entry=>[entry.id,entry]));
 if(sources.size!==snapshot.sources.length||snapshot.sources.reduce((size,entry)=>size+entry.text.length,0)>12000)return false;
 if(!Array.isArray(snapshot.items)||snapshot.items.length<1||snapshot.items.length>12||!snapshot.items.every(item=>object(item)&&text(item.requirement,6000)&&['supported','partial','missing'].includes(String(item.status))&&text(item.advice,12000,false)&&Array.isArray(item.evidence)&&item.evidence.length<=60&&item.evidence.every(evidence=>object(evidence)&&text(evidence.quote,12000)&&typeof evidence.sourceId==='string'&&sources.get(evidence.sourceId)?.text.includes(evidence.quote))))return false;
 if(!Array.isArray(snapshot.suggestions)||snapshot.suggestions.length>6||!snapshot.suggestions.every(suggestion=>object(suggestion)&&Object.keys(suggestion).length===2&&typeof suggestion.sourceId==='string'&&sources.has(suggestion.sourceId)&&sources.get(suggestion.sourceId)!.paragraph>=0&&text(suggestion.replacement,12000)))return false;
 try{return new TextEncoder().encode(JSON.stringify(snapshot)).length<=128*1024;}catch{return false;}
}
export function isSavedJobReportPage(value:unknown,resumeId:string,page:number):value is SavedJobReportPage{
 return object(value)&&integer(value.page)&&value.page===page&&value.pageSize===20&&integer(value.total,0,100)&&integer(value.pages,0,5)&&value.pages===Math.ceil(value.total/20)&&Array.isArray(value.items)&&value.items.length===Math.min(20,Math.max(0,value.total-page*20))&&value.items.every(item=>summary(item,resumeId))&&new Set(value.items.map(item=>item.id)).size===value.items.length;
}
export function isJobReportDeleteReceipt(value:unknown,reportId:string):value is {id:string;deleted:true}{return object(value)&&value.id===reportId&&value.deleted===true;}
export type JobReportSaveAttempt={resumeId:string;sourceRevision:number;request:Readonly<SaveJobReportRequest>};
export function createJobReportSaveAttempt(input:{resumeId:string;previewId:string;reportId:string;label:string;sourceRevision:number}):JobReportSaveAttempt{
 const label=input.label.trim();
 if(!text(label,120)||!id(input.resumeId)||!id(input.previewId)||!id(input.reportId)||!integer(input.sourceRevision))throw new RangeError('请填写 1–120 字的报告名称，并使用当前服务器生成的报告。');
 return Object.freeze({resumeId:input.resumeId,sourceRevision:input.sourceRevision,request:Object.freeze({previewId:input.previewId,reportId:input.reportId,label})});
}
// Keep only request identity and label in this tab, never resume material or provider credentials.
const pendingSaves=new Map<string,JobReportSaveAttempt>();
function sameAttempt(left:JobReportSaveAttempt,right:JobReportSaveAttempt){return left.resumeId===right.resumeId&&left.sourceRevision===right.sourceRevision&&left.request.previewId===right.request.previewId&&left.request.reportId===right.request.reportId&&left.request.label===right.request.label;}
export function rememberJobReportSave(attempt:JobReportSaveAttempt){
 const existing=pendingSaves.get(attempt.resumeId);
 if(existing&&!sameAttempt(existing,attempt))throw new RangeError('上次保存尚未确认，请先重试同一保存请求。');
 pendingSaves.set(attempt.resumeId,attempt);
}
export function pendingJobReportSave(resumeId:string){return pendingSaves.get(resumeId);}
export function resolveJobReportSave(attempt:JobReportSaveAttempt){const pending=pendingSaves.get(attempt.resumeId);if(pending&&sameAttempt(pending,attempt))pendingSaves.delete(attempt.resumeId);}
