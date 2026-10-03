import {api,ApiFailure} from './api';
import type {Suggestion} from './modelApi';
import {isSavedJobReportDetail,isSavedJobReportPage,isJobReportDeleteReceipt} from './jobReportReceipt';

export type JobSource={id:string;sectionId:string;entryId:string;paragraph:number;sectionTitle:string;entryTitle:string;text:string};
export type JobPreview={id:string;resumeId:string;revision:number;settingsRevision:number;profileId:string;profileName:string;provider:string;model:string;destination:string;jobDescription:string;payload:string;sources:JobSource[];expiresAt:string};
export type JobEvidence={sourceId:string;quote:string};
export type JobItem={requirement:string;status:'supported'|'partial'|'missing';evidence:JobEvidence[];advice:string};
export type JobReport={id:string;resumeId:string;revision:number;profileName:string;provider:string;model:string;destination:string;expiresAt:string;items:JobItem[];suggestions:Suggestion[]};
export type SavedJobReportSummary={id:string;resumeId:string;label:string;sourceRevision:number;createdAt:string;profileName:string;provider:string;model:string;sourceChanged:boolean};
export type JobReportSnapshot={schemaVersion:1;sourceRevision:number;jobDescription:string;profileName:string;provider:string;model:string;destination:string;sources:JobSource[];items:JobItem[];suggestions:{sourceId:string;replacement:string}[]};
export type SavedJobReportDetail=SavedJobReportSummary&{snapshot:JobReportSnapshot};
export type SavedJobReportPage={page:number;pageSize:20;total:number;pages:number;items:SavedJobReportSummary[]};
export type SaveJobReportRequest={previewId:string;reportId:string;label:string};
export function previewJob(body:{resumeId:string;expectedRevision:number;sectionIds:string[];jobDescription:string;profileId:string;settingsRevision:number},signal?:AbortSignal){return api<JobPreview>('/api/ai/job-matches/preview',body,undefined,signal);}
export function generateJob(previewId:string,signal?:AbortSignal){return api<JobReport>('/api/ai/job-matches',{previewId,confirmSend:true},undefined,signal);}
function reportPath(resumeId:string,reportId?:string){return '/api/resumes/'+encodeURIComponent(resumeId)+'/job-reports'+(reportId?'/'+encodeURIComponent(reportId):'');}
export async function saveJobReport(resumeId:string,body:Readonly<SaveJobReportRequest>,sourceRevision:number){
 const result=await api<unknown>(reportPath(resumeId),body);
 if(!isSavedJobReportDetail(result,resumeId,body.reportId,body.label,sourceRevision))throw new ApiFailure('NETWORK_ERROR','保存回执无法确认，请保留报告名称并重试同一保存请求。');
 return result;
}
export async function savedJobReports(resumeId:string,page=0,signal?:AbortSignal){
 const result=await api<unknown>(reportPath(resumeId)+'?page='+page,undefined,undefined,signal);
 if(!isSavedJobReportPage(result,resumeId,page))throw new ApiFailure('NETWORK_ERROR','报告历史响应无法确认，请重新读取。');
 return result;
}
export async function savedJobReport(resumeId:string,reportId:string,signal?:AbortSignal){
 const result=await api<unknown>(reportPath(resumeId,reportId),undefined,undefined,signal);
 if(!isSavedJobReportDetail(result,resumeId,reportId))throw new ApiFailure('NETWORK_ERROR','已保存报告响应无法确认，请重新读取。');
 return result;
}
export async function deleteJobReport(resumeId:string,reportId:string){
 const result=await api<unknown>(reportPath(resumeId,reportId),undefined,'DELETE');
 if(!isJobReportDeleteReceipt(result,reportId))throw new ApiFailure('NETWORK_ERROR','删除回执无法确认，请重试删除同一报告。');
 return result;
}
