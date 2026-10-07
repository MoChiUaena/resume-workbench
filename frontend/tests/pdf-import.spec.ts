import {test,expect,type APIRequestContext,type Page} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';

const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
const fixture=(name:string)=>path.join(root,'fixtures','pdf',name);
let owned:string[]=[];
test.beforeEach(()=>{owned=[];});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function ids(request:APIRequestContext){const response=await request.get('/api/resumes');expect(response.ok()).toBeTruthy();return ((await response.json()) as {id:string}[]).map(item=>item.id).sort();}
async function open(page:Page){await page.goto('/');await page.getByRole('button',{name:'导入 PDF'}).click();await expect(page.getByRole('dialog',{name:'导入 PDF 简历'})).toBeVisible();}
async function choose(page:Page,name:string){await page.getByLabel('选择 PDF 文件').setInputFiles(fixture(name));await page.getByRole('button',{name:'预览提取内容'}).click();}

test('selectable two-page PDF is preview-only until edited confirmation, then opens editor and exports Chinese PDF',async({page,request})=>{
 const before=await ids(request);await open(page);await choose(page,'two-pages.pdf');
 await expect(page.getByLabel('原文提取内容')).toHaveValue(/奶龙/);
 await expect(page.getByLabel('原文提取内容')).toHaveValue(/第二页的原文也应保留/);
 await expect(page.locator('.docx-summary')).toContainText('2 页');
 await expect(page.getByRole('list',{name:'提取提示'})).toContainText('PDF_READING_ORDER');
 await expect(page.getByLabel('导入姓名')).toHaveValue('奶龙');
 expect(await ids(request)).toEqual(before);
 await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output','pdf-import-review.png'),animations:'disabled'});
 const unique='PDF 合成导入 '+crypto.randomUUID();await page.getByLabel('新简历名称').fill(unique);
 await page.getByLabel('导入姓名').fill('奶龙修订');await page.getByLabel('导入联系电话').fill('13900000000');
 await page.getByLabel('模块 1 类型').selectOption('custom');await page.getByLabel('模块 1 标题').fill('教育与训练');
 const paragraphs=await page.locator('.docx-entry textarea').all();let edited=false;
 for(const input of paragraphs){if((await input.inputValue()).includes('本地简历工作台')){await input.fill('奶龙修订：人工核对第二页项目与正文。');edited=true;break;}}
 expect(edited).toBeTruthy();
 await page.getByRole('button',{name:'确认创建新简历'}).click();
 await expect(page.getByRole('dialog',{name:'导入 PDF 简历'})).toHaveCount(0);
 await expect(page.getByLabel('简历名称')).toHaveValue(unique);await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙修订');
 await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');
 const after=await ids(request);expect(after).toHaveLength(before.length+1);const createdId=after.find(id=>!before.includes(id));expect(createdId).toBeTruthy();owned.push(createdId!);
 const saved=await(await request.get('/api/resumes/'+createdId)).json();expect(saved.document.content.name).toBe('奶龙修订');expect(saved.document.content.phone).toBe('13900000000');expect(saved.document.content.sections[0].type).toBe('custom');expect(JSON.stringify(saved.document)).toContain('人工核对第二页');
 const download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();expect((await download).suggestedFilename()).toMatch(/\.pdf$/i);
});

test('cancelled PDF preview and invalid, scanned, encrypted and oversized PDFs never create records',async({page,request})=>{
 const before=await ids(request);await open(page);await choose(page,'text.pdf');await expect(page.getByLabel('原文提取内容')).toHaveValue(/奶龙/);expect(await ids(request)).toEqual(before);
 await page.getByRole('button',{name:'取消导入'}).click();await expect(page.getByRole('button',{name:'导入 PDF'})).toBeFocused();expect(await ids(request)).toEqual(before);
 await page.getByRole('button',{name:'导入 PDF'}).click();const input=page.getByLabel('选择 PDF 文件');
 await input.setInputFiles({name:'wrong.docx',mimeType:'application/pdf',buffer:Buffer.from('%PDF-1.7')});await expect(page.getByRole('alert')).toContainText('.pdf');expect(await ids(request)).toEqual(before);
 await input.setInputFiles({name:'broken.pdf',mimeType:'application/pdf',buffer:Buffer.from('not a PDF')});await page.getByRole('button',{name:'预览提取内容'}).click();await expect(page.getByRole('alert')).toContainText('PDF_INVALID');expect(await ids(request)).toEqual(before);
 for(const [name,code] of [['scanned.pdf','PDF_NO_TEXT'],['encrypted.pdf','PDF_ENCRYPTED']] as const){await input.setInputFiles(fixture(name));await page.getByRole('button',{name:'预览提取内容'}).click();await expect(page.getByRole('alert')).toContainText(code);expect(await ids(request)).toEqual(before);}
 await input.setInputFiles({name:'oversize.pdf',mimeType:'application/pdf',buffer:Buffer.alloc(5242881)});await expect(page.getByRole('alert')).toContainText('5 MiB');expect(await ids(request)).toEqual(before);
 await expect(page.getByRole('button',{name:'确认创建新简历'})).toHaveCount(0);
});

