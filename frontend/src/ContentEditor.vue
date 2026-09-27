<script setup lang="ts">
import { ref, nextTick } from 'vue';
import type { ResumeDocument, Section, Entry } from './api';
const props=defineProps<{ content: ResumeDocument['content'] }>();
const newType=ref<Section['type']>('project');
const openSections=ref(new Set<string>());
const sectionElements=new Map<string,HTMLDetailsElement>();
function bindSection(id:string,element:unknown){if(element instanceof HTMLDetailsElement)sectionElements.set(id,element);else sectionElements.delete(id);}
function rememberToggle(id:string,event:Event){if((event.target as HTMLDetailsElement).open)openSections.value.add(id);else openSections.value.delete(id);}
async function editSection(id:string){
 await nextTick();const element=sectionElements.get(id);if(!element)return;element.open=true;await nextTick();
 const panel=element.closest('.editor-panel');if(panel)panel.scrollTop+=element.getBoundingClientRect().top-panel.getBoundingClientRect().top-12;
 element.querySelector<HTMLTextAreaElement>('textarea')?.focus({preventScroll:true});
}
const labels={education:'教育背景',experience:'工作 / 实习经历',project:'项目经历',skills:'专业技能',custom:'自定义文本'};
function entry():Entry {return {id:crypto.randomUUID(),title:'新条目',meta:'',bulleted:true,bullets:['']};}
function addSection(){const id=crypto.randomUUID();props.content.sections.push({id,type:newType.value,title:labels[newType.value],visible:true,pageBreakBefore:false,entries:[entry()]});void editSection(id);}
function move(index:number,step:number){ const sections=props.content.sections; const target=index+step; if(target>=0 && target<sections.length) [sections[index],sections[target]]=[sections[target],sections[index]]; }
function bold(event:MouseEvent, item:Entry){
  const area=(event.currentTarget as HTMLElement).closest('.entry-editor')!.querySelector('textarea')!;
  const start=area.selectionStart,end=area.selectionEnd,text=area.value;
  item.bullets=(text.slice(0,start)+'**'+(text.slice(start,end)||'重点')+'**'+text.slice(end)).split('\n'); area.focus();
}
</script>
<template>
 <div class="content-editor">
  <div class="content-guide"><h3>编辑简历正文</h3><p>选择下方模块，修改标题、时间和经历描述。修改会自动保存，并同步到右侧预览。</p><div class="content-shortcuts"><button v-for="section in content.sections" :key="section.id" :aria-label="'编辑'+section.title" @click="editSection(section.id)">{{section.title}} <span aria-hidden="true">↗</span></button></div><p v-if="!content.sections.length">使用下方“添加模块”，开始填写教育、项目或工作经历。</p></div>
  <details class="editor-block" open><summary><span>基本信息</span></summary><div class="basic-section">
   <label>姓名<input v-model="content.name" maxlength="30" aria-label="姓名"></label>
   <label>求职方向<input v-model="content.headline" maxlength="70" aria-label="求职方向"></label>
   <div class="two-inputs"><label>手机<input v-model="content.phone" maxlength="30" aria-label="手机"></label><label>城市 / 毕业年份<input v-model="content.location" maxlength="40" aria-label="城市和毕业年份"></label></div>
   <label>邮箱<input v-model="content.email" maxlength="100" aria-label="邮箱"></label>
  </div></details>
  <details v-for="(section,index) in content.sections" :key="section.id" :ref="element=>bindSection(section.id,element)" class="editor-block" :data-testid="'section-'+section.type" @toggle="rememberToggle(section.id,$event)">
   <summary><span>{{ section.title }}<small>{{ section.visible?'':'已隐藏 · ' }}{{ section.entries.length }} 条</small></span><span class="section-edit-label">{{openSections.has(section.id)?'收起':'编辑内容'}}</span></summary>
   <div class="section-fields"><label>模块标题<input v-model="section.title" maxlength="40" aria-label="模块标题"></label>
    <div class="section-actions"><label class="check"><input v-model="section.visible" type="checkbox" aria-label="显示模块">显示</label><label class="check"><input v-model="section.pageBreakBefore" type="checkbox" aria-label="模块另起一页">另起一页</label><button :disabled="index===0" aria-label="上移模块" @click="move(index,-1)">↑</button><button :disabled="index===content.sections.length-1" aria-label="下移模块" @click="move(index,1)">↓</button><button class="text-button" aria-label="删除模块" @click="content.sections.splice(index,1)">删除</button></div>
    <div v-for="(item,entryIndex) in section.entries" :key="item.id" class="entry-editor">
     <label>学校 / 公司 / 项目<input v-model="item.title" maxlength="80" aria-label="条目标题"></label><label>时间 / 补充说明<input v-model="item.meta" maxlength="120" aria-label="条目说明"></label>
     <div class="text-toolbar"><button aria-label="加粗所选文字" @click="bold($event,item)"><b>B</b> 加粗</button><label class="check"><input v-model="item.bulleted" type="checkbox" aria-label="使用项目列表">项目列表</label></div>
     <label class="body-label">经历描述 / 正文<textarea :value="item.bullets.join('\n')" rows="6" aria-label="条目正文" placeholder="在这里填写具体经历，每行一条；用 **文字** 标记重点" @input="item.bullets=($event.target as HTMLTextAreaElement).value.split('\n')"></textarea></label>
     <div class="entry-foot"><small>每行一条，支持 **加粗**</small><button class="text-button" aria-label="删除条目" @click="section.entries.splice(entryIndex,1)">删除条目</button></div>
    </div>
    <button class="add-entry" :disabled="section.entries.length>=15" @click="section.entries.push(entry())">＋ 添加条目</button>
   </div>
  </details>
  <div class="add-section"><select v-model="newType" aria-label="新模块类型"><option v-for="(label,key) in labels" :key="key" :value="key">{{label}}</option></select><button :disabled="content.sections.length>=20" @click="addSection">＋ 添加模块</button></div>
 </div>
</template>
