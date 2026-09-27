export type Slot = { id: string | null; visible: boolean; widthMm: number; heightMm: number; fit: 'cover' | 'contain'; quarterTurns: number; zoom: number; positionX: number; positionY: number };
export type Asset = { id: string; format: string; bytes: number; sourceWidth: number; sourceHeight: number; width: number; height: number; exifOrientation: number };
export type Draft = { schemaVersion: number; sample: 'one' | 'two'; name: string; headline: string; email: string; phone: string; location: string; swapImages: boolean; photo: Slot; logo: Slot };
export async function api<T>(url: string, body?: object | FormData): Promise<T> {
  const response = await fetch(url, { method: body ? 'POST' : 'GET', headers: body instanceof FormData ? { 'X-Local-Resume': '1' } : body ? { 'Content-Type': 'application/json', 'X-Local-Resume': '1' } : {}, body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined });
  if (!response.ok) { const e = await response.json().catch(() => ({ code: 'NETWORK_ERROR', message: '无法连接本地服务，请检查服务是否正在运行。' })); throw new Error(`${e.message}（${e.code}）`); }
  return response.json();
}
export async function upload(file: File, maxBytes: number): Promise<Asset> {
  if (file.size > maxBytes) throw new Error(`图片为 ${(file.size / 1048576).toFixed(2)} MiB，超过 ${(maxBytes / 1048576).toFixed(0)} MiB 限制。（FILE_TOO_LARGE）`);
  const body = new FormData(); body.append('file', file); return api('/api/assets', body);
}
