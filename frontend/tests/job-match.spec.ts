import {test,expect,type APIRequestContext,type Page} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';

const headers={'X-Local-Resume':'1'};
const root=path.resolve(import.meta.dirname,'../..');
test.beforeEach(()=>test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Job match tests require disposable data.'));
async function models(request:APIRequestContext){return(await request.get('/api/models')).json();}
async function configure(request:APIRequestContext,model='qa-job-normal'){
 let state=await models(request);
 const created=await request.post('/api/models/profiles',{headers,data:{expectedRevision:state.revision,name:'岗位匹配 fixture',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model,apiKey:'job-fixture-canary',clearKey:false}});
 expect(created.ok(),await created.text()).toBe(true);
 state=await created.json();const id=state.profiles.at(-1).id;
 state=await(await request.post('/api/models/profiles/'+id+'/default',{headers,data:{expectedRevision:state.revision}})).json();
 state=await(await request.put('/api/models/enabled',{headers,data:{expectedRevision:state.revision,enabled:true}})).json();
 return{id,revision:state.revision};
}
async function cleanup(request:APIRequestContext,resumeId:string,profileId:string){
 let state=await models(request);
 if(state.enabled)state=await(await request.put('/api/models/enabled',{headers,data:{expectedRevision:state.revision,enabled:false}})).json();
 if(state.profiles.some((profile:any)=>profile.id===profileId))await request.delete('/api/models/profiles/'+profileId,{headers,data:{expectedRevision:state.revision}});
 const resume=await request.get('/api/resumes/'+resumeId);
 if(resume.ok())await request.delete('/api/resumes/'+resumeId,{headers,data:{expectedRevision:(await resume.json()).revision}});
}
async function preparedResume(request:APIRequestContext){
 let created=await request.post('/api/resumes',{headers,data:{title:'岗位匹配 QA · 合成数据',sample:'one'}});
 if(!created.ok()&&(await created.json()).code==='WORKSPACE_BUSY'){
  await new Promise(resolve=>setTimeout(resolve,400));
  created=await request.post('/api/resumes',{headers,data:{title:'岗位匹配 QA · 合成数据',sample:'one'}});
 }
 expect(created.ok()).toBe(true);
 let resume=await created.json();
 resume.document.content.name='PRIVATE-NAME-CANARY';resume.document.content.email='private-email-canary@example.invalid';resume.document.content.phone='PRIVATE-PHONE-CANARY';
 const project=resume.document.content.sections.find((section:any)=>section.type==='project');
 const other=resume.document.content.sections.find((section:any)=>section.id!==project.id);
 project.entries[0].title='项目标题 CANARY';project.entries[0].bullets[0]='PROJECT-BODY-CANARY 整理接口文档并记录联调问题。';
 other.entries[0].title='UNSELECTED-TITLE-CANARY';other.entries[0].bullets[0]='UNSELECTED-BODY-CANARY';
 resume=await(await request.put('/api/resumes/'+resume.id,{headers,data:{title:resume.title,document:resume.document,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}})).json();
 return{resume,project,other};
}
async function open(page:Page,id:string){
 await page.goto('/?view=editor&resume='+id);
 await expect(page.getByTestId('preview-status')).toContainText('预览已更新');
 await page.getByRole('button',{name:'职位匹配',exact:true}).click();
 return page.getByRole('dialog',{name:'职位匹配'});
}
async function ready(page:Page,id:string,jd='具备 Java 项目经验；拥有云平台部署经验；具备缺失技能'){
 const dialog=await open(page,id);
 await dialog.getByLabel('匹配模块 项目经历').check();
 await dialog.getByLabel('岗位要求原文').fill(jd);
 await expect(dialog.getByRole('button',{name:'生成匹配分析'})).toHaveCount(0);
 await dialog.getByRole('button',{name:'预览发送内容'}).click();
 await expect(dialog.getByTestId('job-payload')).toBeVisible();
 return dialog;
}
async function fixtureRequests(request:APIRequestContext){return(await(await request.get('http://127.0.0.1:18770/requests')).json()) as any[];}

test('editor opens job matching before any model request',async({page,request})=>{
 const created=await(await request.post('/api/resumes',{headers,data:{title:'职位匹配 QA · 合成数据',sample:'one'}})).json();
 try{
  await page.goto(`/?view=editor&resume=${created.id}`);
  await expect(page.getByTestId('preview-status')).toContainText('预览已更新');
  const entry=page.getByRole('button',{name:'职位匹配',exact:true});
  await expect(entry).toBeVisible({timeout:1500});
  await entry.click();
  await expect(page.getByRole('dialog',{name:'职位匹配'})).toBeVisible();
 }finally{
  const current=await(await request.get('/api/resumes/'+created.id)).json();
  await request.delete('/api/resumes/'+created.id,{headers,data:{expectedRevision:current.revision}});
 }
});

test('selected module is the exact sent payload; report is read only until review and apply',async({page,request})=>{
 const profile=await configure(request),{resume,project}=await preparedResume(request);
 let otherProfileId='';
 try{
  let settings=await models(request);
  settings=await(await request.post('/api/models/profiles',{headers,data:{expectedRevision:settings.revision,name:'不同的默认模型',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model:'qa-job-other',apiKey:'job-fixture-canary',clearKey:false}})).json();
  otherProfileId=settings.profiles.at(-1).id;
  await request.post('/api/models/profiles/'+otherProfileId+'/default',{headers,data:{expectedRevision:settings.revision}});
  const before=await fixtureRequests(request),versions=(await(await request.get('/api/resumes/'+resume.id+'/versions')).json()).length;
  const dialog=await open(page,resume.id);
  await dialog.getByLabel('匹配模块 项目经历').check();
  await dialog.getByLabel('岗位要求原文').fill('具备 Java 项目经验；拥有云平台部署经验；具备缺失技能');
  await dialog.getByLabel('匹配模型').selectOption(profile.id);
  await dialog.getByRole('button',{name:'预览发送内容'}).click();
  const payload=JSON.parse(await dialog.getByTestId('job-payload').inputValue());
  expect(payload.sources.some((source:any)=>source.text.includes('PROJECT-BODY-CANARY'))).toBe(true);
  expect(JSON.stringify(payload)).not.toMatch(/PRIVATE-|UNSELECTED-/);
  await expect(dialog.getByRole('button',{name:'生成匹配分析'})).toBeDisabled();
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  const generated=page.waitForResponse(response=>new URL(response.url()).pathname==='/api/ai/job-matches'&&response.request().method()==='POST');
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();
  const match=await(await generated).json();
  expect(match.suggestions.every((suggestion:any)=>suggestion.paragraph>=0&&suggestion.sectionId===project.id)).toBe(true);
  const report=dialog.getByLabel('匹配报告');
  await expect(report).toContainText('有依据');
  await expect(report).toContainText('部分依据');
  await expect(report).toContainText('缺少依据');
  await expect(report).toContainText('模型判断');
  await expect(report).toContainText('条目标题与说明');
  await expect(report).toContainText('PROJECT-BODY-CANARY');
  const sent=await fixtureRequests(request);
  expect(sent.length).toBe(before.length+1);
  expect(sent.at(-1).messages.find((message:any)=>message.role==='user').content).toBe(await dialog.getByTestId('job-payload').inputValue());
  expect(sent.at(-1).tools).toBeNull();
  expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);
  expect((await(await request.get('/api/resumes/'+resume.id+'/versions')).json()).length).toBe(versions);
  await fs.mkdir(path.join(root,'output'),{recursive:true});
  await report.getByRole('heading',{name:'匹配报告'}).scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(root,'output/job-match-a.png'),animations:'disabled'});
  await report.getByRole('button',{name:'审核修改'}).click();
  const review=page.getByRole('dialog',{name:'审核职位匹配建议'});
  await expect(review).toContainText('岗位匹配 fixture · compatible · qa-job-normal');
  await expect(review.getByRole('button',{name:'生成润色建议'})).toHaveCount(0);
  await expect(review.getByRole('button',{name:'选择整段'})).toHaveCount(0);
  expect((await fixtureRequests(request)).length).toBe(sent.length);
  await expect(review.getByRole('button',{name:'确认应用建议'})).toBeDisabled();
  await review.getByTestId('ai-edit-suggestion').fill('人工核对：整理接口文档并记录联调问题，提升 30%。');
  await expect(review.getByTestId('ai-number-risk')).toBeVisible();
  await page.screenshot({path:path.join(root,'output/job-match-review.png'),animations:'disabled'});
  await review.getByLabel('确认建议事实与表达').check();
  await review.getByRole('button',{name:'确认应用建议'}).click();
  await expect(review).toHaveCount(0);
  const saved=await(await request.get('/api/resumes/'+resume.id)).json();
  expect(saved.document.content.sections.find((section:any)=>section.id===project.id).entries[0].bullets[0]).toBe('人工核对：整理接口文档并记录联调问题，提升 30%。');
  expect((await(await request.get('/api/resumes/'+resume.id+'/versions')).json()).some((version:any)=>version.label.startsWith('AI 应用前自动保留'))).toBe(true);
  await page.getByRole('button',{name:'撤销',exact:true}).click();
  await expect.poll(async()=>(await(await request.get('/api/resumes/'+resume.id)).json()).document).toEqual(resume.document);
  expect(await page.evaluate(()=>Object.values(localStorage).join('|'))).not.toMatch(/具备 Java 项目经验|PROJECT-BODY-CANARY/);
 }finally{
  if(otherProfileId){const settings=await models(request);await request.delete('/api/models/profiles/'+otherProfileId,{headers,data:{expectedRevision:settings.revision}});}
  await cleanup(request,resume.id,profile.id);
 }
});

