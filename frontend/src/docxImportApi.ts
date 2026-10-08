import {api,ApiFailure,type Resume,type ResumeDocument} from './api.ts';

export type DocxPreview={format:'docx';fileName:string;title:string;document:ResumeDocument;sourceText:string;warnings:{code:string;message:string}[];statistics:{paragraphs:number;tables:number;images:number}};
export type PdfImageCandidate={id:string;page:number;width:number;height:number;mimeType:'image/png'|'image/jpeg';base64:string};
export type PdfPreview={format:'pdf';fileName:string;title:string;document:ResumeDocument;sourceText:string;warnings:{code:string;message:string}[];statistics:{paragraphs:number;pages:number;images:number};images:PdfImageCandidate[]};
export type DocxCreateAttempt=Readonly<{mutationId:string;title:string;document:ResumeDocument}>;
export type PdfSelectedImage=Readonly<Pick<PdfImageCandidate,'mimeType'|'base64'>>;
export type PdfCreateAttempt=DocxCreateAttempt&Readonly<{photo?:PdfSelectedImage;logo?:PdfSelectedImage}>;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const object=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const string=(v:unknown,max:number,required=false):v is string=>typeof v==='string'&&v.length<=max&&(!required||!!v.trim());
const integer=(v:unknown,min:number,max:number)=>Number.isSafeInteger(v)&&Number(v)>=min&&Number(v)<=max;
const number=(v:unknown,min:number,max:number)=>typeof v==='number'&&Number.isFinite(v)&&v>=min&&v<=max;
const oneOf=(v:unknown,options:readonly string[])=>typeof v==='string'&&options.includes(v);
function imageCandidate(v:unknown):v is PdfImageCandidate{return object(v)&&typeof v.id==='string'&&/^p(?:[1-9]|1[0-9]|20)-i[1-9][0-9]{0,2}$/.test(v.id)&&integer(v.page,1,20)&&integer(v.width,16,640)&&integer(v.height,16,640)&&oneOf(v.mimeType,['image/png','image/jpeg'])&&typeof v.base64==='string'&&v.base64.length>=8&&v.base64.length<=699052&&v.base64.length%4===0&&/^[A-Za-z0-9+/]+={0,2}$/.test(v.base64);}
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
 return deepFreeze({mutationId,title:cleanTitle,document:JSON.parse(JSON.stringify(document)) as ResumeDocument});
}
export function isDocxCreateReceipt(value:unknown,attempt:DocxCreateAttempt):value is {mutationId:string;resume:Resume}{
 if(!object(value)||value.mutationId!==attempt.mutationId||!object(value.resume))return false;
 const r=value.resume;
 if(typeof r.id!=='string'||!uuid.test(r.id)||!string(r.title,120,true)||!integer(r.revision,1,Number.MAX_SAFE_INTEGER)||!(r.lastMutationId===null||typeof r.lastMutationId==='string'&&uuid.test(r.lastMutationId))||typeof r.updatedAt!=='string'||r.updatedAt.length>40||!/^\d{4}-\d{2}-\d{2}T.*Z$/.test(r.updatedAt)||!Number.isFinite(Date.parse(r.updatedAt))||!validDocument(r.document))return false;
 return Number(r.revision)>1||(r.title===attempt.title&&canonical(r.document)===canonical(attempt.document));
}
const knownBusinessErrors:Record<'docx'|'pdf',Record<string,number>>={
 docx:{DOCX_UNSUPPORTED:422,DOCX_INVALID:422,DOCX_TOO_LARGE:413,DOCX_CONTENT_TOO_LARGE:413,DOCX_EMPTY:422,DOCX_IMPORT_CONFLICT:409,DOCX_IMPORT_DELETED:410,INVALID_INPUT:400},
 pdf:{PDF_INVALID:422,PDF_ENCRYPTED:422,PDF_NO_TEXT:422,PDF_TOO_LARGE:413,PDF_CONTENT_TOO_LARGE:413,PDF_IMAGE_INVALID:422,PDF_IMAGE_TOO_LARGE:413,CORRUPT_IMAGE:422,IMAGE_PROCESSING_FAILED:422,PDF_IMPORT_CONFLICT:409,PDF_IMPORT_DELETED:410,INVALID_INPUT:400}
};
export function isDocxCreateUncertain(code:string|undefined,status?:number){return !code||knownBusinessErrors.docx[code]!==status;}
export function isPdfCreateUncertain(code:string|undefined,status?:number){return !code||knownBusinessErrors.pdf[code]!==status;}
export function createPdfImportAttempt(title:string,document:ResumeDocument,mutationId:string,photo?:PdfImageCandidate|null,logo?:PdfImageCandidate|null):PdfCreateAttempt{
 const base=createDocxImportAttempt(title,document,mutationId);
 if(photo&&!imageCandidate(photo)||logo&&!imageCandidate(logo)||photo&&logo&&photo.id===logo.id)throw new RangeError('请重新选择 PDF 图片后确认导入。');
 if(!photo&&!logo)return base;
 return deepFreeze({...base,...(photo?{photo:{mimeType:photo.mimeType,base64:photo.base64}}:{}),...(logo?{logo:{mimeType:logo.mimeType,base64:logo.base64}}:{})});
}
export function isPdfCreateReceipt(value:unknown,attempt:PdfCreateAttempt):value is {mutationId:string;resume:Resume}{
 if(!attempt.photo&&!attempt.logo)return isDocxCreateReceipt(value,attempt);
 if(!object(value)||!object(value.resume)||!integer(value.resume.revision,1,Number.MAX_SAFE_INTEGER)||!validDocument(value.resume.document))return false;
 if(Number(value.resume.revision)>1)return isDocxCreateReceipt(value,attempt);
 const actual=value.resume.document;
 if(!!actual.layout.photo.id!==!!attempt.photo||!!actual.layout.logo.id!==!!attempt.logo)return false;
 const expected=JSON.parse(JSON.stringify(attempt.document)) as ResumeDocument;
 expected.layout.photo.id=actual.layout.photo.id;expected.layout.logo.id=actual.layout.logo.id;
 return isDocxCreateReceipt(value,{mutationId:attempt.mutationId,title:attempt.title,document:expected});
}
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
export async function previewPdf(file:File):Promise<PdfPreview>{
 const body=new FormData();body.append('file',file);
 const result=await api<unknown>('/api/imports/pdf/preview',body);
 if(!object(result)||result.format!=='pdf'||!string(result.fileName,120,true)||!string(result.title,120,true)||!validDocument(result.document)||typeof result.sourceText!=='string'||result.sourceText.length>40000||!Array.isArray(result.warnings)||!result.warnings.every(w=>object(w)&&string(w.code,80,true)&&string(w.message,1024,true))||!object(result.statistics)||!integer(result.statistics.paragraphs,0,600)||!integer(result.statistics.pages,1,20)||!integer(result.statistics.images,0,512)||!Array.isArray(result.images)||result.images.length>8||!result.images.every(imageCandidate)||new Set(result.images.map(image=>image.id)).size!==result.images.length||result.images.reduce((sum,image)=>sum+image.base64.length,0)>2796224)throw new ApiFailure('NETWORK_ERROR','PDF 预览响应无法确认，请重新选择文件。');
 return result as PdfPreview;
}
export async function createPdfImport(attempt:PdfCreateAttempt):Promise<Resume>{
 const response=await api<unknown>('/api/imports/pdf/create',attempt);
 if(!isPdfCreateReceipt(response,attempt))throw new ApiFailure('NETWORK_ERROR','创建回执无法确认，请使用同一请求重试创建。');
 return response.resume;
}
