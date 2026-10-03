import {test,expect,type APIRequestContext,type Page} from '@playwright/test';
const headers={'X-Local-Resume':'1'};
test.beforeEach(()=>test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Report history tests require disposable data and the local model fixture.'));
async function setup(request:APIRequestContext){
 let state=await(await request.get('/api/models')).json();
 const response=await request.post('/api/models/profiles',{headers,data:{expectedRevision:state.revision,name:'报告历史 fixture',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model:'qa-job-normal',apiKey:'history-fixture-canary',clearKey:false}});
 expect(response.ok(),await response.text()).toBe(true);state=await response.json();const profileId=state.profiles.at(-1).id;
 state=await(await request.post('/api/models/profiles/'+profileId+'/default',{headers,data:{expectedRevision:state.revision}})).json();
 await request.put('/api/models/enabled',{headers,data:{expectedRevision:state.revision,enabled:true}});
 const created=await request.post('/api/resumes',{headers,data:{title:'报告历史 QA · 合成数据',sample:'one'}});expect(created.ok(),await created.text()).toBe(true);
 const resume=await created.json(),project=resume.document.content.sections.find((item:any)=>item.type==='project');
 project.entries[0].bullets[0]='PROJECT-BODY-CANARY 使用 Java 整理接口文档并记录联调问题。';
 const saved=await request.put('/api/resumes/'+resume.id,{headers,data:{title:resume.title,document:resume.document,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}});expect(saved.ok(),await saved.text()).toBe(true);
 return {profileId,resume:await saved.json()};
}
async function cleanup(request:APIRequestContext,resumeId:string,profileId:string){
 let state=await(await request.get('/api/models')).json();
 if(state.enabled)state=await(await request.put('/api/models/enabled',{headers,data:{expectedRevision:state.revision,enabled:false}})).json();
 if(state.profiles.some((item:any)=>item.id===profileId))await request.delete('/api/models/profiles/'+profileId,{headers,data:{expectedRevision:state.revision}});
 const response=await request.get('/api/resumes/'+resumeId);
 if(response.ok())await request.delete('/api/resumes/'+resumeId,{headers,data:{expectedRevision:(await response.json()).revision}});
}
async function open(page:Page,id:string){
 await page.goto('/?view=editor&resume='+id);await expect(page.getByTestId('preview-status')).toContainText('预览已更新');
 await page.getByRole('button',{name:'职位匹配',exact:true}).click();return page.getByRole('dialog',{name:'职位匹配',exact:true});
}
async function generated(page:Page,id:string){
 const dialog=await open(page,id);await dialog.getByLabel('匹配模块 项目经历').check();await dialog.getByLabel('岗位要求原文',{exact:true}).fill('具备 Java 项目经验；具备云平台部署经验');
 await dialog.getByRole('button',{name:'预览发送内容',exact:true}).click();await expect(dialog.getByTestId('job-payload')).toBeVisible();
 await dialog.getByLabel('确认发送岗位和选中模块').check();await dialog.getByRole('button',{name:'生成匹配分析',exact:true}).click();await expect(dialog.getByLabel('匹配报告',{exact:true})).toBeVisible();return dialog;
}
test('explicitly saved report survives refresh, labels historical sources and deletes only after confirmation',async({page,request})=>{
 const {resume,profileId}=await setup(request);let generationCalls=0;page.on('request',event=>{if(new URL(event.url()).pathname==='/api/ai/job-matches'&&event.method()==='POST')generationCalls++;});
 try{
  let dialog=await generated(page,resume.id);const beforeVersions=await(await request.get('/api/resumes/'+resume.id+'/versions')).json();
  expect((await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json()).total).toBe(0);
  await dialog.getByLabel('报告名称',{exact:true}).fill('Java 岗位 · 已核对');await dialog.getByRole('button',{name:'保存报告到本机',exact:true}).click();
  await expect(dialog.getByRole('region',{name:'保存匹配报告',exact:true}).getByRole('status')).toContainText('已保存到本机');
  const list=await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json(),id=list.items[0].id;
  const original=await(await request.get('/api/resumes/'+resume.id+'/job-reports/'+id)).json();
  expect(original.snapshot.suggestions.length).toBeGreaterThan(0);const settings=await(await request.get('/api/models')).json();await request.put('/api/models/enabled',{headers,data:{expectedRevision:settings.revision,enabled:false}});
  expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);expect(await(await request.get('/api/resumes/'+resume.id+'/versions')).json()).toEqual(beforeVersions);
  await page.reload();dialog=await open(page,resume.id);await dialog.getByRole('button',{name:'已保存报告',exact:true}).click();
  await dialog.getByRole('button',{name:'查看报告 Java 岗位 · 已核对',exact:true}).click();const archived=dialog.getByLabel('已保存报告详情');
  await expect(archived).toContainText('来源修订 r'+resume.revision);await expect(archived).toContainText(original.snapshot.jobDescription);
  await expect(archived.getByLabel('保存时的原始材料')).toContainText(original.snapshot.sources[0].text);
  await expect(archived.getByLabel('保存时的修改建议')).toContainText(original.snapshot.suggestions[0].replacement);
  await expect(archived.getByRole('button',{name:'审核修改'})).toHaveCount(0);await expect(archived.getByRole('button',{name:/应用/})).toHaveCount(0);
  const updatedResponse=await request.put('/api/resumes/'+resume.id,{headers,data:{title:'外部更新的合成简历',document:resume.document,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}});expect(updatedResponse.ok()).toBe(true);const updated=await updatedResponse.json();
  await dialog.getByRole('button',{name:'刷新报告历史',exact:true}).click();await dialog.getByRole('button',{name:'查看报告 Java 岗位 · 已核对',exact:true}).click();await expect(archived).toContainText('当前简历已改变');
  await archived.getByRole('button',{name:'删除这份报告',exact:true}).click();const confirm=dialog.getByLabel('删除报告确认');
  expect((await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json()).total).toBe(1);
  await expect(confirm.getByRole('button',{name:'确认删除报告',exact:true})).toBeDisabled();await confirm.getByRole('button',{name:'取消删除',exact:true}).click();
  await expect(archived.getByRole('button',{name:'删除这份报告',exact:true})).toBeFocused();await archived.getByRole('button',{name:'删除这份报告',exact:true}).click();
  await confirm.getByLabel('确认删除这份已保存报告').check();await confirm.getByRole('button',{name:'确认删除报告',exact:true}).click();
  const deletedStatus=dialog.getByRole('region',{name:'已保存报告历史',exact:true}).getByRole('status').filter({hasText:/已删除报告/});await expect(deletedStatus).toHaveCount(1);await expect(deletedStatus).toContainText('已删除报告');
  expect((await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json()).total).toBe(0);expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(updated);expect(generationCalls).toBe(1);
 }finally{await cleanup(request,resume.id,profileId);}
});
for(const acknowledgement of ['connection','truncated JSON','wrong report','invalid error envelope'])test('save recovery keeps the original request after '+acknowledgement+' and live-input invalidation',async({page,request})=>{
 const {resume,profileId}=await setup(request);const sent:any[]=[];let generationCalls=0;page.on('request',event=>{if(new URL(event.url()).pathname==='/api/ai/job-matches'&&event.method()==='POST')generationCalls++;});
 try{
  const dialog=await generated(page,resume.id);
  await page.route('**/api/resumes/'+resume.id+'/job-reports',async route=>{
   if(route.request().method()!=='POST'){await route.continue();return;}sent.push(route.request().postDataJSON());
   if(sent.length!==1){await route.continue();return;}const response=await route.fetch();expect(response.ok()).toBe(true);const result=await response.json();
   if(acknowledgement==='connection')await route.abort();else if(acknowledgement==='truncated JSON')await route.fulfill({status:200,contentType:'application/json',body:'{'});
   else if(acknowledgement==='wrong report')await route.fulfill({response,json:{...result,id:crypto.randomUUID()}});else await route.fulfill({status:503,json:{message:'missing error code'}});
  });
  await dialog.getByLabel('报告名称',{exact:true}).fill('固定的报告名称');await dialog.getByRole('button',{name:'保存报告到本机',exact:true}).click();
  await expect(dialog.getByRole('alert')).toContainText('NETWORK_ERROR');await expect(dialog.getByLabel('报告名称',{exact:true})).toBeDisabled();
  await dialog.getByLabel('岗位要求原文',{exact:true}).fill('新的岗位输入');await expect(dialog.getByLabel('匹配报告',{exact:true})).toHaveCount(0);
  if(acknowledgement==='connection'){await dialog.getByRole('button',{name:'取消并关闭',exact:true}).click();await page.getByRole('button',{name:'职位匹配',exact:true}).click();await expect(dialog.getByLabel('报告名称',{exact:true})).toHaveValue('固定的报告名称');await expect(dialog.getByLabel('报告名称',{exact:true})).toBeDisabled();}
  await dialog.getByRole('button',{name:'重试同一保存请求',exact:true}).click();await expect(dialog.getByRole('region',{name:'保存匹配报告',exact:true}).getByRole('status')).toContainText('已保存到本机');
  expect(sent).toHaveLength(2);expect(sent[1]).toEqual(sent[0]);expect(sent[1].label).toBe('固定的报告名称');expect(generationCalls).toBe(1);
  expect((await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json()).total).toBe(1);expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);
 }finally{await cleanup(request,resume.id,profileId);}
});
test('save recovery remains available after the live report expires and models are disabled',async({page,request})=>{
 const {resume,profileId}=await setup(request);const sent:any[]=[];let generationCalls=0;
 try{
  await page.route('**/api/ai/job-matches',async route=>{generationCalls++;const response=await route.fetch(),result=await response.json();await route.fulfill({response,json:{...result,expiresAt:new Date(Date.now()+5000).toISOString()}});});
  const dialog=await generated(page,resume.id);
  await page.route('**/api/resumes/'+resume.id+'/job-reports',async route=>{if(route.request().method()!=='POST'){await route.continue();return;}sent.push(route.request().postDataJSON());if(sent.length!==1){await route.continue();return;}const response=await route.fetch();expect(response.ok()).toBe(true);await route.abort();});
  await dialog.getByLabel('报告名称',{exact:true}).fill('过期后核对回执');await dialog.getByRole('button',{name:'保存报告到本机',exact:true}).click();await expect(dialog.getByLabel('报告名称',{exact:true})).toBeDisabled();
  const settings=await(await request.get('/api/models')).json();await request.put('/api/models/enabled',{headers,data:{expectedRevision:settings.revision,enabled:false}});
  await expect(dialog.getByLabel('匹配报告',{exact:true})).toHaveCount(0,{timeout:8000});await expect(dialog.getByLabel('报告名称',{exact:true})).toHaveValue('过期后核对回执');
  await dialog.getByRole('button',{name:'重试同一保存请求',exact:true}).click();await expect(dialog.getByRole('region',{name:'保存匹配报告',exact:true}).getByRole('status')).toContainText('已保存到本机');expect(sent).toHaveLength(2);expect(sent[1]).toEqual(sent[0]);expect(generationCalls).toBe(1);
  expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);
 }finally{await cleanup(request,resume.id,profileId);}
});
test('an uncertain delete retries only the confirmed report and prevents saving that report again',async({page,request})=>{
 const {resume,profileId}=await setup(request);const deletes:string[]=[];
 try{
  const dialog=await generated(page,resume.id);await dialog.getByLabel('报告名称',{exact:true}).fill('待删除报告');await dialog.getByRole('button',{name:'保存报告到本机',exact:true}).click();await expect(dialog.getByRole('region',{name:'保存匹配报告',exact:true}).getByRole('status')).toContainText('已保存到本机');
  const id=(await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json()).items[0].id;
  await page.route('**/api/resumes/'+resume.id+'/job-reports/'+id,async route=>{
   if(route.request().method()!=='DELETE'){await route.continue();return;}deletes.push(route.request().url());
   if(deletes.length!==1){await route.continue();return;}const response=await route.fetch();expect(response.ok()).toBe(true);await route.fulfill({status:200,contentType:'application/json',body:'{'});
  });
  await dialog.getByRole('button',{name:'已保存报告',exact:true}).click();await dialog.getByRole('button',{name:'查看报告 待删除报告',exact:true}).click();await dialog.getByRole('button',{name:'删除这份报告',exact:true}).click();
  const confirm=dialog.getByLabel('删除报告确认');await confirm.getByLabel('确认删除这份已保存报告').check();await confirm.getByRole('button',{name:'确认删除报告',exact:true}).click();
  await expect(confirm.getByRole('alert')).toContainText('NETWORK_ERROR');await expect(confirm.getByRole('button',{name:'取消删除',exact:true})).toBeDisabled();await expect(dialog.getByRole('button',{name:'分析当前简历',exact:true})).toBeDisabled();
  await confirm.getByRole('button',{name:'重试删除同一报告',exact:true}).click();
  const deletedStatus=dialog.getByRole('region',{name:'已保存报告历史',exact:true}).getByRole('status').filter({hasText:/已删除报告/});await expect(deletedStatus).toHaveCount(1);await expect(deletedStatus).toContainText('已删除报告');expect(deletes).toHaveLength(2);expect(deletes[1]).toBe(deletes[0]);
  await dialog.getByRole('button',{name:'分析当前简历',exact:true}).click();await expect(dialog.getByLabel('保存匹配报告')).toContainText('无法再次保存同一报告');await expect(dialog.getByRole('button',{name:'保存报告到本机',exact:true})).toHaveCount(0);
  expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);
 }finally{await cleanup(request,resume.id,profileId);}
});
test('history pages and detail fit both themes and narrow dark view without nested dialogs',async({page,request})=>{
 const {resume,profileId}=await setup(request);
 try{
  const dialog=await generated(page,resume.id);await dialog.getByLabel('报告名称',{exact:true}).fill('分页报告');await dialog.getByRole('button',{name:'保存报告到本机',exact:true}).click();await expect(dialog.getByRole('region',{name:'保存匹配报告',exact:true}).getByRole('status')).toContainText('已保存到本机');
  const saved=(await(await request.get('/api/resumes/'+resume.id+'/job-reports')).json()).items[0];
  const summaries=Array.from({length:21},(_,index)=>({...saved,id:index===0?saved.id:`22222222-2222-4222-8222-${String(index).padStart(12,'0')}`,label:'分页报告 '+index}));
  await page.route('**/api/resumes/'+resume.id+'/job-reports?*',async route=>{const index=Number(new URL(route.request().url()).searchParams.get('page')||0);await route.fulfill({json:{page:index,pageSize:20,total:21,pages:2,items:summaries.slice(index*20,(index+1)*20)}});});
  await dialog.getByRole('button',{name:'已保存报告',exact:true}).click();const history=dialog.getByLabel('已保存报告历史');await expect(history.getByRole('button',{name:/查看报告 分页报告/})).toHaveCount(20);
  await history.getByRole('button',{name:'下一页',exact:true}).click();await expect(history.getByRole('button',{name:/查看报告 分页报告/})).toHaveCount(1);await expect(history.getByLabel('报告历史分页')).toContainText('第 2 / 2 页');
  await history.getByRole('button',{name:'上一页',exact:true}).click();await history.getByRole('button',{name:'查看报告 分页报告 0',exact:true}).click();await expect(dialog.getByLabel('已保存报告详情')).toBeVisible();
  for(const theme of ['a','b'])for(const dark of [false,true]){
   await page.evaluate(({theme,dark})=>{document.documentElement.dataset.uiTheme=theme;document.documentElement.dataset.dark=String(dark);},{theme,dark});await page.setViewportSize({width:390,height:844});
   expect(await dialog.evaluate(node=>node.scrollWidth<=node.clientWidth+1)).toBe(true);await expect(page.getByRole('dialog')).toHaveCount(1);
  }
  await dialog.getByRole('button',{name:'删除这份报告',exact:true}).click();await expect(dialog.getByLabel('确认删除这份已保存报告')).toBeFocused();await page.keyboard.press('Escape');await expect(dialog.getByLabel('删除报告确认')).toHaveCount(0);await expect(dialog).toBeVisible();
  await dialog.getByRole('button',{name:'取消并关闭',exact:true}).click();await expect(page.getByRole('button',{name:'职位匹配',exact:true})).toBeFocused();
 }finally{await cleanup(request,resume.id,profileId);}
});
