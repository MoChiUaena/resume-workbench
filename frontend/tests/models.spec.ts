import {test,expect,type APIRequestContext,type Page} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
let profiles:string[]=[],owned:string[]=[];
test.beforeEach(()=>{profiles=[];owned=[];test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Model tests require their own disposable workspace.');});
async function state(request:APIRequestContext){return(await request.get('/api/models')).json();}
async function config(request:APIRequestContext,model='qa-model'){
 let current=await state(request);const response=await request.post('/api/models/profiles',{headers,data:{expectedRevision:current.revision,name:'本机测试服务',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model,apiKey:'fixture-key-canary',clearKey:false}});expect(response.ok(),await response.text()).toBeTruthy();current=await response.json();const id=current.profiles.at(-1).id;profiles.push(id);current=await(await request.post(`/api/models/profiles/${id}/default`,{headers,data:{expectedRevision:current.revision}})).json();current=await(await request.put('/api/models/enabled',{headers,data:{expectedRevision:current.revision,enabled:true}})).json();return{id,current};
}
test.afterEach(async({request})=>{if(process.env.RESUME_TEST_ISOLATED!=='1')return;let current=await state(request);await request.put('/api/models/enabled',{headers,data:{expectedRevision:current.revision,enabled:false}});for(const id of profiles){current=await state(request);if(current.profiles.some((p:any)=>p.id===id))await request.delete('/api/models/profiles/'+id,{headers,data:{expectedRevision:current.revision}});}for(const id of owned){const result=await request.get('/api/resumes/'+id);if(result.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await result.json()).revision}});}});
async function resume(request:APIRequestContext){const result=await(await request.post('/api/resumes',{headers,data:{title:'润色 QA · 合成数据',sample:'one'}})).json();owned.push(result.id);result.document.content.name='PRIVATE-NAME-CANARY';result.document.content.email='private-email-canary@example.invalid';result.document.content.phone='PRIVATE-PHONE-CANARY';return(await request.put('/api/resumes/'+result.id,{headers,data:{title:result.title,document:result.document,expectedRevision:result.revision,mutationId:crypto.randomUUID()}})).json();}
async function open(page:Page,id:string){await page.goto(`/?view=editor&resume=${id}`);await expect(page.getByTestId('preview-status')).toContainText('预览已更新');await page.getByTestId('nav-project').click();await page.getByRole('button',{name:'AI 段落润色',exact:true}).first().click();const dialog=page.getByRole('dialog',{name:'AI 段落润色',exact:true});await expect(dialog.getByLabel('确认发送选定段落')).toBeEnabled();return dialog;}

test('provider cards manage presets, keep credentials masked, switch default and support B dark narrow views',async({page,request})=>{
 await page.goto('/?view=models');await expect(page.getByRole('heading',{name:'模型设置',exact:true})).toBeVisible();
 for(const provider of ['dashscope','deepseek','glm']){
  await page.getByRole('button',{name:'＋ 添加模型'}).click();await page.getByLabel('模型服务商').selectOption(provider);await page.getByLabel('模型配置名称').fill('QA '+provider);await page.getByLabel('模型 API Key').fill('fixture-'+provider+'-key');
  const saved=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/models/profiles'&&r.request().method()==='POST');await page.getByRole('button',{name:'保存模型配置'}).click();const body=await(await saved).json();profiles.push(body.profiles.at(-1).id);await expect(page.getByRole('status').filter({hasText:'模型配置已保存'})).toBeVisible();
 }
 const fixture=await config(request);await page.getByRole('button',{name:'刷新',exact:true}).click();const card=page.getByTestId('model-'+fixture.id);await expect(card).toContainText('默认文字模型');await card.getByRole('button',{name:'测试连接'}).click();await expect(card.getByRole('status')).toContainText('连接成功');
 expect(JSON.stringify(await state(request))).not.toContain('fixture-key-canary');expect(await page.locator('body').textContent()).not.toContain('fixture-key-canary');
 const other=page.getByTestId('model-'+profiles[1]);await other.getByRole('button',{name:'设为文字默认'}).click();await expect(other).toContainText('默认文字模型');
 await other.getByRole('button',{name:'编辑',exact:true}).click();await expect(page.getByLabel('模型 API Key')).toHaveValue('');await page.getByLabel('模型配置名称').fill('DeepSeek 岗位润色');await page.getByRole('button',{name:'保存模型配置'}).click();await expect(other).toContainText('DeepSeek 岗位润色');expect((await state(request)).profiles.find((p:any)=>p.id===profiles[1]).hasApiKey).toBe(true);
 await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.setViewportSize({width:430,height:1000});await expect(page.getByRole('button',{name:'模型设置',exact:true})).toBeVisible();expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
 await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output/model-settings-dark.png'),fullPage:true,animations:'disabled'});await page.setViewportSize({width:1480,height:1120});await page.getByRole('switch',{name:'深色模式'}).click();await page.getByLabel('界面风格').selectOption('a');await page.screenshot({path:path.join(root,'output/model-settings.png'),fullPage:true,animations:'disabled'});
 page.once('dialog',dialog=>dialog.accept());await other.getByRole('button',{name:'删除',exact:true}).click();await expect(other).toHaveCount(0);await page.getByRole('button',{name:'我的简历',exact:true}).click();await expect(page.getByRole('heading',{name:/我的简历/})).toBeVisible();
});

