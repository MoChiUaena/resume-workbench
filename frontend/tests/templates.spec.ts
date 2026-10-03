import {test,expect,type Page} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
const templates=[{id:'card',name:'横栏名片'},{id:'rail',name:'侧栏标题'}] as const;
let owned:string[]=[];
test.beforeEach(()=>{owned=[];});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function ready(page:Page){await expect(page.getByLabel('简历名称',{exact:true})).toBeEnabled();await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');await expect(page.getByTestId('preview-status')).toContainText('预览已更新');}
async function safePages(page:Page){
 const frame=page.frameLocator('iframe');
 await expect.poll(async()=>{
  try{
   return await frame.locator('#pages .sheet').evaluateAll(sheets=>{
    const layout=window as Window&{__resumeReady?:boolean;__resumePages?:number;__resumeError?:string};
    if(document.readyState!=='complete'||layout.__resumeReady!==true||layout.__resumeError||sheets.length===0||layout.__resumePages!==sheets.length)return false;
    return sheets.every(s=>s.querySelector('.page-content')!.getBoundingClientRect().bottom<Math.min(s.querySelector('.page-footer')!.getBoundingClientRect().top-8,s.getBoundingClientRect().bottom-parseFloat(getComputedStyle(s).paddingBottom)));
   });
  }catch(cause){
   // A new preview src can replace the iframe while its previous document is being inspected.
   if(cause instanceof Error&&cause.message.includes('Execution context was destroyed, most likely because of a navigation'))return false;
   throw cause;
  }
 }).toBe(true);
}

for(const template of templates)test(`${template.id} template preserves content/images, exports one/two pages and survives history`,async({page,request})=>{
 await fs.mkdir(path.join(root,'output/pdf'),{recursive:true});
 for(const sample of ['one','two']){
  const original=await(await request.post('/api/resumes',{headers,data:{title:`新模板 · ${template.name} · ${sample}`,sample}})).json();owned.push(original.id);
  await page.goto(`/?view=editor&resume=${original.id}`);await ready(page);await page.getByRole('button',{name:'版式设置',exact:true}).click();
  for(const name of ['经典单栏','并列页眉','横栏名片','侧栏标题'])await expect(page.getByRole('button',{name:name+'模板',exact:true})).toBeVisible();
  await page.getByRole('button',{name:template.name+'模板',exact:true}).click();await ready(page);
  const saved=await(await request.get('/api/resumes/'+original.id)).json();expect(saved.document.content).toEqual(original.document.content);expect(saved.document.layout).toEqual({...original.document.layout,template:template.id});expect(saved.document.schemaVersion).toBe(4);
  const frame=page.frameLocator('iframe');await expect(frame.locator('body')).toHaveClass(new RegExp('template-'+template.id));await expect(frame.locator('#pages .sheet')).toHaveCount(sample==='one'?1:2);await safePages(page);
  await expect(frame.locator('#pages [data-kind=photo] img')).toHaveAttribute('src',`/api/assets/${original.document.layout.photo.id}/image`);await expect(frame.locator('#pages [data-kind=logo] img')).toHaveAttribute('src',`/api/assets/${original.document.layout.logo.id}/image`);
  if(template.id==='card'){
   const geometry=await frame.locator('#pages .resume-header').evaluate(header=>{const contacts=header.querySelector('.contacts')!.getBoundingClientRect();return{direction:getComputedStyle(header.querySelector('.contacts')!).flexDirection,contactsTop:contacts.top,imageBottom:Math.max(...Array.from(header.querySelectorAll('.image-frame')).map(el=>el.getBoundingClientRect().bottom)),within:Array.from(header.querySelectorAll('.contact-line')).every(el=>{const r=el.getBoundingClientRect();return r.left>=contacts.left-1&&r.right<=contacts.right+1;})};});
   expect(geometry.direction).toBe('row');expect(geometry.contactsTop).toBeGreaterThanOrEqual(geometry.imageBottom);expect(geometry.within).toBeTruthy();
  }else{
   const geometry=await frame.locator('#pages .resume-section').first().evaluate(section=>({headingRight:section.querySelector('h2')!.getBoundingClientRect().right,entryLeft:section.querySelector('.entry')!.getBoundingClientRect().left}));expect(geometry.entryLeft).toBeGreaterThan(geometry.headingRight);
  }
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await download).saveAs(path.join(root,`output/pdf/template-${template.id}-${sample}.pdf`));
  if(sample==='one'){
   await page.setViewportSize({width:1640,height:1120});await page.screenshot({path:path.join(root,`output/template-${template.id}.png`),animations:'disabled'});
   await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByLabel('快照名称').fill(template.name+'基线');await page.getByRole('button',{name:'保存版本快照'}).click();await expect(page.getByRole('listitem').filter({hasText:template.name+'基线'})).toBeVisible();
   await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('button',{name:'并列页眉模板',exact:true}).click();await ready(page);
   await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByRole('listitem').filter({hasText:template.name+'基线'}).getByRole('button',{name:'对比',exact:true}).click();
   const dialog=page.getByRole('dialog',{name:'版本对比'});await expect(dialog.getByTestId('difference-layout')).toContainText(template.name);await expect(dialog.getByTestId('difference-layout')).toContainText('并列页眉');
   const restored=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/restore'));await dialog.getByRole('button',{name:'恢复此版本'}).click();expect((await restored).ok()).toBeTruthy();await expect(dialog).toHaveCount(0);await ready(page);expect((await(await request.get('/api/resumes/'+original.id)).json()).document.layout.template).toBe(template.id);
   await page.getByRole('button',{name:'撤销',exact:true}).click();await ready(page);expect((await(await request.get('/api/resumes/'+original.id)).json()).document.layout.template).toBe('banner');
   await page.getByRole('button',{name:'重做',exact:true}).click();await ready(page);await page.reload();await ready(page);expect((await(await request.get('/api/resumes/'+original.id)).json()).document.layout.template).toBe(template.id);
   await page.getByRole('button',{name:'← 我的简历',exact:true}).click();await expect(page.getByTestId('resume-'+original.id)).toContainText(template.name);
  }
 }
});

