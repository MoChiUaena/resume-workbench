<script setup lang="ts">
import {nextTick,onMounted,onUnmounted,ref} from 'vue';
import {api} from './api';
type RuntimeDetails={version:string;buildTime:string|null;pid:number;startedAt:string;instanceId:string;javaVersion:string;jarPath:string|null;jarSha256:string|null;dataDirectory:string;logsDirectory:string|null;port:number};
const emit=defineEmits<{close:[]}>();
const details=ref<RuntimeDetails>(),busy=ref(false),error=ref(''),health=ref<'checking'|'ok'|'unavailable'>('checking'),checkedAt=ref('');
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>();
let previousFocus:HTMLElement|undefined,disposed=false,controller:AbortController|undefined;
function date(value:string|null){if(!value)return '未记录';const parsed=new Date(value);return Number.isFinite(parsed.getTime())?parsed.toLocaleString('zh-CN'):'未记录';}
async function refresh(){
 if(busy.value)return;busy.value=true;error.value='';health.value='checking';controller=new AbortController();
 try{
  const current=await api<RuntimeDetails>('/api/runtime',undefined,'GET',controller.signal);
  if(disposed)return;details.value=current;checkedAt.value=new Date().toISOString();
  try{const result=await api<{status:string}>('/api/health',undefined,'GET',controller.signal);if(!disposed)health.value=result.status==='ok'?'ok':'unavailable';}
  catch{if(!disposed)health.value='unavailable';}
 }catch{if(!disposed){health.value='unavailable';error.value=details.value?'本次刷新失败，下方保留上次读取的记录；请检查本地应用后重试。':'运行信息暂时无法读取，请检查本地应用后重试。';}}
 finally{if(!disposed)busy.value=false;}
}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'){event.preventDefault();emit('close');return;}
 if(event.key!=='Tab')return;
 const controls=Array.from(dialog.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)')||[]);
 const first=controls[0],last=controls.at(-1);
 if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
 else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
}
onMounted(async()=>{previousFocus=document.activeElement instanceof HTMLElement?document.activeElement:undefined;window.addEventListener('keydown',keys);await nextTick();closeButton.value?.focus();void refresh();});
onUnmounted(()=>{disposed=true;controller?.abort();window.removeEventListener('keydown',keys);nextTick(()=>{if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="runtime-overlay"><section ref="dialog" class="runtime-dialog" role="dialog" aria-modal="true" aria-labelledby="runtime-title">
  <header><div><span class="rw-overline">本机应用</span><h2 id="runtime-title">运行状态</h2></div><button ref="closeButton" aria-label="关闭运行状态" @click="emit('close')">×</button></header>
  <p class="runtime-intro">查看实际运行的版本和数据位置。这里只读取信息，简历内容保持不变。</p>
  <p v-if="error" class="runtime-error" role="alert">{{error}}</p>
  <p data-testid="runtime-health" class="runtime-health" role="status">{{health==='checking'?'正在检查本地应用…':health==='ok'?'应用及数据库正常':'本次未能确认应用及数据库状态'}}</p>
  <dl v-if="details" class="runtime-details">
   <div><dt>运行版本</dt><dd data-testid="runtime-version">{{details.version}}</dd></div>
   <div><dt>构建时间</dt><dd>{{date(details.buildTime)}}</dd></div>
   <div><dt>启动时间</dt><dd>{{date(details.startedAt)}}</dd></div>
   <div><dt>进程 PID</dt><dd data-testid="runtime-pid">{{details.pid}}</dd></div>
   <div><dt>应用监听端口</dt><dd>{{details.port}}</dd></div>
   <div><dt>Java 版本</dt><dd>{{details.javaVersion}}</dd></div>
   <div><dt>数据目录</dt><dd><code>{{details.dataDirectory}}</code></dd></div>
   <div><dt>日志位置</dt><dd><code v-if="details.logsDirectory">{{details.logsDirectory}}</code><span v-else>日志输出到启动终端或容器日志。</span></dd></div>
   <div v-if="details.jarPath"><dt>运行安装包</dt><dd><code>{{details.jarPath}}</code></dd></div>
   <div v-if="details.jarSha256"><dt>安装包校验值</dt><dd><code>{{details.jarSha256}}</code></dd></div>
  </dl>
  <p v-if="details" class="runtime-note">Docker 部署时，上述目录位于容器内，数据由挂载卷保存。最后读取：{{date(checkedAt)}}。</p>
  <footer><button class="rw-primary" :disabled="busy" @click="refresh">{{busy?'正在刷新…':'刷新运行信息'}}</button><button @click="emit('close')">关闭</button></footer>
 </section></div>
</template>
<style src="./runtime.css"></style>
