import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'../..'), headers={'X-Local-Resume':'1'};
let created:string[]=[];
async function create(request:APIRequestContext,sample='one'){
 const response=await request.post('/api/resumes',{headers,data:{title:'QA · '+sample,sample}});expect(response.ok()).toBeTruthy();const resume=await response.json();created.push(resume.id);return resume;
}
async function open(page:Page,id:string){await page.addInitScript(id=>localStorage.setItem('local-resume-selected',id),id);await page.goto('/');await expect(page.getByTestId('preview-status')).toContainText('预览已更新');}
async function saved(page:Page){await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');}
test.beforeEach(()=>{created=[];});
test.afterEach(async({request})=>{for(const id of created){const r=await request.get('/api/resumes/'+id);if(r.ok()){const body=await r.json();await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:body.revision}});}}});

test('editable content, persistence, module controls, image version restore, copy and delete',async({page,request})=>{
 const resume=await create(request);await open(page,resume.id);
 await page.getByLabel('简历名称',{exact:true}).fill('Java 岗 · 验证');
 await page.getByLabel('姓名',{exact:true}).fill('陈明远');
 const project=page.getByTestId('section-project');await project.locator('summary').click();
 await project.getByLabel('条目正文').first().fill('**Spring Boot** 实现事务与版本恢复。\n图片独立管理，中文原字符可提取。');await saved(page);
 await expect(page.frameLocator('iframe').locator('#pages strong').filter({hasText:'Spring Boot'})).toBeVisible();
 await project.getByLabel('上移模块').click();await saved(page);
 let persisted=await (await request.get('/api/resumes/'+resume.id)).json();expect(persisted.document.content.sections[1].type).toBe('project');
 await project.getByLabel('显示模块').uncheck();await saved(page);
 await expect(page.frameLocator('iframe').locator('#pages h2').filter({hasText:'项目经历'})).toHaveCount(0);
 await project.getByLabel('显示模块').check();await saved(page);
 await page.reload();await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('陈明远');await saved(page);
 await page.getByRole('button',{name:'版本',exact:true}).click();await page.getByLabel('快照名称').fill('照片基线');await page.getByRole('button',{name:'保存版本快照'}).click();
 await expect(page.getByRole('listitem').filter({hasText:'照片基线'})).toBeVisible();
 await page.getByRole('button',{name:'图片',exact:true}).click();
 const frame=page.frameLocator('iframe');const photo=await frame.locator('[data-kind=photo] img').getAttribute('src');
 await page.getByTestId('photo-controls').getByText('裁剪与旋转').click();
 await page.getByTestId('photo-controls').getByRole('button',{name:'顺时针旋转 90°'}).click();await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('style',/rotate\(90deg\)/);
 await page.getByLabel('证件照缩放',{exact:true}).fill('1.5');await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('style',/scale\(1.5\)/);
 await page.getByTestId('photo-controls').getByRole('button',{name:'重置裁剪'}).click();
 await page.getByLabel('显示学校 Logo').uncheck();await expect(frame.locator('[data-kind=logo]')).toHaveCount(0);await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('src',photo!);
 await page.getByLabel('显示学校 Logo').check();
 await page.getByLabel('上传学校 Logo').setInputFiles(path.join(root,'fixtures/corrupt.png'));await expect(page.getByTestId('logo-controls')).toContainText('CORRUPT_IMAGE');await expect(frame.locator('[data-kind=logo]')).toBeVisible();
 await page.getByLabel('上传学校 Logo').setInputFiles(path.join(root,'fixtures/university-logo.png'));await expect(page.getByTestId('logo-controls').getByRole('alert')).toHaveCount(0);
 await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures/portrait-upright.jpg'));await expect(frame.locator('[data-kind=photo] img')).not.toHaveAttribute('src',photo!);await saved(page);
 await page.getByRole('button',{name:'版本',exact:true}).click();await page.getByRole('listitem').filter({hasText:'照片基线'}).getByRole('button',{name:'恢复'}).click();await saved(page);
 await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('src',photo!);await expect(page.getByText(/恢复前自动保留/)).toBeVisible();
 await page.getByRole('button',{name:'复制简历',exact:true}).click();await expect(page.getByLabel('简历名称',{exact:true})).toHaveValue('Java 岗 · 验证 · 副本');
 const copyId=await page.evaluate(()=>localStorage.getItem('local-resume-selected')!);created.push(copyId);
 await page.getByLabel('简历名称',{exact:true}).fill('AI 岗 · 副本');await saved(page);
 expect((await (await request.get('/api/resumes/'+resume.id)).json()).title).toBe('Java 岗 · 验证');
 page.once('dialog',dialog=>dialog.accept());await page.getByRole('button',{name:'删除简历',exact:true}).click();await expect(page.getByLabel('简历名称',{exact:true})).toHaveValue('Java 岗 · 验证');
 expect((await request.get('/api/resumes/'+copyId)).status()).toBe(404);
 await page.getByRole('button',{name:'内容编辑',exact:true}).click();await fs.mkdir(path.join(root,'output'),{recursive:true});await page.setViewportSize({width:1640,height:1150});await page.screenshot({path:path.join(root,'output/workbench-stage-b.png'),fullPage:true});
});

