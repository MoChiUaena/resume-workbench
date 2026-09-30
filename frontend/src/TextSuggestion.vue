<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue';
import {api,ApiFailure,type Resume} from './api';
import {modelSettings,type ModelSettings,type ParagraphTarget,type Suggestion} from './modelApi';
import {textDifference} from './textDifference';
import {addedNumericClaims,claimWarnings} from './claimWarnings';
const props=defineProps<{source:Resume;target:ParagraphTarget;returnFocus?:HTMLElement}>();
const emit=defineEmits<{close:[];settings:[];applied:[Resume];applying:[boolean]}>();
const state=ref<ModelSettings>(),profileId=ref(''),paragraph=ref(props.target.paragraph),consent=ref(false),verified=ref(false),working=ref(false),applying=ref(false),error=ref(''),suggestion=ref<Suggestion>(),reviewText=ref(''),pendingApplyText=ref<string>();
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>(),sourceArea=ref<HTMLTextAreaElement>();
const entry=computed(()=>props.source.document.content.sections.find(s=>s.id===props.target.sectionId)?.entries.find(e=>e.id===props.target.entryId));
const original=computed(()=>entry.value?.bullets[paragraph.value]||'');
const selectionStart=ref(props.target.selectionStart??0),selectionEnd=ref(props.target.selectionEnd??original.value.length);
const invalidSelection=ref(!!props.target.invalidSelection);
const selectedText=computed(()=>original.value.slice(selectionStart.value,selectionEnd.value));
const profile=computed(()=>state.value?.profiles.find(p=>p.id===profileId.value));
const destination=computed(()=>profile.value?profile.value.baseUrl+'/chat/completions':'');
const difference=computed(()=>textDifference(suggestion.value?.selectedOriginal||selectedText.value,suggestion.value?reviewText.value:selectedText.value));
const risk=computed(()=>claimWarnings(suggestion.value?.selectedOriginal||'',reviewText.value));
const addedNumbers=computed(()=>!!suggestion.value&&addedNumericClaims(suggestion.value.selectedOriginal,reviewText.value));
const reviewValid=computed(()=>!!reviewText.value.trim()&&reviewText.value.length<=800&&!/[\r\n]/.test(reviewText.value));
const canGenerate=computed(()=>state.value?.readable&&state.value.enabled&&profile.value?.hasApiKey&&selectedText.value.trim()&&!invalidSelection.value&&consent.value&&!working.value&&!applying.value&&!pendingApplyText.value);
const canApply=computed(()=>!!suggestion.value&&verified.value&&reviewValid.value&&reviewText.value!==suggestion.value.selectedOriginal&&!applying.value);
let disposed=false,generation=0,controller:AbortController|undefined,previousFocus:HTMLElement|undefined;
function changed(){generation++;controller?.abort();working.value=false;suggestion.value=undefined;reviewText.value='';pendingApplyText.value=undefined;verified.value=false;consent.value=false;error.value='';}
function reviewEdited(){verified.value=false;error.value='';}
function choose(event:Event){const area=event.target as HTMLTextAreaElement;if(area.selectionEnd>area.selectionStart){selectionStart.value=area.selectionStart;selectionEnd.value=area.selectionEnd;invalidSelection.value=false;}}
function whole(){selectionStart.value=0;selectionEnd.value=original.value.length;invalidSelection.value=false;sourceArea.value?.setSelectionRange(0,original.value.length);}
watch(paragraph,async()=>{selectionStart.value=0;selectionEnd.value=original.value.length;invalidSelection.value=false;await nextTick();sourceArea.value?.setSelectionRange(0,original.value.length);});
watch([profileId,paragraph,selectionStart,selectionEnd],changed);
async function generate(){
 if(!canGenerate.value||!state.value||!profile.value)return;const version=++generation;working.value=true;error.value='';suggestion.value=undefined;verified.value=false;controller=new AbortController();
 try{const result=await api<Suggestion>('/api/ai/suggestions',{resumeId:props.source.id,expectedRevision:props.source.revision,sectionId:props.target.sectionId,entryId:props.target.entryId,paragraph:paragraph.value,selectionStart:selectionStart.value,selectionEnd:selectionEnd.value,profileId:profile.value.id,settingsRevision:state.value.revision,confirmSend:true},undefined,controller.signal);if(!disposed&&version===generation){reviewText.value=result.replacement;suggestion.value=result;}}
 catch(cause){if(!disposed&&version===generation)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{if(!disposed&&version===generation)working.value=false;}
}
async function apply(){
 if(!canApply.value||!suggestion.value)return;
 const chosen=pendingApplyText.value??reviewText.value;pendingApplyText.value=chosen;applying.value=true;emit('applying',true);error.value='';
 try{const result=await api<Resume>(`/api/ai/suggestions/${suggestion.value.id}/apply`,{resumeId:props.source.id,expectedRevision:props.source.revision,confirmApply:true,reviewedText:chosen});if(!disposed)emit('applied',result);}
 catch(cause){if(!disposed){if(!(cause instanceof ApiFailure)||cause.code!=='NETWORK_ERROR')pendingApplyText.value=undefined;error.value=cause instanceof Error?cause.message:String(cause);}}
 finally{applying.value=false;emit('applying',false);}
}
function close(){if(!applying.value){controller?.abort();emit('close');}}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'&&!applying.value){event.preventDefault();close();}
 if(event.key==='Tab'){const controls=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),select:not(:disabled),input:not(:disabled),a[href]')||[]).filter(el=>el.getClientRects().length);const first=controls[0],last=controls.at(-1);if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}
}
onMounted(async()=>{previousFocus=props.returnFocus||(document.activeElement instanceof HTMLElement?document.activeElement:undefined);window.addEventListener('keydown',keys);await nextTick();const area=sourceArea.value;if(area&&selectedText.value&&selectedText.value!==original.value){area.focus();area.setSelectionRange(selectionStart.value,selectionEnd.value);}else closeButton.value?.focus();try{const result=await modelSettings();if(!disposed){state.value=result;profileId.value=result.defaultId||'';}}catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}});
onUnmounted(()=>{disposed=true;controller?.abort();window.removeEventListener('keydown',keys);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="suggestion-overlay"><section ref="dialog" class="suggestion-dialog" role="dialog" aria-modal="true" aria-labelledby="suggestion-title"><header><div><span class="rw-overline">文字编辑</span><h2 id="suggestion-title">AI 段落润色</h2></div><button ref="closeButton" :disabled="applying" aria-label="关闭 AI 润色" @click="close">×</button></header>
  <p class="suggestion-intro">只发送下方显示的文字。可在段落原文中划选一句或几句；确认应用时仅替换选区，其他内容保持不变。</p>
  <div class="suggestion-options"><label class="form-label">要润色的段落<select v-model.number="paragraph" :disabled="applying||!!pendingApplyText" aria-label="要润色的段落"><option v-for="(text,index) in entry?.bullets" :key="index" :value="index">第 {{index+1}} 段 · {{text.slice(0,35)||'空段落'}}</option></select></label><label class="form-label">使用模型<select v-model="profileId" :disabled="applying||!!pendingApplyText" aria-label="使用的润色模型"><option value="">选择已配置的模型</option><option v-for="item in state?.profiles" :key="item.id" :value="item.id">{{item.name}} · {{item.model}}</option></select></label></div>
  <div class="suggestion-destination"><span>{{profile?`${profile.name} · ${profile.model}`:'尚未选择模型'}}</span><small>{{destination}}</small><button :disabled="applying||!!pendingApplyText" @click="emit('settings')">模型设置</button></div>
  <div class="suggestion-source"><label class="form-label">段落原文（在框内划选句段）<textarea ref="sourceArea" :value="original" :disabled="applying||!!pendingApplyText" readonly rows="4" aria-label="选取要润色的句段" @select="choose" @mouseup="choose" @keyup="choose"></textarea></label><div class="suggestion-source-tools"><small>未划选时默认整段；可重新划选，也可手动选择整段。</small><button :disabled="applying||!!pendingApplyText" @click="whole">选择整段</button></div><p v-if="invalidSelection" class="suggestion-warning">一次只能选择同一段落中的文字。请在上方重新划选，或选择整段。</p></div>
  <p v-if="state&&!state.enabled" class="suggestion-warning">文字润色尚未启用，请在模型设置中开启。</p><p v-if="profile&&!profile.hasApiKey" class="suggestion-warning">此模型尚未配置 API Key。</p><p v-if="state&&!state.readable" class="suggestion-warning">模型设置无法读取，已暂停调用。</p>
  <p v-if="suggestion" class="suggestion-diff-key">划线表示原文移除，高亮表示建议新增；未标记的文字保持不变。</p>
  <div class="suggestion-comparison"><article><h3>{{suggestion?'原文（选中句段）':'将发送的文字'}}</h3><p v-if="suggestion" class="suggestion-text" data-testid="ai-original-text"><template v-for="(part,index) in difference.before" :key="index"><del v-if="part.changed">{{part.text}}</del><span v-else>{{part.text}}</span></template></p><p v-else class="suggestion-text" data-testid="ai-sent-text">{{selectedText||'请在段落原文中选择要润色的文字。'}}</p></article><article><h3>建议</h3><p v-if="suggestion" class="suggestion-text" data-testid="ai-result-text"><template v-for="(part,index) in difference.after" :key="index"><mark v-if="part.changed">{{part.text}}</mark><span v-else>{{part.text}}</span></template></p><p v-else class="suggestion-placeholder">{{working?'正在生成建议…':'点击生成后，在这里查看改写结果。'}}</p></article></div>
  <label v-if="suggestion" class="suggestion-review-edit form-label">建议文字（可在应用前修改）<textarea v-model="reviewText" :disabled="applying||!!pendingApplyText" maxlength="800" rows="4" aria-label="可修改的润色建议" data-testid="ai-edit-suggestion" @input="reviewEdited"></textarea><small>修改后差异和风险提示会同步更新，并需重新确认；修改建议不会再次调用模型。</small></label>
  <label class="suggestion-confirm"><input v-model="consent" type="checkbox" :disabled="applying||!state?.readable||!state.enabled||!profile?.hasApiKey" aria-label="确认发送选定段落">我确认将这段文字发送到上述模型服务</label><button class="rw-primary" :disabled="!canGenerate" @click="generate">{{working?'正在生成…':suggestion?'重新生成建议':'生成润色建议'}}</button>
  <div v-if="suggestion&&(addedNumbers||risk.roles.length||risk.outcomes.length)" class="suggestion-fact-risks" role="note" aria-label="建议中需要核对的事实变化"><p v-if="risk.roles.length" data-testid="ai-role-risk">建议新增或加强了 {{risk.roles.map(term=>`“${term}”`).join('、')}} 等职责用语，请确认职责确实属于你。</p><p v-if="risk.outcomes.length" data-testid="ai-outcome-risk">建议新增了 {{risk.outcomes.map(term=>`“${term}”`).join('、')}} 等结果描述，请确认有事实依据。</p><p v-if="addedNumbers" data-testid="ai-number-risk">建议中出现原文没有的数字，请逐项核对。</p></div><p v-if="suggestion&&!reviewValid" class="suggestion-error">建议文字须为非空、单段且不超过 800 字。</p><p v-if="pendingApplyText" class="suggestion-warning">应用结果尚未确认，建议文字暂时锁定；请用同一份文字重试。</p><p v-if="error" class="suggestion-error" role="alert">{{error}}</p>
  <footer><div><label v-if="suggestion" class="suggestion-confirm"><input v-model="verified" type="checkbox" :disabled="applying" aria-label="确认建议事实与表达">我已核对事实与表达，确认采用这段文字</label><p>应用前自动保留原稿；之后也可以撤销。</p></div><div class="suggestion-actions"><button :disabled="applying" @click="close">取消</button><button class="rw-primary" :disabled="!canApply" @click="apply">{{applying?'正在应用…':'确认应用建议'}}</button></div></footer>
 </section></div>
</template>
