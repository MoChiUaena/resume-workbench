import {test,expect,type APIRequestContext,type Page} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
const defaults={name:true,phone:true,email:true,location:true,photo:true,logo:true,matchingText:true};
let owned:string[]=[];
test.beforeEach(()=>{owned=[];});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function create(request:APIRequestContext,sample='one',template='classic'){
 const response=await request.post('/api/resumes',{headers,data:{title:'脱敏测试 · '+template+' · '+sample,sample}});expect(response.ok()).toBeTruthy();const source=await response.json();owned.push(source.id);
 source.document.layout.template=template;
 source.document.content.sections[0].entries[0].bullets[0]='奶龙 · 138 0000 0000 · nailong@example.invalid · 杭州 · 2027 届';
 const saved=await request.put('/api/resumes/'+source.id,{headers,data:{title:source.title,document:source.document,expectedRevision:source.revision,mutationId:crypto.randomUUID()}});expect(saved.ok()).toBeTruthy();return await saved.json();
}
async function ready(page:Page){await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');await expect(page.getByTestId('preview-status')).toContainText('预览已更新');}
async function open(page:Page,id:string){await page.goto(`/?view=editor&resume=${id}`);await ready(page);await page.getByRole('button',{name:'脱敏 PDF',exact:true}).click();const dialog=page.getByRole('dialog',{name:'脱敏 PDF',exact:true});await expect(dialog.getByTestId('redacted-status')).toHaveText('脱敏预览已更新');return dialog;}

test('redacted PDFs for all four templates keep source, images, layout and pinned history intact',async({page,request})=>{
 test.setTimeout(240000);await fs.mkdir(path.join(root,'output/pdf'),{recursive:true});
 for(const template of ['classic','banner','card','rail'])for(const sample of ['one','two']){
  const source=await create(request,sample,template),versions=await(await request.get(`/api/resumes/${source.id}/versions`)).json();
  const dialog=await open(page,source.id);const frame=dialog.frameLocator('iframe');
  for(const name of ['隐藏姓名','隐藏电话','隐藏邮箱','隐藏城市 / 毕业年份','隐藏证件照','隐藏学校 Logo','同步处理正文中的相同信息'])await expect(dialog.getByLabel(name,{exact:true})).toBeChecked();
  await expect(frame.locator('#pages h1')).toHaveText('候选人');await expect(frame.locator('#pages .sheet')).toHaveCount(sample==='one'?1:2);await expect(frame.locator('img')).toHaveCount(0);
  const html=await frame.locator('body').innerHTML();for(const hidden of ['奶龙','138 0000 0000','nailong@example.invalid','杭州 · 2027 届',source.document.layout.photo.id,source.document.layout.logo.id])expect(html).not.toContain(hidden);
  expect(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).toEqual(versions);
  if(template==='card'&&sample==='one')await page.screenshot({path:path.join(root,'output/redacted-pdf.png'),animations:'disabled'});
  const download=page.waitForEvent('download');await dialog.getByRole('button',{name:'导出脱敏 PDF',exact:true}).click();const result=await download;expect(result.suggestedFilename()).toBe('resume-workbench-redacted.pdf');await result.saveAs(path.join(root,`output/pdf/redacted-${template}-${sample}.pdf`));await expect(dialog).toHaveCount(0);
  expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);
  const after=await(await request.get(`/api/resumes/${source.id}/versions`)).json();expect(after).toHaveLength(versions.length+1);
  const pinned=after.find((v:any)=>v.label==='脱敏 PDF 原稿 · r'+source.revision);expect(pinned).toBeTruthy();const detail=await(await request.get(`/api/resumes/${source.id}/versions/${pinned.id}`)).json();expect(detail.document).toEqual(source.document);
 }
});

