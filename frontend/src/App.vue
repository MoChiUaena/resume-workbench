<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import AppearanceControls from './AppearanceControls.vue';
import BackupPanel from './BackupPanel.vue';
import FocusedSection from './FocusedSection.vue';
import ImageControls from './ImageControls.vue';
import { api, type Asset, type Resume, type Section, type Summary, type Version } from './api';
import { useWorkspace } from './useWorkspace';

const ws=useWorkspace();
const {resumes,current,document:doc,title,dirty,saving,saveError,saveStatus}=ws;
const uiTheme=ref<'a'|'b'>(localStorage.getItem('rw-ui-theme')==='b'?'b':'a');
const darkMode=ref(localStorage.getItem('rw-dark-mode')==='1');
watch([uiTheme,darkMode],([theme,dark])=>{
  document.documentElement.dataset.uiTheme=theme;
  document.documentElement.dataset.dark=dark?'true':'false';
  localStorage.setItem('rw-ui-theme',theme);localStorage.setItem('rw-dark-mode',dark?'1':'0');
},{immediate:true});

const view=ref<'list'|'editor'>('list'), listMode=ref<'list'|'grid'>(localStorage.getItem('rw-list-mode')==='grid'?'grid':'list');
watch(listMode,mode=>localStorage.setItem('rw-list-mode',mode));
const cache=reactive<Record<string,Resume>>({});
const selected=ref('basic'), newType=ref<Section['type']>('project');
const names:Record<Section['type'],string>={education:'教育背景',experience:'工作 / 实习经历',project:'项目经历',skills:'专业技能',custom:'自定义文本'};
const selectedSection=computed(()=>doc.value?.content.sections.find(section=>section.id===selected.value));
const config=ref({maxUploadBytes:5242880,maxBackupBytes:268435456}), assets=reactive<{photo?:Asset;logo?:Asset}>({});
const showBackup=ref(false);
async function openBackup(){await ws.flush();showBackup.value=true;}
async function restoredWorkspace(){view.value='list';routeList();await ws.reloadList();await hydrateCards();}
const versions=ref<Version[]>([]),versionLabel=ref(''),exported=ref<{id:string;revision:number}>();
const previewUrl=ref(''),previewState=ref('准备预览'),pageCount=ref(1),scale=ref(.85),problem=ref(''),busy=ref(false);
const previewPane=ref<HTMLElement>(),previewFrame=ref<HTMLIFrameElement>();
const paperHeight=computed(()=>1123*pageCount.value+(pageCount.value-1)*23);
let generation=0,previewTimer:ReturnType<typeof setTimeout>,observer:ResizeObserver|undefined;