test('lost PDF create response retries the byte-identical request and creates one record',async({page,request})=>{
 const before=await ids(request);await open(page);await choose(page,'text.pdf');await expect(page.getByLabel('原文提取内容')).toBeVisible();
 const sent:unknown[]=[];let createdId='';
 await page.route('**/api/imports/pdf/create',async route=>{sent.push(route.request().postDataJSON());const actual=await route.fetch();expect(actual.ok()).toBeTruthy();createdId=(await actual.json()).resume.id;if(sent.length===1)await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'INVALID_INPUT',message:'上游响应丢失'})});else await route.fulfill({response:actual});});
 await page.getByRole('button',{name:'确认创建新简历'}).click();await expect(page.getByText('创建结果尚未确认')).toBeVisible();
 await expect(page.getByLabel('导入姓名')).toBeDisabled();await expect(page.getByLabel('选择 PDF 文件')).toBeDisabled();await expect(page.getByRole('button',{name:'关闭 PDF 导入'})).toBeDisabled();
 expect(await page.evaluate(()=>{const event=new Event('beforeunload',{cancelable:true});window.dispatchEvent(event);return event.defaultPrevented;})).toBeTruthy();
 await page.getByRole('button',{name:'重试创建'}).click();await expect(page.getByRole('dialog',{name:'导入 PDF 简历'})).toHaveCount(0);
 expect(sent).toHaveLength(2);expect(sent[1]).toEqual(sent[0]);owned.push(createdId);expect(await ids(request)).toEqual([...before,createdId].sort());
});

test('known PDF validation releases review for a corrected confirmation with a new mutation ID',async({page,request})=>{
 const before=await ids(request);await open(page);await choose(page,'text.pdf');await expect(page.getByLabel('原文提取内容')).toBeVisible();
 const sent:{mutationId:string}[]=[];let createdId='';
 await page.route('**/api/imports/pdf/create',async route=>{sent.push(route.request().postDataJSON());if(sent.length===1)await route.fulfill({status:400,contentType:'application/json',body:JSON.stringify({code:'INVALID_INPUT',message:'请修正内容'})});else{const actual=await route.fetch();expect(actual.ok()).toBeTruthy();createdId=(await actual.json()).resume.id;await route.fulfill({response:actual});}});
 await page.getByRole('button',{name:'确认创建新简历'}).click();await expect(page.getByRole('alert')).toContainText('INVALID_INPUT');await expect(page.getByLabel('导入姓名')).toBeEnabled();expect(await ids(request)).toEqual(before);
 await page.getByLabel('导入姓名').fill('奶龙修订');await page.getByRole('button',{name:'确认创建新简历'}).click();await expect(page.getByRole('dialog',{name:'导入 PDF 简历'})).toHaveCount(0);
 expect(sent).toHaveLength(2);expect(sent[1].mutationId).not.toBe(sent[0].mutationId);owned.push(createdId);expect(await ids(request)).toEqual([...before,createdId].sort());
});

test('PDF review traps focus in B dark narrow view and leaves the existing editor untouched',async({page,request})=>{
 const original=await(await request.post('/api/resumes',{headers,data:{title:'PDF 键盘合成基线',sample:'one'}})).json();owned.push(original.id);const before=await ids(request);
 await page.setViewportSize({width:390,height:800});await page.goto(`/?view=editor&resume=${original.id}`);await expect(page.getByRole('button',{name:'← 我的简历'})).toBeVisible();await page.getByRole('button',{name:'← 我的简历'}).click();
 await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.getByRole('button',{name:'导入 PDF'}).click();await expect(page.getByRole('button',{name:'关闭 PDF 导入'})).toBeFocused();
 await choose(page,'two-pages.pdf');await expect(page.getByLabel('原文提取内容')).toHaveValue(/第二页的原文也应保留/);expect(await ids(request)).toEqual(before);
 await page.getByRole('button',{name:'关闭 PDF 导入'}).focus();await page.keyboard.press('Shift+Tab');await expect(page.getByRole('button',{name:'确认创建新简历'})).toBeFocused();await page.keyboard.press('Tab');await expect(page.getByRole('button',{name:'关闭 PDF 导入'})).toBeFocused();
 await expect(page.locator('html')).toHaveAttribute('data-ui-theme','b');await expect(page.locator('html')).toHaveAttribute('data-dark','true');await expect(page.locator('.rw-app')).toHaveAttribute('inert','');
 await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output','pdf-import-dark-narrow.png'),animations:'disabled'});
 await page.evaluate(()=>history.back());await expect(page).toHaveURL(/view=editor/);await page.getByLabel('导入姓名').focus();await page.keyboard.press('Control+z');await expect(page.getByLabel('导入姓名')).toHaveValue('奶龙');
 await page.keyboard.press('Escape');await expect(page.getByRole('dialog',{name:'导入 PDF 简历'})).toHaveCount(0);expect(await ids(request)).toEqual(before);
 await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙');expect((await(await request.get('/api/resumes/'+original.id)).json()).revision).toBe(1);
});
