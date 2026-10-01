<script setup lang="ts">
import {onMounted,onUnmounted,ref,watch,computed} from 'vue';
import {api,ApiFailure} from './api';
import QuarantineCleanup from './QuarantineCleanup.vue';
import {type QuarantineHistory,type QuarantineReceipt,isQuarantineReceipt,storageSize as size,storageDate as date,quarantineStates as states} from './storageTypes';
const props=defineProps<{locked:boolean}>(),emit=defineEmits<{busy:[boolean];changed:[]}>();
const history=ref<QuarantineHistory>(),selected=ref<QuarantineReceipt>(),confirmed=ref(false),working=ref(false),error=ref(''),message=ref('');
const cleanup=ref<QuarantineReceipt>(),cleanupBusy=ref(false);
const pending=ref<{id:string;body:{expectedDigest:string;confirm:true}}>(),uncertain=ref(false);
const locked=computed(()=>props.locked||working.value||uncertain.value||cleanupBusy.value);
let disposed=false;const controller=new AbortController();
watch(()=>working.value||uncertain.value||cleanupBusy.value,value=>emit('busy',value));
async function refresh(page=history.value?.page||0){try{
 const result=await api<QuarantineHistory>('/api/storage/quarantine?page='+page,undefined,undefined,controller.signal);
 if(!result||!Array.isArray(result.items)||!Number.isSafeInteger(result.page)||result.page<0||typeof result.hasMore!=='boolean'||!Number.isSafeInteger(result.unreadable)||result.unreadable<0)throw new ApiFailure('NETWORK_ERROR','暂存记录响应无法确认，请重新检查。');
 if(!disposed){const valid=result.items.filter(item=>item&&isQuarantineReceipt(item,item.id));history.value={...result,items:valid,unreadable:result.unreadable+result.items.length-valid.length};}
}catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}}
async function refreshCleanup(){await refresh();return history.value?.items.find(item=>item.id===cleanup.value?.id);}
async function reload(page=0){if(locked.value)return;working.value=true;error.value='';try{await refresh(page);}finally{working.value=false;}}
function review(item:QuarantineReceipt){cleanup.value=undefined;selected.value=item;confirmed.value=false;error.value='';message.value='';pending.value=undefined;}
async function restore(){
 if(working.value||props.locked||!confirmed.value||!selected.value)return;
 pending.value??={id:selected.value.id,body:{expectedDigest:selected.value.digest,confirm:true}};working.value=true;error.value='';message.value='';
 try{const receipt=await api<unknown>(`/api/storage/quarantine/${pending.value.id}/restore`,pending.value.body,undefined,controller.signal);if(disposed)return;
  if(!isQuarantineReceipt(receipt,pending.value.id))throw new ApiFailure('NETWORK_ERROR','恢复回执未能确认原记录，请保持页面并用同一请求重试。');
  uncertain.value=false;pending.value=undefined;
  if(receipt.state==='restored'&&!receipt.errorCode){message.value='已恢复原位置，文件重新进入 30 天保护期。';selected.value=undefined;confirmed.value=false;}
  else{selected.value=receipt;error.value=`恢复尚未完成：${states[receipt.state]}${receipt.errorCode?'（'+receipt.errorCode+'）':''}。文件保留，请查看记录后继续恢复。`;}
  await refresh();emit('changed');
 }catch(cause){if(disposed)return;error.value=cause instanceof Error?cause.message:String(cause);uncertain.value=cause instanceof ApiFailure&&cause.code==='NETWORK_ERROR';if(!uncertain.value){pending.value=undefined;await refresh();emit('changed');}}
 finally{working.value=false;}
}
defineExpose({refresh});onMounted(()=>refresh(0));onUnmounted(()=>{disposed=true;controller.abort();emit('busy',false);});
</script>
<template>
 <section class="quarantine-history" aria-label="暂存与清理记录"><div class="backup-history-heading"><h3>暂存与清理记录</h3><button :disabled="locked" @click="reload(0)">刷新暂存记录</button></div>
  <p>暂存仍占磁盘空间。记录仅检查结构与大小，恢复前会再次核对文件内容；不会覆盖原位置的新文件。</p>
  <p v-if="history?.unreadable" class="error" role="alert">有 {{history.unreadable}} 个暂存摘要无法读取，文件保留。此处只读提示，不会自动修复或移动文件。</p>
  <p v-if="history&&!history.items.length">本页暂无可读取的暂存记录。</p>
  <ol v-if="history" class="quarantine-records"><li v-for="item in history.items" :key="item.id" :data-testid="'quarantine-'+item.id"><div><strong>{{states[item.state]}}</strong><small>创建：{{date(item.createdAt)}} · 更新：{{date(item.updatedAt)}}</small><p>{{item.items.length}} 项 · 原批次 {{size(item.bytes)}}<span v-if="item.state==='purged'"> · 当前占用 0 B</span></p><code>{{item.id}}</code><p v-if="item.errorCode" class="backup-note">尚需处理（{{item.errorCode}}），请核对记录后继续。</p></div><div class="quarantine-actions"><a v-if="item.backupId" :href="`/api/backups/${item.backupId}/download`">下载移前完整备份</a><button v-if="!['restored','purging','purged'].includes(item.state)" :disabled="locked" @click="review(item)">查看恢复清单</button><button v-if="item.state==='quarantined'&&!item.errorCode||item.state==='purging'" :disabled="locked" @click="cleanup=item;selected=undefined;error='';message=''">{{item.state==='purging'?'继续永久清理':'查看永久清理清单'}}</button></div></li></ol>
  <div v-if="history" class="backup-history-pages" role="group" aria-label="暂存记录分页"><button :disabled="locked||history.page===0" @click="reload(history.page-1)">上一页</button><span>第 {{history.page+1}} 页</span><button :disabled="locked||!history.hasMore" @click="reload(history.page+1)">下一页</button></div>
  <div v-if="selected" class="backup-restore-review" aria-label="暂存恢复确认"><strong>恢复 {{selected.items.length}} 项 · {{size(selected.bytes)}} 到原位置</strong><p>已有文件不会被覆盖。缺失、损坏或冲突时保留暂存记录；准备中或部分完成的记录也可在核对后恢复。</p><ul class="quarantine-review-items"><li v-for="item in selected.items" :key="item.kind+item.id">{{item.kind==='image'?'图片':'PDF'}} · <code>{{item.id}}</code> · {{size(item.bytes)}}</li></ul><label class="quarantine-confirm"><input v-model="confirmed" type="checkbox" :disabled="locked" aria-label="确认恢复这些暂存文件">我确认恢复这些暂存文件</label><button class="rw-primary" :disabled="working||props.locked||!confirmed" @click="restore">{{uncertain?'用同一请求重试恢复':'确认恢复原位置'}}</button><button :disabled="locked" @click="selected=undefined;confirmed=false;error=''">取消恢复选择</button><p v-if="uncertain" class="backup-note">恢复结果尚未确认。请保持此页，用原记录与同一恢复标识重试。</p></div>
  <QuarantineCleanup v-if="cleanup" :key="cleanup.id" :receipt="cleanup" :locked="props.locked||working||uncertain" :refresh="refreshCleanup" @busy="cleanupBusy=$event" @changed="emit('changed')" @cancel="cleanup=undefined"/>
  <p v-if="working" role="status">正在核对暂存记录与恢复文件…</p><p v-if="error" class="error" role="alert">{{error}}</p><p v-if="message" class="backup-success" role="status">{{message}}</p>
 </section>
</template>
