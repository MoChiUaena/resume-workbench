import { computed, nextTick, ref, watch } from 'vue';
import { api, type Resume, type ResumeDocument, type Summary } from './api';

/** Serialize writes, acknowledge only the bytes actually saved, never replace in-progress edits. */
export function useWorkspace() {
  const resumes=ref<Summary[]>([]), current=ref<Resume>(), document=ref<ResumeDocument>(), title=ref('');
  const savedSignature=ref(''), saving=ref(false), saveError=ref('');
  let paused=false, timer: ReturnType<typeof setTimeout>, inFlight: Promise<void> | undefined;
  let lastAttempt: {signature:string;mutationId:string}|undefined;
  const signature=()=>JSON.stringify({title:title.value,document:document.value});
  const dirty=computed(()=>!!current.value && signature()!==savedSignature.value);
  const saveStatus=computed(()=>saveError.value ? '保存失败' : saving.value ? '保存中…' : dirty.value ? '待保存' : current.value ? '已保存到本机' : '正在加载');
  function updateList(resume: Resume) {
    resumes.value=[resume,...resumes.value.filter(r=>r.id!==resume.id)].map(({id,title,revision,updatedAt})=>({id,title,revision,updatedAt}));
  }
  async function install(resume: Resume) {
    clearTimeout(timer); paused=true; current.value=resume; title.value=resume.title;
    document.value=JSON.parse(JSON.stringify(resume.document)); savedSignature.value=signature(); saveError.value=''; lastAttempt=undefined;
    localStorage.setItem('local-resume-selected',resume.id); updateList(resume);
    await nextTick(); paused=false;
  }
  async function send() {
    if(inFlight) return inFlight;
    if(!current.value || !document.value || !dirty.value) return;
    const id=current.value.id, revision=current.value.revision, payload=JSON.parse(signature()), sentSignature=signature();
    if(lastAttempt?.signature!==sentSignature) lastAttempt={signature:sentSignature,mutationId:crypto.randomUUID()};
    const mutationId=lastAttempt.mutationId;
    saving.value=true; saveError.value='';
    inFlight=(async()=>{
      try {
        const result=await api<Resume>(`/api/resumes/${id}`,{...payload,expectedRevision:revision,mutationId},'PUT');
        current.value=result; savedSignature.value=sentSignature; updateList(result);
      } catch(e) { saveError.value=e instanceof Error ? e.message : '保存失败，请保留页面重试。'; throw e; }
      finally { saving.value=false; inFlight=undefined; }
    })();
    return inFlight;
  }
  async function flush() {
    clearTimeout(timer);
    if(inFlight) await inFlight;
    while(dirty.value) await send();
  }
  const schedule=()=>{clearTimeout(timer); timer=setTimeout(()=>{flush().catch(()=>{});},700);};
  watch([document,title],()=>{ if(paused || !current.value) return; saveError.value=''; schedule(); },{deep:true});
  async function reloadList() { resumes.value=await api('/api/resumes'); }
  async function open(id:string) { await flush(); await install(await api<Resume>(`/api/resumes/${id}`)); }
  async function create(sample:'blank'|'one'|'two') {
    await flush();
    await install(await api<Resume>('/api/resumes',{title:sample==='blank'?'未命名简历':sample==='one'?'中文技术岗 · 一页示例':'中文技术岗 · 两页示例',sample}));
  }
  async function duplicate() {
    await flush(); if(!current.value) return;
    await install(await api<Resume>(`/api/resumes/${current.value.id}/duplicate`,{expectedRevision:current.value.revision,title:(title.value+' · 副本').slice(0,120)}));
  }
  async function preserveAsCopy() {
    if(inFlight) { try { await inFlight; } catch {} }
    await install(await api<Resume>('/api/resumes',{title:(title.value+' · 本地副本').slice(0,120),document:document.value}));
  }
  async function reloadCurrent() {
    if(inFlight) { try { await inFlight; } catch {} }
    if(current.value) await install(await api(`/api/resumes/${current.value.id}`));
  }
  async function remove() {
    await flush(); if(!current.value) return;
    await api(`/api/resumes/${current.value.id}`,{expectedRevision:current.value.revision},'DELETE');
    paused=true; current.value=undefined; document.value=undefined; title.value=''; await nextTick(); paused=false;
    await reloadList(); if(resumes.value.length) await open(resumes.value[0].id);
  }
  async function initialize() {
    await reloadList();
    if(!resumes.value.length) { if(!localStorage.getItem('local-resume-selected')) await create('one'); }
    else await open(resumes.value.find(r=>r.id===localStorage.getItem('local-resume-selected'))?.id || resumes.value[0].id);
  }
  function beforeUnload(e:BeforeUnloadEvent) { if(dirty.value || saving.value) { e.preventDefault(); e.returnValue=''; } }
  function dispose() {clearTimeout(timer);}
  return {resumes,current,document,title,dirty,saving,saveError,saveStatus,flush,open,create,duplicate,preserveAsCopy,reloadCurrent,remove,initialize,install,beforeUnload,dispose};
}
