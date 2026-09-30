<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue';
import {api,type Resume} from './api';
import {modelSettings,type ModelSettings,type ParagraphTarget,type Suggestion} from './modelApi';
import {textDifference} from './textDifference';
import {claimWarnings} from './claimWarnings';
const props=defineProps<{source:Resume;target:ParagraphTarget;returnFocus?:HTMLElement}>();
const emit=defineEmits<{close:[];settings:[];applied:[Resume];applying:[boolean]}>();
const state=ref<ModelSettings>(),profileId=ref(''),paragraph=ref(props.target.paragraph),consent=ref(false),verified=ref(false),working=ref(false),applying=ref(false),error=ref(''),suggestion=ref<Suggestion>();
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>();
const entry=computed(()=>props.source.document.content.sections.find(s=>s.id===props.target.sectionId)?.entries.find(e=>e.id===props.target.entryId));
const original=computed(()=>entry.value?.bullets[paragraph.value]||'');
const profile=computed(()=>state.value?.profiles.find(p=>p.id===profileId.value));
const destination=computed(()=>profile.value?profile.value.baseUrl+'/chat/completions':'');
const difference=computed(()=>textDifference(suggestion.value?.original||original.value,suggestion.value?.replacement||original.value));
const risk=computed(()=>claimWarnings(suggestion.value?.original||'',suggestion.value?.replacement||''));
const canGenerate=computed(()=>state.value?.readable&&state.value.enabled&&profile.value?.hasApiKey&&original.value.trim()&&consent.value&&!working.value&&!applying.value);
let disposed=false,generation=0,controller:AbortController|undefined,previousFocus:HTMLElement|undefined;
function changed(){generation++;controller?.abort();working.value=false;suggestion.value=undefined;verified.value=false;consent.value=false;error.value='';}
watch([profileId,paragraph],changed);
async function generate(){
 if(!canGenerate.value||!state.value||!profile.value)return;const version=++generation;working.value=true;error.value='';suggestion.value=undefined;verified.value=false;controller=new AbortController();
 try{const result=await api<Suggestion>('/api/ai/suggestions',{resumeId:props.source.id,expectedRevision:props.source.revision,sectionId:props.target.sectionId,entryId:props.target.entryId,paragraph:paragraph.value,profileId:profile.value.id,settingsRevision:state.value.revision,confirmSend:true},undefined,controller.signal);if(!disposed&&version===generation)suggestion.value=result;}
 catch(cause){if(!disposed&&version===generation)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{if(!disposed&&version===generation)working.value=false;}
}
async function apply(){
 if(!suggestion.value||!verified.value||applying.value)return;applying.value=true;emit('applying',true);error.value='';
 try{const result=await api<Resume>(`/api/ai/suggestions/${suggestion.value.id}/apply`,{resumeId:props.source.id,expectedRevision:props.source.revision,confirmApply:true});if(!disposed)emit('applied',result);}
 catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{applying.value=false;emit('applying',false);}
}
function close(){if(!applying.value){controller?.abort();emit('close');}}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'&&!applying.value){event.preventDefault();close();}
 if(event.key==='Tab'){const controls=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),select:not(:disabled),input:not(:disabled),a[href]')||[]).filter(el=>el.getClientRects().length);const first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}
}
onMounted(async()=>{previousFocus=props.returnFocus||(document.activeElement instanceof HTMLElement?document.activeElement:undefined);window.addEventListener('keydown',keys);await nextTick();closeButton.value?.focus();try{const result=await modelSettings();if(!disposed){state.value=result;profileId.value=result.defaultId||'';}}catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}});
onUnmounted(()=>{disposed=true;controller?.abort();window.removeEventListener('keydown',keys);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="suggestion-overlay"><section ref="dialog" class="suggestion-dialog" role="dialog" aria-modal="true" aria-labelledby="suggestion-title"><header><div><span class="rw-overline">文字编辑</span><h2 id="suggestion-title">AI 段落润色</h2></div><button ref="closeButton" :disabled="applying" aria-label="关闭 AI 润色" @click="close">×</button></header>
  <p class="suggestion-intro">只发送下方选定的段落。先查看建议和差异，确认后应用；原稿会保留为历史版本。</p>
  <div class="suggestion-options"><label class="form-label">要润色的段落<select v-model.number="paragraph" :disabled="applying" aria-label="要润色的段落"><option v-for="(text,index) in entry?.bullets" :key="index" :value="index">第 {{index+1}} 段 · {{text.slice(0,35)||'空段落'}}</option></select></label><label class="form-label">使用模型<select v-model="profileId" :disabled="applying" aria-label="使用的润色模型"><option value="">选择已配置的模型</option><option v-for="item in state?.profiles" :key="item.id" :value="item.id">{{item.name}} · {{item.model}}</option></select></label></div>
  <div class="suggestion-destination"><span>{{profile?`${profile.name} · ${profile.model}`:'尚未选择模型'}}</span><small>{{destination}}</small><button :disabled="applying" @click="emit('settings')">模型设置</button></div>
  <p v-if="state&&!state.enabled" class="suggestion-warning">文字润色尚未启用，请在模型设置中开启。</p><p v-if="profile&&!profile.hasApiKey" class="suggestion-warning">此模型尚未配置 API Key。</p><p v-if="state&&!state.readable" class="suggestion-warning">模型设置无法读取，已暂停调用。</p>
  <p v-if="suggestion" class="suggestion-diff-key">划线表示原文移除，高亮表示建议新增；未标记的文字保持不变。</p>
  <div class="suggestion-comparison"><article><h3>{{suggestion?'原文':'将发送的文字'}}</h3><p v-if="suggestion" class="suggestion-text" data-testid="ai-original-text"><template v-for="(part,index) in difference.before" :key="index"><del v-if="part.changed">{{part.text}}</del><span v-else>{{part.text}}</span></template></p><p v-else class="suggestion-text" data-testid="ai-sent-text">{{original||'请先填写这一段内容。'}}</p></article><article><h3>建议</h3><p v-if="suggestion" class="suggestion-text" data-testid="ai-result-text"><template v-for="(part,index) in difference.after" :key="index"><mark v-if="part.changed">{{part.text}}</mark><span v-else>{{part.text}}</span></template></p><p v-else class="suggestion-placeholder">{{working?'正在生成建议…':'点击生成后，在这里查看改写结果。'}}</p></article></div>
  <label class="suggestion-confirm"><input v-model="consent" type="checkbox" :disabled="applying||!state?.readable||!state.enabled||!profile?.hasApiKey" aria-label="确认发送选定段落">我确认将这段文字发送到上述模型服务</label><button class="rw-primary" :disabled="!canGenerate" @click="generate">{{working?'正在生成…':suggestion?'重新生成建议':'生成润色建议'}}</button>
  <div v-if="suggestion&&(suggestion.addedNumbers||risk.roles.length||risk.outcomes.length)" class="suggestion-fact-risks" role="note" aria-label="建议中需要核对的事实变化"><p v-if="risk.roles.length" data-testid="ai-role-risk">建议新增或加强了 {{risk.roles.map(term=>`“${term}”`).join('、')}} 等职责用语，请确认职责确实属于你。</p><p v-if="risk.outcomes.length" data-testid="ai-outcome-risk">建议新增了 {{risk.outcomes.map(term=>`“${term}”`).join('、')}} 等结果描述，请确认有事实依据。</p><p v-if="suggestion.addedNumbers">建议中出现原文没有的数字，请逐项核对。</p></div><p v-if="error" class="suggestion-error" role="alert">{{error}}</p>
  <footer><div><label v-if="suggestion" class="suggestion-confirm"><input v-model="verified" type="checkbox" :disabled="applying" aria-label="确认建议事实与表达">我已核对事实与表达，确认采用这段文字</label><p>应用前自动保留原稿；之后也可以撤销。</p></div><div class="suggestion-actions"><button :disabled="applying" @click="close">取消</button><button class="rw-primary" :disabled="!suggestion||!verified||suggestion.original===suggestion.replacement||applying" @click="apply">{{applying?'正在应用…':'确认应用建议'}}</button></div></footer>
 </section></div>
</template>
