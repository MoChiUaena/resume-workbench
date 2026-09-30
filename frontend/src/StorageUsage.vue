<script setup lang="ts">
import {computed,onMounted,onUnmounted,ref,watch} from 'vue';
import {api} from './api';
type Item={kind:'image'|'pdf'|'other';id:string|null;status:string;reason:string;bytes:number;lastModified:string|null};
type Count={key:string;count:number;bytes:number};
type Report={checkedAt:string;graceDays:number;referencesVerified:boolean;bytesComplete:boolean;kinds:Count[];statuses:Count[];items:Item[];digest:string};
const emit=defineEmits<{busy:[boolean]}>();
const report=ref<Report>(),busy=ref(false),error=ref(''),filter=ref('review'),page=ref(0);
const labels:Record<string,string>={in_use:'仍在使用',recent:'近期文件',candidate:'清理候选',check:'需要检查'};
const kinds:Record<string,string>={image:'图片',pdf:'PDF',other:'其他文件项'};
const reasons:Record<string,string>={current_image:'当前简历仍引用这张图片',historical_image:'历史版本仍引用这张图片',saved_pdf:'仍对应有效简历和历史版本',recent_file:'最近 30 天内创建或修改，暂时保留',unreferenced_image:'当前简历和历史版本都未引用，文件校验通过',deleted_pdf:'对应简历和历史版本已不存在，PDF 校验通过',unlinked_pdf:'未关联已保存的简历，PDF 校验通过',missing_image:'有图片记录或引用，但未找到完整图片目录',incomplete_image:'图片目录中的文件不完整或含额外文件',incomplete_pdf:'PDF 与导出摘要未成对保存',invalid_metadata:'文件摘要内容无效',file_mismatch:'文件大小、格式或校验值与摘要不一致',metadata_mismatch:'文件摘要与保存记录不一致',owner_mismatch:'PDF 对应的简历与历史版本关系需要核对',references_unverified:'简历引用未能完整核对，暂时保留',unexpected_file:'无法识别的文件项，需手动核对',unexpected_path:'发现链接或异常目录，未继续读取',unreadable_file:'文件无法完整读取',changed_during_scan:'检查期间文件发生变化，请重新检查',verification_limit:'超过本次文件校验体积范围，暂时保留'};
const matches=computed(()=>report.value?.items.filter(item=>filter.value==='all'||filter.value==='review'&&['candidate','check'].includes(item.status)||filter.value===item.status)||[]);
const pages=computed(()=>Math.max(1,Math.ceil(matches.value.length/20))),visible=computed(()=>matches.value.slice(page.value*20,(page.value+1)*20));
watch(filter,()=>page.value=0);
const totalBytes=computed(()=>report.value?.kinds.reduce((sum,item)=>sum+item.bytes,0)||0);
let disposed=false,controller:AbortController|undefined;
function size(bytes:number){return bytes<1024?`${bytes} B`:bytes<1048576?`${(bytes/1024).toFixed(1)} KiB`:`${(bytes/1048576).toFixed(2)} MiB`;}
function date(value:string|null){return value?new Date(value).toLocaleString('zh-CN'):'时间未知';}
async function refresh(){
 if(busy.value)return;busy.value=true;emit('busy',true);error.value='';report.value=undefined;page.value=0;controller=new AbortController();
 try{const result=await api<Report>('/api/storage/preview',undefined,undefined,controller.signal);if(!disposed)report.value=result;}
 catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}
 finally{busy.value=false;emit('busy',false);}
}
onMounted(refresh);
onUnmounted(()=>{disposed=true;controller?.abort();emit('busy',false);});
</script>
<template>
 <section class="storage-usage" aria-label="空间占用与清理预览">
  <div class="backup-history-heading"><h3>空间占用与清理预览</h3><button :disabled="busy" @click="refresh">{{busy?'正在检查…':'重新检查空间'}}</button></div>
  <p>查看图片和导出文件的占用，以及已失去引用的文件。这一页只生成检查清单，所有文件继续保留。</p>
  <p v-if="busy" role="status">正在核对简历、历史版本和本机文件，检查期间短暂暂停保存。</p>
  <p v-if="error" class="error" role="alert">{{error}}</p>
  <template v-if="report">
   <p class="storage-total" data-testid="storage-total">已统计 {{size(totalBytes)}} · {{report.kinds.map(item=>`${item.count}${item.key==='image'?' 张图片':item.key==='pdf'?' 份 PDF':' 个其他文件项'}`).join(' / ')}}</p>
   <div class="storage-counts"><article v-for="item in report.statuses" :key="item.key" :data-testid="'storage-summary-'+item.key"><span>{{labels[item.key]}}</span><strong>{{item.count}} <small>项</small></strong><span>{{size(item.bytes)}}</span></article></div>
   <p v-if="!report.referencesVerified" class="error" role="alert">简历与历史版本的图片引用未能完整核对，本次没有给出清理候选。请保留数据并检查工作区。</p>
   <p v-if="!report.bytesComplete" class="backup-note" role="note">部分异常目录无法统计内部大小，实际占用可能高于上方数值。</p>
   <p class="backup-note">清理候选须超过 {{report.graceDays}} 天、没有有效引用且文件校验完整。备份 ZIP 和模型配置不在本次检查范围内。</p>
   <label class="storage-filter">查看文件范围<select v-model="filter" aria-label="空间检查文件范围"><option value="review">候选与需检查</option><option value="candidate">清理候选</option><option value="check">需要检查</option><option value="recent">近期文件</option><option value="in_use">仍在使用</option><option value="all">全部</option></select></label>
   <p v-if="!visible.length" class="backup-note">当前范围没有可显示的文件项。</p>
   <ol v-else class="storage-items"><li v-for="(item,index) in visible" :key="item.kind+':'+item.id+':'+index" :data-testid="item.id?'storage-item-'+item.id:undefined"><div class="storage-item-heading"><strong>{{kinds[item.kind]}}</strong><span :class="'storage-status-'+item.status">{{labels[item.status]}}</span><b>{{size(item.bytes)}}</b></div><code v-if="item.id">{{item.id}}</code><small v-else>未识别的文件项</small><p>{{reasons[item.reason]||'需要手动核对，文件已保留'}}</p><small>最近时间：{{date(item.lastModified)}}</small></li></ol>
   <div v-if="matches.length" class="backup-history-pages" role="group" aria-label="空间清单分页"><button :disabled="page===0" @click="page--">上一页</button><span>第 {{page+1}} / {{pages}} 页 · {{matches.length}} 项</span><button :disabled="page+1>=pages" @click="page++">下一页</button></div>
   <p class="backup-note" data-testid="storage-checked-at">检查时间：{{date(report.checkedAt)}}。保存、上传或导出后，可重新检查。</p>
  </template>
 </section>
</template>