test('review separates distant edits and flags new responsibility and outcome claims',async({page,request})=>{
 await config(request);const source=await resume(request);
 await page.goto('/?view=models');await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();
 await page.route('**/api/ai/suggestions',async route=>{
  const response=await route.fetch();const result=await response.json();
  await route.fulfill({response,json:{...result,replacement:`【审核提示】${result.original} 主导联调，确保系统稳定。`}});
 });
 const dialog=await open(page,source.id);await dialog.getByLabel('确认发送选定段落').check();
 await dialog.getByRole('button',{name:'生成润色建议'}).click();
 await expect(dialog.getByTestId('ai-role-risk')).toContainText('主导');
 await expect(dialog.getByTestId('ai-outcome-risk')).toContainText('确保');
 await expect(dialog.getByTestId('ai-result-text').locator('mark')).toHaveCount(2);
 const original=await dialog.getByTestId('ai-original-text').textContent();
 const suggested=await dialog.getByTestId('ai-result-text').textContent();
 expect(suggested).toBe(`【审核提示】${original} 主导联调，确保系统稳定。`);
 await expect(dialog.getByRole('button',{name:'确认应用建议'})).toBeDisabled();
 await fs.mkdir(path.join(root,'output'),{recursive:true});
 await page.screenshot({path:path.join(root,'output/ai-review-risks-dark.png'),animations:'disabled'});
 await page.setViewportSize({width:430,height:960});
 await expect(dialog.getByTestId('ai-role-risk')).toBeVisible();
 const bounds=await dialog.boundingBox();expect(bounds).not.toBeNull();
 expect(bounds!.x).toBeGreaterThanOrEqual(0);
 expect(bounds!.x+bounds!.width).toBeLessThanOrEqual(430);
 expect(await dialog.evaluate(element=>element.scrollWidth<=element.clientWidth+1)).toBe(true);
 await dialog.getByTestId('ai-outcome-risk').scrollIntoViewIfNeeded();
 await page.screenshot({path:path.join(root,'output/ai-review-risks-mobile.png'),animations:'disabled'});
 await dialog.getByRole('button',{name:'取消',exact:true}).click();
 expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);
});

test('only a confirmed selected paragraph is sent; apply is versioned and undoable without leaking contacts',async({page,request})=>{
 await config(request);const source=await resume(request);const initial=(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).length;
 const dialog=await open(page,source.id);await dialog.getByLabel('要润色的段落').selectOption('0');await expect(dialog.getByRole('button',{name:'生成润色建议'})).toBeDisabled();const original=await dialog.getByTestId('ai-sent-text').textContent();expect(original).not.toContain('PRIVATE-');await dialog.getByLabel('确认发送选定段落').check();
 const sent=page.waitForRequest(r=>new URL(r.url()).pathname==='/api/ai/suggestions'&&r.method()==='POST');await dialog.getByRole('button',{name:'生成润色建议'}).click();expect((await sent).postData()).not.toContain('PRIVATE-');await expect(dialog.getByTestId('ai-result-text')).toContainText('表述优化');
 expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);expect((await(await request.get(`/api/resumes/${source.id}/versions`)).json()).length).toBe(initial);await expect(dialog.getByRole('button',{name:'确认应用建议'})).toBeDisabled();
 await page.screenshot({path:path.join(root,'output/ai-paragraph-review.png'),animations:'disabled'});await dialog.getByLabel('确认建议事实与表达').check();await dialog.getByRole('button',{name:'确认应用建议'}).click();await expect(dialog).toHaveCount(0);await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');
 const applied=await(await request.get('/api/resumes/'+source.id)).json();expect(applied.revision).toBe(source.revision+1);expect(applied.document.content.email).toBe(source.document.content.email);expect(applied.document.layout).toEqual(source.document.layout);expect((await(await request.get(`/api/resumes/${source.id}/versions`)).json()).some((v:any)=>v.label.startsWith('AI 应用前自动保留'))).toBe(true);
 await page.getByRole('button',{name:'撤销',exact:true}).click();await expect.poll(async()=>(await(await request.get('/api/resumes/'+source.id)).json()).document).toEqual(source.document);
});