async function action(fn:()=>Promise<unknown>){busy.value=true;problem.value='';try{await fn();}catch(e){problem.value=e instanceof Error?e.message:String(e);}finally{busy.value=false;}}
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
  if(!window.confirm(`删除「${item.title}」及其历史版本？图片文件会保留。`))return;
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
async function restore(version:Version){await ws.flush();if(!current.value)return;await ws.install(await api<Resume>(`/api/resumes/${current.value.id}/versions/${version.id}/restore`,{expectedRevision:current.value.revision}));versions.value=await api(`/api/resumes/${current.value.id}/versions`);selected.value='versions';}
async function exportPdf(){await ws.flush();if(!current.value)return;const result=await api<{id:string;revision:number}>(`/api/resumes/${current.value.id}/export`,{expectedRevision:current.value.revision});exported.value=result;const link=document.createElement('a');link.href=`/api/exports/${result.id}/pdf`;link.download='resume-workbench.pdf';link.click();if(selected.value==='versions')versions.value=await api(`/api/resumes/${current.value.id}/versions`);}
function imported(kind:'photo'|'logo',asset:Asset){if(!doc.value)return;assets[kind]=asset;Object.assign(doc.value.layout[kind],{id:asset.id,visible:true,zoom:1,quarterTurns:0,positionX:50,positionY:50});}
function layoutMessage(event:MessageEvent){if(event.origin!==location.origin||event.source!==previewFrame.value?.contentWindow||event.data?.type!=='resume-layout')return;pageCount.value=event.data.pages||1;previewState.value=event.data.error?'排版超限':'预览已更新';if(event.data.error)problem.value='当前内容无法安全分页，请缩短单条内容或减小字号。';}
async function refresh(){if(!doc.value||view.value!=='editor')return;const currentGeneration=++generation;previewState.value='更新预览中';try{const result=await api<{url:string}>('/api/documents/preview',JSON.parse(JSON.stringify(doc.value)));if(currentGeneration===generation)previewUrl.value=result.url;}catch(e){if(currentGeneration===generation){previewState.value='预览失败';problem.value=e instanceof Error?e.message:String(e);}}}
watch(doc,()=>{generation++;previewState.value='更新预览中';clearTimeout(previewTimer);previewTimer=setTimeout(refresh,350);},{deep:true});
watch(()=>current.value?.id,()=>{previewUrl.value='';pageCount.value=1;exported.value=undefined;versions.value=[];});
watch(()=>[doc.value?.layout.photo.id,doc.value?.layout.logo.id],async ids=>{for(const [index,kind] of (['photo','logo'] as const).entries()){const id=ids[index];if(!id){assets[kind]=undefined;continue;}try{const asset=await api<Asset>('/api/assets/'+id);if(doc.value?.layout[kind].id===id)assets[kind]=asset;}catch{assets[kind]=undefined;}}});
function resizePreview(){if(!previewPane.value)return;scale.value=Math.max(.25,Math.min(1,(previewPane.value.clientWidth-48)/794));}
watch(view,async()=>{await nextTick();observer?.disconnect();if(view.value==='editor'&&previewPane.value){observer=new ResizeObserver(resizePreview);observer.observe(previewPane.value);resizePreview();if(doc.value)await refresh();}});
async function popstate(){await action(async()=>{const query=new URLSearchParams(location.search),id=query.get('resume');if(query.get('view')==='editor'&&id)await editResume(id,false);else await backToList(false);});}
onMounted(async()=>{window.addEventListener('message',layoutMessage);window.addEventListener('beforeunload',ws.beforeUnload);window.addEventListener('popstate',popstate);await action(async()=>{config.value=await api('/api/config');await ws.initialize();const query=new URLSearchParams(location.search),id=query.get('resume');if(query.get('view')==='editor'&&id&&resumes.value.some(item=>item.id===id))await editResume(id,false);else await hydrateCards();});});
onUnmounted(()=>{observer?.disconnect();clearTimeout(previewTimer);ws.dispose();window.removeEventListener('message',layoutMessage);window.removeEventListener('beforeunload',ws.beforeUnload);window.removeEventListener('popstate',popstate);});
</script>
<template>
 <div class="rw-app" :inert="showBackup">
  <header class="rw-header"><div class="rw-header-inner"><button class="rw-brand" @click="view==='editor'?action(()=>backToList()):undefined"><span class="rw-mark">简</span><span>简历工作台<small>RESUME WORKBENCH</small></span></button><nav><button :class="{active:view==='list'}" @click="view==='editor'?action(()=>backToList()):undefined">我的简历</button><span v-if="view==='editor'" class="active">简历编辑</span></nav><button class="backup-open" :disabled="busy" @click="action(openBackup)">备份与恢复</button><div class="rw-local">● 数据保存在本机</div><AppearanceControls v-model:theme="uiTheme" v-model:dark="darkMode"/></div></header>
  <p v-if="problem" class="rw-error" role="alert">{{problem}} <button @click="problem=''">收起</button></p>

  <main v-if="view==='list'" class="rw-list-page"><div class="rw-list-heading"><div><span class="rw-overline">简历管理</span><h1>我的简历 <small>{{String(resumes.length).padStart(2,'0')}}</small></h1><p>从已有版本继续编辑，或为新岗位复制一份。</p></div><button class="rw-primary" :disabled="busy" @click="action(()=>createResume('blank'))">＋ 新建简历</button></div>
   <div class="rw-list-toolbar"><div><button class="active">全部简历 <small>{{resumes.length}}</small></button><span>最近修改优先</span></div><div class="rw-view-modes"><button aria-label="列表视图" :aria-pressed="listMode==='list'" @click="listMode='list'">☰ 列表</button><button aria-label="网格视图" :aria-pressed="listMode==='grid'" @click="listMode='grid'">▦ 网格</button></div></div>
   <div v-if="!resumes.length" class="rw-empty"><h2>从第一份简历开始</h2><p>新建空白简历，或用合成示例熟悉编辑和 PDF 导出。</p><div><button class="rw-primary" @click="action(()=>createResume('blank'))">新建空白简历</button><button @click="action(()=>createResume('one'))">创建一页示例</button><button @click="action(()=>createResume('two'))">创建两页示例</button></div></div>
   <div v-else class="rw-collection" :class="{grid:listMode==='grid'}"><article v-for="item in resumes" :key="item.id" class="rw-resume-card" :data-testid="'resume-'+item.id"><button class="rw-thumb" :aria-label="'编辑'+item.title" @click="action(()=>editResume(item.id))"><span class="rw-mini-paper"><span class="mini-name">{{cache[item.id]?.document.content.name||'简历'}}</span><span class="mini-line"></span><span v-for="part in cache[item.id]?.document.content.sections.filter(s=>s.visible).slice(0,4)||[]" :key="part.id" class="mini-section">{{part.title}}<i></i><i></i></span></span></button><div class="rw-card-body"><span class="rw-card-tag">{{cache[item.id]?.document.content.headline||'简历'}} · 修订 r{{item.revision}}</span><h2>{{item.title}}</h2><p>修改于 {{new Date(item.updatedAt).toLocaleString('zh-CN')}} · {{cache[item.id]?.document.layout.template==='banner'?'并列页眉':'经典单栏'}}</p><div class="rw-card-actions"><button class="rw-primary" :disabled="busy" @click="action(()=>editResume(item.id))">继续编辑</button><button :disabled="busy" @click="action(()=>renameResume(item))">重命名</button><button :disabled="busy" @click="action(()=>duplicateResume(item))">复制</button><button class="rw-delete" :disabled="busy" @click="action(()=>deleteResume(item))">删除</button></div></div></article></div>
   <div v-if="resumes.length" class="rw-list-extras"><button @click="action(()=>createResume('one'))">＋ 一页合成示例</button><button @click="action(()=>createResume('two'))">＋ 两页合成示例</button></div>
  </main>

  <div v-else-if="doc&&current" class="rw-editor-page"><div class="rw-editor-top"><button @click="action(()=>backToList())">← 我的简历</button><input v-model="title" aria-label="简历名称" maxlength="120" :disabled="busy"><span data-testid="save-status" role="status" :class="{failed:!!saveError}">{{saveStatus}}</span><span class="rw-revision">r{{current.revision}}</span><button @click="action(checkpoint)">保存版本</button><button class="rw-primary" :disabled="busy" @click="action(exportPdf)">导出 PDF</button></div>
   <div v-if="saveError" class="rw-save-error" role="alert">{{saveError}}<button @click="action(ws.flush)">重试保存</button><button @click="action(ws.preserveAsCopy)">另存副本</button><button @click="action(ws.reloadCurrent)">重新载入</button></div>
   <div class="rw-editor-grid"><aside class="rw-module-nav"><div class="rw-module-heading"><strong>修改简历</strong><small>选择左边模块，右边实时预览</small></div><div class="rw-module-list"><span class="rw-menu-caption">简历内容</span><button :class="{active:selected==='basic'}" @click="selected='basic'">基本信息 <span aria-hidden="true">›</span></button><button v-for="section in doc.content.sections" :key="section.id" :class="{active:selected===section.id,hidden:!section.visible}" :data-testid="'nav-'+section.type" @click="selected=section.id">{{section.title}} <span aria-hidden="true">›</span></button><div class="rw-add-module"><select v-model="newType" aria-label="新模块类型"><option v-for="(label,key) in names" :key="key" :value="key">{{label}}</option></select><button :disabled="doc.content.sections.length>=20" @click="addSection">＋ 添加模块</button></div><span class="rw-menu-caption">版式与文件</span><button :class="{active:selected==='images'}" @click="selected='images'">照片与校徽 <span aria-hidden="true">›</span></button><button :class="{active:selected==='layout'}" @click="selected='layout'">版式设置 <span aria-hidden="true">›</span></button><button :class="{active:selected==='versions'}" @click="action(()=>selectPanel('versions'))">历史版本 <span aria-hidden="true">›</span></button></div></aside>
    <section class="rw-form-pane"><fieldset :disabled="busy"><div class="rw-form-heading"><span class="rw-overline">简历内容 / EDITOR</span><h1>{{selectedSection?.title||({basic:'基本信息',images:'照片与校徽',layout:'版式设置',versions:'历史版本'} as Record<string,string>)[selected]}}</h1><p>修改后会自动保存，右侧简历随输入更新。</p></div>
      <div v-if="selected==='basic'" class="rw-basic-fields"><label class="form-label">姓名<input v-model="doc.content.name" maxlength="30" aria-label="姓名"></label><label class="form-label">求职方向<input v-model="doc.content.headline" maxlength="70" aria-label="求职方向"></label><label class="form-label">联系电话<input v-model="doc.content.phone" maxlength="30" aria-label="手机"></label><label class="form-label">邮箱<input v-model="doc.content.email" maxlength="100" aria-label="邮箱"></label><label class="form-label">城市 / 毕业年份<input v-model="doc.content.location" maxlength="40" aria-label="城市和毕业年份"></label></div>
      <FocusedSection v-else-if="selectedSection" :section="selectedSection" :content="doc.content" @removed="removeSection"/>
      <div v-else-if="selected==='images'" class="rw-image-fields"><ImageControls kind="photo" title="证件照" :slot="doc.layout.photo" :asset="assets.photo" :max-bytes="config.maxUploadBytes" @imported="imported('photo',$event)" @removed="doc.layout.photo.id=null"/><ImageControls kind="logo" title="学校 Logo" :slot="doc.layout.logo" :asset="assets.logo" :max-bytes="config.maxUploadBytes" @imported="imported('logo',$event)" @removed="doc.layout.logo.id=null"/></div>
      <div v-else-if="selected==='layout'" class="rw-layout-fields"><label class="form-label">简历模板<select v-model="doc.layout.template" aria-label="简历模板"><option value="classic">经典单栏</option><option value="banner">并列页眉</option></select></label><label class="form-label">PDF 中文字体<select v-model="doc.layout.font" aria-label="中文字体"><option value="sans">黑体</option><option value="serif">宋体</option></select></label><label class="form-label">图片位置<select v-model="doc.layout.swapImages" aria-label="页眉布局"><option :value="false">证件照在前 · 右上角学校 Logo</option><option :value="true">学校 Logo 在前 · 右上角证件照</option></select></label><div class="rw-form-pair"><label class="form-label">正文字号（pt）<input v-model.number="doc.layout.fontSize" type="number" min="9" max="12" step="0.2" aria-label="正文字号"></label><label class="form-label">行距<input v-model.number="doc.layout.lineHeight" type="number" min="1.3" max="1.85" step="0.05" aria-label="正文行距"></label><label class="form-label">模块间距（mm）<input v-model.number="doc.layout.sectionGapMm" type="number" min="2" max="8" aria-label="模块间距"></label><label class="form-label">页边距（mm）<input v-model.number="doc.layout.marginMm" type="number" min="12" max="22" aria-label="页边距"></label></div></div>
      <div v-else-if="selected==='versions'" class="rw-version-fields"><label class="form-label">快照名称<input v-model="versionLabel" placeholder="例如：Java 岗投递前" aria-label="快照名称"></label><button class="rw-primary" @click="action(checkpoint)">保存版本快照</button><p>恢复前会自动保留当前版本。</p><ol><li v-for="version in versions" :key="version.id"><span><strong>{{version.label}}</strong><small>r{{version.sourceRevision}} · {{new Date(version.createdAt).toLocaleString('zh-CN')}}</small></span><button @click="action(()=>restore(version))">恢复</button></li></ol></div>
     </fieldset></section>
    <section class="rw-preview-area" ref="previewPane"><div class="rw-preview-heading"><span data-testid="preview-status" role="status">{{previewState}}</span><span>A4 · {{pageCount}} 页</span></div><div class="rw-paper-wrap" :style="{width:794*scale+'px',height:paperHeight*scale+'px'}"><iframe v-if="previewUrl" ref="previewFrame" title="简历实时预览" :src="previewUrl" :style="{width:'794px',height:paperHeight+'px',transform:`scale(${scale})`}"></iframe><div v-else class="rw-preview-empty">正在生成预览…</div></div><p v-if="exported" class="rw-export-result">PDF 已生成 · 修订 r{{exported.revision}} <a :href="'/api/exports/'+exported.id+'/pdf'">重新下载</a></p></section>
   </div></div>
 </div>
 <BackupPanel v-if="showBackup" :max-bytes="config.maxBackupBytes" @close="showBackup=false" @restored="action(restoredWorkspace)"/>
</template>
