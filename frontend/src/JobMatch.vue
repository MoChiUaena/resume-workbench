<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue';
import {api,ApiFailure,type Resume,type Section} from './api';
import {modelSettings,type ModelSettings,type Suggestion} from './modelApi';
import {generateJob,previewJob,type JobPreview,type JobReport,type JobSource} from './jobMatchApi';
import JobReportSave from './JobReportSave.vue';
import JobReportHistory from './JobReportHistory.vue';
const props=defineProps<{source:Resume;returnFocus?:HTMLElement;suspended?:boolean}>();
const emit=defineEmits<{close:[];review:[Suggestion,HTMLElement];settings:[]}>();
const sections=computed(()=>props.source.document.content.sections.filter(section=>section.visible));
const selected=ref<string[]>([]),job=ref(''),state=ref<ModelSettings>(),profileId=ref('');
const preview=ref<JobPreview>(),report=ref<JobReport>(),consent=ref(false),busy=ref(false),retry=ref(false),error=ref('');
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>();
const historyOpen=ref(false),historyLocked=ref(false),saveBusy=ref(false),savePending=ref(false),savedDeletedId=ref<string>();
const operationLocked=computed(()=>historyLocked.value||saveBusy.value);
const profile=computed(()=>state.value?.profiles.find(item=>item.id===profileId.value));
const canPreview=computed(()=>!!state.value?.readable&&!!state.value.enabled&&!!profile.value?.hasApiKey&&selected.value.length>0&&selected.value.length<=12&&job.value.trim().length>0&&job.value.length<=6000&&!busy.value&&!saveBusy.value&&!savePending.value);
const canGenerate=computed(()=>!!preview.value&&!report.value&&consent.value&&!busy.value&&!saveBusy.value&&!savePending.value);
const sourceMap=computed(()=>new Map(preview.value?.sources.map(source=>[source.id,source])||[]));
const statusLabel={supported:'有依据',partial:'部分依据',missing:'缺少依据'};
let version=0,controller:AbortController|undefined,disposed=false,previousFocus:HTMLElement|undefined,expiryTimer:ReturnType<typeof setTimeout>|undefined,reviewFocus:HTMLElement|undefined;
function scheduleExpiry(expiresAt:string,kind:'preview'|'report',id:string){
 clearTimeout(expiryTimer);
 expiryTimer=setTimeout(()=>{
  if(disposed||(kind==='preview'&&preview.value?.id!==id)||(kind==='report'&&report.value?.id!==id))return;
  invalidate();error.value=kind==='preview'?'预览已过期，请重新预览发送内容。':'报告已过期，请重新预览并生成。';
 },Math.max(0,Date.parse(expiresAt)-Date.now()));
}
function invalidate(){
 version++;controller?.abort();controller=undefined;clearTimeout(expiryTimer);expiryTimer=undefined;busy.value=false;
 preview.value=undefined;report.value=undefined;consent.value=false;retry.value=false;error.value='';
}
watch([selected,job,profileId],invalidate,{deep:true});
watch(()=>props.suspended,async suspended=>{if(!suspended){await nextTick();if(reviewFocus?.isConnected)reviewFocus.focus();else closeButton.value?.focus();}});
function toggle(section:Section,event:Event){
 const checked=(event.target as HTMLInputElement).checked;
 selected.value=checked?[...selected.value,section.id]:selected.value.filter(id=>id!==section.id);
}
async function loadSettings(){
 const active=++version;controller?.abort();clearTimeout(expiryTimer);preview.value=undefined;report.value=undefined;consent.value=false;retry.value=false;busy.value=true;error.value='';
 try{
  const result=await modelSettings();
  if(disposed||active!==version)return;
  state.value=result;profileId.value=result.defaultId||'';
 }catch(cause){if(!disposed&&active===version)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{if(!disposed&&active===version)busy.value=false;}
}
async function prepare(){
 if(!canPreview.value||!state.value)return;
 const active=++version;controller=new AbortController();clearTimeout(expiryTimer);busy.value=true;preview.value=undefined;report.value=undefined;consent.value=false;retry.value=false;error.value='';
 try{
  const result=await previewJob({resumeId:props.source.id,expectedRevision:props.source.revision,sectionIds:selected.value,jobDescription:job.value,profileId:profileId.value,settingsRevision:state.value.revision},controller.signal);
  if(!disposed&&active===version){preview.value=result;scheduleExpiry(result.expiresAt,'preview',result.id);}
 }catch(cause){if(!disposed&&active===version)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{if(!disposed&&active===version)busy.value=false;}
}
async function generate(){
 if(!preview.value)return;
 if(Date.parse(preview.value.expiresAt)<=Date.now()){invalidate();error.value='预览已过期，请重新预览发送内容。';return;}
 if(!canGenerate.value)return;
 const active=++version;controller=new AbortController();busy.value=true;report.value=undefined;error.value='';
 try{
  const result=await generateJob(preview.value.id,controller.signal);
  if(!disposed&&active===version){report.value=result;retry.value=false;consent.value=false;scheduleExpiry(result.expiresAt,'report',result.id);}
 }catch(cause){
  if(!disposed&&active===version){
   error.value=cause instanceof Error?cause.message:String(cause);consent.value=false;
   retry.value=cause instanceof ApiFailure&&(cause.code==='NETWORK_ERROR'||cause.code==='MODEL_BUSY')&&!!preview.value&&Date.parse(preview.value.expiresAt)>Date.now();
   if(!retry.value){preview.value=undefined;clearTimeout(expiryTimer);}
  }
 }finally{if(!disposed&&active===version)busy.value=false;}
}
async function checkFresh(){
 if(!preview.value&&!report.value)return;
 const active=version,snapshot=preview.value;
 if(!snapshot)return;
 try{
  const [latest,settings]=await Promise.all([api<Resume>('/api/resumes/'+props.source.id),modelSettings()]);
  if(disposed||active!==version)return;
  if(latest.revision!==snapshot.revision||settings.revision!==snapshot.settingsRevision||!settings.readable){
   invalidate();error.value='简历或模型配置已改变，请重新打开并预览发送内容。';
  }
 }catch{
  if(!disposed&&active===version){invalidate();error.value='无法核对最新简历或模型配置，请重新打开并预览。';}
 }
}
async function review(suggestion:Suggestion,event:Event){
 if(saveBusy.value||historyOpen.value)return;
 const trigger=event.currentTarget;
 await checkFresh();
 if(!report.value)return;
 if(Date.parse(report.value.expiresAt)<=Date.now()){invalidate();error.value='报告已过期，请重新预览并生成。';return;}
 if(trigger instanceof HTMLElement){reviewFocus=trigger;emit('review',suggestion,trigger);}
}
function close(){if(operationLocked.value)return;invalidate();emit('close');}
function settings(){if(operationLocked.value)return;invalidate();emit('settings');}
async function showHistory(){if(operationLocked.value)return;historyOpen.value=true;await nextTick();}
async function showAnalysis(){if(operationLocked.value)return;historyOpen.value=false;await nextTick();dialog.value?.querySelector<HTMLButtonElement>('[data-job-view=analysis]')?.focus();}
function label(source:JobSource){
 const entry=source.entryTitle.trim()||'未命名条目';
 return source.sectionTitle+' / '+entry+' / '+(source.paragraph<0?'条目标题与说明':'第 '+(source.paragraph+1)+' 段');
}
function keys(event:KeyboardEvent){
 if(props.suspended)return;
 if(event.key==='Escape'){event.preventDefault();close();return;}
 if(event.key!=='Tab')return;
 const controls=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),select:not(:disabled),input:not(:disabled),textarea:not(:disabled),a[href],summary')||[]).filter(node=>node.getClientRects().length&&!node.closest('[inert]'));
 const first=controls[0],last=controls.at(-1);
 if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
 else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
}
onMounted(async()=>{previousFocus=props.returnFocus||(document.activeElement instanceof HTMLElement?document.activeElement:undefined);window.addEventListener('keydown',keys);window.addEventListener('focus',checkFresh);await nextTick();closeButton.value?.focus();await loadSettings();});
onUnmounted(()=>{disposed=true;version++;controller?.abort();clearTimeout(expiryTimer);window.removeEventListener('keydown',keys);window.removeEventListener('focus',checkFresh);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div v-show="!suspended" class="job-overlay"><section ref="dialog" class="job-dialog" role="dialog" aria-modal="true" aria-labelledby="job-title">
  <header><div><span class="rw-overline">简历编辑</span><h2 id="job-title">职位匹配</h2></div><button ref="closeButton" :disabled="operationLocked" aria-label="关闭职位匹配" @click="close">×</button></header>
  <nav class="job-view-actions" aria-label="职位匹配视图"><button data-job-view="analysis" :aria-pressed="!historyOpen" :disabled="operationLocked" @click="showAnalysis">分析当前简历</button><button :aria-pressed="historyOpen" :disabled="operationLocked" @click="showHistory">已保存报告</button></nav>
  <div v-show="!historyOpen">
  <p class="job-intro">选择要分析的可见模块，输入岗位原文。生成前会显示服务器形成的完整发送内容和接收模型；模型判断与改写都需要你核对。</p>
  <div class="job-input-grid"><fieldset :disabled="busy||saveBusy"><legend>选择模块（1–12 个）</legend><label v-for="section in sections" :key="section.id" class="job-check"><input type="checkbox" :checked="selected.includes(section.id)" :disabled="!selected.includes(section.id)&&selected.length>=12" :aria-label="'匹配模块 '+section.title" @change="toggle(section,$event)">{{section.title}}</label><p v-if="!sections.length">没有可见模块可供分析。</p></fieldset>
   <label class="job-description">岗位要求原文<textarea v-model="job" :disabled="saveBusy" maxlength="6000" rows="8" aria-label="岗位要求原文" placeholder="粘贴岗位职责和要求"></textarea><small>{{job.length}} / 6000 字</small></label></div>
  <div class="job-model"><div><strong>使用模型</strong><select v-model="profileId" aria-label="匹配模型" :disabled="busy||saveBusy||!state?.readable"><option value="">选择已配置的模型</option><option v-for="item in state?.profiles" :key="item.id" :value="item.id">{{item.name}} · {{item.model}}</option></select><small v-if="profile">{{profile.baseUrl}}/chat/completions</small></div><button :disabled="busy||saveBusy" @click="loadSettings">刷新模型配置</button><button :disabled="busy||saveBusy" @click="settings">模型设置</button></div>
  <p v-if="state&&!state.readable" class="job-warning">模型设置无法读取，已暂停调用。</p><p v-else-if="state&&!state.enabled" class="job-warning">模型调用尚未启用，可在模型设置中开启。</p><p v-if="profile&&!profile.hasApiKey" class="job-warning">所选模型没有 API Key。</p>
  <div class="job-step"><button class="rw-primary" :disabled="!canPreview" @click="prepare">{{busy&&!preview?'正在处理…':'预览发送内容'}}</button><span>预览只读取当前已保存修订，不调用模型。</span></div>
  <section v-if="preview" class="job-preview" aria-label="发送预览"><h3>发送确认</h3><p>接收模型：{{preview.profileName}} · {{preview.provider}} · {{preview.model}}</p><p class="job-destination">{{preview.destination}}</p><p>简历修订 r{{preview.revision}} · 配置修订 r{{preview.settingsRevision}} · {{preview.sources.length}} 段来源 · 截止 {{new Date(preview.expiresAt).toLocaleString('zh-CN')}}</p><p class="job-warning">模块正文可能包含个人信息。请自行检查下方完整发送内容；姓名、联系方式、照片、Logo 和其他未选模块不会自动附带。</p><label class="job-payload">将发送的完整内容<textarea :value="preview.payload" readonly rows="8" aria-label="将发送的完整内容" data-testid="job-payload"></textarea></label><label class="job-check"><input v-model="consent" type="checkbox" aria-label="确认发送岗位和选中模块">我已核对完整内容及模型服务，确认发送</label><p v-if="retry" class="job-warning">已生成且仍在缓存中的报告会直接返回；没有可用缓存时，重试可能再次调用模型并产生费用。</p><button class="rw-primary" :disabled="!canGenerate" @click="generate">{{busy?'正在生成…':retry?'重试同一请求':'生成匹配分析'}}</button></section>
  <p v-if="error" class="job-error" role="alert">{{error}}</p>
  <section v-if="report" class="job-report" aria-label="匹配报告"><h3>匹配报告</h3><p class="job-warning">以下是模型判断，需对照岗位原文和简历逐项核实；不代表招聘方评价。</p><p>实际生成模型：{{report.profileName}} · {{report.provider}} · {{report.model}}<br><span class="job-destination">{{report.destination}}</span></p>
   <ol class="job-items"><li v-for="(item,index) in report.items" :key="index"><div class="job-item-heading"><strong>{{item.requirement}}</strong><span :class="'job-status-'+item.status">{{statusLabel[item.status]}}</span></div><ul v-if="item.evidence.length"><li v-for="(evidence,number) in item.evidence" :key="number"><small>{{label(sourceMap.get(evidence.sourceId)!)}}</small><blockquote>{{evidence.quote}}</blockquote></li></ul><p v-else>所选材料中未找到模型可引用的依据。</p><p v-if="item.advice">补充建议：{{item.advice}}</p></li></ol>
   <div v-if="report.suggestions.length" class="job-suggestions"><h4>逐条审核修改建议</h4><article v-for="suggestion in report.suggestions" :key="suggestion.id"><p>{{label({id:'',sectionId:suggestion.sectionId,entryId:suggestion.entryId,paragraph:suggestion.paragraph,sectionTitle:source.document.content.sections.find(s=>s.id===suggestion.sectionId)?.title||'模块',entryTitle:source.document.content.sections.find(s=>s.id===suggestion.sectionId)?.entries.find(e=>e.id===suggestion.entryId)?.title||'',text:suggestion.original})}}</p><p>{{suggestion.replacement}}</p><button class="rw-primary" @click="review(suggestion,$event)">审核修改</button></article></div>
  </section>
  <JobReportSave :resume-id="source.id" :preview-id="preview?.id" :report="report" :deleted-id="savedDeletedId" :locked="historyLocked" @busy="saveBusy=$event" @pending="savePending=$event" @history="showHistory"/>
  </div>
  <JobReportHistory v-if="historyOpen" :resume-id="source.id" :source-revision="source.revision" :locked="saveBusy" @locked="historyLocked=$event" @deleted="savedDeletedId=$event"/>
  <footer><button :disabled="operationLocked" @click="close">取消并关闭</button></footer>
 </section></div>
</template>