test('selected sentence is the only model input and only that range is applied',async({page,request})=>{
 await config(request);let source=await resume(request);
 const section=source.document.content.sections.find((item:any)=>item.type==='project');
 const entry=section.entries[0];entry.bullets[0]+=' '+'在课程练习中整理接口文档，与同组成员核对字段，并记录联调问题。'.repeat(15);entry.bullets.push('对照段落保持不变。');
 source=await(await request.put('/api/resumes/'+source.id,{headers,data:{title:source.title,document:source.document,expectedRevision:source.revision,mutationId:crypto.randomUUID()}})).json();
 const first=source.document.content.sections.find((item:any)=>item.id===section.id).entries[0].bullets[0];
 expect(first.length).toBeGreaterThan(500);expect(first.length).toBeLessThan(780);
 const phrase='核对字段',start=first.indexOf(phrase,Math.floor(first.length/2)),end=start+phrase.length,selected=first.slice(start,end);
 expect(start).toBeGreaterThan(0);
 await page.goto(`/?view=editor&resume=${source.id}`);await expect(page.getByTestId('preview-status')).toContainText('预览已更新');
 await page.getByTestId('nav-project').click();
 const area=page.getByRole('textbox',{name:'条目正文'}).first();
 await area.evaluate((node:HTMLTextAreaElement,range)=>{node.focus();node.setSelectionRange(range.start,range.end);},{start,end});
 await page.getByRole('button',{name:'AI 段落润色',exact:true}).first().click();
 const dialog=page.getByRole('dialog',{name:'AI 段落润色',exact:true});
 const picker=dialog.getByLabel('选取要润色的句段');
 await expect(picker).toBeFocused();
 expect(await picker.evaluate((node:HTMLTextAreaElement)=>node.selectionStart)).toBe(start);
 expect(await picker.evaluate((node:HTMLTextAreaElement)=>node.selectionEnd)).toBe(end);
 expect(await picker.evaluate((node:HTMLTextAreaElement)=>node.scrollTop)).toBeGreaterThan(0);
 await expect(dialog.getByTestId('ai-sent-text')).toHaveText(selected);
 await expect(dialog.getByRole('button',{name:'生成润色建议'})).toBeDisabled();
 await dialog.getByLabel('确认发送选定段落').check();
 const response=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ai/suggestions'&&r.request().method()==='POST');
 await dialog.getByRole('button',{name:'生成润色建议'}).click();
 const sent=await response,proposal=await sent.json();
 expect(proposal.original).toBe(first);expect(proposal.selectedOriginal).toBe(selected);
 expect(proposal.selectionStart).toBe(start);expect(proposal.selectionEnd).toBe(end);
 expect(sent.request().postData()).not.toContain('PRIVATE-');
 await expect(dialog.getByTestId('ai-original-text')).toHaveText(selected);
 await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output/ai-selected-sentence.png'),animations:'disabled'});
 await dialog.getByLabel('确认建议事实与表达').check();await dialog.getByRole('button',{name:'确认应用建议'}).click();
 await expect(dialog).toHaveCount(0);
 const applied=await(await request.get('/api/resumes/'+source.id)).json();
 const changed=applied.document.content.sections.find((item:any)=>item.id===section.id).entries[0].bullets;
 expect(changed[0]).toBe(first.slice(0,start)+proposal.replacement+first.slice(end));
 expect(changed.slice(1)).toEqual(source.document.content.sections.find((item:any)=>item.id===section.id).entries[0].bullets.slice(1));
 expect((await(await request.get(`/api/resumes/${source.id}/versions`)).json()).some((version:any)=>version.label.startsWith('AI 应用前自动保留'))).toBe(true);
 await page.getByRole('button',{name:'撤销',exact:true}).click();
 await expect.poll(async()=>(await(await request.get('/api/resumes/'+source.id)).json()).document).toEqual(source.document);

 const restored=page.getByRole('textbox',{name:'条目正文'}).first(),text=await restored.inputValue(),lineEnd=text.indexOf('\n');
 await restored.evaluate((node:HTMLTextAreaElement,index)=>{node.focus();node.setSelectionRange(index-3,index+4);},lineEnd);
 await page.getByRole('button',{name:'AI 段落润色',exact:true}).first().click();
 const cross=page.getByRole('dialog',{name:'AI 段落润色',exact:true});
 await expect(cross.getByText(/一次只能选择同一段落中的文字/)).toBeVisible();
 await cross.getByLabel('确认发送选定段落').check();
 await expect(cross.getByRole('button',{name:'生成润色建议'})).toBeDisabled();
 await cross.getByLabel('选取要润色的句段').evaluate((node:HTMLTextAreaElement)=>{node.focus();node.setSelectionRange(1,6);node.dispatchEvent(new Event('select',{bubbles:true}));});
 await expect(cross.getByTestId('ai-sent-text')).toHaveText(first.slice(1,6));
 await expect(cross.getByLabel('确认发送选定段落')).not.toBeChecked();
 await expect(cross.getByText(/一次只能选择同一段落中的文字/)).toHaveCount(0);
 await cross.getByRole('button',{name:'取消',exact:true}).click();
});

