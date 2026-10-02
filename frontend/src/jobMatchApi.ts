import {api} from './api';
import type {Suggestion} from './modelApi';

export type JobSource={id:string;sectionId:string;entryId:string;paragraph:number;sectionTitle:string;entryTitle:string;text:string};
export type JobPreview={id:string;resumeId:string;revision:number;settingsRevision:number;profileId:string;profileName:string;provider:string;model:string;destination:string;jobDescription:string;payload:string;sources:JobSource[];expiresAt:string};
export type JobEvidence={sourceId:string;quote:string};
export type JobItem={requirement:string;status:'supported'|'partial'|'missing';evidence:JobEvidence[];advice:string};
export type JobReport={id:string;resumeId:string;revision:number;profileName:string;provider:string;model:string;destination:string;expiresAt:string;items:JobItem[];suggestions:Suggestion[]};
export function previewJob(body:{resumeId:string;expectedRevision:number;sectionIds:string[];jobDescription:string;profileId:string;settingsRevision:number},signal?:AbortSignal){return api<JobPreview>('/api/ai/job-matches/preview',body,undefined,signal);}
export function generateJob(previewId:string,signal?:AbortSignal){return api<JobReport>('/api/ai/job-matches',{previewId,confirmSend:true},undefined,signal);}
