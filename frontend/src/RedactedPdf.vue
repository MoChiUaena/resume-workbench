<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,reactive,ref,watch} from 'vue';
import {api,ApiFailure,type Resume} from './api';
import {downloadPdf} from './downloadPdf';
const props=defineProps<{source:Resume;returnFocus?:HTMLElement}>();
const emit=defineEmits<{close:[];reload:[];created:[result:{id:string;revision:number;redacted:boolean}];busy:[value:boolean]}>();
const options=reactive({name:true,phone:true,email:true,location:true,photo:true,logo:true,matchingText:true});
const choices=[{key:'name',label:'隐藏姓名'},{key:'phone',label:'隐藏电话'},{key:'email',label:'隐藏邮箱'},{key:'location',label:'隐藏城市 / 毕业年份'},{key:'photo',label:'隐藏证件照'},{key:'logo',label:'隐藏学校 Logo'}] as const;
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>(),frame=ref<HTMLIFrameElement>(),pane=ref<HTMLElement>();
const preview=ref<{url:string;digest:string}>(),status=ref('准备脱敏预览'),pages=ref(1),scale=ref(.7),error=ref(''),conflict=ref(false),ready=ref(false),working=ref(false);
const generated=ref<{id:string;revision:number}>();
const height=computed(()=>1123*pages.value+(pages.value-1)*23);
let timer:ReturnType<typeof setTimeout>,observer:ResizeObserver|undefined,generation=0,installedGeneration=0,disposed=false;
let previousFocus:HTMLElement|undefined;
function request(){return{expectedRevision:props.source.revision,redaction:{...options}};}
function failure(cause:unknown){error.value=cause instanceof Error?cause.message:String(cause);conflict.value=cause instanceof ApiFailure&&cause.code==='REVISION_CONFLICT';}
async function refresh(version=++generation){
 ready.value=false;error.value='';conflict.value=false;status.value='更新脱敏预览中';
 try{
  const result=await api<{url:string;digest:string}>(`/api/resumes/${props.source.id}/export/preview`,request());
  if(!disposed&&version===generation){installedGeneration=version;preview.value=result;pages.value=1;}
 }catch(cause){if(!disposed&&version===generation){status.value='脱敏预览失败';failure(cause);}}
}
function changed(){generation++;ready.value=false;generated.value=undefined;status.value='更新脱敏预览中';clearTimeout(timer);timer=setTimeout(()=>refresh(generation),250);}
watch(options,changed,{deep:true,flush:'sync'});
function message(event:MessageEvent){
 if(!preview.value||event.origin!==location.origin||event.source!==frame.value?.contentWindow||event.data?.type!=='resume-layout')return;
 // Ignore the old iframe while a new projection is requested.
 if(event.data.path!==preview.value.url||installedGeneration!==generation)return;
 pages.value=event.data.pages||1;
 if(event.data.error){ready.value=false;status.value='排版超限';error.value='当前内容无法安全分页，请关闭后调整原简历的字号或段落。';}
 else if(status.value==='更新脱敏预览中'&&preview.value){ready.value=true;status.value='脱敏预览已更新';}
}
async function exportPdf(){
 if(working.value||!ready.value||!preview.value)return;
 working.value=true;emit('busy',true);error.value='';
 let downloaded=false;
 try{
  const fresh=!generated.value;
  const result=generated.value||await api<{id:string;revision:number}>(`/api/resumes/${props.source.id}/export`,{...request(),previewDigest:preview.value.digest});
  if(disposed)return;
  generated.value=result;if(fresh)emit('created',{...result,redacted:true});
  await downloadPdf(result.id,true);
  downloaded=true;
 }catch(cause){if(!disposed)failure(cause);}
 finally{working.value=false;emit('busy',false);}
 if(downloaded&&!disposed)emit('close');
}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'&&!working.value){event.preventDefault();emit('close');}
 if(event.key==='Tab'){
  const controls=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),a[href]')||[]).filter(el=>el.getClientRects().length);
  const first=controls[0],last=controls.at(-1);
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
 }
}
onMounted(async()=>{
 previousFocus=props.returnFocus||(document.activeElement instanceof HTMLElement?document.activeElement:undefined);
 window.addEventListener('keydown',keys);window.addEventListener('message',message);
 await nextTick();closeButton.value?.focus();
 observer=new ResizeObserver(()=>{scale.value=Math.min(1,Math.max(.2,((pane.value?.clientWidth||600)-40)/794));});if(pane.value)observer.observe(pane.value);
 void refresh();
});
onUnmounted(()=>{disposed=true;generation++;clearTimeout(timer);observer?.disconnect();window.removeEventListener('keydown',keys);window.removeEventListener('message',message);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="redacted-overlay"><section ref="dialog" class="redacted-dialog" role="dialog" aria-modal="true" aria-labelledby="redacted-title" aria-describedby="redacted-description">
  <header><div><span class="rw-overline">导出副本 · r{{source.revision}}</span><h2 id="redacted-title">脱敏 PDF</h2></div><button ref="closeButton" :disabled="working" aria-label="关闭脱敏导出" @click="emit('close')">×</button></header>
  <div class="redacted-body"><aside>
   <p id="redacted-description">选择副本里要隐藏的信息，检查右侧预览后导出。原简历和图片保留在本机。</p>
   <fieldset :disabled="working"><legend>隐藏范围</legend><label v-for="item in choices" :key="item.key"><input v-model="options[item.key]" type="checkbox">{{item.label}}</label>
    <small v-if="options.name">姓名显示为「{{source.document.layout.presentation.language==='en'?'Candidate':'候选人'}}」。</small>
    <label class="redacted-matching"><input v-model="options.matchingText" type="checkbox">同步处理正文中的相同信息</label>
   </fieldset>
   <p class="redacted-note">正文只替换与所选基本信息完全相同的文字。其他学校、单位或链接信息，请结合预览检查。</p>
   <p class="redacted-note">此处只生成 PDF。完整备份仍包含原始内容、图片和历史记录。</p>
  </aside><div ref="pane" class="redacted-preview"><div class="redacted-preview-status"><span data-testid="redacted-status" role="status">{{status}}</span><span>A4 · {{pages}} 页</span></div>
   <div v-if="preview" class="redacted-paper" :style="{width:794*scale+'px',height:height*scale+'px'}"><iframe ref="frame" tabindex="-1" title="脱敏简历预览" :src="preview.url" :style="{width:'794px',height:height+'px',transform:`scale(${scale})`}"></iframe></div>
   <div v-else class="redacted-placeholder">正在生成脱敏预览…</div>
  </div></div>
  <p v-if="error" class="redacted-error" role="alert">{{error}} <button v-if="conflict" :disabled="working" @click="emit('reload')">关闭并重新载入</button><button v-else-if="!ready" :disabled="working" @click="refresh()">重试预览</button></p>
  <footer><span>副本设置仅用于本次导出</span><div><button :disabled="working" @click="emit('close')">取消</button><button class="redacted-primary" :disabled="working||!ready||conflict" @click="exportPdf">{{working?'正在导出…':generated?'重试下载':'导出脱敏 PDF'}}</button></div></footer>
 </section></div>
</template>
<style scoped>
.redacted-overlay{position:fixed;inset:0;z-index:100;display:grid;place-items:center;padding:24px;background:#14203088;color:var(--ui-ink);font:13px ResumeSans,Arial,sans-serif}.redacted-dialog{width:min(1130px,100%);max-height:94vh;display:flex;flex-direction:column;overflow:hidden;background:var(--ui-surface);border:1px solid var(--ui-line);border-radius:var(--ui-radius);box-shadow:0 12px 50px #0003}.redacted-dialog *{box-sizing:border-box}.redacted-dialog button{font:inherit;padding:8px 13px;border:1px solid var(--ui-line);border-radius:var(--ui-radius);background:var(--ui-surface);color:var(--ui-ink);cursor:pointer}.redacted-dialog button:disabled{opacity:.55;cursor:not-allowed}.redacted-dialog button:focus-visible,.redacted-dialog input:focus-visible{outline:2px solid var(--ui-accent);outline-offset:3px}.redacted-dialog header{display:flex;justify-content:space-between;align-items:center;padding:20px 25px;border-bottom:1px solid var(--ui-line)}.redacted-dialog h2{font:700 24px var(--ui-heading);margin:6px 0 0}.redacted-dialog header>button{font-size:22px;line-height:1;padding:7px 10px}.redacted-body{min-height:0;display:grid;grid-template-columns:285px minmax(0,1fr);overflow:auto}.redacted-body aside{padding:20px 24px;border-right:1px solid var(--ui-line)}.redacted-body p{font-size:12px;line-height:1.9;margin:0 0 22px;color:var(--ui-muted)}.redacted-body fieldset{border:0;padding:0;margin:0 0 24px;min-width:0}.redacted-body legend{font-weight:700;margin-bottom:17px}.redacted-body label{display:flex;align-items:center;gap:9px;margin:0 0 16px;line-height:1.5}.redacted-body input{accent-color:var(--ui-accent);width:15px;height:15px;flex:none;margin:0}.redacted-body small{display:block;font-size:10px;color:var(--ui-muted);line-height:1.8;margin:0 0 16px 24px}.redacted-matching{border-top:1px solid var(--ui-line);padding-top:18px;margin-top:20px!important}.redacted-body p.redacted-note{font-size:11px;margin-bottom:18px}.redacted-preview{background:var(--ui-canvas);padding:18px 20px 30px;min-height:300px;max-height:70vh;overflow:auto;overscroll-behavior:contain}.redacted-preview-status{display:flex;justify-content:space-between;color:var(--ui-muted);font-size:10px;margin-bottom:15px}.redacted-paper{margin:0 auto;background:#fff;position:relative;box-shadow:0 5px 18px #0002}.redacted-paper iframe{position:absolute;left:0;top:0;border:0;display:block;transform-origin:top left}.redacted-placeholder{text-align:center;padding:60px 0;color:var(--ui-muted)}.redacted-error{margin:0;padding:12px 25px;color:var(--ui-ink);background:var(--ui-tint);line-height:1.8;font-size:12px}.redacted-error button{margin-left:10px;font-size:11px}.redacted-dialog footer{display:flex;align-items:center;justify-content:space-between;gap:15px;border-top:1px solid var(--ui-line);padding:16px 25px}.redacted-dialog footer>span{font-size:11px;color:var(--ui-muted)}.redacted-dialog footer>div{display:flex;gap:9px}.redacted-dialog .redacted-primary{background:var(--ui-accent);color:var(--ui-accent-ink);border-color:var(--ui-accent)}
@media(max-width:700px){.redacted-overlay{padding:10px}.redacted-body{grid-template-columns:1fr}.redacted-body aside{border-right:0;border-bottom:1px solid var(--ui-line);padding:18px}.redacted-body fieldset{display:grid;grid-template-columns:1fr 1fr;column-gap:10px;margin-bottom:16px}.redacted-body legend,.redacted-matching,.redacted-body small{grid-column:1/-1}.redacted-preview{max-height:none;overflow:visible}.redacted-body p{margin-bottom:16px}.redacted-dialog header,.redacted-dialog footer{padding-left:18px;padding-right:18px}.redacted-dialog footer>span{display:none}.redacted-dialog footer>div{margin-left:auto}.redacted-dialog footer{flex:none}}
</style>
