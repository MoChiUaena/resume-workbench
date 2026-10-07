import {api,ApiFailure,type Resume,type ResumeDocument} from './api.ts';

export type DocxPreview={format:'docx';fileName:string;title:string;document:ResumeDocument;sourceText:string;warnings:{code:string;message:string}[];statistics:{paragraphs:number;tables:number;images:number}};
export type DocxCreateAttempt=Readonly<{mutationId:string;title:string;document:ResumeDocument}>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const string=(v:unknown,max:number,required=false):v is string=>typeof v==='string'&&v.length<=max&&(!required||!!v.trim());
const integer=(v:unknown,min:number,max:number)=>Number.isSafeInteger(v)&&Number(v)>=min&&Number(v)<=max;
const number=(v:unknown,min:number,max:number)=>typeof v==='number'&&Number.isFinite(v)&&v>=min&&v<=max;
const oneOf=(v:unknown,options:readonly string[])=>typeof v==='string'&&options.includes(v);
function slot(v:unknown):boolean{return object(v)&&(v.id===null||typeof v.id==='string'&&uuid.test(v.id))&&typeof v.visible==='boolean'&&integer(v.widthMm,16,36)&&integer(v.heightMm,16,42)&&oneOf(v.fit,['cover','contain'])&&integer(v.quarterTurns,0,3)&&number(v.zoom,1,2)&&integer(v.positionX,0,100)&&integer(v.positionY,0,100);}
function validDocument(v:unknown):v is ResumeDocument{
 if(!object(v)||v.schemaVersion!==4||!object(v.content)||!object(v.layout))return false;
 const c=v.content,l=v.layout;
 if(!string(c.name,30,true)||!string(c.headline,70)||!string(c.email,100)||!string(c.phone,30)||!string(c.location,40)||!Array.isArray(c.sections)||c.sections.length>20)return false;
 const ids=new Set<string>();
 for(const section of c.sections){
  if(!object(section)||!string(section.id,50,true)||ids.has(section.id)||!oneOf(section.type,['education','experience','project','skills','custom'])||!string(section.title,40,true)||typeof section.visible!=='boolean'||typeof section.pageBreakBefore!=='boolean'||!Array.isArray(section.entries)||section.entries.length>15)return false;
  ids.add(section.id);
  for(const entry of section.entries){
   if(!object(entry)||!string(entry.id,50,true)||ids.has(entry.id)||!string(entry.title,80)||!string(entry.meta,120)||typeof entry.bulleted!=='boolean'||!Array.isArray(entry.bullets)||entry.bullets.length>30||!entry.bullets.every((line:unknown)=>string(line,800)))return false;
   ids.add(entry.id);
  }
 }
 if(!oneOf(l.template,['classic','banner','card','rail'])||!oneOf(l.font,['sans','serif'])||!number(l.fontSize,9,12)||!number(l.lineHeight,1.2,2)||!integer(l.sectionGapMm,0,12)||!integer(l.marginMm,12,22)||typeof l.swapImages!=='boolean'||!slot(l.photo)||!slot(l.logo)||!object(l.presentation))return false;
 const p=l.presentation;
 return oneOf(p.language,['zh','en'])&&typeof p.accentColor==='string'&&/^#[0-9a-fA-F]{6}$/.test(p.accentColor)&&oneOf(p.alignment,['left','center','justify'])&&oneOf(p.contactStyle,['labels','icons','plain'])&&oneOf(p.headingStyle,['template','line','bar','plain'])&&integer(p.marginHorizontalMm,8,32)&&integer(p.marginTopMm,8,32)&&integer(p.marginBottomMm,8,32)&&number(p.entryGapMm,0,8)&&number(p.paragraphGapMm,0,4);
}
function deepFreeze<T>(value:T):T{if(value&&typeof value==='object'){Object.freeze(value);for(const item of Object.values(value))deepFreeze(item);}return value;}
function canonical(value:unknown):string{if(Array.isArray(value))return '['+value.map(canonical).join(',')+']';if(object(value))return '{'+Object.keys(value).sort().map(key=>JSON.stringify(key)+':'+canonical(value[key])).join(',')+'}';return JSON.stringify(value);}
export function createDocxImportAttempt(title:string,document:ResumeDocument,mutationId:string):DocxCreateAttempt{
 const cleanTitle=title.trim();if(!uuid.test(mutationId)||!string(cleanTitle,120,true)||!validDocument(document)||document.layout.photo.id!==null||document.layout.logo.id!==null)throw new RangeError('请检查导入名称及简历内容后重试。');
 return deepFreeze({mutationId,title:cleanTitle,document:structuredClone(document)});
}
export function isDocxCreateReceipt(value:unknown,attempt:DocxCreateAttempt):value is {mutationId:string;resume:Resume}{
 if(!object(value)||value.mutationId!==attempt.mutationId||!object(value.resume))return false;
 const r=value.resume;
 if(typeof r.id!=='string'||!uuid.test(r.id)||!string(r.title,120,true)||!integer(r.revision,1,Number.MAX_SAFE_INTEGER)||!(r.lastMutationId===null||typeof r.lastMutationId==='string'&&uuid.test(r.lastMutationId))||typeof r.updatedAt!=='string'||r.updatedAt.length>40||!/^\d{4}-\d{2}-\d{2}T.*Z$/.test(r.updatedAt)||!Number.isFinite(Date.parse(r.updatedAt))||!validDocument(r.document))return false;
 return Number(r.revision)>1||(r.title===attempt.title&&canonical(r.document)===canonical(attempt.document));
}
const knownBusinessErrors=new Set(['DOCX_UNSUPPORTED','DOCX_INVALID','DOCX_TOO_LARGE','DOCX_CONTENT_TOO_LARGE','DOCX_EMPTY','DOCX_IMPORT_CONFLICT','DOCX_IMPORT_DELETED','INVALID_INPUT']);
export function isDocxCreateUncertain(code:string|undefined){return !code||!knownBusinessErrors.has(code);}
export async function previewDocx(file:File):Promise<DocxPreview>{
 const body=new FormData();body.append('file',file);
 const result=await api<unknown>('/api/imports/docx/preview',body);
 if(!object(result)||result.format!=='docx'||!string(result.fileName,120,true)||!string(result.title,120,true)||!validDocument(result.document)||typeof result.sourceText!=='string'||result.sourceText.length>40000||!Array.isArray(result.warnings)||!result.warnings.every(w=>object(w)&&string(w.code,80,true)&&string(w.message,1024,true))||!object(result.statistics)||!integer(result.statistics.paragraphs,0,600)||!integer(result.statistics.tables,0,512)||!integer(result.statistics.images,0,512))throw new ApiFailure('NETWORK_ERROR','Word 预览响应无法确认，请重新选择文件。');
 return result as DocxPreview;
}
export async function createDocxImport(attempt:DocxCreateAttempt):Promise<Resume>{
 const response=await api<unknown>('/api/imports/docx/create',attempt);
 if(!isDocxCreateReceipt(response,attempt))throw new ApiFailure('NETWORK_ERROR','创建回执无法确认，请使用同一请求重试创建。');
 return response.resume;
}
