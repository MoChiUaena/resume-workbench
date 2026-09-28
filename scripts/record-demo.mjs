// Record only our app in an explicitly isolated, empty loopback workspace.
import {chromium,request} from '../frontend/node_modules/playwright/index.mjs';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'..'),base=process.env.RESUME_DEMO_BASE_URL||'http://127.0.0.1:18767';
if(process.env.RESUME_TEST_ISOLATED!=='1'||!['127.0.0.1','localhost'].includes(new URL(base).hostname)||new URL(base).port==='18765')throw Error('Use an explicitly isolated test workspace, never the normal application port.');
process.env.PLAYWRIGHT_BROWSERS_PATH=path.join(root,'.tools/ms-playwright');process.env.PLAYWRIGHT_SKIP_BROWSER_GC='1';
const out=path.join(root,'output/demo');await fs.mkdir(out,{recursive:true});
const api=await request.newContext({baseURL:base,extraHTTPHeaders:{'X-Local-Resume':'1'}});
if((await(await api.get('/api/resumes')).json()).length)throw Error('The demonstration workspace must be empty.');
const created=await api.post('/api/resumes',{data:{title:'Java 后端 · 奶龙的简历',sample:'one'}});if(!created.ok())throw Error(await created.text());const resume=await created.json();
const browser=await chromium.launch();const context=await browser.newContext({viewport:{width:1440,height:960},locale:'zh-CN',recordVideo:{dir:out,size:{width:1440,height:960}}});const page=await context.newPage();
const video=page.video();const captions=[];
async function pause(){await new Promise(resolve=>setTimeout(resolve,1200));}
async function ready(){await page.getByTestId('save-status').filter({hasText:'已保存到本机'}).waitFor();await page.getByTestId('preview-status').filter({hasText:'预览已更新'}).waitFor();}
async function shot(name,caption){await pause();await page.screenshot({path:path.join(out,name+'.png'),animations:'disabled'});captions.push({file:name+'.png',caption});}
try{
 await page.goto(base);await page.getByTestId('resume-'+resume.id).waitFor();await shot('01-list','选择自己的简历，或新建一份');
 await page.getByTestId('resume-'+resume.id).getByRole('button',{name:'继续编辑'}).click();await ready();await page.getByLabel('求职方向',{exact:true}).fill('Java 后端开发实习');await ready();await shot('02-edit','修改内容，自动保存，右侧实时预览');
 await page.getByRole('button',{name:'撤销',exact:true}).click();await ready();await shot('03-undo','撤销误改，重做可恢复；内容仍会自动保存');await page.getByRole('button',{name:'重做',exact:true}).click();await ready();
 await page.getByTestId('nav-project').click();await page.getByTestId('section-project').getByLabel('条目正文').first().fill('**项目成果**：独立学校 Logo 与证件照，预览和中文 PDF 共用排版。\n保存、版本恢复和完整备份在本机完成。');await ready();
 await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('tab',{name:'样式',exact:true}).click();await page.getByRole('button',{name:'橙色主题色',exact:true}).click();await page.getByRole('button',{name:'图标',exact:true}).click();await ready();await shot('04-style','主题色、信息图标与排版可以自由调整');
 await page.getByRole('tab',{name:'间距',exact:true}).click();await shot('05-spacing','左右、上、下边距分别调整，预览与 PDF 一致');
 await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByRole('listitem').filter({hasText:'初始版本'}).getByRole('button',{name:'对比',exact:true}).click();await page.getByRole('dialog',{name:'版本对比'}).waitFor();await shot('06-compare','对比旧版本与当前修改，恢复前看清变化');await page.getByLabel('关闭版本对比').click();
 const pdf=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await pdf).saveAs(path.join(out,'demo.pdf'));await shot('07-pdf','导出含中文、照片与学校 Logo 的可搜索 PDF');
 await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await pause();await page.screenshot({path:path.join(out,'editor-dark.png'),animations:'disabled'});await page.getByLabel('界面风格').selectOption('a');await page.getByRole('switch',{name:'深色模式'}).click();
 await page.getByRole('button',{name:'备份与恢复',exact:true}).click();const zip=page.waitForEvent('download');await page.getByRole('button',{name:'下载完整备份'}).click();await(await zip).saveAs(path.join(out,'demo-backup.zip'));await page.getByRole('status').filter({hasText:'已创建'}).waitFor();await shot('08-backup','完整 ZIP 备份包含正文、历史、图片与 PDF');
 await fs.writeFile(path.join(out,'frames.json'),JSON.stringify(captions,null,2));
}finally{
 await context.close();if(video)await video.saveAs(path.join(out,'resume-workbench-demo.webm'));await browser.close();
 const latest=await(await api.get('/api/resumes/'+resume.id)).json();await api.delete('/api/resumes/'+resume.id,{data:{expectedRevision:latest.revision}});await api.dispose();
}
console.log('Recorded a synthetic-data demonstration, PDF and backup in output/demo.');
