<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import AppearanceControls from './AppearanceControls.vue';
import BackupPanel from './BackupPanel.vue';
import LayoutControls from './LayoutControls.vue';
import FocusedSection from './FocusedSection.vue';
import ImageControls from './ImageControls.vue';
import VersionCompare from './VersionCompare.vue';
import RedactedPdf from './RedactedPdf.vue';
import ModelSettingsPage from './ModelSettingsPage.vue';
import TextSuggestion from './TextSuggestion.vue';
import JobMatch from './JobMatch.vue';
import RuntimeStatus from './RuntimeStatus.vue';
import type {ParagraphTarget,Suggestion} from './modelApi';
import {downloadPdf} from './downloadPdf';
import { api, type Asset, type Resume, type Section, type Summary, type Version, type VersionDetail } from './api';
import { useWorkspace } from './useWorkspace';
import {templateName} from './resumeTemplates';

const ws=useWorkspace();
const {resumes,current,document:doc,title,dirty,saving,saveError,saveStatus,canUndo,canRedo}=ws;
const uiTheme=ref<'a'|'b'>(localStorage.getItem('rw-ui-theme')==='b'?'b':'a');
const darkMode=ref(localStorage.getItem('rw-dark-mode')==='1');
watch([uiTheme,darkMode],([theme,dark])=>{
  document.documentElement.dataset.uiTheme=theme;
  document.documentElement.dataset.dark=dark?'true':'false';
  localStorage.setItem('rw-ui-theme',theme);localStorage.setItem('rw-dark-mode',dark?'1':'0');
},{immediate:true});

const view=ref<'list'|'editor'|'models'>('list'), listMode=ref<'list'|'grid'>(localStorage.getItem('rw-list-mode')==='grid'?'grid':'list');
watch(listMode,mode=>localStorage.setItem('rw-list-mode',mode));
const cache=reactive<Record<string,Resume>>({});
const selected=ref('basic'), newType=ref<Section['type']>('project');
const names:Record<Section['type'],string>={education:'教育背景',experience:'工作 / 实习经历',project:'项目经历',skills:'专业技能',custom:'自定义文本'};
const selectedSection=computed(()=>doc.value?.content.sections.find(section=>section.id===selected.value));
const config=ref({maxUploadBytes:5242880,maxBackupBytes:268435456}), assets=reactive<{photo?:Asset;logo?:Asset}>({});
const showBackup=ref(false),showRuntime=ref(false);
async function openBackup(){await ws.flush();showBackup.value=true;}
async function restoredWorkspace(){view.value='list';routeList();await ws.reloadList();await hydrateCards();}
const versions=ref<Version[]>([]),versionLabel=ref(''),exported=ref<{id:string;revision:number;redacted?:boolean}>();
const redactedSource=ref<Resume>(),redactedBusy=ref(false),redactedFocus=ref<HTMLElement>();
const aiSource=ref<Resume>(),aiTarget=ref<ParagraphTarget>(),aiFocus=ref<HTMLElement>(),aiApplying=ref(false),aiInitial=ref<Suggestion>();
const jobSource=ref<Resume>(),jobFocus=ref<HTMLElement>();
async function openJobMatch(){await ws.flush();if(current.value){jobFocus.value=document.activeElement instanceof HTMLElement?document.activeElement:undefined;jobSource.value=JSON.parse(JSON.stringify(current.value));}}
function closeJobMatch(){jobSource.value=undefined;}
function reviewJobSuggestion(suggestion:Suggestion,trigger:HTMLElement){if(!jobSource.value)return;aiSource.value=jobSource.value;aiTarget.value={sectionId:suggestion.sectionId,entryId:suggestion.entryId,paragraph:suggestion.paragraph,selectionStart:suggestion.selectionStart,selectionEnd:suggestion.selectionEnd};aiFocus.value=trigger;aiInitial.value=suggestion;}
async function jobSettings(){closeJobMatch();await openModels();}
async function openSuggestion(target:ParagraphTarget){await ws.flush();if(current.value){aiFocus.value=document.activeElement instanceof HTMLElement?document.activeElement:undefined;aiTarget.value=target;aiSource.value=JSON.parse(JSON.stringify(current.value));}}
function closeSuggestion(){if(!aiApplying.value){aiSource.value=undefined;aiTarget.value=undefined;aiInitial.value=undefined;}}
async function suggestionApplied(resume:Resume){if(current.value?.id!==resume.id)return;await ws.install(resume,true);aiSource.value=undefined;aiTarget.value=undefined;aiInitial.value=undefined;jobSource.value=undefined;if(selected.value==='versions')versions.value=await api(`/api/resumes/${resume.id}/versions`);}
async function suggestionSettings(){closeSuggestion();await openModels();}
async function openRedactedPdf(){await ws.flush();if(current.value){redactedFocus.value=document.activeElement instanceof HTMLElement?document.activeElement:undefined;redactedSource.value=JSON.parse(JSON.stringify(current.value));}}
function closeRedactedPdf(){if(!redactedBusy.value)redactedSource.value=undefined;}
async function redactedCreated(result:{id:string;revision:number;redacted:boolean}){const source=current.value;if(!source||source.id!==redactedSource.value?.id)return;exported.value=result;if(selected.value==='versions')versions.value=await api(`/api/resumes/${source.id}/versions`);}
async function reloadAfterRedaction(){await ws.reloadCurrent();closeRedactedPdf();}
const compared=ref<VersionDetail>();
const comparisonFocus=ref<HTMLElement>();
const previewUrl=ref(''),previewState=ref('准备预览'),pageCount=ref(1),scale=ref(.85),problem=ref(''),busy=ref(false);
const previewPane=ref<HTMLElement>(),previewFrame=ref<HTMLIFrameElement>();
const paperHeight=computed(()=>1123*pageCount.value+(pageCount.value-1)*23);
let generation=0,previewTimer:ReturnType<typeof setTimeout>,observer:ResizeObserver|undefined;
let pendingNavigation=false;

