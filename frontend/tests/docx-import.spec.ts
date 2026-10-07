import {test,expect,type APIRequestContext,type Page} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
const fixture=(name:string)=>path.join(root,'fixtures','docx',name);
let owned:string[]=[];
test.beforeEach(()=>{owned=[];});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function open(page:Page){await page.goto('/');await page.getByRole('button',{name:'导入 Word'}).click();await expect(page.getByRole('dialog',{name:'导入 Word 简历'})).toBeVisible();}
async function choose(page:Page,name:string){await page.getByLabel('选择 Word 文件').setInputFiles(fixture(name));await page.getByRole('button',{name:'预览提取内容'}).click();await expect(page.getByLabel('原文提取内容')).toBeVisible();}
async function resumeIds(request:APIRequestContext){const response=await request.get('/api/resumes');expect(response.ok()).toBeTruthy();return ((await response.json()) as {id:string}[]).map(item=>item.id).sort();}

test('real DOCX preview is read-only until reviewed fields and paragraphs are confirmed, then opens editor and exports PDF',async({page,request})=>{
 await open(page);const beforeIds=await resumeIds(request);await choose(page,'basic.docx');expect(await resumeIds(request)).toEqual(beforeIds);
 await expect(page.getByLabel('原文提取内容')).toHaveValue(/姓名：奶龙/);
 await expect(page.getByLabel('原文提取内容')).toHaveValue(/未归类文字也应保留/);
 await expect(page.getByLabel('导入姓名')).toHaveValue('奶龙');
 await expect(page.getByRole('list',{name:'提取提示'})).toContainText('DOCX_FIELDS_INFERRED');
 await expect(page.locator('html')).toHaveAttribute('data-ui-theme','a');await expect(page.locator('html')).toHaveAttribute('data-dark','false');
 await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output','docx-import-review.png'),animations:'disabled'});
 const unique='DOCX 合成导入 '+crypto.randomUUID();await page.getByLabel('新简历名称').fill(unique);
 const before=await(await request.get('/api/resumes')).json();expect(before.some((r:{title:string})=>r.title===unique)).toBeFalsy();
 await page.getByLabel('导入姓名').fill('奶龙修订');
 await page.getByLabel('模块 1 类型').selectOption('custom');await page.getByLabel('模块 1 标题').fill('教育与训练');
 const paragraphs=await page.locator('.docx-entry textarea').all();let paragraphInput=paragraphs[0];
 for(const candidate of paragraphs)if((await candidate.inputValue()).includes('使用 Java 和 Spring Boot')){paragraphInput=candidate;break;}
 expect(await paragraphInput.inputValue()).toContain('使用 Java 和 Spring Boot');
 await paragraphInput.fill('使用 Java 和 Spring Boot 实现修订后的接口校验。');
 await page.getByRole('button',{name:'确认创建新简历'}).click();
 await expect(page.getByRole('dialog',{name:'导入 Word 简历'})).toHaveCount(0);
 await expect(page.getByLabel('简历名称')).toHaveValue(unique);
 await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙修订');
 await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');
 const after=await(await request.get('/api/resumes')).json();const created=after.find((r:{title:string})=>r.title===unique);expect(created).toBeTruthy();owned.push(created.id);expect(await resumeIds(request)).toEqual([...beforeIds,created.id].sort());
 const saved=await(await request.get('/api/resumes/'+created.id)).json();expect(saved.document.content.name).toBe('奶龙修订');expect(saved.document.content.sections[0].type).toBe('custom');expect(saved.document.content.sections[0].title).toBe('教育与训练');expect(JSON.stringify(saved.document)).toContain('修订后的接口校验');
 const download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();expect((await download).suggestedFilename()).toMatch(/\.pdf$/i);
});

test('cancellation after table extraction leaves no record and keeps image, header, and unclassified warnings visible',async({page,request})=>{
 await open(page);const beforeIds=await resumeIds(request);await choose(page,'table.docx');expect(await resumeIds(request)).toEqual(beforeIds);
 await expect(page.getByLabel('原文提取内容')).toHaveValue(/表格单元格内容完整保留/);
 await expect(page.getByLabel('原文提取内容')).toHaveValue(/页眉补充/);
 await expect(page.getByRole('list',{name:'提取提示'})).toContainText('DOCX_UNCLASSIFIED');
 await expect(page.getByRole('list',{name:'提取提示'})).toContainText(/图片|DOCX_IMAGES/);
 const unique='DOCX 取消 '+crypto.randomUUID();await page.getByLabel('新简历名称').fill(unique);
 await page.getByRole('button',{name:'取消导入'}).click();await expect(page.getByRole('dialog',{name:'导入 Word 简历'})).toHaveCount(0);
 await expect(page.getByRole('button',{name:'导入 Word'})).toBeFocused();
 expect(await resumeIds(request)).toEqual(beforeIds);
 const after=await(await request.get('/api/resumes')).json();expect(after.some((r:{title:string})=>r.title===unique)).toBeFalsy();
});

