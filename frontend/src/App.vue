<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import ImageControls from './ImageControls.vue';
import ContentEditor from './ContentEditor.vue';
import { api, type Asset, type Resume, type Version } from './api';
import { useWorkspace } from './useWorkspace';
const ws=useWorkspace();
const {resumes,current,document:doc,title,dirty,saving,saveError,saveStatus}=ws;
const config=ref({maxUploadBytes:5242880}), assets=reactive<{photo?:Asset;logo?:Asset}>({});
const tab=ref('content'), busy=ref(false), problem=ref(''), previewUrl=ref(''), previewState=ref('准备预览'), pageCount=ref(1), scale=ref(.9);
const versions=ref<Version[]>([]), versionLabel=ref(''), exported=ref<{id:string;revision:number}>();
const previewPane=ref<HTMLElement>(), previewFrame=ref<HTMLIFrameElement>();
let generation=0, previewTimer:ReturnType<typeof setTimeout>, observer:ResizeObserver|undefined;
const paperHeight=computed(()=>1123*pageCount.value+(pageCount.value-1)*23);
async function action(fn:()=>Promise<unknown>){busy.value=true;problem.value='';try{await fn();}catch(e){problem.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
async function refresh(){
 if(!doc.value)return;const currentGeneration=++generation;previewState.value='更新预览中';
 try{const result=await api<{url:string}>('/api/documents/preview',JSON.parse(JSON.stringify(doc.value)));if(currentGeneration===generation)previewUrl.value=result.url;}
 catch(e){if(currentGeneration===generation){previewState.value='预览失败';problem.value=e instanceof Error?e.message:String(e);}}
}
watch(doc,()=>{generation++;previewState.value='更新预览中';clearTimeout(previewTimer);previewTimer=setTimeout(refresh,350);},{deep:true});
watch(()=>[doc.value?.layout.photo.id,doc.value?.layout.logo.id],async ids=>{
 for(const [i,kind] of (['photo','logo'] as const).entries()){
  const id=ids[i];if(!id){assets[kind]=undefined;continue;}
  try{const asset=await api<Asset>('/api/assets/'+id);if(doc.value?.layout[kind].id===id)assets[kind]=asset;}catch{assets[kind]=undefined;}
 }
});
watch(()=>current.value?.id,()=>{exported.value=undefined;versions.value=[];});
function layoutMessage(event:MessageEvent){
 if(event.origin!==location.origin || event.source!==previewFrame.value?.contentWindow || event.data?.type!=='resume-layout')return;
 pageCount.value=event.data.pages||1;previewState.value=event.data.error?'排版超限':'预览已更新';
 if(event.data.error)problem.value='当前内容无法安全分页，请缩短单条内容或减小字号。';
}
function imported(kind:'photo'|'logo',asset:Asset){if(!doc.value)return;assets[kind]=asset;Object.assign(doc.value.layout[kind],{id:asset.id,visible:true,zoom:1,quarterTurns:0,positionX:50,positionY:50});}
async function loadVersions(){if(current.value)versions.value=await api(`/api/resumes/${current.value.id}/versions`);}
async function checkpoint(){await ws.flush();if(!current.value)return;await api(`/api/resumes/${current.value.id}/versions`,{expectedRevision:current.value.revision,title:versionLabel.value||'手动快照'});versionLabel.value='';await loadVersions();}
async function restore(version:Version){await ws.flush();if(!current.value)return;await ws.install(await api<Resume>(`/api/resumes/${current.value.id}/versions/${version.id}/restore`,{expectedRevision:current.value.revision}));await loadVersions();}
async function exportPdf(){
 await ws.flush();if(!current.value)return;
 exported.value=await api(`/api/resumes/${current.value.id}/export`,{expectedRevision:current.value.revision});
 const a=window.document.createElement('a');a.href='/api/exports/'+exported.value!.id+'/pdf';a.download='resume-workbench.pdf';a.click();if(tab.value==='versions')await loadVersions();
}
function deleteResume(){if(confirm('删除当前简历及其历史版本？本地图片文件会保留。'))void action(ws.remove);}
function reloadCurrent(){if(!dirty.value||confirm('重新载入会放弃当前页面尚未保存的修改。继续吗？'))void action(ws.reloadCurrent);}
watch(tab,value=>{if(value==='versions')void action(loadVersions);});
onMounted(async()=>{
 window.addEventListener('message',layoutMessage);window.addEventListener('beforeunload',ws.beforeUnload);
 await nextTick();observer=new ResizeObserver(entries=>{scale.value=Math.max(.25,Math.min(1,(entries[0].contentRect.width-48)/794));});if(previewPane.value)observer.observe(previewPane.value);
 await action(async()=>{config.value=await api('/api/config');await ws.initialize();});
});
onUnmounted(()=>{observer?.disconnect();clearTimeout(previewTimer);ws.dispose();window.removeEventListener('message',layoutMessage);window.removeEventListener('beforeunload',ws.beforeUnload);});
</script>
<template>
 <div class="workbench">
  <header class="app-header"><a class="brand" href="/"><span class="brand-mark">简</span><span>简历工作台<span class="brand-en">RESUME WORKBENCH</span></span></a><div class="header-middle"><span class="local-dot"></span>本地简历工作台<span class="stage-badge">阶段 B</span></div><button class="primary export-button" :disabled="busy||!current" @click="action(exportPdf)">{{busy?'正在处理…':'↓ 导出 PDF'}}</button></header>
  <div class="workspace-heading"><div><div class="eyebrow">YOUR NEXT CHAPTER</div><h1>把经历，写得清楚。</h1><p>从一份基础简历出发，为每一次机会保留一个版本。</p></div><div class="privacy-note"><span class="local-dot"></span>内容、照片和版本保存在本机<br><small>PostgreSQL · 本地附件 · 无需登录</small></div></div>
  <div v-if="problem" class="global-notice error" role="alert">{{problem}}<button class="text-button" @click="problem=''">收起提示</button></div>
  <div class="workspace-grid workspace-b">
   <aside class="library-panel"><div class="panel-heading"><h2>我的简历</h2><span>{{resumes.length}}</span></div><div class="library-list"><button v-for="resume in resumes" :key="resume.id" :class="{selected:current?.id===resume.id}" :disabled="busy" :data-testid="'resume-'+resume.id" @click="action(()=>ws.open(resume.id))"><strong>{{resume.title}}</strong><small>r{{resume.revision}} · {{new Date(resume.updatedAt).toLocaleDateString()}}</small></button><p v-if="!resumes.length" class="empty-library">还没有简历，创建一份开始编辑。</p></div><div class="library-actions"><button :disabled="busy" @click="action(()=>ws.create('blank'))">＋ 新建空白简历</button><div class="sample-actions"><span>从示例开始</span><button :disabled="busy" @click="action(()=>ws.create('one'))">一页示例</button><button :disabled="busy" @click="action(()=>ws.create('two'))">两页示例</button></div></div><p class="library-note">切换前会先保存。保存失败时保留当前编辑内容。</p></aside>
   <aside class="settings-panel editor-panel">
    <template v-if="doc&&current"><div class="document-heading"><label>简历名称<input v-model="title" maxlength="120" aria-label="简历名称" :disabled="busy"></label><div class="document-status"><span role="status" data-testid="save-status" :class="{failed:!!saveError}"><span class="local-dot" :class="{pending:dirty||saving}"></span>{{saveStatus}}</span><small>r{{current.revision}}</small></div><div class="document-actions"><button :disabled="busy" @click="action(ws.duplicate)">复制简历</button><button class="text-button danger" :disabled="busy" @click="deleteResume">删除简历</button></div></div>
     <div v-if="saveError" class="save-error error" role="alert">{{saveError}}<div><button :disabled="busy" @click="action(ws.flush)">重试保存</button><button :disabled="busy" @click="action(ws.preserveAsCopy)">另存副本</button><button :disabled="busy" @click="reloadCurrent">重新载入</button></div></div>
     <nav class="editor-tabs"><button v-for="(label,key) in {content:'内容编辑',layout:'版式',images:'图片',versions:'版本'}" :key="key" :class="{active:tab===key}" :aria-pressed="tab===key" @click="tab=key">{{label}}</button></nav>
     <fieldset :disabled="busy" class="editor-fields">
      <ContentEditor v-if="tab==='content'" :content="doc.content"/>
      <section v-if="tab==='layout'" class="layout-fields"><label>模板<select v-model="doc.layout.template" aria-label="简历模板"><option value="classic">经典单栏 · 图片两侧</option><option value="banner">并列页眉 · 图片右侧</option></select></label><label>中文字体<select v-model="doc.layout.font" aria-label="中文字体"><option value="sans">本地黑体</option><option value="serif">本地宋体</option></select></label><div class="two-inputs"><label>字号（pt）<input v-model.number="doc.layout.fontSize" type="number" min="9" max="12" step="0.2" aria-label="正文字号"></label><label>行距<input v-model.number="doc.layout.lineHeight" type="number" min="1.3" max="1.85" step="0.05" aria-label="正文行距"></label><label>模块间距（mm）<input v-model.number="doc.layout.sectionGapMm" type="number" min="2" max="8" aria-label="模块间距"></label><label>页边距（mm）<input v-model.number="doc.layout.marginMm" type="number" min="12" max="22" aria-label="页边距"></label></div><label>图片位置<select v-model="doc.layout.swapImages" aria-label="页眉布局"><option :value="false">证件照在前 · 右上角学校 Logo</option><option :value="true">学校 Logo 在前 · 右上角证件照</option></select></label><p>预览和 PDF 使用相同的分页规则。内容变长时自动续页，也可按模块设置“另起一页”。</p></section>
      <template v-if="tab==='images'"><ImageControls kind="logo" title="学校 Logo" :slot="doc.layout.logo" :asset="assets.logo" :max-bytes="config.maxUploadBytes" @imported="imported('logo',$event)" @removed="doc.layout.logo.id=null"/><ImageControls kind="photo" title="证件照" :slot="doc.layout.photo" :asset="assets.photo" :max-bytes="config.maxUploadBytes" @imported="imported('photo',$event)" @removed="doc.layout.photo.id=null"/><p class="settings-footnote">两张图片独立配置。移除只解除当前引用，历史版本里的照片仍可恢复。</p></template>
      <section v-if="tab==='versions'" class="version-panel"><label>快照名称<input v-model="versionLabel" maxlength="120" placeholder="例如：Java 岗投递前" aria-label="快照名称"></label><button class="primary" @click="action(checkpoint)">保存版本快照</button><p>恢复前会自动保留当前版本。PDF 导出也会记录对应快照。</p><ol><li v-for="version in versions" :key="version.id"><div><strong>{{version.label}}</strong><small>r{{version.sourceRevision}} · {{new Date(version.createdAt).toLocaleString()}}</small></div><button @click="action(()=>restore(version))">恢复</button></li></ol></section>
     </fieldset>
    </template><div v-else class="empty-preview">选择或新建一份简历</div>
   </aside>
   <section class="preview-panel" ref="previewPane"><div class="preview-toolbar"><strong>实时预览</strong><div class="preview-status" role="status" data-testid="preview-status"><span class="local-dot" :class="{pending:previewState!=='预览已更新'}"></span>{{previewState}}<span class="zoom-label">{{Math.round(scale*100)}}%</span></div></div><div v-if="exported" class="notice success" role="status">PDF 已生成 · 修订 r{{exported.revision}}<a :href="'/api/exports/'+exported.id+'/pdf'">重新下载</a></div><div class="paper-caption"><span>{{doc?.layout.template==='banner'?'并列页眉':'经典单栏'}}</span><span>A4 · {{pageCount}} {{pageCount===1?'PAGE':'PAGES'}}</span></div><div v-if="doc" class="paper-wrap" :style="{width:794*scale+'px',height:paperHeight*scale+'px'}"><iframe v-if="previewUrl" ref="previewFrame" title="简历实时预览" :src="previewUrl" :style="{width:'794px',height:paperHeight+'px',transform:`scale(${scale})`}"></iframe><div v-else class="empty-preview">正在准备中文字体与图片…</div></div><p class="preview-footnote">相同模板 · 本地字体 · 可选择的中文文本</p></section>
  </div>
 </div>
</template>