test('independent choices and retrying a download do not generate a second export or modify the source',async({page,request})=>{
 const source=await create(request),dialog=await open(page,source.id);await dialog.getByLabel('隐藏电话',{exact:true}).uncheck();await dialog.getByLabel('隐藏证件照',{exact:true}).uncheck();await expect(dialog.getByTestId('redacted-status')).toHaveText('脱敏预览已更新');
 const frame=dialog.frameLocator('iframe');await expect(frame.locator('#pages [data-kind=photo] img')).toHaveCount(1);await expect(frame.locator('#pages [data-kind=logo] img')).toHaveCount(0);await expect(frame.locator('#pages .contacts')).toContainText('138 0000 0000');
 let downloads=0,exports=0;page.on('request',r=>{if(r.method()==='POST'&&new URL(r.url()).pathname===`/api/resumes/${source.id}/export`)exports++;});
 await page.route('**/api/exports/*/pdf',async route=>{if(downloads++===0)await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DOWNLOAD_FAILED',message:'合成下载中断'})});else await route.continue();});
 await dialog.getByRole('button',{name:'导出脱敏 PDF',exact:true}).click();await expect(dialog.getByRole('alert')).toContainText('合成下载中断');await expect(dialog.getByRole('button',{name:'重试下载',exact:true})).toBeEnabled();
 const download=page.waitForEvent('download');await dialog.getByRole('button',{name:'重试下载',exact:true}).click();await(await download).saveAs(path.join(root,'output/pdf/redacted-selective.pdf'));await expect(dialog).toHaveCount(0);expect(exports).toBe(1);
 expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);
});

test('invalid choices, changed preview and a racing editor are rejected before exporting',async({page,request})=>{
 const source=await create(request),before=await(await request.get(`/api/resumes/${source.id}/versions`)).json(),route=`/api/resumes/${source.id}/export`;
 const invalid=await request.post(route+'/preview',{headers,data:{expectedRevision:source.revision,redaction:{name:true}}});expect(invalid.status()).toBe(400);
 const missing=await request.post(route,{headers,data:{expectedRevision:source.revision,redaction:defaults}});expect(missing.status()).toBe(422);expect((await missing.json()).code).toBe('EXPORT_PREVIEW_REQUIRED');
 const projected=await(await request.post(route+'/preview',{headers,data:{expectedRevision:source.revision,redaction:defaults}})).json();
 const changed=await request.post(route,{headers,data:{expectedRevision:source.revision,redaction:{...defaults,phone:false},previewDigest:projected.digest}});expect(changed.status()).toBe(422);expect((await changed.json()).code).toBe('EXPORT_PREVIEW_CHANGED');
 expect(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).toEqual(before);
 const dialog=await open(page,source.id);source.document.content.headline='另一页面的新方向';const saved=await request.put('/api/resumes/'+source.id,{headers,data:{title:source.title,document:source.document,expectedRevision:source.revision,mutationId:crypto.randomUUID()}});expect(saved.ok()).toBeTruthy();const current=await saved.json();
 await dialog.getByRole('button',{name:'导出脱敏 PDF',exact:true}).click();await expect(dialog.getByRole('alert')).toContainText('REVISION_CONFLICT');await expect(dialog.getByRole('button',{name:'导出脱敏 PDF',exact:true})).toBeDisabled();
 expect(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).toEqual(before);
 await dialog.getByRole('button',{name:'关闭并重新载入',exact:true}).click();await expect(dialog).toHaveCount(0);await ready(page);await expect(page.getByLabel('求职方向',{exact:true})).toHaveValue(current.document.content.headline);
 expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(current);
});

test('obsolete preview replies cannot enable export, cancel resets choices, and dialog works in A/B dark and narrow views',async({page,request})=>{
 const source=await create(request),versions=await(await request.get(`/api/resumes/${source.id}/versions`)).json();let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});let held=false;
 let dialog=await open(page,source.id);
 await page.route(`**/api/resumes/${source.id}/export/preview`,async route=>{const data=route.request().postDataJSON();if(!held&&!data.redaction.photo&&data.redaction.email){held=true;const response=await route.fetch();await gate;await route.fulfill({response});}else await route.continue();});
 try{
  const sent=page.waitForRequest(r=>r.method()==='POST'&&r.url().endsWith('/export/preview')&&!r.postDataJSON().redaction.photo);
  await dialog.getByLabel('隐藏证件照',{exact:true}).uncheck();await sent;
  await dialog.frameLocator('iframe').locator('body').evaluate(()=>window.parent.postMessage({type:'resume-layout',path:location.pathname,pages:1,error:null},location.origin));
  await expect(dialog.getByRole('button',{name:'导出脱敏 PDF',exact:true})).toBeDisabled();
  await dialog.getByLabel('隐藏邮箱',{exact:true}).uncheck();await expect(dialog.getByTestId('redacted-status')).toHaveText('脱敏预览已更新');await expect(dialog.frameLocator('iframe').locator('#pages .contacts')).toContainText('nailong@example.invalid');
  const late=page.waitForResponse(r=>r.url().endsWith('/export/preview')&&r.request().postDataJSON().redaction.email);release();await late;
  await expect(dialog.frameLocator('iframe').locator('#pages .contacts')).toContainText('nailong@example.invalid');
  await dialog.getByRole('button',{name:'取消',exact:true}).click();await expect(dialog).toHaveCount(0);
  await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.setViewportSize({width:390,height:844});
  const trigger=page.getByRole('button',{name:'脱敏 PDF',exact:true});await expect(trigger).toBeVisible();await trigger.click();dialog=page.getByRole('dialog',{name:'脱敏 PDF',exact:true});await expect(dialog.getByTestId('redacted-status')).toHaveText('脱敏预览已更新');
  await expect(dialog.getByLabel('隐藏证件照',{exact:true})).toBeChecked();await expect(dialog.getByLabel('隐藏邮箱',{exact:true})).toBeChecked();await expect(page.locator('.rw-app')).toHaveAttribute('inert','');
  expect(await dialog.evaluate(el=>el.getBoundingClientRect().left>=0&&el.getBoundingClientRect().right<=window.innerWidth&&el.getBoundingClientRect().bottom<=window.innerHeight)).toBeTruthy();
  const close=dialog.getByRole('button',{name:'关闭脱敏导出'});await expect(close).toBeFocused();await close.press('Shift+Tab');await expect(dialog.getByRole('button',{name:'导出脱敏 PDF',exact:true})).toBeFocused();
  await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(trigger).toBeFocused();
  await page.setViewportSize({width:1480,height:1120});await page.getByLabel('界面风格').selectOption('a');await page.getByRole('switch',{name:'深色模式'}).click();
  expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(source);expect(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).toEqual(versions);
 }finally{release();}
});
