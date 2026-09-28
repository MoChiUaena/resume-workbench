import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
let owned:string[]=[];
test.beforeEach(()=>{owned=[];});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function create(request:APIRequestContext,title='撤销测试 · 合成简历'){
 const response=await request.post('/api/resumes',{headers,data:{title,sample:'one'}});expect(response.ok()).toBeTruthy();const resume=await response.json();owned.push(resume.id);return resume;
}
async function ready(page:Page){await expect(page.getByLabel('简历名称',{exact:true})).toBeEnabled();await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');await expect(page.getByTestId('preview-status')).toContainText('预览已更新');}

test('undo and redo group typing, include layout and deleted modules, and reset between resumes',async({page,request})=>{
 const resume=await create(request),other=await create(request,'另一份合成简历');
 await page.goto(`/?view=editor&resume=${resume.id}`);await ready(page);
 const undo=page.getByRole('button',{name:'撤销',exact:true}),redo=page.getByRole('button',{name:'重做',exact:true}),name=page.getByLabel('姓名',{exact:true});
 await expect(undo).toBeDisabled();await expect(redo).toBeDisabled();
 await name.fill('奶龙·连续');await name.pressSequentially(' QA',{delay:50});
 await name.dispatchEvent('keydown',{key:'z',code:'KeyZ',ctrlKey:true,isComposing:true});await expect(name).toHaveValue('奶龙·连续 QA');
 await name.press('Control+z');await expect(name).toHaveValue('奶龙');
 await name.press('Control+Shift+z');await expect(name).toHaveValue('奶龙·连续 QA');
 await page.getByLabel('求职方向',{exact:true}).fill('新的岗位');await undo.click();await expect(page.getByLabel('求职方向',{exact:true})).toHaveValue(resume.document.content.headline);
 await undo.click();await expect(name).toHaveValue('奶龙');await redo.click();await expect(name).toHaveValue('奶龙·连续 QA');await redo.click();
 await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('tab',{name:'样式',exact:true}).click();await page.getByRole('button',{name:'橙色主题色',exact:true}).click();
 const frame=page.frameLocator('iframe');await expect(frame.locator('h2').first()).toHaveCSS('color','rgb(198, 92, 25)');
 await undo.click();await expect(frame.locator('h2').first()).toHaveCSS('color','rgb(36, 79, 99)');await redo.click();await expect(frame.locator('h2').first()).toHaveCSS('color','rgb(198, 92, 25)');
 await page.getByTestId('nav-project').click();await page.getByRole('button',{name:'删除模块',exact:true}).click();await expect(page.getByTestId('nav-project')).toHaveCount(0);
 await undo.click();await expect(page.getByTestId('nav-project')).toHaveCount(1);await expect(redo).toBeEnabled();
 await page.getByRole('button',{name:'基本信息',exact:true}).click();await name.fill('新的编辑分支');await expect(redo).toBeDisabled();await ready(page);
 await page.getByRole('button',{name:'← 我的简历',exact:true}).click();await page.getByTestId('resume-'+other.id).getByRole('button',{name:'继续编辑'}).click();await ready(page);
 await expect(undo).toBeDisabled();await expect(redo).toBeDisabled();await expect(name).toHaveValue('奶龙');
 await page.getByRole('button',{name:'← 我的简历',exact:true}).click();await page.getByTestId('resume-'+resume.id).getByRole('button',{name:'继续编辑'}).click();await ready(page);
 await expect(name).toHaveValue('新的编辑分支');await expect(undo).toBeDisabled();
 const persisted=await(await request.get('/api/resumes/'+resume.id)).json();expect(persisted.document.layout.presentation.accentColor).toBe('#c65c19');expect(persisted.document.content.sections.some((s:any)=>s.type==='project')).toBeTruthy();
});

test('undo during an in-flight save is serialized and a rejected edit can be undone',async({page,request})=>{
 const resume=await create(request);await page.goto(`/?view=editor&resume=${resume.id}`);await ready(page);
 let release!:()=>void;const gate=new Promise<void>(resolve=>{release=resolve;});let writes=0,active=0,maxActive=0;
 const url=`**/api/resumes/${resume.id}`;
 await page.route(url,async route=>{
  if(route.request().method()!=='PUT'){await route.continue();return;}
  active++;maxActive=Math.max(active,maxActive);writes++;
  if(writes===1)await gate;
  try{const response=await route.fetch();await route.fulfill({response});}finally{active--;}
 });
 const sent=page.waitForRequest(r=>r.method()==='PUT'&&new URL(r.url()).pathname===`/api/resumes/${resume.id}`);
 try{
  await page.getByLabel('姓名',{exact:true}).fill('正在保存的修改');await sent;
  await page.getByLabel('姓名',{exact:true}).press('Control+z');await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙');
 }finally{release();}
 await ready(page);let current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document).toEqual(resume.document);expect(current.revision).toBe(3);expect(writes).toBe(2);expect(maxActive).toBe(1);
 await page.unroute(url);await page.route(url,async route=>{if(route.request().method()==='PUT')await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DATABASE_UNAVAILABLE',message:'合成测试：保存失败'})});else await route.continue();});
 await page.getByLabel('姓名',{exact:true}).fill('失败的修改');await expect(page.getByTestId('save-status')).toHaveText('保存失败');await page.getByRole('button',{name:'撤销',exact:true}).click();await ready(page);
 current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document).toEqual(resume.document);expect(current.revision).toBe(3);
 // The write commits, then its response is lost. Undo must resolve that receipt before saving.
 await page.unroute(url);const mutations:string[]=[];
 await page.route(url,async route=>{
  if(route.request().method()!=='PUT'){await route.continue();return;}
  mutations.push(route.request().postDataJSON().mutationId);const response=await route.fetch();
  if(mutations.length===1)await route.abort('failed');else await route.fulfill({response});
 });
 await page.getByLabel('姓名',{exact:true}).fill('响应丢失的修改');await expect(page.getByTestId('save-status')).toHaveText('保存失败');expect((await(await request.get('/api/resumes/'+resume.id)).json()).document.content.name).toBe('响应丢失的修改');
 await page.getByRole('button',{name:'撤销',exact:true}).click();await ready(page);current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document).toEqual(resume.document);expect(current.revision).toBe(5);expect(mutations).toHaveLength(3);expect(mutations[0]).toBe(mutations[1]);expect(mutations[2]).not.toBe(mutations[0]);
 // A different writer after the lost response must still win; preserve the undone draft as a copy.
 await page.unroute(url);let loseAgain=true;
 await page.route(url,async route=>{if(route.request().method()!=='PUT'){await route.continue();return;}const response=await route.fetch();if(loseAgain){loseAgain=false;await route.abort('failed');}else await route.fulfill({response});});
 await page.getByLabel('姓名',{exact:true}).fill('再次丢失响应');await expect(page.getByTestId('save-status')).toHaveText('保存失败');
 const committed=await(await request.get('/api/resumes/'+resume.id)).json();committed.document.content.headline='另一个页面的更新';
 const peer=await(await request.put('/api/resumes/'+resume.id,{headers,data:{title:committed.title,document:committed.document,expectedRevision:committed.revision,mutationId:crypto.randomUUID()}})).json();
 await page.getByRole('button',{name:'撤销',exact:true}).click();await expect(page.getByRole('alert')).toContainText('REVISION_CONFLICT');await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙');expect((await(await request.get('/api/resumes/'+resume.id)).json()).document).toEqual(peer.document);
 const copied=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/resumes'&&r.request().method()==='POST');await page.getByRole('button',{name:'另存副本',exact:true}).click();const copy=await(await copied).json();owned.push(copy.id);await ready(page);expect(copy.document).toEqual(resume.document);expect((await(await request.get('/api/resumes/'+resume.id)).json()).document).toEqual(peer.document);
 await page.reload();await ready(page);expect(new URL(page.url()).searchParams.get('resume')).toBe(copy.id);await expect(page.getByLabel('姓名',{exact:true})).toHaveValue('奶龙');
});

