import { computed, nextTick, reactive, ref, watch } from 'vue';
import { api, ApiFailure, type Resume, type ResumeDocument, type Summary } from './api';
import { EditHistory } from './editHistory';

/** Serialize writes, acknowledge only the bytes actually saved, never replace in-progress edits. */
export function useWorkspace() {
  const resumes=ref<Summary[]>([]), current=ref<Resume>(), document=ref<ResumeDocument>(), title=ref('');
  const savedSignature=ref(''), saving=ref(false), saveError=ref(''), unconfirmed=ref(false);
  let paused=false, timer: ReturnType<typeof setTimeout>, inFlight: Promise<void> | undefined;
  let lastAttempt: {signature:string;mutationId:string;id:string;revision:number;payload:{title:string;document:ResumeDocument}}|undefined;
  const signature=()=>JSON.stringify({title:title.value,document:document.value});
  const history=reactive(new EditHistory());
  let editGroup:EventTarget|null=null;
  const canUndo=computed(()=>!!current.value&&history.canUndo),canRedo=computed(()=>!!current.value&&history.canRedo);
  function capture() { if(!paused&&current.value&&document.value)history.record(signature(),editGroup); }
  function breakUndoGroup() { capture();editGroup=null;history.endGroup(); }
  function editInput(event:Event) {
    const target=event.target;
    editGroup=target instanceof HTMLTextAreaElement || (target instanceof HTMLInputElement&&!['checkbox','file','radio'].includes(target.type)) ? target : null;
  }
  function applyHistory(state:string|undefined) {
    if(state===undefined)return;
    const draft=JSON.parse(state);title.value=draft.title;document.value=draft.document;
  }
  function undo(){capture();editGroup=null;applyHistory(history.undo());}
  function redo(){capture();editGroup=null;applyHistory(history.redo());}
  watch(signature,()=>{if(!current.value||!document.value)history.reset(signature());else capture();});
  const dirty=computed(()=>!!current.value && (unconfirmed.value||signature()!==savedSignature.value));
  const saveStatus=computed(()=>saveError.value ? '保存失败' : saving.value ? '保存中…' : dirty.value ? '待保存' : current.value ? '已保存到本机' : '正在加载');
  function updateList(resume: Resume) {
    resumes.value=[resume,...resumes.value.filter(r=>r.id!==resume.id)].map(({id,title,revision,updatedAt})=>({id,title,revision,updatedAt}));
  }
  async function install(resume: Resume, keepHistory=false) {
    const append=keepHistory&&current.value?.id===resume.id;
    if(append)breakUndoGroup();
    clearTimeout(timer); paused=true; current.value=resume; title.value=resume.title;
    document.value=JSON.parse(JSON.stringify(resume.document)); savedSignature.value=signature(); saveError.value=''; lastAttempt=undefined;unconfirmed.value=false;
    editGroup=null;
    if(append)history.record(signature());else history.reset(signature());
    localStorage.setItem('local-resume-selected',resume.id); updateList(resume);
    await nextTick(); paused=false;
  }
  async function send() {
    if(inFlight) return inFlight;
    if(!current.value || !document.value || !dirty.value) return;
    const sentSignature=signature();
    // Resolve a lost acknowledgement with the same immutable mutation before writing a newer draft.
    if(!lastAttempt||(!unconfirmed.value&&lastAttempt.signature!==sentSignature))lastAttempt={signature:sentSignature,mutationId:crypto.randomUUID(),id:current.value.id,revision:current.value.revision,payload:JSON.parse(sentSignature)};
    const attempt=lastAttempt;
    saving.value=true; saveError.value='';
    inFlight=(async()=>{
      try {
        const result=await api<Resume>(`/api/resumes/${attempt.id}`,{...attempt.payload,expectedRevision:attempt.revision,mutationId:attempt.mutationId},'PUT');
        current.value=result; savedSignature.value=attempt.signature;unconfirmed.value=false;updateList(result);
      } catch(e) { if(!(e instanceof ApiFailure)||e.code==='NETWORK_ERROR')unconfirmed.value=true;saveError.value=e instanceof Error ? e.message : '保存失败，请保留页面重试。'; throw e; }
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
  }
  function beforeUnload(e:BeforeUnloadEvent) { if(dirty.value || saving.value) { e.preventDefault(); e.returnValue=''; } }
  function dispose() {clearTimeout(timer);}
  return {resumes,current,document,title,dirty,saving,saveError,saveStatus,canUndo,canRedo,undo,redo,editInput,breakUndoGroup,flush,open,create,duplicate,preserveAsCopy,reloadCurrent,reloadList,remove,initialize,install,beforeUnload,dispose};
}