async function action(fn:()=>Promise<unknown>){if(busy.value)return;ws.breakUndoGroup();busy.value=true;problem.value='';try{await fn();}catch(e){problem.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
async function hydrateCards(){
  const results=await Promise.allSettled(resumes.value.map(async item=>{
    if(cache[item.id]?.revision===item.revision)return;
    cache[item.id]=await api<Resume>(`/api/resumes/${item.id}`);
  }));
  if(results.every(result=>result.status==='rejected') && results.length)problem.value='简历列表读取失败，请检查本地服务后刷新。';
}
function routeEditor(id:string){const url=new URL(location.href);url.searchParams.set('view','editor');url.searchParams.set('resume',id);history.pushState(null,'',url.pathname+url.search);}
function routeList(){const url=new URL(location.href);url.searchParams.delete('view');url.searchParams.delete('resume');history.pushState(null,'',url.pathname+url.search);}
async function editResume(id:string,push=true){await ws.open(id);view.value='editor';selected.value='basic';if(push)routeEditor(id);}
async function backToList(push=true){await ws.flush();view.value='list';if(push)routeList();await ws.reloadList();await hydrateCards();}
async function openModels(push=true){await ws.flush();view.value='models';if(push){const url=new URL(location.href);url.searchParams.set('view','models');url.searchParams.delete('resume');history.pushState(null,'',url.pathname+url.search);}}
async function createResume(sample:'blank'|'one'|'two'){
  await ws.create(sample);if(!current.value)return;
  view.value='editor';selected.value='basic';routeEditor(current.value.id);
}
async function record(id:string){return await api<Resume>(`/api/resumes/${id}`);}
async function renameResume(item:Summary){
  const next=window.prompt('简历名称',item.title)?.trim();if(!next||next===item.title)return;
  const saved=await record(item.id);
  const updated=await api<Resume>(`/api/resumes/${item.id}`,{title:next,document:saved.document,expectedRevision:saved.revision,mutationId:crypto.randomUUID()},'PUT');
  cache[item.id]=updated;await ws.reloadList();
  if(current.value?.id===item.id)await ws.install(updated);
}
async function duplicateResume(item:Summary){
  const saved=await record(item.id);
  const copy=await api<Resume>(`/api/resumes/${item.id}/duplicate`,{expectedRevision:saved.revision,title:(item.title+' · 副本').slice(0,120)});
  cache[copy.id]=copy;await ws.reloadList();
}
async function deleteResume(item:Summary){
  if(!window.confirm(`删除「${item.title}」、历史版本及已保存的职位匹配报告？图片文件会保留。`))return;
  const saved=await record(item.id);
  await api(`/api/resumes/${item.id}`,{expectedRevision:saved.revision},'DELETE');
  delete cache[item.id];await ws.reloadList();
  if(current.value?.id===item.id){current.value=undefined;doc.value=undefined;title.value='';localStorage.removeItem('local-resume-selected');}
}
function addSection(){
  if(!doc.value||doc.value.content.sections.length>=20)return;
  const id=crypto.randomUUID();doc.value.content.sections.push({id,type:newType.value,title:names[newType.value],visible:true,pageBreakBefore:false,entries:[{id:crypto.randomUUID(),title:'新条目',meta:'',bulleted:true,bullets:['']}]});selected.value=id;
}
function removeSection(){if(!doc.value||!selectedSection.value)return;doc.value.content.sections.splice(doc.value.content.sections.findIndex(s=>s.id===selected.value),1);selected.value='basic';}
async function selectPanel(id:string){selected.value=id;if(id==='versions'&&current.value)versions.value=await api(`/api/resumes/${current.value.id}/versions`);}
async function checkpoint(){await ws.flush();if(!current.value)return;await api(`/api/resumes/${current.value.id}/versions`,{expectedRevision:current.value.revision,title:versionLabel.value||'手动快照'});versionLabel.value='';versions.value=await api(`/api/resumes/${current.value.id}/versions`);}
async function restore(version:Version){await ws.flush();if(!current.value)return;await ws.install(await api<Resume>(`/api/resumes/${current.value.id}/versions/${version.id}/restore`,{expectedRevision:current.value.revision}),true);versions.value=await api(`/api/resumes/${current.value.id}/versions`);selected.value='versions';}
async function compareVersion(version:Version){if(!current.value)return;const id=current.value.id,trigger=document.activeElement instanceof HTMLElement?document.activeElement:undefined;const result=await api<VersionDetail>(`/api/resumes/${id}/versions/${version.id}`);if(current.value?.id===id&&view.value==='editor'){comparisonFocus.value=trigger;compared.value=result;}}
async function restoreCompared(){if(!compared.value)return;await restore(compared.value);compared.value=undefined;}
function closeComparison(){if(!busy.value)compared.value=undefined;}
async function preserveDraftCopy(){await ws.preserveAsCopy();if(current.value)routeEditor(current.value.id);selected.value='basic';}
function historyKeys(event:KeyboardEvent){
 if(event.defaultPrevented||event.isComposing||event.altKey||!(event.ctrlKey||event.metaKey)||view.value!=='editor'||busy.value||showBackup.value||showRuntime.value||compared.value||redactedSource.value||aiSource.value||jobSource.value)return;
 const key=event.key.toLowerCase();if(key!=='z'&&key!=='y')return;
 const target=event.target instanceof HTMLElement?event.target:undefined;
 const editing=target?.closest('input,textarea,[contenteditable=true]');
 if(editing&&((!target?.closest('.rw-form-pane')&&editing.getAttribute('aria-label')!=='简历名称')||(selected.value==='versions'&&editing.getAttribute('aria-label')!=='简历名称')))return;
 event.preventDefault();if(key==='y'||event.shiftKey)ws.redo();else ws.undo();
}
async function exportPdf(){await ws.flush();if(!current.value)return;const result=await api<{id:string;revision:number}>(`/api/resumes/${current.value.id}/export`,{expectedRevision:current.value.revision});exported.value=result;const link=document.createElement('a');link.href=`/api/exports/${result.id}/pdf`;link.download='resume-workbench.pdf';link.click();if(selected.value==='versions')versions.value=await api(`/api/resumes/${current.value.id}/versions`);}
function imported(kind:'photo'|'logo',asset:Asset){if(!doc.value)return;assets[kind]=asset;Object.assign(doc.value.layout[kind],{id:asset.id,visible:true,zoom:1,quarterTurns:0,positionX:50,positionY:50});}
function layoutMessage(event:MessageEvent){if(event.origin!==location.origin||event.source!==previewFrame.value?.contentWindow||event.data?.type!=='resume-layout')return;pageCount.value=event.data.pages||1;previewState.value=event.data.error?'排版超限':'预览已更新';if(event.data.error)problem.value='当前内容无法安全分页，请缩短单条内容或减小字号。';}
async function refresh(){if(!doc.value||view.value!=='editor')return;const currentGeneration=++generation;previewState.value='更新预览中';try{const result=await api<{url:string}>('/api/documents/preview',JSON.parse(JSON.stringify(doc.value)));if(currentGeneration===generation)previewUrl.value=result.url;}catch(e){if(currentGeneration===generation){previewState.value='预览失败';problem.value=e instanceof Error?e.message:String(e);}}}
watch(doc,()=>{generation++;previewState.value='更新预览中';clearTimeout(previewTimer);previewTimer=setTimeout(refresh,350);},{deep:true});
watch(()=>current.value?.id,()=>{previewUrl.value='';pageCount.value=1;exported.value=undefined;versions.value=[];compared.value=undefined;redactedSource.value=undefined;aiSource.value=undefined;aiTarget.value=undefined;jobSource.value=undefined;});
watch(()=>doc.value?.content.sections.map(section=>section.id),ids=>{if(!['basic','images','layout','versions'].includes(selected.value)&&!ids?.includes(selected.value))selected.value='basic';});
watch(()=>[doc.value?.layout.photo.id,doc.value?.layout.logo.id],async ids=>{for(const [index,kind] of (['photo','logo'] as const).entries()){const id=ids[index];if(!id){assets[kind]=undefined;continue;}try{const asset=await api<Asset>('/api/assets/'+id);if(doc.value?.layout[kind].id===id)assets[kind]=asset;}catch{assets[kind]=undefined;}}});
function resizePreview(){if(!previewPane.value)return;scale.value=Math.max(.25,Math.min(1,(previewPane.value.clientWidth-48)/794));}
watch(view,async()=>{if(view.value!=='editor')compared.value=undefined;await nextTick();observer?.disconnect();if(view.value==='editor'&&previewPane.value){observer=new ResizeObserver(resizePreview);observer.observe(previewPane.value);resizePreview();if(doc.value)await refresh();}});
async function popstate(){if(busy.value||redactedBusy.value||aiApplying.value){pendingNavigation=true;return;}pendingNavigation=false;closeRedactedPdf();closeSuggestion();closeJobMatch();await action(async()=>{const query=new URLSearchParams(location.search),id=query.get('resume');if(query.get('view')==='models')await openModels(false);else if(query.get('view')==='editor'&&id)await editResume(id,false);else await backToList(false);});}
watch([busy,redactedBusy,aiApplying],([active,exporting,applying])=>{if(!active&&!exporting&&!applying&&pendingNavigation)void popstate();});
onMounted(async()=>{window.addEventListener('keydown',historyKeys);window.addEventListener('message',layoutMessage);window.addEventListener('beforeunload',ws.beforeUnload);window.addEventListener('popstate',popstate);await action(async()=>{config.value=await api('/api/config');await ws.initialize();const query=new URLSearchParams(location.search),id=query.get('resume');if(query.get('view')==='models')await openModels(false);else if(query.get('view')==='editor'&&id&&resumes.value.some(item=>item.id===id))await editResume(id,false);else await hydrateCards();});});
onUnmounted(()=>{observer?.disconnect();clearTimeout(previewTimer);ws.dispose();window.removeEventListener('keydown',historyKeys);window.removeEventListener('message',layoutMessage);window.removeEventListener('beforeunload',ws.beforeUnload);window.removeEventListener('popstate',popstate);});
</script>
<template>
 <div class="rw-app" :inert="showBackup||showRuntime||!!compared||!!redactedSource||!!aiSource||!!jobSource" @input.capture="ws.editInput" @change.capture="ws.editInput" @click.capture="ws.breakUndoGroup" @focusout.capture="ws.breakUndoGroup">
  <header class="rw-header"><div class="rw-header-inner">
   <button class="rw-brand" @click="view!=='list'?action(()=>backToList()):undefined"><span class="rw-mark">简</span><span>简历工作台<small>RESUME WORKBENCH</small></span></button>
   <nav><button :class="{active:view==='list'}" @click="view!=='list'?action(()=>backToList()):undefined">我的简历</button><span v-if="view==='editor'" class="active">简历编辑</span></nav>
   <button class="model-open" :class="{active:view===String('models')}" :disabled="busy" @click="action(openModels)">模型设置</button>
   <button class="backup-open" :disabled="busy" @click="showRuntime=true">运行信息</button>
   <button class="backup-open" :disabled="busy" @click="action(openBackup)">备份与恢复</button>
   <div class="rw-local">● 数据保存在本机</div><AppearanceControls v-model:theme="uiTheme" v-model:dark="darkMode"/>
  </div></header>
  <p v-if="problem" class="rw-error" role="alert">{{problem}} <button @click="problem=''">收起</button></p>

  <main v-if="view==='list'" class="rw-list-page"><div class="rw-list-heading"><div><span class="rw-overline">简历管理</span><h1>我的简历 <small>{{String(resumes.length).padStart(2,'0')}}</small></h1><p>从已有版本继续编辑，或为新岗位复制一份。</p></div><button class="rw-primary" :disabled="busy" @click="action(()=>createResume('blank'))">＋ 新建简历</button></div>
   <div class="rw-list-toolbar"><div><button class="active">全部简历 <small>{{resumes.length}}</small></button><span>最近修改优先</span></div><div class="rw-view-modes"><button aria-label="列表视图" :aria-pressed="listMode==='list'" @click="listMode='list'">☰ 列表</button><button aria-label="网格视图" :aria-pressed="listMode==='grid'" @click="listMode='grid'">▦ 网格</button></div></div>
   <div v-if="!resumes.length" class="rw-empty"><h2>从第一份简历开始</h2><p>新建空白简历，或用合成示例熟悉编辑和 PDF 导出。</p><div><button class="rw-primary" @click="action(()=>createResume('blank'))">新建空白简历</button><button @click="action(()=>createResume('one'))">创建一页示例</button><button @click="action(()=>createResume('two'))">创建两页示例</button></div></div>
   <div v-else class="rw-collection" :class="{grid:listMode==='grid'}"><article v-for="item in resumes" :key="item.id" class="rw-resume-card" :data-testid="'resume-'+item.id"><button class="rw-thumb" :aria-label="'编辑'+item.title" @click="action(()=>editResume(item.id))"><span class="rw-mini-paper"><span class="mini-name">{{cache[item.id]?.document.content.name||'简历'}}</span><span class="mini-line"></span><span v-for="part in cache[item.id]?.document.content.sections.filter(s=>s.visible).slice(0,4)||[]" :key="part.id" class="mini-section">{{part.title}}<i></i><i></i></span></span></button><div class="rw-card-body"><span class="rw-card-tag">{{cache[item.id]?.document.content.headline||'简历'}} · 修订 r{{item.revision}}</span><h2>{{item.title}}</h2><p>修改于 {{new Date(item.updatedAt).toLocaleString('zh-CN')}} · {{templateName(cache[item.id]?.document.layout.template)}}</p><div class="rw-card-actions"><button class="rw-primary" :disabled="busy" @click="action(()=>editResume(item.id))">继续编辑</button><button :disabled="busy" @click="action(()=>renameResume(item))">重命名</button><button :disabled="busy" @click="action(()=>duplicateResume(item))">复制</button><button class="rw-delete" :disabled="busy" @click="action(()=>deleteResume(item))">删除</button></div></div></article></div>
   <div v-if="resumes.length" class="rw-list-extras"><button @click="action(()=>createResume('one'))">＋ 一页合成示例</button><button @click="action(()=>createResume('two'))">＋ 两页合成示例</button></div>
  </main>

  <ModelSettingsPage v-else-if="view==='models'"/>
  <div v-else-if="doc&&current" class="rw-editor-page"><div class="rw-editor-top"><button :disabled="busy" @click="action(()=>backToList())">← 我的简历</button><input v-model="title" aria-label="简历名称" maxlength="120" :disabled="busy"><div class="rw-undo-tools" role="group" aria-label="撤销与重做"><button :disabled="busy||!canUndo" aria-label="撤销" aria-keyshortcuts="Control+z Meta+z" title="撤销（Ctrl / ⌘ Z）" @click="ws.undo"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 5 4 10l5 5M4 10h10a6 6 0 0 1 0 12" transform="translate(0 -3)"/></svg>撤销</button><button :disabled="busy||!canRedo" aria-label="重做" aria-keyshortcuts="Control+y Control+Shift+z Meta+Shift+z" title="重做（Ctrl Y / Ctrl ⇧ Z / ⌘ ⇧ Z）" @click="ws.redo"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m15 5 5 5-5 5m5-5H10a6 6 0 0 0 0 12" transform="translate(0 -3)"/></svg>重做</button></div><span data-testid="save-status" role="status" :class="{failed:!!saveError}">{{saveStatus}}</span><span class="rw-revision">r{{current.revision}}</span><button :disabled="busy" @click="action(openJobMatch)">职位匹配</button><button class="rw-save-version" :disabled="busy" @click="action(checkpoint)">保存版本</button><button :disabled="busy" @click="action(openRedactedPdf)">脱敏 PDF</button><button class="rw-primary" :disabled="busy" @click="action(exportPdf)">导出 PDF</button></div>
   <div v-if="saveError" class="rw-save-error" role="alert">{{saveError}}<button @click="action(ws.flush)">重试保存</button><button @click="action(preserveDraftCopy)">另存副本</button><button @click="action(ws.reloadCurrent)">重新载入</button></div>
   <div class="rw-editor-grid"><aside class="rw-module-nav"><div class="rw-module-heading"><strong>修改简历</strong><small>选择左边模块，右边实时预览</small></div><div class="rw-module-list"><span class="rw-menu-caption">简历内容</span><button :class="{active:selected==='basic'}" @click="selected='basic'">基本信息 <span aria-hidden="true">›</span></button><button v-for="section in doc.content.sections" :key="section.id" :class="{active:selected===section.id,hidden:!section.visible}" :data-testid="'nav-'+section.type" @click="selected=section.id">{{section.title}} <span aria-hidden="true">›</span></button><div class="rw-add-module"><select v-model="newType" aria-label="新模块类型"><option v-for="(label,key) in names" :key="key" :value="key">{{label}}</option></select><button :disabled="doc.content.sections.length>=20" @click="addSection">＋ 添加模块</button></div><span class="rw-menu-caption">版式与文件</span><button :class="{active:selected==='images'}" @click="selected='images'">照片与校徽 <span aria-hidden="true">›</span></button><button :class="{active:selected==='layout'}" @click="selected='layout'">版式设置 <span aria-hidden="true">›</span></button><button :class="{active:selected==='versions'}" @click="action(()=>selectPanel('versions'))">历史版本 <span aria-hidden="true">›</span></button></div></aside>
    <section class="rw-form-pane"><fieldset :disabled="busy"><div class="rw-form-heading"><span class="rw-overline">简历内容 / EDITOR</span><h1>{{selectedSection?.title||({basic:'基本信息',images:'照片与校徽',layout:'版式设置',versions:'历史版本'} as Record<string,string>)[selected]}}</h1><p>修改后会自动保存，右侧简历随输入更新。</p></div>
      <div v-if="selected==='basic'" class="rw-basic-fields"><label class="form-label">姓名<input v-model="doc.content.name" maxlength="30" aria-label="姓名"></label><label class="form-label">求职方向<input v-model="doc.content.headline" maxlength="70" aria-label="求职方向"></label><label class="form-label">联系电话<input v-model="doc.content.phone" maxlength="30" aria-label="手机"></label><label class="form-label">邮箱<input v-model="doc.content.email" maxlength="100" aria-label="邮箱"></label><label class="form-label">城市 / 毕业年份<input v-model="doc.content.location" maxlength="40" aria-label="城市和毕业年份"></label></div>
      <FocusedSection v-else-if="selectedSection" :section="selectedSection" :content="doc.content" @removed="removeSection" @polish="target=>action(()=>openSuggestion(target))"/>
      <div v-else-if="selected==='images'" class="rw-image-fields"><ImageControls kind="photo" title="证件照" :slot="doc.layout.photo" :asset="assets.photo" :max-bytes="config.maxUploadBytes" @imported="imported('photo',$event)" @removed="doc.layout.photo.id=null"/><ImageControls kind="logo" title="学校 Logo" :slot="doc.layout.logo" :asset="assets.logo" :max-bytes="config.maxUploadBytes" @imported="imported('logo',$event)" @removed="doc.layout.logo.id=null"/></div>
      <LayoutControls v-else-if="selected==='layout'" :layout="doc.layout"/>
      <div v-else-if="selected==='versions'" class="rw-version-fields"><label class="form-label">快照名称<input v-model="versionLabel" placeholder="例如：Java 岗投递前" aria-label="快照名称"></label><button class="rw-primary" @click="action(checkpoint)">保存版本快照</button><p>先查看对比，再选择需要恢复的版本。恢复前会自动保留当前版本。</p><ol><li v-for="version in versions" :key="version.id"><span><strong>{{version.label}}</strong><small>r{{version.sourceRevision}} · {{new Date(version.createdAt).toLocaleString('zh-CN')}}</small></span><div class="rw-version-actions"><button @click="action(()=>compareVersion(version))">对比</button><button @click="action(()=>restore(version))">恢复</button></div></li></ol></div>
     </fieldset></section>
    <section class="rw-preview-area" ref="previewPane"><div class="rw-preview-heading"><span data-testid="preview-status" role="status">{{previewState}}</span><span>A4 · {{pageCount}} 页</span></div><div class="rw-paper-wrap" :style="{width:794*scale+'px',height:paperHeight*scale+'px'}"><iframe v-if="previewUrl" ref="previewFrame" title="简历实时预览" :src="previewUrl" :style="{width:'794px',height:paperHeight+'px',transform:`scale(${scale})`}"></iframe><div v-else class="rw-preview-empty">正在生成预览…</div></div><p v-if="exported" class="rw-export-result">{{exported.redacted?'脱敏 PDF':'PDF'}} 已生成 · 修订 r{{exported.revision}} <a :href="'/api/exports/'+exported.id+'/pdf'" @click.prevent="action(()=>downloadPdf(exported!.id,exported!.redacted))">重新下载</a></p></section>
   </div></div>
 </div>
 <JobMatch v-if="jobSource" :source="jobSource" :return-focus="jobFocus" :suspended="!!aiInitial" @close="closeJobMatch" @settings="action(jobSettings)" @review="reviewJobSuggestion"/>
 <TextSuggestion v-if="aiSource&&aiTarget" :source="aiSource" :target="aiTarget" :initial-suggestion="aiInitial" :return-focus="aiFocus" @close="closeSuggestion" @settings="action(suggestionSettings)" @applying="aiApplying=$event" @applied="result=>action(()=>suggestionApplied(result))"/>
 <BackupPanel v-if="showBackup" :max-bytes="config.maxBackupBytes" @close="showBackup=false" @restored="action(restoredWorkspace)"/>
 <RuntimeStatus v-if="showRuntime" @close="showRuntime=false"/>
 <RedactedPdf v-if="redactedSource" :source="redactedSource" :return-focus="redactedFocus" @close="closeRedactedPdf" @reload="action(reloadAfterRedaction)" @created="result=>action(()=>redactedCreated(result))" @busy="redactedBusy=$event"/>
 <VersionCompare v-if="compared&&doc&&current" :version="compared" :title="title" :document="doc" :revision="current.revision" :dirty="dirty" :busy="busy" :error="problem" :return-focus="comparisonFocus" @close="closeComparison" @restore="action(restoreCompared)"/>
</template>
