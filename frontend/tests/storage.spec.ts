import {test,expect} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
let owned:string[]=[];
test.beforeEach(()=>{owned=[];test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Storage tests require a disposable workspace.');});
test.afterEach(async({request})=>{if(process.env.RESUME_TEST_ISOLATED!=='1')return;for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});

test('real inventory preserves images, PDFs and revisions across checks and deleted resumes',async({page,request})=>{
 const response=await request.post('/api/resumes',{headers,data:{title:'PRIVATE-TITLE-CANARY',sample:'one'}});expect(response.ok()).toBeTruthy();const source=await response.json();owned.push(source.id);
 const photo=source.document.layout.photo.id,logo=source.document.layout.logo.id;
 const originalPhoto=await(await request.get('/api/assets/'+photo+'/image')).body();
 const exported=await(await request.post(`/api/resumes/${source.id}/export`,{headers,data:{expectedRevision:source.revision}})).json();
 const pdf=await(await request.get(`/api/exports/${exported.id}/pdf`)).body();const versions=await(await request.get(`/api/resumes/${source.id}/versions`)).json();
 source.document.layout.photo.id=null;const saved=await(await request.put(`/api/resumes/${source.id}`,{headers,data:{title:source.title,document:source.document,expectedRevision:source.revision,mutationId:crypto.randomUUID()}})).json();
 await page.goto('/');await page.getByRole('button',{name:'备份与恢复',exact:true}).click();const dialog=page.getByRole('dialog',{name:'备份与恢复',exact:true});
 const reply=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/storage/preview');await dialog.getByRole('tab',{name:'空间检查',exact:true}).click();const scan=await reply;expect(scan.ok()).toBeTruthy();const report=await scan.json();
 expect(report.referencesVerified).toBe(true);expect(report.digest).toMatch(/^[a-f0-9]{64}$/);expect(JSON.stringify(report)).not.toContain('PRIVATE-TITLE-CANARY');
 expect(report.statuses.find((item:any)=>item.key==='in_use').count).toBeGreaterThanOrEqual(3);
 const historical=report.items.find((item:any)=>item.id===photo);expect(historical).toBeDefined();expect(historical.reason).toBe('historical_image');
 await expect(dialog.getByTestId('storage-total')).toBeVisible();expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(saved);expect(await(await request.get(`/api/resumes/${source.id}/versions`)).json()).toEqual(versions);
 await request.delete('/api/resumes/'+source.id,{headers,data:{expectedRevision:saved.revision}});
 const deleted=await(await request.get('/api/storage/preview')).json();expect(deleted.referencesVerified).toBe(true);expect(deleted.digest).not.toBe(report.digest);
 for(const id of [photo,logo,exported.id]){const item=deleted.items.find((item:any)=>item.id===id);expect(item).toBeDefined();expect(item.status).toBe('recent');}
 expect(await(await request.get('/api/assets/'+photo+'/image')).body()).toEqual(originalPhoto);expect(await(await request.get(`/api/exports/${exported.id}/pdf`)).body()).toEqual(pdf);
 const forbidden=await request.delete('/api/storage/preview',{headers});expect(forbidden.ok()).toBe(false);expect(await(await request.get('/api/assets/'+photo+'/image')).body()).toEqual(originalPhoto);
});

test('preview explains candidate and uncertain files, handles refresh failure and supports A/B dark narrow views',async({page})=>{
 const ids=['11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222','33333333-3333-4333-8333-333333333333'];
 const report={checkedAt:'2026-09-30T08:00:00Z',graceDays:30,referencesVerified:true,bytesComplete:false,kinds:[{key:'image',count:2,bytes:16384},{key:'pdf',count:1,bytes:4096},{key:'other',count:0,bytes:0}],statuses:[{key:'in_use',count:1,bytes:8192},{key:'recent',count:0,bytes:0},{key:'candidate',count:1,bytes:4096},{key:'check',count:1,bytes:8192}],items:[{kind:'image',id:ids[0],status:'check',reason:'file_mismatch',bytes:8192,lastModified:'2026-08-01T00:00:00Z'},{kind:'pdf',id:ids[1],status:'candidate',reason:'deleted_pdf',bytes:4096,lastModified:'2026-08-01T00:00:00Z'},{kind:'image',id:ids[2],status:'in_use',reason:'historical_image',bytes:8192,lastModified:'2026-08-01T00:00:00Z'}],omitted:0,digest:'a'.repeat(64)};
 for(let index=0;index<22;index++)report.items.push({kind:'image',id:`44444444-4444-4444-8444-${String(index).padStart(12,'0')}`,status:'in_use',reason:'current_image',bytes:8192,lastModified:'2026-08-01T00:00:00Z'});
 report.kinds[0].count+=22;report.kinds[0].bytes+=22*8192;report.statuses[0].count+=22;report.statuses[0].bytes+=22*8192;
 let fail=false;const methods:string[]=[];await page.route('**/api/storage/preview',async route=>{methods.push(route.request().method());await route.fulfill({status:fail?503:200,contentType:'application/json',body:JSON.stringify(fail?{code:'STORAGE_SCAN_FAILED',message:'合成检查失败，文件保留。'}:report)});});
 await page.goto('/');const trigger=page.getByRole('button',{name:'备份与恢复',exact:true});await trigger.click();const dialog=page.getByRole('dialog',{name:'备份与恢复',exact:true});await dialog.getByRole('tab',{name:'空间检查',exact:true}).click();
 await expect(dialog.getByTestId('storage-summary-candidate')).toContainText('1');await expect(dialog.getByTestId('storage-item-'+ids[1])).toContainText('对应简历和历史版本已不存在');await expect(dialog.getByTestId('storage-item-'+ids[0])).toContainText('校验值');
 await expect(dialog.getByRole('button',{name:/删除|清理文件|确认清理/})).toHaveCount(0);await dialog.getByLabel('空间检查文件范围').selectOption('in_use');await expect(dialog.getByTestId('storage-item-'+ids[2])).toContainText('历史版本仍引用');await expect(dialog.getByTestId('storage-item-'+ids[1])).toHaveCount(0);
 await expect(dialog.locator('.storage-items>li')).toHaveCount(20);await dialog.getByRole('group',{name:'空间清单分页'}).getByRole('button',{name:'下一页'}).click();await expect(dialog.locator('.storage-items>li')).toHaveCount(3);await expect(dialog.getByTestId('storage-item-'+ids[2])).toHaveCount(0);
 await dialog.getByLabel('空间检查文件范围').selectOption('review');await expect(dialog.getByRole('group',{name:'空间清单分页'})).toContainText('第 1 / 1 页');await page.setViewportSize({width:1480,height:1350});await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output/storage-preview.png'),animations:'disabled'});
 fail=true;await dialog.getByRole('button',{name:'重新检查空间'}).click();await expect(dialog.getByRole('alert')).toContainText('STORAGE_SCAN_FAILED');await expect(dialog.getByTestId('storage-total')).toHaveCount(0);fail=false;await dialog.getByRole('button',{name:'重新检查空间'}).click();await expect(dialog.getByTestId('storage-total')).toBeVisible();
 await page.keyboard.press('Escape');await expect(trigger).toBeFocused();await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.setViewportSize({width:390,height:1000});await trigger.click();await page.getByRole('tab',{name:'空间检查',exact:true}).click();
 await expect(dialog.getByTestId('storage-total')).toBeVisible();expect(await dialog.evaluate(el=>el.scrollWidth<=el.clientWidth+1)).toBe(true);expect(await dialog.evaluate(el=>el.getBoundingClientRect().left>=0&&el.getBoundingClientRect().right<=innerWidth)).toBe(true);
 await page.screenshot({path:path.join(root,'output/storage-preview-dark-mobile.png'),animations:'disabled'});await dialog.getByRole('button',{name:'关闭备份与恢复'}).focus();await page.keyboard.press('Shift+Tab');await expect(dialog.getByRole('button',{name:'关闭',exact:true})).toBeFocused();await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);expect(methods.every(method=>method==='GET')).toBe(true);
});
