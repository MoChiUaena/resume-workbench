<script setup lang="ts">
import {computed,nextTick,onMounted,onUnmounted,ref} from 'vue';
import {ApiFailure,type Resume,type ResumeDocument} from './api';
import {createDocxImport,createDocxImportAttempt,createPdfImport,createPdfImportAttempt,isDocxCreateUncertain,isPdfCreateUncertain,previewDocx,previewPdf,type DocxCreateAttempt,type DocxPreview,type PdfPreview} from './docxImportApi';

const props=defineProps<{format?:'docx'|'pdf';maxBytes:number;returnFocus?:HTMLElement}>();
const emit=defineEmits<{close:[];created:[resume:Resume]}>();
const dialog=ref<HTMLElement>(),closeButton=ref<HTMLButtonElement>();
const file=ref<File>(),preview=ref<DocxPreview|PdfPreview>(),review=ref<ResumeDocument>(),title=ref(''),error=ref('');
const isPdf=computed(()=>props.format==='pdf'),formatName=computed(()=>isPdf.value?'PDF':'Word');
const loading=ref(false),creating=ref(false),attempt=ref<DocxCreateAttempt>();
let previousFocus:HTMLElement|undefined,disposed=false;
const types:{value:ResumeDocument['content']['sections'][number]['type'];label:string}[]=[{value:'education',label:'教育背景'},{value:'experience',label:'工作 / 实习经历'},{value:'project',label:'项目经历'},{value:'skills',label:'专业技能'},{value:'custom',label:'自定义文本'}];
function close(){if(!loading.value&&!creating.value&&!attempt.value)emit('close');}
function limitMiB(){const size=props.maxBytes/1048576;return Number.isInteger(size)?String(size):size.toFixed(2).replace(/\.?0+$/,'');}
function selectFile(event:Event){
 const chosen=(event.target as HTMLInputElement).files?.[0];file.value=chosen;preview.value=undefined;review.value=undefined;title.value='';error.value='';
 if(!chosen)return;
 if(isPdf.value?!/\.pdf$/i.test(chosen.name):!/\.docx$/i.test(chosen.name)){error.value=isPdf.value?'请选择 .pdf 格式的 PDF 文件。':'请选择 .docx 格式的 Word 文件；旧版 .doc 暂不支持。';return;}
 if(chosen.size>props.maxBytes){error.value=`${formatName.value} 文件超过 ${limitMiB()} MiB 限制。`;return;}
 if(chosen.size===0)error.value=`文件为空，请选择有效的 ${isPdf.value?'.pdf':'.docx'} 文件。`;
}
async function loadPreview(){
 if(!file.value||error.value||loading.value||attempt.value)return;
 loading.value=true;error.value='';
 try{const result=isPdf.value?await previewPdf(file.value):await previewDocx(file.value);if(disposed)return;preview.value=result;review.value=structuredClone(result.document);title.value=result.title;}
 catch(cause){if(!disposed)error.value=cause instanceof Error?cause.message:`无法读取 ${formatName.value} 文件。`;}
 finally{loading.value=false;}
}
async function submit(){
 if(creating.value||!review.value||!preview.value)return;
 if(!attempt.value){try{attempt.value=(isPdf.value?createPdfImportAttempt:createDocxImportAttempt)(title.value,review.value,crypto.randomUUID());}catch(cause){error.value=cause instanceof Error?cause.message:'请检查导入内容。';return;}}
 creating.value=true;error.value='';
 try{const resume=await (isPdf.value?createPdfImport:createDocxImport)(attempt.value);if(!disposed)emit('created',resume);}
 catch(cause){if(disposed)return;const code=cause instanceof ApiFailure?cause.code:undefined,status=cause instanceof ApiFailure?cause.status:undefined;if(!(isPdf.value?isPdfCreateUncertain:isDocxCreateUncertain)(code,status))attempt.value=undefined;error.value=cause instanceof Error?cause.message:'创建结果无法确认，请用同一请求重试。';}
 finally{creating.value=false;}
}
function beforeUnload(event:BeforeUnloadEvent){if(attempt.value){event.preventDefault();event.returnValue='';}}
function keys(event:KeyboardEvent){
 if(event.key==='Escape'){event.preventDefault();close();return;}
 if(event.key!=='Tab')return;
 const controls=Array.from(dialog.value?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled)')||[]).filter(element=>element.getClientRects().length);
 const first=controls[0],last=controls.at(-1);if(!first){event.preventDefault();return;}
 if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}
 else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}
}
onMounted(async()=>{previousFocus=document.activeElement instanceof HTMLElement?document.activeElement:undefined;window.addEventListener('keydown',keys);window.addEventListener('beforeunload',beforeUnload);await nextTick();closeButton.value?.focus();});
onUnmounted(()=>{disposed=true;window.removeEventListener('keydown',keys);window.removeEventListener('beforeunload',beforeUnload);nextTick(()=>{if(props.returnFocus?.isConnected)props.returnFocus.focus();else if(previousFocus?.isConnected)previousFocus.focus();});});
</script>
<template>
 <div class="docx-overlay"><section ref="dialog" class="docx-dialog" role="dialog" aria-modal="true" aria-labelledby="docx-title">
  <header><div><span class="rw-overline">本机 {{formatName}} 导入</span><h2 id="docx-title">导入 {{formatName}} 简历</h2></div><button ref="closeButton" :aria-label="`关闭 ${formatName} 导入`" :disabled="loading||creating||!!attempt" @click="close">×</button></header>
  <p v-if="isPdf" class="docx-intro">PDF 仅在本机提取可选择的文字供你检查。扫描件、图片文字和加密 PDF 无法导入；此处不使用 OCR。原 PDF 的排版、图片和多栏阅读顺序不会原样保留，请对照原件核对。确认前不会创建简历。</p>
  <p v-else class="docx-intro">Word 文件仅在本机解析。导入文字和简历字段供你检查；原 DOCX 的排版、图片及嵌入对象不会导入，链接只保留可见文字。确认前不会创建简历。</p>
  <div class="docx-upload"><label>选择 {{formatName}} 文件（{{isPdf?'.pdf':'.docx'}}，最多 {{limitMiB()}} MiB）<input type="file" :accept="isPdf?'.pdf,application/pdf':'.docx,application/vnd.openxmlformats-officedocument.wordprocessingml.document'" :aria-label="`选择 ${formatName} 文件`" :disabled="loading||creating||!!attempt" @change="selectFile"></label><button :disabled="loading||creating||!!attempt||!file||!!error" @click="loadPreview">{{loading?'正在解析…':'预览提取内容'}}</button></div>
  <p v-if="error" class="docx-error" role="alert">{{error}}</p>
  <p v-if="attempt" class="docx-uncertain" role="alert">创建结果尚未确认。请保留本窗口，使用同一请求重试；编辑、重新选文件和关闭暂时锁定。</p>
  <template v-if="preview&&review"><div class="docx-summary"><strong>{{preview.fileName}}</strong><span v-if="preview.format==='pdf'">{{preview.statistics.pages}} 页 · {{preview.statistics.paragraphs}} 段文字 · {{preview.statistics.images}} 张图片</span><span v-else>{{preview.statistics.paragraphs}} 段文字 · {{preview.statistics.tables}} 个表格 · {{preview.statistics.images}} 张图片标记</span></div>
   <ul v-if="preview.warnings.length" class="docx-warnings" aria-label="提取提示"><li v-for="(warning,index) in preview.warnings" :key="index"><strong>{{warning.code}}</strong> {{warning.message}}</li></ul>
   <div class="docx-review"><section class="docx-source"><h3>原文提取</h3><p v-if="isPdf">按 PDF 提取顺序显示可选择文字；多栏顺序和未分类文字请仔细核对。</p><p v-else>按 Word XML 文字顺序显示；未分类的文字也保留供核对。</p><textarea readonly aria-label="原文提取内容" :value="preview.sourceText"></textarea></section>
    <section class="docx-fields"><h3>检查并编辑</h3><fieldset :disabled="loading||creating||!!attempt"><label>新简历名称<input v-model="title" aria-label="新简历名称" maxlength="120" required></label><div class="docx-basic"><label>姓名<input v-model="review.content.name" aria-label="导入姓名" maxlength="30" required></label><label>求职方向<input v-model="review.content.headline" aria-label="导入求职方向" maxlength="70"></label><label>邮箱<input v-model="review.content.email" aria-label="导入邮箱" maxlength="100"></label><label>联系电话<input v-model="review.content.phone" aria-label="导入联系电话" maxlength="30"></label><label>城市 / 毕业年份<input v-model="review.content.location" aria-label="导入城市和毕业年份" maxlength="40"></label></div>
     <div v-for="(section,sectionIndex) in review.content.sections" :key="section.id" class="docx-section"><h4>模块 {{sectionIndex+1}}</h4><div class="docx-section-head"><label>模块类型<select v-model="section.type" :aria-label="`模块 ${sectionIndex+1} 类型`"><option v-for="type in types" :key="type.value" :value="type.value">{{type.label}}</option></select></label><label>模块标题<input v-model="section.title" :aria-label="`模块 ${sectionIndex+1} 标题`" maxlength="40" required></label></div><div v-for="(entry,entryIndex) in section.entries" :key="entry.id" class="docx-entry"><label>条目标题<input v-model="entry.title" :aria-label="`模块 ${sectionIndex+1} 条目 ${entryIndex+1} 标题`" maxlength="80"></label><label>补充信息<input v-model="entry.meta" :aria-label="`模块 ${sectionIndex+1} 条目 ${entryIndex+1} 补充信息`" maxlength="120"></label><label v-for="(_,paragraphIndex) in entry.bullets" :key="paragraphIndex">段落 {{paragraphIndex+1}}<textarea v-model="entry.bullets[paragraphIndex]" :aria-label="`模块 ${sectionIndex+1} 条目 ${entryIndex+1} 段落 ${paragraphIndex+1}`" maxlength="800" rows="3"></textarea></label></div></div>
    </fieldset></section></div>
  </template>
  <footer><button :disabled="loading||creating||!!attempt" @click="close">取消导入</button><button v-if="preview&&review" class="docx-primary" :disabled="loading||creating" @click="submit">{{creating?'正在创建…':attempt?'重试创建':'确认创建新简历'}}</button></footer>
 </section></div>
</template>
<style src="./docx-import.css"></style>
