<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue';
import {ApiFailure} from './api';
import {saveJobReport,type JobReport,type SavedJobReportDetail} from './jobMatchApi';
import {createJobReportSaveAttempt,pendingJobReportSave,rememberJobReportSave,resolveJobReportSave,type JobReportSaveAttempt} from './jobReportReceipt';
const props=defineProps<{resumeId:string;previewId?:string;report?:JobReport;deletedId?:string;locked?:boolean}>();
const emit=defineEmits<{busy:[boolean];pending:[boolean];saved:[SavedJobReportDetail];history:[]}>();
const attempt=ref<JobReportSaveAttempt|undefined>(pendingJobReportSave(props.resumeId));
const label=ref(attempt.value?.request.label||''),busy=ref(false),uncertain=ref(!!attempt.value),saved=ref<SavedJobReportDetail>(),error=ref(''),deleted=ref(false);
let disposed=false;
const relevant=computed(()=>!!attempt.value||!!saved.value||!!props.report);
const canSave=computed(()=>!busy.value&&!props.locked&&!uncertain.value&&!deleted.value&&!!props.previewId&&!!props.report&&saved.value?.id!==props.report.id&&label.value.trim().length>0&&label.value.trim().length<=120);
watch(()=>props.report?.id,(id,previous)=>{if(id&&id!==previous&&!busy.value&&!uncertain.value){attempt.value=undefined;saved.value=undefined;deleted.value=false;label.value='';error.value='';}});
watch(()=>props.deletedId,id=>{if(id&&(id===saved.value?.id||id===attempt.value?.request.reportId)){deleted.value=true;uncertain.value=false;if(attempt.value)resolveJobReportSave(attempt.value);error.value='';}});
watch(busy,value=>emit('busy',value));
watch(uncertain,value=>emit('pending',value),{immediate:true});
async function save(){
 if(busy.value||props.locked||deleted.value)return;
 if(!uncertain.value){
  if(!canSave.value||!props.report||!props.previewId)return;
  attempt.value=createJobReportSaveAttempt({resumeId:props.resumeId,previewId:props.previewId,reportId:props.report.id,label:label.value,sourceRevision:props.report.revision});
  label.value=attempt.value.request.label;rememberJobReportSave(attempt.value);
 }
 const pending=attempt.value;if(!pending)return;busy.value=true;error.value='';
 try{
  const result=await saveJobReport(pending.resumeId,pending.request,pending.sourceRevision);resolveJobReportSave(pending);
  if(!disposed){saved.value=result;uncertain.value=false;emit('saved',result);}
 }catch(cause){
  const unknown=!(cause instanceof ApiFailure)||cause.code==='NETWORK_ERROR';
  if(!unknown)resolveJobReportSave(pending);
  if(!disposed){error.value=cause instanceof Error?cause.message:String(cause);uncertain.value=unknown;if(cause instanceof ApiFailure&&cause.code==='JOB_REPORT_DELETED')deleted.value=true;}
 }finally{busy.value=false;}
}
onUnmounted(()=>{disposed=true;emit('busy',false);emit('pending',false);});
</script>
<template>
 <section v-if="relevant" class="job-save" aria-label="保存匹配报告">
  <h3>保存这份报告</h3><p>将岗位原文、原始材料、模型判断与建议保存到本机，之后可在报告历史中查看。每份简历最多保留 100 份报告。</p>
  <template v-if="!saved&&!deleted"><label class="job-save-label">报告名称<input v-model="label" maxlength="120" :disabled="busy||uncertain||locked" aria-label="报告名称" placeholder="例如：Java 后端岗位 · 第一轮核对"></label>
   <p v-if="attempt&&(busy||uncertain)" class="job-meta">来源修订 r{{attempt.sourceRevision}} · 报告 {{attempt.request.reportId}}</p>
   <button class="rw-primary" :disabled="busy||locked||(!uncertain&&!canSave)" @click="save">{{busy?'正在保存…':uncertain?'重试同一保存请求':'保存报告到本机'}}</button>
   <p v-if="uncertain" class="job-warning">保存结果尚未确认，报告名称与请求已锁定。重试只核对并保存同一份报告，不会调用模型；即使当前预览已过期，仍可重试确认。</p>
  </template>
  <p v-if="error" class="job-error" role="alert">{{error}}</p>
  <p v-if="saved&&!deleted" class="job-save-success" role="status">“{{saved.label}}”已保存到本机 · 来源修订 r{{saved.sourceRevision}}。</p>
  <p v-if="deleted" role="status">这份报告已删除，无法再次保存同一报告。需要新报告时，请重新预览并生成。</p>
  <button v-if="saved||deleted" :disabled="busy||locked" @click="emit('history')">查看报告历史</button>
 </section>
</template>