test('input changes revoke consent and cancelled slow response cannot show a report',async({page,request})=>{
 const profile=await configure(request,'qa-job-slow'),{resume}=await preparedResume(request);
 try{
  const dialog=await ready(page,resume.id);
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  await dialog.getByLabel('岗位要求原文').fill('岗位要求变化');
  await expect(dialog.getByTestId('job-payload')).toHaveCount(0);
  await dialog.getByRole('button',{name:'预览发送内容'}).click();
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  const sent=page.waitForRequest(item=>new URL(item.url()).pathname==='/api/ai/job-matches'&&item.method()==='POST');
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();await sent;
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(page.getByRole('button',{name:'职位匹配',exact:true})).toBeFocused();
  await page.waitForTimeout(2200);
  await expect(page.getByLabel('匹配报告')).toHaveCount(0);
  expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);
 }finally{await cleanup(request,resume.id,profile.id);}
});

test('bad references, provider failure, and stale revisions leave the resume unchanged',async({page,request})=>{
 const profile=await configure(request,'qa-job-bad-reference'),{resume}=await preparedResume(request);
 try{
  const dialog=await ready(page,resume.id);
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(dialog.getByRole('alert')).toContainText('JOB_MATCH_OUTPUT_INVALID');
  await expect(dialog.getByLabel('匹配报告')).toHaveCount(0);
  let state=await models(request);
  await request.put('/api/models/profiles/'+profile.id,{headers,data:{expectedRevision:state.revision,name:'岗位匹配 fixture',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model:'qa-job-error',apiKey:'',clearKey:false}});
  await dialog.getByRole('button',{name:'刷新模型配置'}).click();
  await dialog.getByRole('button',{name:'预览发送内容'}).click();
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(dialog.getByRole('alert')).toContainText('MODEL_AUTH_FAILED');
  await dialog.getByRole('button',{name:'预览发送内容'}).click();
  state=await models(request);
  await request.put('/api/models/profiles/'+profile.id,{headers,data:{expectedRevision:state.revision,name:'配置已改',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model:'qa-job-normal',apiKey:'',clearKey:false}});
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(dialog.getByRole('alert')).toContainText('MODEL_SETTINGS_CHANGED');
  expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);
 }finally{await cleanup(request,resume.id,profile.id);}
});