test('human edits refresh the diff and risk hints before one confirmed apply',async({page,request})=>{
 await config(request);const source=await resume(request);
 await page.goto('/?view=models');await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();
 const dialog=await open(page,source.id);await dialog.getByLabel('确认发送选定段落').check();
 let sends=0;page.on('request',sent=>{if(new URL(sent.url()).pathname==='/api/ai/suggestions'&&sent.method()==='POST')sends++;});
 const reply=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ai/suggestions'&&r.request().method()==='POST');
 await dialog.getByRole('button',{name:'生成润色建议'}).click();const modelReply=await(await reply).json();
 const editor=dialog.getByTestId('ai-edit-suggestion');await expect(editor).toHaveValue(modelReply.replacement);
 await dialog.getByLabel('确认建议事实与表达').check();
 await editor.fill('主导联调，提升 30%。');
 await expect(dialog.getByLabel('确认建议事实与表达')).not.toBeChecked();
 await expect(dialog.getByTestId('ai-role-risk')).toContainText('主导');
 await expect(dialog.getByTestId('ai-outcome-risk')).toContainText('提升');
 await expect(dialog.getByTestId('ai-number-risk')).toContainText('数字');
 await expect(dialog.getByTestId('ai-result-text')).toHaveText('主导联调，提升 30%。');
 await page.setViewportSize({width:1480,height:1540});await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output/ai-manual-review-dark.png'),animations:'disabled'});
 await editor.fill('人工核对后的句段。');
 await expect(dialog.getByTestId('ai-role-risk')).toHaveCount(0);
 await expect(dialog.getByTestId('ai-outcome-risk')).toHaveCount(0);
 await expect(dialog.getByTestId('ai-number-risk')).toHaveCount(0);
 expect(sends).toBe(1);
 expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);
 await dialog.getByLabel('确认建议事实与表达').check();await dialog.getByRole('button',{name:'确认应用建议'}).click();
 await expect(dialog).toHaveCount(0);
 const applied=await(await request.get('/api/resumes/'+source.id)).json();
 const target=applied.document.content.sections.find((section:any)=>section.id===modelReply.sectionId).entries.find((entry:any)=>entry.id===modelReply.entryId);
 expect(target.bullets[modelReply.paragraph]).toBe(modelReply.original.slice(0,modelReply.selectionStart)+'人工核对后的句段。'+modelReply.original.slice(modelReply.selectionEnd));
 await page.getByRole('button',{name:'撤销',exact:true}).click();
 await expect.poll(async()=>(await(await request.get('/api/resumes/'+source.id)).json()).document).toEqual(source.document);
});

