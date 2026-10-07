import test from 'node:test';
import assert from 'node:assert/strict';
import {reactive} from 'vue';
import {createDocxImportAttempt,isDocxCreateReceipt,isDocxCreateUncertain} from '../src/docxImportApi.ts';

const mutationId='11111111-1111-4111-8111-111111111111';
const resumeId='22222222-2222-4222-8222-222222222222';
const slot={id:null,visible:true,widthMm:26,heightMm:34,fit:'cover',quarterTurns:0,zoom:1,positionX:50,positionY:50};
const document=()=>({schemaVersion:4 as const,content:{name:'奶龙',headline:'Java 开发',email:'nailong@example.invalid',phone:'',location:'杭州',sections:[{id:'education-1',type:'education' as const,title:'教育背景',visible:true,pageBreakBefore:false,entries:[{id:'school-1',title:'测试大学',meta:'',bulleted:true,bullets:['软件工程'] }]}]},layout:{template:'classic' as const,font:'sans' as const,fontSize:9.4,lineHeight:1.68,sectionGapMm:4,marginMm:16,swapImages:false,photo:{...slot},logo:{...slot},presentation:{language:'zh' as const,accentColor:'#244f63',alignment:'left' as const,contactStyle:'plain' as const,headingStyle:'template' as const,marginHorizontalMm:16,marginTopMm:16,marginBottomMm:16,entryGapMm:3,paragraphGapMm:.9}}});
const resume=(attempt:ReturnType<typeof createDocxImportAttempt>)=>({id:resumeId,title:attempt.title,document:attempt.document,revision:1,lastMutationId:null,updatedAt:'2026-10-07T00:00:00Z'});

test('create attempt freezes a deep copy of the exact reviewed request',()=>{
 const source=document();const attempt=createDocxImportAttempt('  导入简历  ',source,mutationId);
 source.content.name='后来修改';source.content.sections[0].entries[0].bullets[0]='另一段';
 assert.equal(attempt.title,'导入简历');assert.equal(attempt.document.content.name,'奶龙');assert.equal(attempt.document.content.sections[0].entries[0].bullets[0],'软件工程');
 assert.equal(Object.isFrozen(attempt.document.content.sections[0].entries[0].bullets),true);
 assert.throws(()=>{attempt.document.content.name='篡改';},TypeError);
});

test('create attempt snapshots the Vue reactive review without keeping its proxy',()=>{
 const source=reactive(document());
 source.content.name='奶龙修订';
 const attempt=createDocxImportAttempt('导入简历',source,mutationId);
 source.content.name='随后又修改';
 assert.equal(attempt.document.content.name,'奶龙修订');
 assert.equal(Object.isFrozen(attempt.document.content),true);
});

test('mapped DOCX paragraphs may have an empty entry heading',()=>{
 const mapped=document();mapped.content.sections[0].entries[0].title='';
 assert.equal(createDocxImportAttempt('导入简历',mapped,mutationId).document.content.sections[0].entries[0].title,'');
});

test('fresh receipt requires the matching mutation and exact reviewed r1 content',()=>{
 const attempt=createDocxImportAttempt('导入简历',document(),mutationId);const original=resume(attempt);
 assert.equal(isDocxCreateReceipt({mutationId,resume:original},attempt),true);
 assert.equal(isDocxCreateReceipt({mutationId:'33333333-3333-4333-8333-333333333333',resume:original},attempt),false);
 assert.equal(isDocxCreateReceipt({mutationId,resume:{...original,title:'别的标题'}},attempt),false);
 assert.equal(isDocxCreateReceipt({mutationId,resume:{...original,document:{...original.document,content:{...original.document.content,name:'错误'}}}},attempt),false);
});

test('edited retry accepts complete current state, including replacement photo, but rejects malformed records',()=>{
 const attempt=createDocxImportAttempt('导入简历',document(),mutationId),original=resume(attempt);
 const edited={...original,revision:3,title:'随后修改',document:{...original.document,content:{...original.document.content,name:'新名字'},layout:{...original.document.layout,photo:{...slot,id:resumeId}}}};
 assert.equal(isDocxCreateReceipt({mutationId,resume:edited},attempt),true);
 for(const changed of [{id:'bad'},{revision:0},{revision:1},{updatedAt:'yesterday'},{document:{schemaVersion:4}},{document:{...edited.document,content:{...edited.document.content,sections:[{...edited.document.content.sections[0],entries:[{...edited.document.content.sections[0].entries[0],bullets:['x'.repeat(801)]}]}]}}}]){
  assert.equal(isDocxCreateReceipt({mutationId,resume:{...edited,...changed}},attempt),false);
 }
 assert.equal(isDocxCreateReceipt({mutationId,resume:{...edited,revision:1}},attempt),false);
});

test('only known business failures release an uncertain create',()=>{
 for(const code of ['NETWORK_ERROR','INTERNAL_ERROR','DATABASE_UNAVAILABLE',undefined,'DOCX_INVALID'])assert.equal(isDocxCreateUncertain(code),code!=='DOCX_INVALID');
 for(const code of ['DOCX_IMPORT_CONFLICT','DOCX_IMPORT_DELETED','DOCX_CONTENT_TOO_LARGE','INVALID_INPUT'])assert.equal(isDocxCreateUncertain(code),false);
});
