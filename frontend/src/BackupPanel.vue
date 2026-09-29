<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref} from 'vue';
import {api} from './api';
const props=defineProps<{maxBytes:number}>();
const emit=defineEmits<{close:[];restored:[]}>();
type Created={id:string;bytes:number;resumes:number;versions:number;attachments:number;exports:number;createdAt:string};
type Restored={resumes:number;versions:number;attachments:number;exports:number};
type Item={backup:Created;kind:'manual'|'automatic'|'legacy';integrityRecorded:boolean};
type History={items:Item[];page:number;hasMore:boolean;unreadable:number};
type Automatic={state:{enabled:boolean;frequency:'daily'|'weekly';nextCheck:string|null;lastCheck:string|null;outcome:string;errorCode:string|null;lastBackup:Created|null};running:boolean;settingsReadable:boolean};
const busy=ref(false),error=ref(''),file=ref<File>(),created=ref<Created>(),restored=ref<Restored>();
const tab=ref<'backup'|'automatic'|'history'>('backup'),automatic=ref<Automatic>(),enabled=ref(false),frequency=ref<'daily'|'weekly'>('daily'),policyLoaded=ref(false),savedSettings=ref(false);
const history=ref<History>(),selected=ref<Item>();
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>();
let timer:ReturnType<typeof setInterval>,previousFocus:HTMLElement|undefined,disposed=false;
const policyDirty=computed(()=>!!automatic.value&&(enabled.value!==automatic.value.state.enabled||frequency.value!==automatic.value.state.frequency));
const outcome=computed(()=>automatic.value?.running?'正在检查并生成备份':({never:'尚未检查',created:'已生成新的完整备份',unchanged:'内容没有变化，已跳过重复备份',empty:'工作区为空，尚未生成备份',failed:'检查失败，已有备份保留'} as Record<string,string>)[automatic.value?.state.outcome||'never']);
function date(value:string|null|undefined){return value?new Date(value).toLocaleString('zh-CN'):'—';}
function size(bytes:number){return(bytes/1048576).toFixed(2)+' MiB';}
async function refreshAutomatic(){const result=await api<Automatic>('/api/backups/automatic');if(disposed)return;automatic.value=result;if(!policyLoaded.value){enabled.value=result.state.enabled;frequency.value=result.state.frequency;policyLoaded.value=true;}}
async function refreshHistory(page=history.value?.page||0){const result=await api<History>('/api/backups?page='+page);if(!disposed)history.value=result;}
async function action(operation:()=>Promise<unknown>){if(busy.value)return;busy.value=true;error.value='';try{await operation();}catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);}finally{busy.value=false;}}
async function selectTab(value:typeof tab.value){tab.value=value;selected.value=undefined;if(value==='automatic')await refreshAutomatic();if(value==='history')await refreshHistory();}
async function create(){created.value=await api('/api/backups',{});const link=document.createElement('a');link.href=`/api/backups/${created.value!.id}/download`;link.download='resume-workbench-backup.zip';link.click();}
function choose(event:Event){const chosen=(event.target as HTMLInputElement).files?.[0];file.value=chosen;error.value='';restored.value=undefined;if(chosen&&chosen.size>props.maxBytes)error.value=`备份超过 ${(props.maxBytes/1048576).toFixed(0)} MiB 限制，请选择较小的备份。`;}
async function restore(){if(!file.value||file.value.size>props.maxBytes)return;const body=new FormData();body.append('file',file.value);restored.value=await api('/api/backups/restore',body);emit('restored');}
async function restoreSaved(){if(!selected.value)return;restored.value=await api(`/api/backups/${selected.value.backup.id}/restore`,{});selected.value=undefined;emit('restored');await refreshHistory();}
async function savePolicy(){automatic.value=await api('/api/backups/automatic',{enabled:enabled.value,frequency:frequency.value},'PUT');savedSettings.value=true;}
async function checkNow(){automatic.value=await api('/api/backups/automatic/check',{});await refreshHistory(0);}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'&&!busy.value){event.preventDefault();emit('close');}
 if(event.key==='Tab'){
  const controls=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),select:not(:disabled),a[href]')||[]).filter(el=>el.getClientRects().length);
  const first=controls[0],last=controls.at(-1);if(!first){event.preventDefault();return;}
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
 }
}
onMounted(async()=>{previousFocus=document.activeElement instanceof HTMLElement?document.activeElement:undefined;window.addEventListener('keydown',keys);await nextTick();closeButton.value?.focus();timer=setInterval(()=>{if(!busy.value&&(tab.value==='automatic'||tab.value==='history'))void Promise.all([refreshAutomatic(),tab.value==='history'?refreshHistory():Promise.resolve()]).catch(cause=>{if(!disposed)error.value=cause instanceof Error?cause.message:String(cause);});},10000);});
onUnmounted(()=>{disposed=true;clearInterval(timer);window.removeEventListener('keydown',keys);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="backup-overlay"><section ref="dialog" class="backup-dialog" role="dialog" aria-modal="true" aria-labelledby="backup-title"><header><h2 id="backup-title">备份与恢复</h2><button ref="closeButton" :disabled="busy" aria-label="关闭备份与恢复" @click="emit('close')">×</button></header><p class="backup-intro">备份包含可编辑的简历、历史版本、照片、Logo 和相关 PDF。请保存在自己的设备上。</p>
  <nav class="backup-tabs" role="tablist" aria-label="备份功能"><button v-for="item in [{id:'backup',label:'备份与恢复'},{id:'automatic',label:'自动备份'},{id:'history',label:'本机历史'}] as const" :key="item.id" :id="'backup-tab-'+item.id" role="tab" :aria-selected="tab===item.id" :aria-controls="'backup-panel-'+item.id" :disabled="busy" @click="action(()=>selectTab(item.id))">{{item.label}}</button></nav>
  <div :id="'backup-panel-'+tab" role="tabpanel" :aria-labelledby="'backup-tab-'+tab">
   <template v-if="tab==='backup'"><section><h3>创建完整备份</h3><p>创建时短暂暂停修改，以保证内容和图片来自同一份工作区。</p><button class="rw-primary" :disabled="busy" @click="action(create)">{{busy?'正在处理…':'下载完整备份'}}</button><p v-if="created" class="backup-success" role="status">已创建：{{created.resumes}} 份简历、{{created.versions}} 个版本、{{created.attachments}} 张图片 · {{size(created.bytes)}}。<a :href="`/api/backups/${created.id}/download`">再次下载</a></p></section>
   <section><h3>从备份恢复</h3><p>恢复会创建新记录，保留当前简历。文件版本和校验值通过后才开始导入。</p><label class="backup-file"><span>{{file?file.name:'选择备份 ZIP 文件'}}</span><input type="file" accept=".zip,application/zip" aria-label="选择备份文件" :disabled="busy" @change="choose"></label><small>ZIP · 最多 {{(maxBytes/1048576).toFixed(0)}} MiB</small><button :disabled="busy||!file||file.size>maxBytes" @click="action(restore)">恢复为新记录</button></section></template>
   <section v-else-if="tab==='automatic'" class="backup-automatic"><h3>自动本地备份</h3><p>按频率检查已保存的数据，有变化时生成完整副本。应用关闭时暂停，重新启动后补做一次到期检查。</p>
    <template v-if="automatic"><p v-if="!automatic.settingsReadable" class="error" role="alert">原有自动备份设置无法读取，已暂停运行。请重新保存设置，已有副本保留。</p><fieldset :disabled="busy||automatic.running"><label class="backup-enable"><input v-model="enabled" type="checkbox" aria-label="启用自动备份">启用自动备份</label><label class="backup-frequency">检查频率<select v-model="frequency" aria-label="自动备份频率"><option value="daily">每天</option><option value="weekly">每周</option></select></label><div class="backup-policy-actions"><button class="rw-primary" @click="action(savePolicy)">保存自动备份设置</button><button :disabled="!automatic.state.enabled||policyDirty||!automatic.settingsReadable" @click="action(checkNow)">立即检查备份</button></div></fieldset>
    <p v-if="savedSettings" class="backup-success" role="status">设置已保存{{automatic.state.enabled?'，到期检查将在一分钟内执行':'，自动备份已关闭'}}。</p><p v-if="policyDirty" class="backup-note">修改尚未保存。</p>
    <dl class="backup-schedule" data-testid="automatic-backup-status"><div><dt>运行状态</dt><dd>{{automatic.state.enabled?outcome:'已关闭'}}</dd></div><div><dt>上次检查</dt><dd>{{date(automatic.state.lastCheck)}}</dd></div><div><dt>下次检查</dt><dd>{{date(automatic.state.nextCheck)}}</dd></div><div><dt>最近成功副本</dt><dd>{{date(automatic.state.lastBackup?.createdAt)}}</dd></div></dl><p v-if="automatic.state.errorCode" class="error" role="alert">检查未完成（{{automatic.state.errorCode}}）。设置正常时会在五分钟后重试，最近成功的副本保留。</p></template>
    <p class="backup-note">副本保存在本机 data/backups，所有已有副本保留。下载并另存到数据卷之外，可用于迁移或设备恢复。自动备份设置仅属于当前实例。</p>
   </section>
   <section v-else class="backup-history"><div class="backup-history-heading"><h3>本机备份历史</h3><button :disabled="busy" @click="action(()=>refreshHistory())">刷新历史</button></div><p>查看手动、自动及升级前的副本。恢复为新记录，当前简历保留。</p><p v-if="history?.unreadable" role="alert">本页有 {{history.unreadable}} 个副本摘要无法读取，未删除文件；可以选择其他副本或从 ZIP 恢复。</p><p v-if="history&&!history.items.length">暂时没有可读取的本机备份。</p>
    <ol v-if="history" class="backup-history-list"><li v-for="item in history.items" :key="item.backup.id" :data-testid="'backup-'+item.backup.id"><div><strong>{{date(item.backup.createdAt)}} <span>{{({manual:'手动',automatic:'自动',legacy:'旧版'} as Record<string,string>)[item.kind]}}</span></strong><small>{{item.backup.resumes}} 份简历 · {{item.backup.versions}} 个版本 · {{item.backup.attachments}} 张图片 · {{size(item.backup.bytes)}}</small></div><div class="backup-history-actions"><a :href="'/api/backups/'+item.backup.id+'/download'">下载</a><button :disabled="busy" @click="selected=item;restored=undefined">选择恢复</button></div></li></ol>
    <div v-if="history" class="backup-history-pages"><button :disabled="busy||history.page===0" @click="action(()=>refreshHistory(history!.page-1))">上一页</button><span>第 {{history.page+1}} 页</span><button :disabled="busy||!history.hasMore" @click="action(()=>refreshHistory(history!.page+1))">下一页</button></div>
    <div v-if="selected" class="backup-restore-review"><strong>从 {{date(selected.backup.createdAt)}} 恢复 {{selected.backup.resumes}} 份简历</strong><p>将新增可编辑的记录，当前简历保留。完整校验通过后才开始导入。</p><button class="rw-primary" :disabled="busy" @click="action(restoreSaved)">确认恢复为新记录</button><button :disabled="busy" @click="selected=undefined">取消选择</button></div>
   </section>
  </div>
  <p v-if="restored" class="backup-success" role="status">已恢复 {{restored.resumes}} 份简历、{{restored.versions}} 个版本和 {{restored.attachments}} 张图片。原有简历保留。</p><p v-if="error" class="error" role="alert">{{error}}</p><footer><button :disabled="busy" @click="emit('close')">{{restored?'关闭并查看简历':'关闭'}}</button></footer>
 </section></div>
</template>
