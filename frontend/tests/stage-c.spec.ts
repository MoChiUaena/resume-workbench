import {test,expect} from '@playwright/test';
import path from 'node:path';
import fs from 'node:fs/promises';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};

test('complete backup UI preserves current records and restores editable versions and images',async({page,request})=>{
 test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Restore is tested only in an explicitly isolated instance.');
 const external:string[]=[];
 await page.route('**/*',async route=>{if(!['127.0.0.1','localhost'].includes(new URL(route.request().url()).hostname)){external.push(route.request().url());await route.abort();}else await route.continue();});
 const owned:string[]=[];
 try{
  const response=await request.post('/api/resumes',{headers,data:{title:'备份闭环 · 合成示例',sample:'one'}});expect(response.ok()).toBeTruthy();const original=await response.json();owned.push(original.id);
  await request.post(`/api/resumes/${original.id}/versions`,{headers,data:{expectedRevision:original.revision,title:'投递前版本'}});
  const exported=await request.post(`/api/resumes/${original.id}/export`,{headers,data:{expectedRevision:original.revision},timeout:90000});expect(exported.ok()).toBeTruthy();
  await page.goto('/');await page.getByRole('button',{name:'备份与恢复',exact:true}).click();
  await expect(page.getByRole('dialog')).toBeVisible();const download=page.waitForEvent('download');await page.getByRole('button',{name:'下载完整备份'}).click();
  const backup=path.join(root,'output/stage-c-ui-backup.zip');await fs.mkdir(path.dirname(backup),{recursive:true});await(await download).saveAs(backup);
  await expect(page.getByRole('status').filter({hasText:'已创建'})).toBeVisible();
  await page.getByLabel('选择备份文件').setInputFiles(backup);const completed=page.waitForResponse(r=>r.url().endsWith('/api/backups/restore')&&r.request().method()==='POST');
  await page.getByRole('button',{name:'恢复为新记录'}).click();const result=await(await completed).json();owned.push(...result.resumeIds);
  await expect(page.getByRole('status').filter({hasText:'已恢复'})).toBeVisible();expect(result.versions).toBeGreaterThanOrEqual(3);expect(result.attachments).toBeGreaterThanOrEqual(2);expect(result.exports).toBeGreaterThanOrEqual(1);
  const current=await(await request.get('/api/resumes/'+original.id)).json();expect(current).toEqual(original);
  const imported=await Promise.all(result.resumeIds.map(async(id:string)=>(await request.get('/api/resumes/'+id)).json()));
  const restored=imported.find(record=>record.title===original.title&&record.createdAt===original.createdAt);expect(restored).toBeTruthy();
  const importedId=restored.id;expect(importedId).not.toBe(original.id);expect(restored.document.content).toEqual(original.document.content);
  const a=await(await request.get('/api/assets/'+original.document.layout.photo.id)).json();const b=await(await request.get('/api/assets/'+restored.document.layout.photo.id)).json();expect(b.sha256).toBe(a.sha256);expect(b.normalizedSha256).toBe(a.normalizedSha256);
  await page.getByRole('button',{name:'关闭并查看简历'}).click();await page.getByTestId('resume-'+importedId).getByRole('button',{name:'继续编辑'}).click();await expect(page.getByTestId('preview-status')).toContainText('预览已更新');
  await page.getByLabel('求职方向',{exact:true}).fill('恢复后继续编辑');await expect(page.getByTestId('save-status')).toHaveText('已保存到本机');
  const pdfDownload=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await pdfDownload).saveAs(path.join(root,'output/pdf/restored-editable.pdf'));
  expect(external).toEqual([]);
 }finally{
  for(const id of owned){const r=await request.get('/api/resumes/'+id);if(r.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await r.json()).revision}});}
 }
});
