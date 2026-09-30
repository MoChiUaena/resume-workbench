import {api} from './api';
export type ModelPreset={id:string;name:string;baseUrl:string;model:string};
export type ModelProfile={id:string;name:string;provider:string;baseUrl:string;model:string;hasApiKey:boolean};
export type ModelSettings={revision:number;enabled:boolean;defaultId:string|null;readable:boolean;profiles:ModelProfile[];presets:ModelPreset[]};
export type ParagraphTarget={sectionId:string;entryId:string;paragraph:number;selectionStart?:number;selectionEnd?:number;invalidSelection?:boolean};
export type Suggestion={id:string;resumeId:string;revision:number;sectionId:string;entryId:string;paragraph:number;original:string;selectionStart:number;selectionEnd:number;selectedOriginal:string;replacement:string;profileName:string;provider:string;model:string;destination:string;expiresAt:string;addedNumbers:boolean};
export const modelSettings=()=>api<ModelSettings>('/api/models');
