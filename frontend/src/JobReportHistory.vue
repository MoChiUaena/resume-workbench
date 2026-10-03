<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref,watch} from 'vue';
import {ApiFailure} from './api';
import {savedJobReports,savedJobReport,deleteJobReport,type SavedJobReportPage,type SavedJobReportDetail,type JobSource} from './jobMatchApi';
const props=defineProps<{resumeId:string;sourceRevision:number;locked?:boolean}>();
const emit=defineEmits<{locked:[boolean];deleted:[string]}>();
const listing=ref<SavedJobReportPage>(),detail=ref<SavedJobReportDetail>(),loading=ref(false),error=ref(''),message=ref('');
const deleting=ref<SavedJobReportDetail>(),confirmed=ref(false),deleteBusy=ref(false),deleteUncertain=ref(false),deleteError=ref('');
const heading=ref<HTMLElement>(),detailHeading=ref<HTMLElement>(),confirmInput=ref<HTMLInputElement>();
const locked=computed(()=>!!props.locked||!!deleting.value),sources=computed(()=>new Map(detail.value?.snapshot.sources.map(source=>[source.id,source])||[]));
const statusLabel={supported:'有依据',partial:'部分依据',missing:'缺少依据'};
let disposed=false,sequence=0,controller:AbortController|undefined,detailTrigger:HTMLElement|undefined,deleteTrigger:HTMLElement|undefined;
watch(()=>!!deleting.value,value=>emit('locked',value));
function sourceLabel(source:JobSource){return source.sectionTitle+' / '+(source.entryTitle.trim()||'未命名条目')+' / '+(source.paragraph<0?'条目标题与说明':'第 '+(source.paragraph+1)+' 段');}
async function refresh(page=listing.value?.page||0,preserveMessage=false){
 if(locked.value)return;
 const active=++sequence;controller?.abort();controller=new AbortController();loading.value=true;error.value='';detail.value=undefined;if(!preserveMessage)message.value='';
 try{
  const result=await savedJobReports(props.resumeId,page,controller.signal);if(disposed||active!==sequence)return;
  listing.value=result;
  if(page>0&&page>=result.pages){loading.value=false;await refresh(Math.max(0,result.pages-1),preserveMessage);return;}
 }catch(cause){if(!disposed&&active===sequence)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{if(!disposed&&active===sequence)loading.value=false;}
}
async function open(id:string,event:Event){
 if(locked.value||loading.value)return;
 detailTrigger=event.currentTarget instanceof HTMLElement?event.currentTarget:undefined;
 const active=++sequence;controller?.abort();controller=new AbortController();loading.value=true;detail.value=undefined;error.value='';message.value='';
 try{
  const result=await savedJobReport(props.resumeId,id,controller.signal);if(disposed||active!==sequence)return;
  detail.value=result;await nextTick();detailHeading.value?.focus();
 }catch(cause){if(!disposed&&active===sequence)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{if(!disposed&&active===sequence)loading.value=false;}
}
async function closeDetail(){if(locked.value)return;detail.value=undefined;await nextTick();if(detailTrigger?.isConnected)detailTrigger.focus();else heading.value?.focus();}
async function reviewDelete(event:Event){
 if(locked.value||loading.value||!detail.value)return;
 deleteTrigger=event.currentTarget instanceof HTMLElement?event.currentTarget:undefined;deleting.value=detail.value;confirmed.value=false;deleteUncertain.value=false;deleteError.value='';
 await nextTick();confirmInput.value?.focus();
}
async function cancelDelete(){
 if(deleteBusy.value||deleteUncertain.value)return;
 deleting.value=undefined;confirmed.value=false;deleteError.value='';await nextTick();if(deleteTrigger?.isConnected)deleteTrigger.focus();else heading.value?.focus();
}
async function remove(){
 if(deleteBusy.value||props.locked||!deleting.value||!confirmed.value)return;
 const target=deleting.value;deleteBusy.value=true;deleteError.value='';
 try{
  await deleteJobReport(props.resumeId,target.id);if(disposed)return;
  deleting.value=undefined;confirmed.value=false;deleteUncertain.value=false;detail.value=undefined;message.value='已删除报告“'+target.label+'”。';emit('deleted',target.id);
  await refresh(listing.value?.page||0,true);await nextTick();heading.value?.focus();
 }catch(cause){if(!disposed){deleteError.value=cause instanceof Error?cause.message:String(cause);deleteUncertain.value=!(cause instanceof ApiFailure)||cause.code==='NETWORK_ERROR';}}
 finally{deleteBusy.value=false;}
}
onMounted(async()=>{await nextTick();heading.value?.focus();await refresh(0);});
onUnmounted(()=>{disposed=true;sequence++;controller?.abort();emit('locked',false);});
</script>
<template>
 <section class="job-history" aria-label="已保存报告历史" :aria-busy="loading">
  <div :inert="!!deleting"><div class="job-history-heading"><h3 ref="heading" tabindex="-1">已保存报告</h3><button :disabled="loading||locked" @click="refresh()">刷新报告历史</button></div>
   <p class="job-intro">保存的报告保留当时的岗位原文、简历材料与模型判断。查看历史不会发送内容或修改当前简历；继续修改时，需要重新分析当前简历。</p>
   <p v-if="loading" role="status">正在读取已保存报告…</p><p v-if="error" class="job-error" role="alert">{{error}}</p><p v-if="message" class="job-save-success" role="status">{{message}}</p>
   <template v-if="listing"><p class="job-meta">共 {{listing.total}} / 100 份 · 每页 20 份</p><p v-if="!listing.total&&!loading">还没有保存报告。生成并核对后，可为报告命名并保存到本机。</p>
    <ol v-else class="job-history-list"><li v-for="item in listing.items" :key="item.id"><div><strong>{{item.label}}</strong><p class="job-meta">来源修订 r{{item.sourceRevision}} · {{new Date(item.createdAt).toLocaleString('zh-CN')}}<br>{{item.profileName}} · {{item.provider}} · {{item.model}}</p><span v-if="item.sourceChanged||item.sourceRevision!==sourceRevision" class="job-warning">当前简历已改变 · 历史材料</span></div><button :disabled="loading||locked" :aria-label="'查看报告 '+item.label" :aria-pressed="detail?.id===item.id" @click="open(item.id,$event)">查看报告</button></li></ol>
    <div v-if="listing.total" class="job-history-pages" role="group" aria-label="报告历史分页"><button :disabled="loading||locked||listing.page===0" @click="refresh(listing.page-1)">上一页</button><span>第 {{listing.page+1}} / {{listing.pages}} 页 · {{listing.total}} 份</span><button :disabled="loading||locked||listing.page+1>=listing.pages" @click="refresh(listing.page+1)">下一页</button></div>
   </template>
   <section v-if="detail" class="job-report job-archive" aria-label="已保存报告详情"><div class="job-history-heading"><h3 ref="detailHeading" tabindex="-1">{{detail.label}}</h3><button :disabled="loading||locked" @click="closeDetail">收起报告详情</button></div>
    <p class="job-meta">来源修订 r{{detail.sourceRevision}} · 保存于 {{new Date(detail.createdAt).toLocaleString('zh-CN')}}</p><p v-if="detail.sourceChanged||detail.sourceRevision!==sourceRevision" class="job-warning">当前简历已改变，以下内容引用的是保存时的历史材料。</p>
    <p class="job-warning">以下是当时的模型判断，需对照岗位原文和原始材料逐项核实；不代表招聘方评价。</p><p>实际生成模型：{{detail.profileName}} · {{detail.provider}} · {{detail.model}}<br><span class="job-destination">{{detail.snapshot.destination}}</span></p>
    <h4>岗位要求原文</h4><blockquote class="job-archive-text">{{detail.snapshot.jobDescription}}</blockquote>
    <div aria-label="保存时的原始材料"><h4>保存时的原始材料</h4><details v-for="source in detail.snapshot.sources" :key="source.id" class="job-archive-source"><summary>{{sourceLabel(source)}}</summary><blockquote class="job-archive-text">{{source.text}}</blockquote></details></div>
    <h4>匹配判断与引用</h4><ol class="job-items"><li v-for="(item,index) in detail.snapshot.items" :key="index"><div class="job-item-heading"><strong>{{item.requirement}}</strong><span :class="'job-status-'+item.status">{{statusLabel[item.status]}}</span></div><ul v-if="item.evidence.length"><li v-for="(evidence,number) in item.evidence" :key="number"><small>{{sourceLabel(sources.get(evidence.sourceId)!)}}</small><blockquote>{{evidence.quote}}</blockquote></li></ul><p v-else>所选材料中未找到模型可引用的依据。</p><p v-if="item.advice">补充建议：{{item.advice}}</p></li></ol>
    <div v-if="detail.snapshot.suggestions.length" class="job-suggestions" aria-label="保存时的修改建议"><h4>保存时的修改建议（只读）</h4><p>这些建议用于回顾。需要修改当前简历时，请重新预览、确认发送并逐条审核新建议。</p><article v-for="(suggestion,index) in detail.snapshot.suggestions" :key="index"><p>{{sourceLabel(sources.get(suggestion.sourceId)!)}}</p><h4>当时的原文</h4><blockquote class="job-archive-text">{{sources.get(suggestion.sourceId)!.text}}</blockquote><h4>当时的建议</h4><p>{{suggestion.replacement}}</p></article></div>
    <button class="job-delete" :disabled="loading||locked" @click="reviewDelete($event)">删除这份报告</button>
   </section>
  </div>
  <section v-if="deleting" class="job-delete-review" aria-label="删除报告确认" @keydown.esc.stop.prevent="cancelDelete"><h3>删除“{{deleting.label}}”</h3><p>这份报告及其保存的材料将从报告历史中删除，无法撤销。当前简历与其历史版本保留。</p>
   <p class="job-meta">来源修订 r{{deleting.sourceRevision}} · {{deleting.id}}</p><label class="job-check"><input ref="confirmInput" v-model="confirmed" type="checkbox" :disabled="deleteBusy||deleteUncertain" aria-label="确认删除这份已保存报告">我确认删除这份已保存报告</label>
   <p v-if="deleteError" class="job-error" role="alert">{{deleteError}}</p><p v-if="deleteUncertain" class="job-warning">删除结果尚未确认，请保持此页并重试删除同一份报告。</p>
   <div class="job-history-actions"><button class="job-delete" :disabled="deleteBusy||props.locked||!confirmed" @click="remove">{{deleteBusy?'正在删除…':deleteUncertain?'重试删除同一报告':'确认删除报告'}}</button><button :disabled="deleteBusy||deleteUncertain" @click="cancelDelete">取消删除</button></div>
  </section>
 </section>
</template>