test('source revision conflict revokes the report and B, dark, and 900px views fit',async({page,request})=>{
 const profile=await configure(request),{resume}=await preparedResume(request);
 try{
  await page.goto('/?view=models');
  await page.getByLabel('界面风格').selectOption('b');
  const dialog=await ready(page,resume.id);
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(dialog.getByLabel('匹配报告')).toContainText('有依据');
  await fs.mkdir(path.join(root,'output'),{recursive:true});
  await dialog.getByRole('heading',{name:'匹配报告'}).scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(root,'output/job-match-b.png'),animations:'disabled'});
  await dialog.getByRole('button',{name:'取消并关闭'}).click();
  await page.getByRole('switch',{name:'深色模式'}).click();
  const dark=await ready(page,resume.id);
  await dark.getByLabel('确认发送岗位和选中模块').check();
  await dark.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(dark.getByLabel('匹配报告')).toContainText('有依据');
  await expect.poll(async()=>page.evaluate(()=>document.documentElement.dataset.dark)).toBe('true');
  await dark.getByRole('heading',{name:'匹配报告'}).scrollIntoViewIfNeeded();
  await page.screenshot({path:path.join(root,'output/job-match-dark.png'),animations:'disabled'});
  await page.setViewportSize({width:900,height:900});
  expect(await dark.evaluate(element=>element.scrollWidth<=element.clientWidth+1)).toBe(true);
  expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
  await dark.getByRole('heading',{name:'匹配报告'}).evaluate(element=>element.scrollIntoView({block:'start'}));
  await page.screenshot({path:path.join(root,'output/job-match-narrow.png'),animations:'disabled'});
  await dark.getByRole('button',{name:'取消并关闭'}).click();
  const stale=await ready(page,resume.id);
  await stale.getByLabel('确认发送岗位和选中模块').check();
  const changed=await request.put('/api/resumes/'+resume.id,{headers,data:{title:'另一窗口更新',document:resume.document,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}});
  expect(changed.ok()).toBe(true);
  await stale.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(stale.getByRole('alert')).toContainText('REVISION_CONFLICT');
  await expect(stale.getByTestId('job-payload')).toHaveCount(0);
  await expect(stale.getByLabel('匹配报告')).toHaveCount(0);
 }finally{await cleanup(request,resume.id,profile.id);}
});

test('returning to a changed source or model clears an old report',async({page,request})=>{
 const profile=await configure(request),{resume}=await preparedResume(request);
 try{
  const dialog=await ready(page,resume.id);
  await dialog.getByLabel('确认发送岗位和选中模块').check();
  await dialog.getByRole('button',{name:'生成匹配分析'}).click();
  await expect(dialog.getByLabel('匹配报告')).toBeVisible();
  const updated=await request.put('/api/resumes/'+resume.id,{headers,data:{title:'另一窗口更新',document:resume.document,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}});
  expect(updated.ok()).toBe(true);
  await page.evaluate(()=>window.dispatchEvent(new Event('focus')));
  await expect(dialog.getByLabel('匹配报告')).toHaveCount(0);
  await expect(dialog.getByTestId('job-payload')).toHaveCount(0);
  await expect(dialog.getByRole('alert')).toContainText('简历或模型配置已改变');
 }finally{await cleanup(request,resume.id,profile.id);}
});