test('comparison includes an unsaved draft without retrying or overwriting it',async({page,request})=>{
 const resume=await create(request);await page.goto(`/?view=editor&resume=${resume.id}`);await ready(page);
 await page.getByRole('button',{name:'历史版本',exact:true}).click();
 let writes=0;const url=`**/api/resumes/${resume.id}`;
 await page.route(url,async route=>{if(route.request().method()==='PUT'){writes++;await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DATABASE_UNAVAILABLE',message:'合成测试：保存失败'})});}else await route.continue();});
 await page.getByLabel('简历名称',{exact:true}).fill('尚未保存的名称');await expect(page.getByTestId('save-status')).toHaveText('保存失败');
 const versions=await(await request.get(`/api/resumes/${resume.id}/versions`)).json();await page.getByRole('listitem').filter({hasText:'初始版本'}).getByRole('button',{name:'对比',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'版本对比'});await expect(dialog).toContainText('包含尚未保存的修改');await expect(dialog.getByTestId('difference-basic')).toContainText('尚未保存的名称');
 expect(await(await request.get('/api/resumes/'+resume.id)).json()).toEqual(resume);expect(await(await request.get(`/api/resumes/${resume.id}/versions`)).json()).toEqual(versions);expect(writes).toBe(1);
 await dialog.getByRole('button',{name:'关闭对比'}).click();await expect(page.getByLabel('简历名称',{exact:true})).toHaveValue('尚未保存的名称');await page.unroute(url);await page.getByRole('button',{name:'撤销',exact:true}).click();await ready(page);
 expect((await(await request.get('/api/resumes/'+resume.id)).json()).revision).toBe(resume.revision);
});

test('version comparison is read-only, covers text/images/layout and can restore then undo',async({page,request})=>{
 const resume=await create(request,'版本对比 · 合成简历'),other=await create(request,'对比隔离验证');
 const version=await(await request.post(`/api/resumes/${resume.id}/versions`,{headers,data:{expectedRevision:resume.revision,title:'投递前基线'}})).json();
 await page.goto(`/?view=editor&resume=${resume.id}`);await ready(page);
 await page.getByLabel('姓名',{exact:true}).fill('奶龙·新版');await page.getByTestId('nav-project').click();
 const project=page.getByTestId('section-project');await project.getByLabel('条目正文').first().fill('新的项目描述\n<script>window.__historyInjected = 1</script>');await project.getByLabel('显示模块').uncheck();
 await page.getByRole('button',{name:'照片与校徽',exact:true}).click();await page.getByRole('button',{name:'移除学校 Logo',exact:true}).click();
 await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures/portrait-upright.jpg'));
 await expect(page.frameLocator('iframe').locator('[data-kind=photo] img')).not.toHaveAttribute('src',`/api/assets/${resume.document.layout.photo.id}/image`);
 await page.getByRole('button',{name:'版式设置',exact:true}).click();await page.getByRole('tab',{name:'文字',exact:true}).click();await page.getByRole('button',{name:'宋体',exact:true}).click();
 await page.getByRole('tab',{name:'样式',exact:true}).click();await page.getByRole('button',{name:'橙色主题色',exact:true}).click();
 await page.getByRole('tab',{name:'间距',exact:true}).click();const top=page.getByLabel('上页边距',{exact:true});await top.focus();await top.press('Home');for(let i=0;i<14;i++)await top.press('ArrowRight');await expect(top).toHaveValue('22');await ready(page);
 const changed=await(await request.get('/api/resumes/'+resume.id)).json(),versions=await(await request.get(`/api/resumes/${resume.id}/versions`)).json();
 const detail=await(await request.get(`/api/resumes/${resume.id}/versions/${version.id}`)).json();expect(detail.document).toEqual(resume.document);
 const foreign=await request.get(`/api/resumes/${other.id}/versions/${version.id}`);expect(foreign.status()).toBe(404);expect((await foreign.json()).code).toBe('VERSION_NOT_FOUND');
 await page.getByRole('button',{name:'历史版本',exact:true}).click();const compare=page.getByRole('listitem').filter({hasText:'投递前基线'}).getByRole('button',{name:'对比',exact:true});await compare.click();
 const dialog=page.getByRole('dialog',{name:'版本对比'});await expect(dialog).toBeVisible();await expect(dialog.getByTestId('difference-basic')).toContainText('奶龙·新版');
 await expect(dialog.getByTestId('version-differences')).toContainText('<script>window.__historyInjected = 1</script>');await expect(dialog.locator('script')).toHaveCount(0);expect(await page.evaluate(()=>Reflect.get(window,'__historyInjected'))).toBeUndefined();
 await dialog.getByRole('button',{name:/^图片 /}).click();await expect(dialog.getByAltText('历史版本证件照')).toHaveAttribute('src',`/api/assets/${resume.document.layout.photo.id}/image`);await expect(dialog.getByAltText('当前证件照')).toHaveAttribute('src',`/api/assets/${changed.document.layout.photo.id}/image`);await expect(dialog.getByTestId('difference-logo')).toContainText('已移除');
 await dialog.getByRole('button',{name:/^版式 /}).click();await expect(dialog.getByTestId('difference-layout')).toContainText('宋体');await expect(dialog.getByTestId('difference-layout')).toContainText('22 mm');await page.screenshot({path:path.join(root,'output/history-compare-layout.png'),animations:'disabled'});
 await dialog.getByRole('button',{name:/^全部 /}).click();await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output/history-compare.png'),animations:'disabled'});
 await dialog.getByLabel('关闭版本对比').focus();await page.keyboard.press('Shift+Tab');await expect(dialog.getByRole('button',{name:'恢复此版本'})).toBeFocused();await page.keyboard.press('Tab');await expect(dialog.getByLabel('关闭版本对比')).toBeFocused();
 await page.keyboard.press('Control+z');expect((await(await request.get('/api/resumes/'+resume.id)).json()).document).toEqual(changed.document);
 expect(await(await request.get(`/api/resumes/${resume.id}/versions`)).json()).toEqual(versions);
 await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(compare).toBeFocused();
 await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await compare.click();await page.screenshot({path:path.join(root,'output/history-compare-dark.png'),animations:'disabled'});
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(root,'output/history-compare-mobile.png'),animations:'disabled'});const bounds=await dialog.boundingBox();expect(bounds!.x).toBeGreaterThanOrEqual(0);expect(bounds!.x+bounds!.width).toBeLessThanOrEqual(390);
 await page.setViewportSize({width:1480,height:1120});
 const restoreUrl=`**/api/resumes/${resume.id}/versions/${version.id}/restore`;await page.route(restoreUrl,route=>route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'DATABASE_UNAVAILABLE',message:'合成测试：恢复失败，请重试。'})}));
 await dialog.getByRole('button',{name:'恢复此版本'}).click();await expect(dialog.getByRole('alert')).toContainText('恢复失败');await expect(dialog).toBeVisible();expect((await(await request.get('/api/resumes/'+resume.id)).json()).document).toEqual(changed.document);await page.unroute(restoreUrl);
 const restored=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/resumes/${resume.id}/versions/${version.id}/restore`&&r.request().method()==='POST');
 await dialog.getByRole('button',{name:'恢复此版本'}).click();expect((await restored).ok()).toBeTruthy();await expect(dialog).toHaveCount(0);await ready(page);
 let current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document).toEqual(resume.document);const revision=current.revision;
 await page.getByRole('button',{name:'撤销',exact:true}).click();await ready(page);current=await(await request.get('/api/resumes/'+resume.id)).json();expect(current.document).toEqual(changed.document);expect(current.revision).toBeGreaterThan(revision);
 await page.reload();await ready(page);await expect(page.getByRole('button',{name:'撤销',exact:true})).toBeDisabled();expect((await(await request.get('/api/resumes/'+resume.id)).json()).document).toEqual(changed.document);
 await page.getByRole('button',{name:'← 我的简历',exact:true}).click();await page.getByTestId('resume-'+resume.id).getByRole('button',{name:'继续编辑'}).click();await ready(page);
 await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByRole('listitem').filter({hasText:'投递前基线'}).getByRole('button',{name:'对比',exact:true}).click();await expect(dialog).toBeVisible();
 // Exercise top-level popstate without traversing the preview iframe's history entries.
 await page.evaluate(()=>{history.replaceState(null,'',location.pathname);window.dispatchEvent(new PopStateEvent('popstate'));});
 await expect(dialog).toHaveCount(0);await expect(page.getByTestId('resume-'+resume.id).getByRole('button',{name:'继续编辑'})).toBeEnabled();
});
