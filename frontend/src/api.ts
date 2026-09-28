export type Slot = { id: string | null; visible: boolean; widthMm: number; heightMm: number; fit: 'cover' | 'contain'; quarterTurns: number; zoom: number; positionX: number; positionY: number };
export type Asset = { id: string; format: string; bytes: number; sourceWidth: number; sourceHeight: number; width: number; height: number; exifOrientation: number };
export type Draft = { schemaVersion: number; sample: 'one' | 'two'; name: string; headline: string; email: string; phone: string; location: string; swapImages: boolean; photo: Slot; logo: Slot };
export class ApiFailure extends Error { constructor(public code: string, message: string) { super(`${message}（${code}）`); } }
export type Entry = { id: string; title: string; meta: string; bulleted: boolean; bullets: string[] };
export type Section = { id: string; type: 'education' | 'experience' | 'project' | 'skills' | 'custom'; title: string; visible: boolean; pageBreakBefore: boolean; entries: Entry[] };
export type Presentation = { language:'zh'|'en'; accentColor:string; alignment:'left'|'center'|'justify'; contactStyle:'labels'|'icons'|'plain'; headingStyle:'template'|'line'|'bar'|'plain'; marginHorizontalMm:number; marginTopMm:number; marginBottomMm:number; entryGapMm:number; paragraphGapMm:number };
export type ResumeLayout = { template:'classic'|'banner'; font:'sans'|'serif'; fontSize:number; lineHeight:number; sectionGapMm:number; marginMm:number; swapImages:boolean; photo:Slot; logo:Slot; presentation:Presentation };
export type ResumeDocument = { schemaVersion: 3; content: { name: string; headline: string; email: string; phone: string; location: string; sections: Section[] }; layout: ResumeLayout };
export type Resume = { id: string; title: string; document: ResumeDocument; revision: number; lastMutationId: string | null; updatedAt: string };
export type Summary = Omit<Resume, 'document' | 'lastMutationId'>;
export type Version = { id: string; title: string; label: string; sourceRevision: number; createdAt: string };
export async function api<T>(url: string, body?: object | FormData, method?: string): Promise<T> {
  let response: Response;
  try { response = await fetch(url, { method: method || (body ? 'POST' : 'GET'), headers: body instanceof FormData ? { 'X-Local-Resume': '1' } : body ? { 'Content-Type': 'application/json', 'X-Local-Resume': '1' } : {}, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(url.startsWith('/api/backups') ? 120000 : url.endsWith('/export') ? 90000 : 15000) }); }
  catch { throw new ApiFailure('NETWORK_ERROR', '无法连接本地服务，本次修改可能尚未保存。请保留页面并重试。'); }
  if (!response.ok) { const e = await response.json().catch(() => ({ code: 'NETWORK_ERROR', message: '无法连接本地服务，请检查服务是否正在运行。' })); throw new ApiFailure(e.code,e.message); }
  return response.json();
}
export async function upload(file: File, maxBytes: number): Promise<Asset> {
  if (file.size > maxBytes) throw new Error(`图片为 ${(file.size / 1048576).toFixed(2)} MiB，超过 ${(maxBytes / 1048576).toFixed(0)} MiB 限制。（FILE_TOO_LARGE）`);
  const body = new FormData(); body.append('file', file); return api('/api/assets', body);
}
