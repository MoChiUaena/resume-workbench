import {ApiFailure} from './api';

export async function downloadPdf(id:string,redacted=false) {
  let response:Response;
  try { response=await fetch(`/api/exports/${id}/pdf`,{signal:AbortSignal.timeout(30000)}); }
  catch { throw new ApiFailure('DOWNLOAD_FAILED','PDF 下载失败，请检查本地服务后重试下载。'); }
  if(!response.ok){const error=await response.json().catch(()=>({code:'DOWNLOAD_FAILED',message:'PDF 下载失败，请重试下载。'}));throw new ApiFailure(error.code,error.message);}
  const url=URL.createObjectURL(await response.blob());
  const link=document.createElement('a');link.href=url;link.download=redacted?'resume-workbench-redacted.pdf':'resume-workbench.pdf';
  link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
