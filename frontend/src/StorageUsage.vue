<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue';
import {api,ApiFailure} from './api';
import QuarantineHistory from './QuarantineHistory.vue';
import {type StorageItem,type StorageReport as Report,type QuarantineRequest,type QuarantineReceipt,storageSize as size,storageDate as date,quarantineStates} from './storageTypes';
const emit=defineEmits<{busy:[boolean]}>();
const report=ref<Report>(),busy=ref(false),error=ref(''),filter=ref('review'),page=ref(0);
const selected=ref<StorageItem[]>([]),reviewing=ref(false),confirmed=ref(false),pending=ref<QuarantineRequest>(),historyBusy=ref(false),history=ref<InstanceType<typeof QuarantineHistory>>(),message=ref('');
const locked=computed(()=>busy.value||!!pending.value||historyBusy.value),selectionBytes=computed(()=>selected.value.reduce((sum,item)=>sum+item.bytes,0));
const labels:Record<string,string>={in_use:'仍在使用',recent:'近期文件',candidate:'清理候选',check:'需要检查',quarantined:'已暂存'};
const kinds:Record<string,string>={image:'图片',pdf:'PDF',other:'其他文件项'};
const reasons:Record<string,string>={current_image:'当前简历仍引用这张图片',historical_image:'历史版本仍引用这张图片',saved_pdf:'仍对应有效简历和历史版本',recent_file:'最近 30 天内创建或修改，暂时保留',unreferenced_image:'当前简历和历史版本都未引用，文件校验通过',deleted_pdf:'对应简历和历史版本已不存在，PDF 校验通过',unlinked_pdf:'未关联已保存的简历，PDF 校验通过',missing_image:'有图片记录或引用，但未找到完整图片目录',incomplete_image:'图片目录中的文件不完整或含额外文件',incomplete_pdf:'PDF 与导出摘要未成对保存',invalid_metadata:'文件摘要内容无效',file_mismatch:'文件大小、格式或校验值与摘要不一致',metadata_mismatch:'文件摘要与保存记录不一致',owner_mismatch:'PDF 对应的简历与历史版本关系需要核对',references_unverified:'简历引用未能完整核对，暂时保留',unexpected_file:'无法识别的文件项，需手动核对',unexpected_path:'发现链接或异常目录，未继续读取',unreadable_file:'文件无法完整读取',changed_during_scan:'检查期间文件发生变化，请重新检查',verification_limit:'超过本次文件校验体积范围，暂时保留'};
const matches=computed(()=>report.value?.items.filter(item=>filter.value==='all'||filter.value==='review'&&['candidate','check'].includes(item.status)||filter.value===item.status)||[]);
const pages=computed(()=>Math.max(1,Math.ceil(matches.value.length/20))),visible=computed(()=>matches.value.slice(page.value*20,(page.value+1)*20));
watch(filter,()=>page.value=0);
const totalBytes=computed(()=>report.value?.kinds.reduce((sum,item)=>sum+item.bytes,0)||0);
watch(locked,value=>emit('busy',value));
const key=(item:StorageItem)=>item.kind+':'+item.id;
const isSelected=(item:StorageItem)=>selected.value.some(entry=>key(entry)===key(item));
function canAdd(item:StorageItem){return item.status==='candidate'&&!!item.id&&['image','pdf'].includes(item.kind)&&selected.value.length<100&&selectionBytes.value+item.bytes<=1073741824;}
function toggle(item:StorageItem){if(locked.value)return;if(isSelected(item))selected.value=selected.value.filter(entry=>key(entry)!==key(item));else if(canAdd(item))selected.value=[...selected.value,item];reviewing.value=false;confirmed.value=false;}
const pageCandidates=computed(()=>visible.value.filter(item=>item.status==='candidate'&&!isSelected(item)));
const canSelectPage=computed(()=>pageCandidates.value.length>0&&selected.value.length+pageCandidates.value.length<=100&&selectionBytes.value+pageCandidates.value.reduce((sum,item)=>sum+item.bytes,0)<=1073741824);
function selectPage(){if(locked.value||!canSelectPage.value)return;selected.value=[...selected.value,...pageCandidates.value];reviewing.value=false;confirmed.value=false;}
async function loadReport(){const result=await api<Report>('/api/storage/preview',undefined,undefined,controller?.signal);if(!disposed){report.value=result;page.value=0;}}
let disposed=false,controller:AbortController|undefined;
async function refresh(){
 if(locked.value)return;busy.value=true;error.value='';report.value=undefined;selected.value=[];reviewing.value=false;confirmed.value=false;message.value='';page.value=0;controller=new AbortController();
 try{await loadReport();await history.value?.refresh(0);}
 catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{busy.value=false;}
}
async function quarantine(){
 if(busy.value||historyBusy.value||!confirmed.value||!report.value||!selected.value.length)return;
 pending.value??={operationId:crypto.randomUUID(),previewDigest:report.value.digest,items:selected.value.map(item=>({kind:item.kind as 'image'|'pdf',id:item.id!})),confirm:true};busy.value=true;error.value='';message.value='';
 try{const receipt=await api<QuarantineReceipt>('/api/storage/quarantine',pending.value,undefined,controller?.signal);if(disposed)return;pending.value=undefined;
  if(receipt.state==='quarantined'&&!receipt.errorCode)message.value='已暂存选中项，仍占磁盘空间。可在下方记录恢复。';else error.value=`暂存尚未完成：${quarantineStates[receipt.state]}${receipt.errorCode?'（'+receipt.errorCode+'）':''}。文件与记录保留，请在下方查看恢复清单。`;
  selected.value=[];reviewing.value=false;confirmed.value=false;await loadReport();await history.value?.refresh(0);
 }catch(cause){if(disposed)return;error.value=cause instanceof Error?cause.message:String(cause);if(!(cause instanceof ApiFailure&&cause.code==='NETWORK_ERROR')){pending.value=undefined;selected.value=[];reviewing.value=false;confirmed.value=false;try{await loadReport();await history.value?.refresh(0);}catch{/* Keep the original failure visible. */}}}
 finally{busy.value=false;}
}
async function recovered(){busy.value=true;report.value=undefined;selected.value=[];reviewing.value=false;confirmed.value=false;message.value='';error.value='';try{await loadReport();}catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}finally{busy.value=false;}}
onMounted(refresh);
onUnmounted(()=>{disposed=true;controller?.abort();emit('busy',false);});
</script>
<template>
 <section class="storage-usage" aria-label="空间占用与清理预览">
  <div class="backup-history-heading"><h3>空间占用与清理预览</h3><button :disabled="locked" @click="refresh">{{busy?'正在检查…':'重新检查空间'}}</button></div>
  <p>查看图片和导出文件的占用。检查只读取数据；选中的候选经过明确确认后可移至可恢复暂存区，暂存仍占磁盘空间。</p>
  <p v-if="busy" role="status">正在核对简历、历史版本和本机文件，检查期间短暂暂停保存。</p>
  <p v-if="error" class="error" role="alert">{{error}}</p>
  <p v-if="message" class="backup-success" role="status">{{message}}</p>
  <template v-if="report">
   <p class="storage-total" data-testid="storage-total">已统计 {{size(totalBytes)}} · {{report.kinds.map(item=>`${item.count}${item.key==='image'?' 张图片':item.key==='pdf'?' 份 PDF':' 个其他文件项'}`).join(' / ')}}</p>
   <div class="storage-counts"><article v-for="item in report.statuses" :key="item.key" :data-testid="'storage-summary-'+item.key"><span>{{labels[item.key]}}</span><strong>{{item.count}} <small>项</small></strong><span>{{size(item.bytes)}}</span></article></div>
   <p v-if="!report.referencesVerified" class="error" role="alert">简历与历史版本的图片引用未能完整核对，本次没有给出清理候选。请保留数据并检查工作区。</p>
   <p v-if="!report.bytesComplete" class="backup-note" role="note">部分异常目录无法统计内部大小，实际占用可能高于上方数值。</p>
   <p class="backup-note">清理候选须超过 {{report.graceDays}} 天、没有有效引用且文件校验完整。备份 ZIP 和模型配置不在本次检查范围内。</p>
   <label class="storage-filter">查看文件范围<select v-model="filter" :disabled="locked" aria-label="空间检查文件范围"><option value="review">候选与需检查</option><option value="candidate">清理候选</option><option value="check">需要检查</option><option value="recent">近期文件</option><option value="in_use">仍在使用</option><option value="quarantined">已暂存</option><option value="all">全部</option></select></label>
   <div class="quarantine-selection" data-testid="quarantine-selection"><strong>已选 {{selected.length}} 项 · {{size(selectionBytes)}}</strong><p>每批最多 100 项、1 GiB。跨页保留选择，只有清理候选可选择。</p><div class="quarantine-actions"><button :disabled="locked||!canSelectPage" @click="selectPage">选择本页候选</button><button :disabled="locked||!selected.length" @click="selected=[];reviewing=false;confirmed=false">清空选择</button><button :disabled="locked||!selected.length" @click="reviewing=true;confirmed=false;error='';message=''">查看暂存清单</button></div></div>
   <p v-if="!visible.length" class="backup-note">当前范围没有可显示的文件项。</p>
   <ol v-else class="storage-items"><li v-for="(item,index) in visible" :key="item.kind+':'+item.id+':'+item.status+':'+item.reason+':'+index" :data-testid="item.id?'storage-item-'+item.id:undefined"><div class="storage-item-heading"><input v-if="item.status==='candidate'&&item.id&&item.kind!=='other'" type="checkbox" :aria-label="item.kind==='image'?'选择这张候选图片':'选择这份候选 PDF'" :checked="isSelected(item)" :disabled="locked||!isSelected(item)&&!canAdd(item)" @change="toggle(item)"><strong>{{kinds[item.kind]}}</strong><span :class="'storage-status-'+item.status">{{labels[item.status]}}</span><b>{{size(item.bytes)}}</b></div><code v-if="item.id">{{item.id}}</code><small v-else>未识别的文件项</small><p>{{item.reason==='quarantined_file'?'文件已移至可恢复暂存区，仍占磁盘空间':item.reason==='incomplete_quarantine'?'暂存记录不完整，请在下方查看恢复清单':reasons[item.reason]||'需要手动核对，文件已保留'}}</p><small>最近时间：{{date(item.lastModified)}}</small></li></ol>
   <div v-if="matches.length" class="backup-history-pages" role="group" aria-label="空间清单分页"><button :disabled="locked||page===0" @click="page--">上一页</button><span>第 {{page+1}} / {{pages}} 页 · {{matches.length}} 项</span><button :disabled="locked||page+1>=pages" @click="page++">下一页</button></div>
   <div v-if="reviewing" class="backup-restore-review" aria-label="暂存确认"><strong>暂存 {{selected.length}} 项 · {{size(selectionBytes)}}</strong><p>暂存仍占磁盘空间，不是永久删除。移动前会重新核对并生成完整工作区 ZIP；ZIP 只包含有效简历与历史关联文件，孤立文件由暂存区独立保留。可在下方记录恢复原位置，不覆盖已有文件。</p><ul class="quarantine-review-items"><li v-for="item in selected" :key="key(item)">{{kinds[item.kind]}} · <code>{{item.id}}</code> · {{size(item.bytes)}}</li></ul><label class="quarantine-confirm"><input v-model="confirmed" type="checkbox" :disabled="locked" aria-label="确认暂存这些未使用文件">我确认暂存这些未使用文件</label><button class="rw-primary" :disabled="busy||historyBusy||!confirmed" @click="quarantine">{{pending?'用同一清单重试暂存':'确认暂存选中项'}}</button><button :disabled="locked" @click="reviewing=false;confirmed=false">返回选择</button><p v-if="pending" class="backup-note">暂存结果尚未确认。选择与检查摘要已锁定，请保持此页用同一清单重试，避免生成另一批操作。</p></div>
   <p class="backup-note" data-testid="storage-checked-at">检查时间：{{date(report.checkedAt)}}。保存、上传或导出后，可重新检查。</p>
  </template>
  <QuarantineHistory ref="history" :locked="busy||!!pending" @busy="historyBusy=$event" @changed="recovered"/>
 </section>
</template>
