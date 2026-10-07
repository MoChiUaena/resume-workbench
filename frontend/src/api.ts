import type {TemplateId} from './resumeTemplates';
export type Slot = { id: string | null; visible: boolean; widthMm: number; heightMm: number; fit: 'cover' | 'contain'; quarterTurns: number; zoom: number; positionX: number; positionY: number };
export type Asset = { id: string; format: string; bytes: number; sourceWidth: number; sourceHeight: number; width: number; height: number; exifOrientation: number };
export type Draft = { schemaVersion: number; sample: 'one' | 'two'; name: string; headline: string; email: string; phone: string; location: string; swapImages: boolean; photo: Slot; logo: Slot };
export class ApiFailure extends Error { public code:string; public status?:number; constructor(code: string, message: string, status?:number) { super(`${message}（${code}）`); this.code=code; this.status=status; } }
export type Entry = { id: string; title: string; meta: string; bulleted: boolean; bullets: string[] };
export type Section = { id: string; type: 'education' | 'experience' | 'project' | 'skills' | 'custom'; title: string; visible: boolean; pageBreakBefore: boolean; entries: Entry[] };
export type Presentation = { language:'zh'|'en'; accentColor:string; alignment:'left'|'center'|'justify'; contactStyle:'labels'|'icons'|'plain'; headingStyle:'template'|'line'|'bar'|'plain'; marginHorizontalMm:number; marginTopMm:number; marginBottomMm:number; entryGapMm:number; paragraphGapMm:number };
export type ResumeLayout = { template:TemplateId; font:'sans'|'serif'; fontSize:number; lineHeight:number; sectionGapMm:number; marginMm:number; swapImages:boolean; photo:Slot; logo:Slot; presentation:Presentation };
export type ResumeDocument = { schemaVersion: 4; content: { name: string; headline: string; email: string; phone: string; location: string; sections: Section[] }; layout: ResumeLayout };
export type Resume = { id: string; title: string; document: ResumeDocument; revision: number; lastMutationId: string | null; updatedAt: string };
export type Summary = Omit<Resume, 'document' | 'lastMutationId'>;
export type Version = { id: string; title: string; label: string; sourceRevision: number; createdAt: string };
export type VersionDetail = Version & { document: ResumeDocument };
function errorEnvelope(value:unknown):value is {code:string;message:string} {
  if(typeof value!=='object'||value===null||Array.isArray(value))return false;
  const error=value as Record<string,unknown>;
  return typeof error.code==='string'&&/^[A-Z][A-Z0-9_]{0,79}$/.test(error.code)&&typeof error.message==='string'&&error.message.length<=1024&&error.message.trim().length>0;
}
export async function api<T>(url: string, body?: object | FormData, method?: string, signal?:AbortSignal): Promise<T> {
  let response: Response;
  const jobGeneration=url==='/api/ai/job-matches';
  const jobHistory=/^\/api\/resumes\/[0-9a-f-]{36}\/job-reports(?:\/[0-9a-f-]{36})?(?:\?[^#]*)?$/.test(url);
  const docxCreate=url==='/api/imports/docx/create'||url==='/api/imports/pdf/create';
  const requestMethod=method||(body?'POST':'GET');
  const mutation=!['GET','HEAD','OPTIONS'].includes(requestMethod.toUpperCase());
  const headers:Record<string,string>=mutation?{'X-Local-Resume':'1'}:{};
  if(body&&!(body instanceof FormData))headers['Content-Type']='application/json';
  const timeout=AbortSignal.timeout(url.startsWith('/api/backups')?120000:url.startsWith('/api/storage/')||url.startsWith('/api/ai/')||url.endsWith('/test')||url.endsWith('/export')?90000:15000);
  try { response = await fetch(url, { method:requestMethod, headers, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined, signal:signal?AbortSignal.any([signal,timeout]):timeout }); }
  catch { throw new ApiFailure('NETWORK_ERROR',jobGeneration?'生成结果尚未确认，请保留预览，重新核对并确认后重试同一请求。':url.startsWith('/api/storage/quarantine')&&body?'文件操作结果尚未确认，请保持页面并用同一请求重试。':url.startsWith('/api/storage/')?'空间检查未完成，请检查本地服务后重试。':url.startsWith('/api/ai/suggestions/')&&url.endsWith('/apply')?'应用结果尚未确认，请保持页面并用同一份文字重试。':url.startsWith('/api/models')||url.startsWith('/api/ai/')?'模型操作未完成，原文保留，请重试。':'无法连接本地服务，本次修改可能尚未保存。请保留页面并重试。'); }
  if (!response.ok) {
    const e = await response.json().catch(() => ({ code: 'NETWORK_ERROR', message: '无法连接本地服务，请检查服务是否正在运行。' }));
    if(jobGeneration&&!errorEnvelope(e))throw new ApiFailure('NETWORK_ERROR','生成错误回执无法确认，请保留预览，重新核对并确认后重试同一请求。');
    if(docxCreate&&!errorEnvelope(e))throw new ApiFailure('NETWORK_ERROR','创建错误回执无法确认，请使用同一请求重试创建。');
    if(jobHistory&&!errorEnvelope(e))throw new ApiFailure('NETWORK_ERROR',mutation?'报告操作错误回执无法确认，请保持窗口并重试同一请求。':'历史报告响应无法确认，请重新读取。');
    if(url.startsWith('/api/storage/')&&!errorEnvelope(e))throw new ApiFailure('NETWORK_ERROR',body?'文件操作错误回执无法确认，请保持页面并用同一请求重试。':'空间检查错误响应无法确认，请检查本地服务后重试。');
    throw new ApiFailure(e.code,e.message,response.status);
  }
  try { return await response.json(); }
  catch(cause) { if(jobGeneration)throw new ApiFailure('NETWORK_ERROR','生成回执无法读取，请保留预览，重新核对并确认后重试同一请求。');if(docxCreate)throw new ApiFailure('NETWORK_ERROR','创建回执无法读取，请使用同一请求重试创建。');if(jobHistory)throw new ApiFailure('NETWORK_ERROR',mutation?'报告操作回执无法读取，请保持窗口并重试同一请求。':'历史报告响应无法读取，请重新读取。');if(url.startsWith('/api/storage/'))throw new ApiFailure('NETWORK_ERROR',body?'文件操作回执无法读取，请保持页面并用同一请求重试。':'空间检查响应无法读取，请检查本地服务后重试。');throw cause; }
}
export async function upload(file: File, maxBytes: number): Promise<Asset> {
  if (file.size > maxBytes) throw new Error(`图片为 ${(file.size / 1048576).toFixed(2)} MiB，超过 ${(maxBytes / 1048576).toFixed(0)} MiB 限制。（FILE_TOO_LARGE）`);
  const body = new FormData(); body.append('file', file); return api('/api/assets', body);
}
