<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import type { ResumeDocument, VersionDetail } from './api';
import { compareDrafts, type DiffCategory } from './versionDiff';
const props=defineProps<{version:VersionDetail;title:string;document:ResumeDocument;revision:number;dirty:boolean;busy:boolean;error:string;returnFocus?:HTMLElement}>();
const emit=defineEmits<{close:[];restore:[]}>();
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>(),filter=ref<DiffCategory|'all'>('all');
const groups=computed(()=>compareDrafts(props.version,{title:props.title,document:props.document}));
const filtered=computed(()=>groups.value.filter(group=>filter.value==='all'||group.category===filter.value));
const total=computed(()=>groups.value.reduce((count,group)=>count+group.changes.length,0));
const filters=[{id:'all',label:'全部'},{id:'content',label:'文字与模块'},{id:'images',label:'图片'},{id:'layout',label:'版式'}] as const;
let previousFocus:HTMLElement|null=null;
function count(category:DiffCategory|'all'){return groups.value.filter(group=>category==='all'||group.category===category).reduce((sum,group)=>sum+group.changes.length,0);}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'&&!props.busy){event.preventDefault();emit('close');}
 if(event.key==='Tab'){
  const controls=Array.from(dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')||[]);
  const first=controls[0],last=controls.at(-1);
  if(!first){event.preventDefault();return;}
  if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
  else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
 }
}
onMounted(async()=>{previousFocus=props.returnFocus||(document.activeElement instanceof HTMLElement?document.activeElement:null);window.addEventListener('keydown',keys);await nextTick();closeButton.value?.focus();});
onUnmounted(()=>{window.removeEventListener('keydown',keys);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="history-overlay"><section ref="dialog" class="history-dialog" role="dialog" aria-modal="true" aria-labelledby="history-title">
  <header><div><span class="rw-overline">历史版本</span><h2 id="history-title">版本对比</h2></div><button ref="closeButton" :disabled="busy" aria-label="关闭版本对比" @click="emit('close')">×</button></header>
  <div class="history-sources"><div><span>历史版本 · r{{version.sourceRevision}}</span><strong>{{version.label}}</strong><small>{{new Date(version.createdAt).toLocaleString('zh-CN')}}</small></div><div><span>当前编辑内容 · r{{revision}}</span><strong>{{title}}</strong><small>{{dirty?'包含尚未保存的修改':'已保存到本机'}}</small></div></div>
  <nav class="history-filters" aria-label="筛选版本变化"><button v-for="tab in filters" :key="tab.id" :aria-pressed="filter===tab.id" :disabled="busy" @click="filter=tab.id">{{tab.label}} <small>{{count(tab.id)}}</small></button></nav>
  <p class="history-summary" role="status">{{total?`共 ${total} 处变化`:'当前内容与此版本相同'}}<span v-if="total"> · 左侧为历史版本，右侧为当前内容</span></p>
  <div class="history-changes" data-testid="version-differences">
   <section v-for="group in filtered" :key="group.id" class="history-group" :data-testid="'difference-'+group.id"><h3>{{group.title}} <small>{{group.changes.length}} 处</small></h3>
    <div v-if="group.image" class="history-image-pair"><figure><img v-if="group.image.before" :src="`/api/assets/${group.image.before}/image`" :alt="'历史版本'+group.title"><span v-else>无图片</span><figcaption>历史版本</figcaption></figure><figure><img v-if="group.image.after" :src="`/api/assets/${group.image.after}/image`" :alt="'当前'+group.title"><span v-else>无图片</span><figcaption>当前内容</figcaption></figure></div>
    <div v-for="(change,index) in group.changes" :key="index" class="history-change"><h4>{{change.field}}</h4><div class="history-values"><div class="history-before"><span class="history-value-label">历史版本</span><p>{{change.before}}</p></div><div class="history-after"><span class="history-value-label">当前内容</span><p>{{change.after}}</p></div></div></div>
   </section>
   <p v-if="!filtered.length" class="history-empty">{{total?'这一类没有变化。':'可以关闭对比，继续编辑。'}}</p>
  </div>
  <p v-if="error" class="history-error" role="alert">{{error}}</p>
  <footer><p>恢复前会自动保留当前版本。</p><div><button :disabled="busy" @click="emit('close')">关闭对比</button><button class="rw-primary" :disabled="busy||!total" @click="emit('restore')">{{busy?'正在恢复…':'恢复此版本'}}</button></div></footer>
 </section></div>
</template>