test('failed save stays dirty and edits during an in-flight save are serialized',async({page,request})=>{
 const resume=await create(request);await open(page,resume.id);
 const url='**/api/resumes/'+resume.id;
 await page.route(url,async route=>{if(route.request().method()==='PUT')await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DATABASE_UNAVAILABLE',message:'测试：数据库不可用，本次修改尚未保存。'})});else await route.continue();});
 await page.getByLabel('姓名',{exact:true}).fill('失败时保留内容');await expect(page.getByTestId('save-status')).toHaveText('保存失败');
 expect((await (await request.get('/api/resumes/'+resume.id)).json()).document.content.name).toBe('林知行');
 await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('失败时保留内容');await page.unroute(url);
 await page.getByRole('button',{name:'重试保存'}).click();await saved(page);
 let active=0,maxActive=0;await page.route(url,async route=>{if(route.request().method()!=='PUT'){await route.continue();return;}active++;maxActive=Math.max(maxActive,active);await new Promise(r=>setTimeout(r,1200));await route.continue();active--;});
 const sent=page.waitForRequest(r=>r.method()==='PUT'&&r.url().endsWith(resume.id));await page.getByLabel('姓名',{exact:true}).fill('第一处修改');await sent;
 await page.getByLabel('求职方向',{exact:true}).fill('保存进行中的第二处修改');await saved(page);
 const final=await (await request.get('/api/resumes/'+resume.id)).json();expect(final.document.content.name).toBe('第一处修改');expect(final.document.content.headline).toBe('保存进行中的第二处修改');expect(maxActive).toBe(1);
});

test('two editor windows reject stale overwrite and preserve unsaved content as a copy',async({page,context,request})=>{
 const resume=await create(request);await open(page,resume.id);const other=await context.newPage();await open(other,resume.id);
 await page.getByLabel('姓名',{exact:true}).fill('先保存的页面');await saved(page);
 await other.getByLabel('姓名',{exact:true}).fill('后编辑的页面');await expect(other.getByTestId('save-status')).toHaveText('保存失败');await expect(other.getByRole('alert')).toContainText('REVISION_CONFLICT');
 expect((await (await request.get('/api/resumes/'+resume.id)).json()).document.content.name).toBe('先保存的页面');
 await other.getByRole('button',{name:'另存副本',exact:true}).click();await saved(other);
 const copyId=await other.evaluate(()=>localStorage.getItem('local-resume-selected')!);created.push(copyId);expect(copyId).not.toBe(resume.id);expect((await (await request.get('/api/resumes/'+copyId)).json()).document.content.name).toBe('后编辑的页面');await other.close();
});

test('both templates export searchable one/two-page PDFs with local images and fonts',async({page,request})=>{
 const external:string[]=[];await page.route('**/*',async route=>{if(new URL(route.request().url()).hostname!=='127.0.0.1'){external.push(route.request().url());await route.abort();}else await route.continue();});
 await fs.mkdir(path.join(root,'output/pdf'),{recursive:true});
 for(const template of ['classic','banner'])for(const sample of ['one','two']){
  let resume=await create(request,sample);resume.document.layout.template=template;resume.document.layout.font=template==='banner'?'serif':'sans';
  const put=await request.put('/api/resumes/'+resume.id,{headers,data:{title:resume.title,document:resume.document,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}});expect(put.ok()).toBeTruthy();resume=await put.json();
  const preview=await (await request.post('/api/documents/preview',{headers,data:resume.document})).json();await page.goto(preview.url);
  await page.waitForFunction(()=>((window as any).__resumeReady || (window as any).__resumeError));expect(await page.evaluate(()=>(window as any).__resumeError)).toBeUndefined();await expect(page.locator('.sheet')).toHaveCount(sample==='one'?1:2);
  const boxes=await page.locator('.resume-header').evaluate(header=>Object.fromEntries(['photo','logo','identity'].map(kind=>{const r=header.querySelector(kind==='identity'?'.identity':`[data-kind=${kind}]`)!.getBoundingClientRect();return[kind,{left:r.left,right:r.right,top:r.top,bottom:r.bottom}];})));
  expect(template==='classic'?boxes.photo.right<boxes.identity.left:boxes.identity.right<boxes.photo.left).toBeTruthy();expect(boxes.logo.left).toBeGreaterThan(boxes.photo.right);
  const exported=await request.post(`/api/resumes/${resume.id}/export`,{headers,data:{expectedRevision:resume.revision},timeout:90000});expect(exported.ok(),await exported.text()).toBeTruthy();const meta=await exported.json();expect(meta.revision).toBe(resume.revision);expect(meta.versionId).toBeTruthy();
  const pdf=await request.get(`/api/exports/${meta.id}/pdf`);await fs.writeFile(path.join(root,`output/pdf/stage-b-${template}-${sample}.pdf`),await pdf.body());
  await page.screenshot({path:path.join(root,`output/preview-${template}-${sample}.png`),fullPage:true});
 }
 expect(external).toEqual([]);await fs.writeFile(path.join(root,'output/stage-b-network.json'),JSON.stringify({externalRequests:external,scope:'Non-loopback browser requests blocked; exporter has a strict local URL allowlist.'},null,2));
});

test('long edited content paginates without clipping or dropping bullet items',async({page,request})=>{
 const resume=await create(request);const doc=resume.document;doc.content.sections=doc.content.sections.filter((s:any)=>s.type==='project');
 doc.layout.fontSize=12;doc.layout.lineHeight=1.85;doc.layout.marginMm=22;
 const entry=doc.content.sections[0].entries[0];doc.content.sections[0].entries=[entry];entry.bullets=Array.from({length:30},(_,i)=>`唯一条目${String(i).padStart(2,'0')}：`+'中文长内容与 Spring Boot 项目设计说明，验证自动续页时每条内容完整保留。'.repeat(3));
 const response=await request.post('/api/documents/preview',{headers,data:doc});expect(response.ok()).toBeTruthy();await page.goto((await response.json()).url);await page.waitForFunction(()=>(window as any).__resumeReady || (window as any).__resumeError);
 expect(await page.evaluate(()=>(window as any).__resumeError)).toBeUndefined();expect(await page.locator('.sheet').count()).toBeGreaterThan(2);
 for(let i=0;i<30;i++)await expect(page.locator('#pages li').filter({hasText:`唯一条目${String(i).padStart(2,'0')}：`})).toHaveCount(1);
 const fits=await page.locator('.sheet').evaluateAll(sheets=>sheets.every(s=>s.querySelector('.page-content')!.getBoundingClientRect().bottom<s.querySelector('.page-footer')!.getBoundingClientRect().top-8));expect(fits).toBeTruthy();
});

test('blank resume adds structured paragraphs and a late image response cannot change another resume',async({page,request})=>{
 const a=await create(request,'blank'),b=await create(request,'blank');await open(page,a.id);
 await page.getByLabel('新模块类型').selectOption('custom');await page.getByRole('button',{name:'＋ 添加模块'}).click();
 const section=page.getByTestId('section-custom');await section.getByLabel('模块标题').fill('自我介绍');await section.getByLabel('条目标题').fill('关于我');
 await section.getByLabel('使用项目列表').uncheck();await section.getByLabel('条目正文').fill('**自定义段落**：专注于 Java 后端。\n第二段说明。');await saved(page);
 await expect(page.frameLocator('iframe').locator('#pages .paragraphs p')).toHaveCount(2);
 await page.getByRole('button',{name:'图片',exact:true}).click();
 let release!:()=>void;const gate=new Promise<void>(r=>{release=r;});
 await page.route('**/api/assets',async route=>{if(route.request().method()==='POST'){await gate;}await route.continue();});
 const sent=page.waitForRequest(r=>r.method()==='POST'&&r.url().endsWith('/api/assets'));
 await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures/portrait-upright.jpg'));await sent;
 await page.getByTestId('resume-'+b.id).click();await saved(page);
 const completed=page.waitForResponse(r=>r.request().method()==='POST'&&r.url().endsWith('/api/assets'));release();await completed;
 await expect(page.getByTestId('photo-controls').getByText('导入图片')).toBeVisible();
 expect((await (await request.get('/api/resumes/'+b.id)).json()).document.layout.photo.id).toBeNull();
});
