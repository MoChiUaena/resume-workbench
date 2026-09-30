<script setup lang="ts">
import type { Entry, ResumeDocument, Section } from './api';
import type {ParagraphTarget} from './modelApi';
const props=defineProps<{ section:Section; content:ResumeDocument['content'] }>();
const emit=defineEmits<{removed:[];polish:[ParagraphTarget]} >();
function entry():Entry {return {id:crypto.randomUUID(),title:'新条目',meta:'',bulleted:true,bullets:['']};}
function move(step:number){const items=props.content.sections;const index=items.findIndex(item=>item.id===props.section.id);const target=index+step;if(target>=0&&target<items.length)[items[index],items[target]]=[items[target],items[index]];}
function bold(event:MouseEvent,item:Entry){
 const area=(event.currentTarget as HTMLElement).closest('.entry-fields')!.querySelector('textarea')!;
 const start=area.selectionStart,end=area.selectionEnd,text=area.value;
 item.bullets=(text.slice(0,start)+'**'+(text.slice(start,end)||'重点')+'**'+text.slice(end)).split('\n');area.focus();
}
function polish(event:MouseEvent,item:Entry){
 const area=(event.currentTarget as HTMLElement).closest('.entry-fields')!.querySelector('textarea')!;
 const start=area.selectionStart,end=area.selectionEnd,text=area.value;
 const paragraph=Math.max(0,Math.min(item.bullets.length-1,text.slice(0,start).split('\n').length-1));
 const lineStart=text.lastIndexOf('\n',start-1)+1;
 const nextLine=text.indexOf('\n',lineStart),lineEnd=nextLine<0?text.length:nextLine;
 const invalidSelection=end>lineEnd;
 const whole=item.bullets[paragraph]||'';
 emit('polish',{sectionId:props.section.id,entryId:item.id,paragraph,
  selectionStart:invalidSelection||start===end?0:start-lineStart,
  selectionEnd:invalidSelection?0:start===end?whole.length:end-lineStart,
  invalidSelection});
}
</script>
<template>
 <div class="section-form" :data-testid="'section-'+section.type">
  <div class="section-form-tools"><label><input v-model="section.visible" type="checkbox" aria-label="显示模块">显示该模块</label><label><input v-model="section.pageBreakBefore" type="checkbox" aria-label="模块另起一页">另起一页</label></div>
  <label class="form-label">模块标题<input v-model="section.title" maxlength="40" aria-label="模块标题"></label>
  <div class="reorder-row"><button type="button" :disabled="content.sections[0].id===section.id" aria-label="上移模块" @click="move(-1)">↑ 上移</button><button type="button" :disabled="content.sections.at(-1)?.id===section.id" aria-label="下移模块" @click="move(1)">↓ 下移</button><button type="button" class="quiet-danger" aria-label="删除模块" @click="emit('removed')">删除模块</button></div>
  <div v-for="(item,index) in section.entries" :key="item.id" class="entry-fields">
   <div class="entry-heading"><strong>第 {{index+1}} 条</strong><button type="button" class="quiet-danger" aria-label="删除条目" @click="section.entries.splice(index,1)">删除</button></div>
   <label class="form-label">学校 / 公司 / 项目<input v-model="item.title" maxlength="80" aria-label="条目标题"></label>
   <label class="form-label">时间 / 补充说明<input v-model="item.meta" maxlength="120" aria-label="条目说明"></label>
   <div class="text-toolbar"><button type="button" aria-label="加粗所选文字" @click="bold($event,item)"><b>B</b> 加粗</button><button type="button" aria-label="AI 段落润色" title="可先划选一句或几句" @click="polish($event,item)">AI 润色</button><label><input v-model="item.bulleted" type="checkbox" aria-label="使用项目列表">项目列表</label></div>
   <label class="form-label">经历描述 / 正文<textarea :value="item.bullets.join('\n')" rows="5" aria-label="条目正文" placeholder="每行一条；用 **文字** 标记重点" @input="item.bullets=($event.target as HTMLTextAreaElement).value.split('\n')"></textarea></label>
  </div>
  <button type="button" class="add-entry" :disabled="section.entries.length>=15" @click="section.entries.push(entry())">＋ 添加条目</button>
 </div>
</template>