test('new templates paginate extreme settings and support swapped/hidden independent images',async({page,request})=>{
 for(const template of templates){
  const original=await(await request.post('/api/resumes',{headers,data:{title:`模板边界 · ${template.name}`,sample:'one'}})).json();owned.push(original.id);
  const doc=original.document;doc.content.sections=doc.content.sections.filter((s:any)=>s.type==='project');doc.content.sections[0].entries=doc.content.sections[0].entries.slice(0,1);
  doc.content.sections[0].entries[0].bullets=Array.from({length:30},(_,i)=>`模板条目${String(i).padStart(2,'0')}：`+'中文段落与 Spring Boot 设计说明，验证最大字号和独立边距时内容可以安全续页。'.repeat(3));
  Object.assign(doc.layout,{template:template.id,fontSize:12,lineHeight:2,sectionGapMm:12,swapImages:true});Object.assign(doc.layout.presentation,{marginHorizontalMm:32,marginTopMm:32,marginBottomMm:32,entryGapMm:8,paragraphGapMm:4});
  const response=await request.put('/api/resumes/'+original.id,{headers,data:{title:original.title,document:doc,expectedRevision:original.revision,mutationId:crypto.randomUUID()}});expect(response.ok()).toBeTruthy();
  await page.goto(`/?view=editor&resume=${original.id}`);await ready(page);const frame=page.frameLocator('iframe');const count=await frame.locator('#pages .sheet').count();expect(count).toBeGreaterThan(2);expect(count).toBeLessThanOrEqual(10);
  for(let i=0;i<30;i++)await expect(frame.locator('#pages li').filter({hasText:`模板条目${String(i).padStart(2,'0')}：`})).toHaveCount(1);await safePages(page);
  await expect(frame.locator('#pages .left-cell [data-kind=logo]')).toHaveCount(1);await expect(frame.locator('#pages .right-cell [data-kind=photo]')).toHaveCount(1);
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await download).saveAs(path.join(root,`output/pdf/template-${template.id}-edge.pdf`));
  await page.getByRole('button',{name:'照片与校徽',exact:true}).click();await page.getByLabel('显示证件照').uncheck();await page.getByLabel('显示学校 Logo').uncheck();await ready(page);await expect(frame.locator('#pages .image-frame')).toHaveCount(0);await safePages(page);
 }
});
