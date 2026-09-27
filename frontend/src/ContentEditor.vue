<script setup lang="ts">
import { ref } from 'vue';
import type { ResumeDocument, Section, Entry } from './api';
const props=defineProps<{ content: ResumeDocument['content'] }>();
const newType=ref<Section['type']>('project');
const labels={education:'教育背景',experience:'工作 / 实习经历',project:'项目经历',skills:'专业技能',custom:'自定义文本'};
function entry():Entry {return {id:crypto.randomUUID(),title:'新条目',meta:'',bulleted:true,bullets:['']};}
function addSection(){props.content.sections.push({id:crypto.randomUUID(),type:newType.value,title:labels[newType.value],visible:true,pageBreakBefore:false,entries:[entry()]});}
function move(index:number,step:number){ const sections=props.content.sections; const target=index+step; if(target>=0 && target<sections.length) [sections[index],sections[target]]=[sections[target],sections[index]]; }
function bold(event:MouseEvent, item:Entry){
  const area=(event.currentTarget as HTMLElement).closest('.entry-editor')!.querySelector('textarea')!;
  const start=area.selectionStart,end=area.selectionEnd,text=area.value;
  item.bullets=(text.slice(0,start)+'**'+(text.slice(start,end)||'重点')+'**'+text.slice(end)).split('\n'); area.focus();
}
</script>
<template>
 <div class="content-editor">
  <details class="editor-block" open><summary><span>基本信息</span></summary><div class="basic-section">
   <label>姓名<input v-model="content.name" maxlength="30" aria-label="姓名"></label>
   <label>求职方向<input v-model="content.headline" maxlength="70" aria-label="求职方向"></label>
   <div class="two-inputs"><label>手机<input v-model="content.phone" maxlength="30" aria-label="手机"></label><label>城市 / 毕业年份<input v-model="content.location" maxlength="40" aria-label="城市和毕业年份"></label></div>
   <label>邮箱<input v-model="content.email" maxlength="100" aria-label="邮箱"></label>
  </div></details>
  <details v-for="(section,index) in content.sections" :key="section.id" class="editor-block" :data-testid="'section-'+section.type">
   <summary><span>{{ section.title }}</span><small>{{ section.visible?'':'已隐藏' }} · {{ section.entries.length }} 条</small></summary>
   <div class="section-fields"><label>模块标题<input v-model="section.title" maxlength="40" aria-label="模块标题"></label>
    <div class="section-actions"><label class="check"><input v-model="section.visible" type="checkbox" aria-label="显示模块">显示</label><label class="check"><input v-model="section.pageBreakBefore" type="checkbox" aria-label="模块另起一页">另起一页</label><button :disabled="index===0" aria-label="上移模块" @click="move(index,-1)">↑</button><button :disabled="index===content.sections.length-1" aria-label="下移模块" @click="move(index,1)">↓</button><button class="text-button" aria-label="删除模块" @click="content.sections.splice(index,1)">删除</button></div>
    <div v-for="(item,entryIndex) in section.entries" :key="item.id" class="entry-editor">
     <label>学校 / 公司 / 项目<input v-model="item.title" maxlength="80" aria-label="条目标题"></label><label>时间 / 补充说明<input v-model="item.meta" maxlength="120" aria-label="条目说明"></label>
     <div class="text-toolbar"><button aria-label="加粗所选文字" @click="bold($event,item)"><b>B</b> 加粗</button><label class="check"><input v-model="item.bulleted" type="checkbox" aria-label="使用项目列表">项目列表</label></div>
     <textarea :value="item.bullets.join('\n')" rows="5" aria-label="条目正文" placeholder="每行一条；用 **文字** 标记重点" @input="item.bullets=($event.target as HTMLTextAreaElement).value.split('\n')"></textarea>
     <div class="entry-foot"><small>每行一条，支持 **加粗**</small><button class="text-button" aria-label="删除条目" @click="section.entries.splice(entryIndex,1)">删除条目</button></div>
    </div>
    <button class="add-entry" :disabled="section.entries.length>=15" @click="section.entries.push(entry())">＋ 添加条目</button>
   </div>
  </details>
  <div class="add-section"><select v-model="newType" aria-label="新模块类型"><option v-for="(label,key) in labels" :key="key" :value="key">{{label}}</option></select><button :disabled="content.sections.length>=20" @click="addSection">＋ 添加模块</button></div>
 </div>
</template>