test('invalid, disguised, and oversized files are rejected without creating a resume',async({page,request})=>{
 await open(page);const beforeIds=await resumeIds(request),input=page.getByLabel('选择 Word 文件');
 await input.setInputFiles({name:'legacy.doc',mimeType:'application/msword',buffer:Buffer.from('not a docx')});await expect(page.getByRole('alert')).toContainText('.docx');expect(await resumeIds(request)).toEqual(beforeIds);
 await input.setInputFiles({name:'disguised.docx',mimeType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',buffer:Buffer.from('not a zip')});await page.getByRole('button',{name:'预览提取内容'}).click();await expect(page.getByRole('alert')).toContainText(/DOCX_INVALID|DOCX_UNSUPPORTED/);expect(await resumeIds(request)).toEqual(beforeIds);
 await input.setInputFiles({name:'oversize.docx',mimeType:'application/vnd.openxmlformats-officedocument.wordprocessingml.document',buffer:Buffer.alloc(5242881)});await expect(page.getByRole('alert')).toContainText('5 MiB');expect(await resumeIds(request)).toEqual(beforeIds);
 await expect(page.getByRole('button',{name:'确认创建新简历'})).toHaveCount(0);
 const records=await(await request.get('/api/resumes')).json();expect(records.some((r:{title:string})=>r.title==='disguised')).toBeFalsy();
});

test('lost create response locks review and exact retry returns one imported resume',async({page,request})=>{
 await open(page);await choose(page,'basic.docx');
 const unique='DOCX 重试 '+crypto.randomUUID();await page.getByLabel('新简历名称').fill(unique);
 const sent:unknown[]=[];let createdId='';
 await page.route('**/api/imports/docx/create',async route=>{
  sent.push(route.request().postDataJSON());const actual=await route.fetch();expect(actual.ok()).toBeTruthy();const receipt=await actual.json();createdId=receipt.resume.id;
  if(sent.length===1)await route.fulfill({status:503,contentType:'application/json',body:'{'});else await route.fulfill({response:actual});
 });
 await page.getByRole('button',{name:'确认创建新简历'}).click();await expect(page.getByText('创建结果尚未确认')).toBeVisible();
 await expect(page.getByLabel('导入姓名')).toBeDisabled();await expect(page.getByLabel('选择 Word 文件')).toBeDisabled();await expect(page.getByRole('button',{name:'关闭 Word 导入'})).toBeDisabled();
 expect(await page.evaluate(()=>{const event=new Event('beforeunload',{cancelable:true});window.dispatchEvent(event);return event.defaultPrevented;})).toBeTruthy();
 await page.getByRole('button',{name:'重试创建'}).click();await expect(page.getByRole('dialog',{name:'导入 Word 简历'})).toHaveCount(0);
 expect(sent).toHaveLength(2);expect(sent[1]).toEqual(sent[0]);owned.push(createdId);
 const list=await(await request.get('/api/resumes')).json();expect(list.filter((r:{id:string})=>r.id===createdId)).toHaveLength(1);await expect(page.getByLabel('简历名称')).toHaveValue(unique);
});

test('known validation error keeps editable preview and permits a corrected new confirmation',async({page})=>{
 await open(page);await choose(page,'basic.docx');
 const sent:{mutationId:string}[]=[];let createdId='';
 await page.route('**/api/imports/docx/create',async route=>{
  sent.push(route.request().postDataJSON());
  if(sent.length===1)await route.fulfill({status:400,contentType:'application/json',body:JSON.stringify({code:'INVALID_INPUT',message:'请修正内容'})});
  else{const actual=await route.fetch();expect(actual.ok()).toBeTruthy();createdId=(await actual.json()).resume.id;await route.fulfill({response:actual});}
 });
 await page.getByRole('button',{name:'确认创建新简历'}).click();await expect(page.getByRole('alert')).toContainText('INVALID_INPUT');
 await expect(page.getByLabel('导入姓名')).toBeEnabled();await page.getByLabel('导入姓名').fill('奶龙修订');
 await page.getByRole('button',{name:'确认创建新简历'}).click();await expect(page.getByRole('dialog',{name:'导入 Word 简历'})).toHaveCount(0);
 expect(sent).toHaveLength(2);expect(sent[1].mutationId).not.toBe(sent[0].mutationId);owned.push(createdId);
});

test('long DOCX review works in B dark narrow view and does not undo the editor behind it',async({page,request})=>{
 const original=await(await request.post('/api/resumes',{headers,data:{title:'DOCX 键盘合成基线',sample:'one'}})).json();owned.push(original.id);
 const beforeIds=await resumeIds(request);
 await page.setViewportSize({width:390,height:800});await page.goto(`/?view=editor&resume=${original.id}`);await expect(page.getByRole('button',{name:'← 我的简历'})).toBeVisible();await page.getByRole('button',{name:'← 我的简历'}).click();
 await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();
 await page.getByRole('button',{name:'导入 Word'}).click();await expect(page.getByRole('button',{name:'关闭 Word 导入'})).toBeFocused();
 await choose(page,'long.docx');await expect(page.getByLabel('原文提取内容')).toHaveValue(/导入段落 24/);expect(await resumeIds(request)).toEqual(beforeIds);
 const dialog=page.getByRole('dialog',{name:'导入 Word 简历'});await expect(dialog).toBeVisible();
 await expect(page.locator('html')).toHaveAttribute('data-ui-theme','b');await expect(page.locator('html')).toHaveAttribute('data-dark','true');
 await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output','docx-import-dark-narrow.png'),animations:'disabled'});
 await page.evaluate(()=>history.back());await expect(page).toHaveURL(/view=editor/);
 await page.getByLabel('导入姓名').focus();await page.keyboard.press('Control+z');await expect(page.getByLabel('导入姓名')).toHaveValue('奶龙');
 await page.getByRole('button',{name:'取消导入'}).click();await expect(dialog).toHaveCount(0);
 expect(await resumeIds(request)).toEqual(beforeIds);
 await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙');expect((await(await request.get('/api/resumes/'+original.id)).json()).revision).toBe(1);
});
