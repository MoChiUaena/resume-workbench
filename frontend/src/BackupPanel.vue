<script setup lang="ts">
import {ref,onMounted,onUnmounted} from 'vue';
import {api} from './api';
const props=defineProps<{maxBytes:number}>();
const emit=defineEmits<{close:[];restored:[]} >();
const busy=ref(false),error=ref(''),file=ref<File>(),created=ref<{id:string;bytes:number;resumes:number;versions:number;attachments:number;exports:number}>(),restored=ref<{resumes:number;versions:number;attachments:number;exports:number}>();
async function create(){busy.value=true;error.value='';try{created.value=await api('/api/backups',{});const link=document.createElement('a');link.href=`/api/backups/${created.value!.id}/download`;link.download='resume-workbench-backup.zip';link.click();}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
function choose(event:Event){const selected=(event.target as HTMLInputElement).files?.[0];file.value=selected;error.value='';restored.value=undefined;if(selected&&selected.size>props.maxBytes)error.value=`备份超过 ${(props.maxBytes/1048576).toFixed(0)} MiB 限制，请选择较小的备份。`;}
async function restore(){if(!file.value||file.value.size>props.maxBytes)return;busy.value=true;error.value='';try{const body=new FormData();body.append('file',file.value);restored.value=await api('/api/backups/restore',body);emit('restored');}catch(e){error.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
function escape(event:KeyboardEvent){if(event.key==='Escape'&&!busy.value)emit('close');}
onMounted(()=>window.addEventListener('keydown',escape));onUnmounted(()=>window.removeEventListener('keydown',escape));
</script>
<template>
 <div class="backup-overlay"><section class="backup-dialog" role="dialog" aria-modal="true" aria-labelledby="backup-title"><header><h2 id="backup-title">备份与恢复</h2><button :disabled="busy" aria-label="关闭备份与恢复" @click="emit('close')">×</button></header><p class="backup-intro">备份包含可编辑的简历、历史版本、照片、Logo 和相关 PDF。请保存在自己的设备上。</p>
  <section><h3>创建完整备份</h3><p>创建时短暂暂停修改，以保证内容和图片来自同一份工作区。</p><button class="rw-primary" :disabled="busy" @click="create">{{busy?'正在处理…':'下载完整备份'}}</button><p v-if="created" class="backup-success" role="status">已创建：{{created.resumes}} 份简历、{{created.versions}} 个版本、{{created.attachments}} 张图片 · {{(created.bytes/1048576).toFixed(2)}} MiB。<a :href="`/api/backups/${created.id}/download`">再次下载</a></p></section>
  <section><h3>从备份恢复</h3><p>恢复会创建新记录，保留当前简历。文件版本和校验值通过后才开始导入。</p><label class="backup-file"><span>{{file?file.name:'选择备份 ZIP 文件'}}</span><input type="file" accept=".zip,application/zip" aria-label="选择备份文件" :disabled="busy" @change="choose"></label><small>ZIP · 最多 {{(maxBytes/1048576).toFixed(0)}} MiB</small><button :disabled="busy||!file||file.size>maxBytes" @click="restore">恢复为新记录</button><p v-if="restored" class="backup-success" role="status">已恢复 {{restored.resumes}} 份简历、{{restored.versions}} 个版本和 {{restored.attachments}} 张图片。原有简历保留。</p></section>
  <p v-if="error" class="error" role="alert">{{error}}</p><footer><button :disabled="busy" @click="emit('close')">{{restored?'关闭并查看简历':'关闭'}}</button></footer>
 </section></div>
</template>