test('failed generation and stale source cannot overwrite saved text',async({page,request})=>{
 const profile=await config(request,'qa-error');const source=await resume(request);const dialog=await open(page,source.id);await dialog.getByLabel('确认发送选定段落').check();await dialog.getByRole('button',{name:'生成润色建议'}).click();await expect(dialog.getByRole('alert')).toContainText('MODEL_AUTH_FAILED');expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);await dialog.getByRole('button',{name:'取消',exact:true}).click();
 const current=await state(request);await request.put('/api/models/profiles/'+profile.id,{headers,data:{expectedRevision:current.revision,name:'修复后的服务',provider:'compatible',baseUrl:'http://127.0.0.1:18770/v1',model:'qa-model',apiKey:'',clearKey:false}});
 const review=await open(page,source.id);await review.getByLabel('确认发送选定段落').check();await review.getByRole('button',{name:'生成润色建议'}).click();await expect(review.getByTestId('ai-result-text')).toContainText('表述优化');source.title='另一页面的新标题';const changed=await(await request.put('/api/resumes/'+source.id,{headers,data:{title:source.title,document:source.document,expectedRevision:source.revision,mutationId:crypto.randomUUID()}})).json();await review.getByLabel('确认建议事实与表达').check();await review.getByRole('button',{name:'确认应用建议'}).click();await expect(review.getByRole('alert')).toContainText('REVISION_CONFLICT');expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(changed);
});

test('cancelling an in-flight reply and retrying a lost apply acknowledgement retain safe history',async({page,request})=>{
 const profile=await config(request,'qa-slow');const source=await resume(request);const dialog=await open(page,source.id);await dialog.getByLabel('确认发送选定段落').check();const sent=page.waitForRequest(r=>new URL(r.url()).pathname==='/api/ai/suggestions');await dialog.getByRole('button',{name:'生成润色建议'}).click();await sent;await dialog.getByRole('button',{name:'取消',exact:true}).click();await expect(dialog).toHaveCount(0);expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);
 await expect.poll(async()=>{const response=await request.post('/api/ai/suggestions',{headers,data:{resumeId:source.id,expectedRevision:source.revision,sectionId:source.document.content.sections[0].id,entryId:source.document.content.sections[0].entries[0].id,paragraph:0,profileId:profile.id,settingsRevision:(await state(request)).revision,confirmSend:true}});return response.status();},{timeout:10000}).toBe(200);
 const review=await open(page,source.id);await review.getByLabel('确认发送选定段落').check();const reply=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ai/suggestions'&&r.request().method()==='POST');await review.getByRole('button',{name:'生成润色建议'}).click();const proposal=await(await reply).json();await expect(review.getByTestId('ai-result-text')).toContainText('表述优化');
 const edited='人工校正后的文字。';await review.getByTestId('ai-edit-suggestion').fill(edited);await review.getByLabel('确认建议事实与表达').check();
 let dropped=false;await page.route('**/api/ai/suggestions/*/apply',async route=>{if(!dropped){dropped=true;await route.fetch();await route.abort();}else await route.continue();});
 await review.getByRole('button',{name:'确认应用建议'}).click();await expect(review.getByRole('alert')).toContainText('NETWORK_ERROR');
 await expect(review.getByTestId('ai-edit-suggestion')).toBeDisabled();
 await expect(review.getByText(/应用结果尚未确认，建议文字暂时锁定/)).toBeVisible();
 const versionCount=(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).length;
 await review.getByRole('button',{name:'确认应用建议'}).click();await expect(review).toHaveCount(0);
 expect((await(await request.get(`/api/resumes/${source.id}/versions`)).json()).length).toBe(versionCount);
 const applied=await(await request.get('/api/resumes/'+source.id)).json();
 const target=applied.document.content.sections.find((section:any)=>section.id===proposal.sectionId).entries.find((entry:any)=>entry.id===proposal.entryId);
 expect(target.bullets[proposal.paragraph]).toBe(proposal.original.slice(0,proposal.selectionStart)+edited+proposal.original.slice(proposal.selectionEnd));
});
