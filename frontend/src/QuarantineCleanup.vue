<script setup lang="ts">
import {computed,onUnmounted,ref,watch} from 'vue';
import {api,ApiFailure} from './api';
import {isFileBackupReceipt,isQuarantineReceipt,type QuarantineReceipt,type FileBackupReceipt,type FileBackupRequest,type PurgeRequest,storageSize as size} from './storageTypes';
const props=defineProps<{receipt:QuarantineReceipt;locked:boolean;refresh:()=>Promise<QuarantineReceipt|undefined>}>(),emit=defineEmits<{busy:[boolean];changed:[];cancel:[]}>();
const current=ref(props.receipt),ticket=ref<FileBackupReceipt>(),saved=ref(false),confirmation=ref(''),working=ref(false),uncertain=ref(false),error=ref(''),message=ref('');
const pending=ref<{action:'export';body:FileBackupRequest}|{action:'purge';body:PurgeRequest}>();
const locked=computed(()=>props.locked||working.value||uncertain.value),proof=computed(()=>current.value.cleanup||ticket.value&&{exportId:ticket.value.id,sha256:ticket.value.sha256,bytes:ticket.value.bytes});
const now=ref(Date.now()),expiryTimer=setInterval(()=>now.value=Date.now(),1000);
const downloaded=computed(()=>!!current.value.cleanup||!!ticket.value?.downloaded&&Date.parse(ticket.value.expiresAt)>now.value);
const canPurge=computed(()=>['quarantined','purging'].includes(current.value.state)&&downloaded.value&&!!proof.value&&saved.value&&confirmation.value===current.value.id.slice(-6));
const knownErrors=new Set(['QUARANTINE_CONFIRM_REQUIRED','QUARANTINE_BACKUP_REQUIRED','QUARANTINE_CONFIRMATION_MISMATCH','QUARANTINE_INVALID','STORAGE_SCAN_LIMIT','QUARANTINE_BACKUP_NOT_FOUND','QUARANTINE_BACKUP_EXPIRED','QUARANTINE_DOWNLOAD_REQUIRED','QUARANTINE_CONFLICT','QUARANTINE_REFERENCED','QUARANTINE_REFERENCES_UNVERIFIED','QUARANTINE_ARCHIVE_LIMIT','QUARANTINE_UNREADABLE','QUARANTINE_IO_FAILED','STORAGE_SCAN_FAILED','WORKSPACE_BUSY','INVALID_INPUT','LOCAL_REQUEST_REQUIRED']);
let disposed=false;const controller=new AbortController();
watch(()=>working.value||uncertain.value,value=>emit('busy',value));
function unknown(message:string):never{throw new ApiFailure('NETWORK_ERROR',message+'请保持页面并用同一请求重试。');}
async function reconcile(){await props.refresh();if(!disposed)emit('changed');}
async function reconcileKnownPurgeError(body:PurgeRequest){
 const previous=proof.value;let latest:QuarantineReceipt|undefined;
 try{latest=await props.refresh();}catch{return false;}
 if(disposed||!latest||!isQuarantineReceipt(latest,current.value.id)||latest.digest!==body.expectedDigest)return false;
 if(latest.state==='quarantined'&&latest.cleanup==null){emit('changed');return true;}
 if(!['purging','purged'].includes(latest.state)||latest.cleanup?.exportId!==body.exportId||latest.cleanup.sha256!==body.archiveSha256||latest.cleanup.bytes!==previous?.bytes)return false;
 current.value=latest;saved.value=false;confirmation.value='';emit('changed');return true;
}
async function submit(action:'export'|'purge'){
 if(working.value||props.locked||uncertain.value&&pending.value?.action!==action)return;
 if(!pending.value){
  if(action==='export'){if(current.value.state!=='quarantined')return;pending.value={action,body:{requestId:crypto.randomUUID(),expectedDigest:current.value.digest,confirm:true}};}
  else{if(!canPurge.value||!proof.value)return;pending.value={action,body:{expectedDigest:current.value.digest,exportId:proof.value.exportId,archiveSha256:proof.value.sha256,confirm:true,backupSaved:true,confirmation:confirmation.value}};}
 }
 const request=pending.value;working.value=true;error.value='';message.value='';
 try{
  const value=await api<unknown>(`/api/storage/quarantine/${current.value.id}/${request.action==='export'?'file-backups':'purge'}`,request.body,undefined,controller.signal);if(disposed)return;
  if(request.action==='export'){
   if(!isFileBackupReceipt(value,request.body.requestId,current.value.id,request.body.expectedDigest))unknown('文件 ZIP 回执无法确认原批次。');
   if(ticket.value?.id!==value.id){saved.value=false;confirmation.value='';}
   ticket.value=value;message.value='ZIP 已生成，请下载并保存副本，再检查下载完成状态。';
  }else{
   if(!isQuarantineReceipt(value,current.value.id)||!['purging','purged'].includes(value.state)||value.digest!==request.body.expectedDigest||value.cleanup?.exportId!==request.body.exportId||value.cleanup.sha256!==request.body.archiveSha256||value.cleanup.bytes!==proof.value?.bytes)unknown('永久清理回执无法确认原批次与下载证明。');
   current.value=value;saved.value=false;confirmation.value='';
   if(value.state==='purged'){message.value='已永久清理。当前占用 0 B，无法从此记录恢复。';ticket.value=undefined;}
   else error.value='永久清理尚未完成（'+(value.errorCode||'QUARANTINE_PURGE_FAILED')+'）。请核对记录、再次确认后继续。';
  }
  uncertain.value=false;pending.value=undefined;await reconcile();
 }catch(cause){
  if(disposed)return;error.value=cause instanceof Error?cause.message:String(cause);
  const known=cause instanceof ApiFailure&&knownErrors.has(cause.code);
  if(!known){uncertain.value=true;return;}
  if(request.action==='purge'&&!await reconcileKnownPurgeError(request.body)){
   uncertain.value=true;error.value+=' 暂存记录未能确认本批次，请保持页面并用同一请求重试。';return;
  }
  if(request.action==='export')await reconcile();
  uncertain.value=false;pending.value=undefined;
  if(['QUARANTINE_BACKUP_EXPIRED','QUARANTINE_BACKUP_NOT_FOUND'].includes(cause.code))ticket.value=undefined;
 }finally{working.value=false;}
}
async function checkDownload(){
 if(locked.value||!ticket.value)return;working.value=true;error.value='';ticket.value={...ticket.value,downloaded:false};
 try{const value=await api<unknown>(`/api/storage/quarantine/${current.value.id}/file-backups/${ticket.value.id}`,undefined,undefined,controller.signal);if(disposed)return;
  if(!isFileBackupReceipt(value,ticket.value.id,current.value.id,current.value.digest)||value.sha256!==ticket.value.sha256||value.bytes!==ticket.value.bytes)throw new ApiFailure('NETWORK_ERROR','下载状态无法核对，请再次检查。');ticket.value=value;
 }catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}finally{working.value=false;}
}
onUnmounted(()=>{disposed=true;clearInterval(expiryTimer);controller.abort();emit('busy',false);});
</script>
<template>
 <div class="backup-restore-review quarantine-cleanup" aria-label="永久清理确认">
  <strong>{{current.state==='purged'?'已永久清理':current.state==='purging'?'继续永久清理':'永久清理清单'}} · {{current.items.length}} 项 · 原批次 {{size(current.bytes)}}</strong>
  <p v-if="current.state==='purged'">当前占用 0 B。此记录仅保留清理摘要，不能恢复文件。</p>
  <template v-else>
   <p>永久清理不可撤销，清理后无法从暂存记录恢复。文件 ZIP 包含本批次图片原图、处理后图片和 PDF；需要时可解压取回文件。它不能导入为完整工作区，移前完整备份也不包含这些孤立文件。</p>
   <ul class="quarantine-review-items"><li v-for="item in current.items" :key="item.kind+item.id">{{item.kind==='image'?'图片':'PDF'}} · <code>{{item.id}}</code> · 原文件 {{size(item.bytes)}}</li></ul>
   <template v-if="!current.cleanup">
    <button v-if="!ticket||Date.parse(ticket.expiresAt)<=now" :disabled="working||props.locked||uncertain&&pending?.action!=='export'" @click="submit('export')">{{uncertain&&pending?.action==='export'?'用同一请求重试生成 ZIP':'生成暂存文件 ZIP'}}</button>
    <template v-if="ticket"><p>文件 ZIP {{size(ticket.bytes)}}。下载确认有效期至 {{new Date(ticket.expiresAt).toLocaleString('zh-CN')}}。</p><a v-if="!locked" :href="`/api/storage/quarantine/${current.id}/file-backups/${ticket.id}/download`">下载暂存文件 ZIP</a><button :disabled="locked" @click="checkDownload">检查下载完成状态</button><p role="status">{{downloaded?'服务端已确认完整下载，请核对已保存的副本。':'尚未确认完整下载。下载中断或未完成时不能永久清理。'}}</p></template>
   </template>
   <p v-else class="backup-note">先前下载证明已保留。本批次清理尚未完成；请再次核对已保存的 ZIP 并确认继续，剩余文件不会自动清理。</p>
   <label class="quarantine-confirm"><input v-model="saved" type="checkbox" :disabled="locked" aria-label="我已将 ZIP 保存到其他位置并确认可用">我已将 ZIP 保存到其他位置并确认可用</label>
   <label class="quarantine-code">输入批次末六位 <code>{{current.id.slice(-6)}}</code><input v-model="confirmation" :disabled="locked" autocomplete="off" spellcheck="false" aria-label="输入批次末六位"></label>
   <button class="quarantine-destructive" :disabled="working||props.locked||!pending&&!canPurge||uncertain&&pending?.action!=='purge'" @click="submit('purge')">{{uncertain&&pending?.action==='purge'?'用同一请求重试永久清理':'确认永久清理'}}</button>
  </template>
  <button :disabled="locked" @click="emit('cancel')">{{current.state==='purged'?'关闭清理摘要':'取消永久清理选择'}}</button>
  <p v-if="working" role="status">正在核对文件 ZIP 与永久清理记录…</p><p v-if="uncertain" class="backup-note">操作结果尚未确认。批次、下载标识和确认内容已锁定，请用原请求重试。</p><p v-if="error" class="error" role="alert">{{error}}</p><p v-if="message" class="backup-success" role="status">{{message}}</p>
 </div>
</template>
