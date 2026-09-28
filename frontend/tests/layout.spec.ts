import {test,expect,type Page} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
let owned:string[]=[];
test.beforeEach(()=>{owned=[];});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function slider(page:Page,label:string,value:number){const input=page.getByLabel(label,{exact:true});const min=Number(await input.getAttribute('min')),step=Number(await input.getAttribute('step'));await input.focus();await input.press('Home');for(let i=0;i<Math.round((value-min)/step);i++)await input.press('ArrowRight');await expect(input).toHaveValue(String(value));}
async function ready(page:Page){await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');await expect(page.getByTestId('preview-status')).toContainText('预览已更新');}

test('text, style and spacing are live, persisted, versioned and exported consistently',async({page,request})=>{
 const resume=await(await request.post('/api/resumes',{headers,data:{title:'排版控制 · 合成验证',sample:'one'}})).json();owned.push(resume.id);
 await page.goto(`/?view=editor&resume=${resume.id}`);await ready(page);
 await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('tab',{name:'文字',exact:true}).click();
 await page.getByRole('button',{name:'宋体',exact:true}).click();await page.getByRole('button',{name:'English',exact:true}).click();await page.getByRole('button',{name:'小 95%',exact:true}).click();
 await page.getByRole('tab',{name:'样式',exact:true}).click();await page.getByRole('button',{name:'橙色主题色',exact:true}).click();await page.getByRole('button',{name:'居中',exact:true}).click();await page.getByRole('button',{name:'图标',exact:true}).click();await page.getByLabel('模块标题样式').selectOption('bar');
 await page.getByRole('tab',{name:'间距',exact:true}).click();await slider(page,'模块间距',5);await slider(page,'正文行距',1.4);await slider(page,'左右页边距',18);await slider(page,'上页边距',22);await slider(page,'下页边距',18);await ready(page);
 const saved=await(await request.get('/api/resumes/'+resume.id)).json();expect(saved.document.schemaVersion).toBe(3);expect(saved.document.layout.font).toBe('serif');expect(saved.document.layout.fontSize).toBe(9.5);
 expect(saved.document.layout.presentation).toMatchObject({accentColor:'#c65c19',language:'en',alignment:'center',contactStyle:'icons',headingStyle:'bar',marginHorizontalMm:18,marginTopMm:22,marginBottomMm:18});
 const frame=page.frameLocator('iframe');await expect(frame.locator('html')).toHaveAttribute('lang','en');await expect(frame.locator('body')).toHaveClass(/alignment-center contacts-icons headings-bar/);await expect(frame.locator('#pages .contact-icon')).toHaveCount(3);
 const actual=await frame.locator('.sheet').first().evaluate(el=>{const css=getComputedStyle(el),heading=getComputedStyle(el.querySelector('h2')!),body=getComputedStyle(document.body);return{top:parseFloat(css.paddingTop),right:parseFloat(css.paddingRight),color:heading.color,font:body.fontFamily,bodySize:parseFloat(body.fontSize)};});
 expect(actual.top).toBeCloseTo(22*96/25.4,1);expect(actual.right).toBeCloseTo(18*96/25.4,1);expect(actual.color).toBe('rgb(198, 92, 25)');expect(actual.font).toContain('ResumeSerif');expect(actual.bodySize).toBeCloseTo(9.5*96/72,1);
 await fs.mkdir(path.join(root,'output/pdf'),{recursive:true});let download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await download).saveAs(path.join(root,'output/pdf/layout-styled.pdf'));
 await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByLabel('快照名称').fill('排版基线');await page.getByRole('button',{name:'保存版本快照'}).click();await expect(page.getByRole('listitem').filter({hasText:'排版基线'})).toBeVisible();
 await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('tab',{name:'样式',exact:true}).click();await page.getByRole('button',{name:'恢复默认样式'}).click();await page.getByRole('tab',{name:'间距',exact:true}).click();await page.getByRole('button',{name:'恢复默认间距'}).click();await ready(page);
 let current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document.layout.presentation).toMatchObject({accentColor:'#244f63',alignment:'left',contactStyle:'plain',marginHorizontalMm:16,marginTopMm:16,marginBottomMm:16});expect(current.document.layout.font).toBe('serif');expect(current.document.content).toEqual(resume.document.content);
 download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await download).saveAs(path.join(root,'output/pdf/layout-reset.pdf'));
 await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByRole('listitem').filter({hasText:'排版基线'}).getByRole('button',{name:'恢复',exact:true}).click();await ready(page);
 current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document.layout).toEqual(saved.document.layout);
 await page.reload();await ready(page);await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('tab',{name:'间距',exact:true}).click();await expect(page.getByLabel('上页边距',{exact:true})).toHaveValue('22');
 await page.setViewportSize({width:1640,height:1100});
 for(const name of ['文字','样式','间距']){await page.getByRole('tab',{name,exact:true}).click();await page.screenshot({path:path.join(root,`output/layout-${name==='文字'?'text':name==='样式'?'style':'spacing'}.png`),animations:'disabled'});}
 await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.screenshot({path:path.join(root,'output/layout-dark.png'),animations:'disabled'});
});

test('maximum type and independent margins keep every paragraph and page footer intact',async({page,request})=>{
 const resume=await(await request.post('/api/resumes',{headers,data:{title:'排版边界 · 合成验证',sample:'one'}})).json();owned.push(resume.id);
 const doc=resume.document;doc.content.sections=doc.content.sections.filter((s:any)=>s.type==='project');doc.content.sections[0].entries=doc.content.sections[0].entries.slice(0,1);
 doc.content.sections[0].entries[0].bullets=Array.from({length:30},(_,i)=>`排版条目${String(i).padStart(2,'0')}：`+'中文段落与 Spring Boot 设计说明，验证最大字号和独立边距时内容可以安全续页。'.repeat(3));
 doc.layout.fontSize=12;doc.layout.lineHeight=2;doc.layout.sectionGapMm=12;Object.assign(doc.layout.presentation,{marginHorizontalMm:32,marginTopMm:32,marginBottomMm:32,alignment:'justify',entryGapMm:8,paragraphGapMm:4});
 const changed=await request.put('/api/resumes/'+resume.id,{headers,data:{title:resume.title,document:doc,expectedRevision:resume.revision,mutationId:crypto.randomUUID()}});expect(changed.ok()).toBeTruthy();
 await page.goto(`/?view=editor&resume=${resume.id}`);await ready(page);const frame=page.frameLocator('iframe');expect(await frame.locator('.sheet').count()).toBeGreaterThan(2);
 for(let i=0;i<30;i++)await expect(frame.locator('#pages li').filter({hasText:`排版条目${String(i).padStart(2,'0')}：`})).toHaveCount(1);
 expect(await frame.locator('.sheet').evaluateAll(sheets=>sheets.every(s=>s.querySelector('.page-content')!.getBoundingClientRect().bottom<Math.min(s.querySelector('.page-footer')!.getBoundingClientRect().top-8,s.getBoundingClientRect().bottom-parseFloat(getComputedStyle(s).paddingBottom))))).toBeTruthy();
 const download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await download).saveAs(path.join(root,'output/pdf/layout-extreme.pdf'));
});
