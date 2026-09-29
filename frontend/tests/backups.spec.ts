import {test,expect} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
let owned:string[]=[];
test.beforeEach(()=>{owned=[];test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Automatic backup tests require their own disposable workspace.');});
test.afterEach(async({request})=>{
 if(process.env.RESUME_TEST_ISOLATED!=='1')return;
 await request.put('/api/backups/automatic',{headers,data:{enabled:false,frequency:'daily'}});
 for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}
});

test('automatic baseline skips unchanged data and history can download and restore earlier content without overwrite',async({page,request})=>{
 const source=await(await request.post('/api/resumes',{headers,data:{title:'自动备份 · 奶龙合成简历',sample:'one'}})).json();owned.push(source.id);
 await page.goto('/');await page.getByRole('button',{name:'备份与恢复',exact:true}).click();const dialog=page.getByRole('dialog',{name:'备份与恢复',exact:true});await dialog.getByRole('tab',{name:'自动备份',exact:true}).click();
 await expect(dialog.getByLabel('启用自动备份')).not.toBeChecked();await dialog.getByLabel('启用自动备份').check();await dialog.getByLabel('自动备份频率').selectOption('weekly');
 expect((await(await request.get('/api/backups/automatic')).json()).state.enabled).toBe(false);
 const previous=(await(await request.get('/api/backups/automatic')).json()).state.lastBackup?.id;
 await dialog.getByRole('button',{name:'保存自动备份设置',exact:true}).click();
 await expect.poll(async()=>{const status=await(await request.get('/api/backups/automatic')).json();return !status.running&&status.state.lastBackup?.id!==previous&&status.state.outcome==='created';},{timeout:75000}).toBe(true);
 const baseline=(await(await request.get('/api/backups/automatic')).json()).state.lastBackup;
 const checked=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/backups/automatic/check'&&r.request().method()==='POST');await dialog.getByRole('button',{name:'立即检查备份',exact:true}).click();expect((await(await checked).json()).state.outcome).toBe('unchanged');await expect(dialog.getByTestId('automatic-backup-status')).toContainText('没有变化');expect((await(await request.get('/api/backups/automatic')).json()).state.lastBackup.id).toBe(baseline.id);
 await page.screenshot({path:path.join(root,'output/automatic-backups.png'),animations:'disabled'});
 await dialog.getByRole('tab',{name:'本机历史',exact:true}).click();const row=dialog.getByTestId('backup-'+baseline.id);await expect(row).toContainText('自动');
 await fs.mkdir(path.join(root,'output'),{recursive:true});const download=page.waitForEvent('download');await row.getByRole('link',{name:'下载',exact:true}).click();await(await download).saveAs(path.join(root,'output/automatic-backup-ui.zip'));
 source.document.content.headline='备份之后的新方向';const saved=await request.put('/api/resumes/'+source.id,{headers,data:{title:source.title,document:source.document,expectedRevision:source.revision,mutationId:crypto.randomUUID()}});expect(saved.ok()).toBeTruthy();const latest=await saved.json();
 const check=await request.post('/api/backups/automatic/check',{headers,data:{}});expect(check.ok()).toBeTruthy();const newer=(await check.json()).state.lastBackup;expect(newer.id).not.toBe(baseline.id);
 const list=(await(await request.get('/api/backups')).json()).items;expect(list.some((item:any)=>item.backup.id===baseline.id)).toBeTruthy();expect(list.some((item:any)=>item.backup.id===newer.id)).toBeTruthy();
 await row.getByRole('button',{name:'选择恢复'}).click();const restoredResponse=page.waitForResponse(r=>new URL(r.url()).pathname===`/api/backups/${baseline.id}/restore`&&r.request().method()==='POST');await dialog.getByRole('button',{name:'确认恢复为新记录',exact:true}).click();const restored=await(await restoredResponse).json();owned.push(...restored.resumeIds);
 await expect(dialog.getByRole('status').filter({hasText:'已恢复'})).toBeVisible();expect(await(await request.get('/api/resumes/'+source.id)).json()).toEqual(latest);
 const imported=await(await request.get('/api/resumes/'+restored.resumeIds[0])).json();expect(imported.document.content.headline).not.toBe(latest.document.content.headline);expect(imported.document.content.name).toBe('奶龙');
 const originalAsset=await(await request.get('/api/assets/'+source.document.layout.photo.id)).json(),importedAsset=await(await request.get('/api/assets/'+imported.document.layout.photo.id)).json();expect(importedAsset.sha256).toBe(originalAsset.sha256);
 await page.screenshot({path:path.join(root,'output/backup-history.png'),animations:'disabled'});
});

test('disabled policy rejects manual checks, settings survive reopen, and dialog supports B dark and keyboard on narrow screens',async({page,request})=>{
 const disabled=await request.put('/api/backups/automatic',{headers,data:{enabled:false,frequency:'daily'}});expect(disabled.ok()).toBeTruthy();
 const check=await request.post('/api/backups/automatic/check',{headers,data:{}});expect(check.status()).toBe(422);
 const invalid=await request.put('/api/backups/automatic',{headers,data:{enabled:true,frequency:'hourly'}});expect(invalid.status()).toBe(400);expect((await(await request.get('/api/backups/automatic')).json()).state.enabled).toBe(false);
 await page.goto('/');await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.setViewportSize({width:390,height:844});
 const trigger=page.getByRole('button',{name:'备份与恢复',exact:true});await trigger.click();let dialog=page.getByRole('dialog',{name:'备份与恢复',exact:true});await dialog.getByRole('tab',{name:'自动备份',exact:true}).click();await dialog.getByLabel('自动备份频率').selectOption('weekly');
 await expect(dialog.getByText('修改尚未保存。',{exact:true})).toBeVisible();expect((await(await request.get('/api/backups/automatic')).json()).state.frequency).toBe('daily');await dialog.getByRole('button',{name:'保存自动备份设置',exact:true}).click();
 await expect(dialog.getByTestId('automatic-backup-status')).toContainText('已关闭');await expect(dialog.getByRole('status').filter({hasText:'设置已保存'})).toBeVisible();await expect(dialog.getByRole('button',{name:'关闭备份与恢复'})).toBeEnabled();await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(trigger).toBeFocused();
 await trigger.click();dialog=page.getByRole('dialog',{name:'备份与恢复',exact:true});await dialog.getByRole('tab',{name:'自动备份',exact:true}).click();await expect(dialog.getByLabel('自动备份频率')).toHaveValue('weekly');await expect(dialog.getByLabel('启用自动备份')).not.toBeChecked();
 expect(await dialog.evaluate(el=>el.getBoundingClientRect().left>=0&&el.getBoundingClientRect().right<=window.innerWidth)).toBeTruthy();await dialog.getByRole('button',{name:'关闭备份与恢复'}).focus();await page.keyboard.press('Shift+Tab');await expect(dialog.getByRole('button',{name:'关闭',exact:true})).toBeFocused();
 await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await page.setViewportSize({width:1480,height:1120});await page.getByLabel('界面风格').selectOption('a');await page.getByRole('switch',{name:'深色模式'}).click();
});
