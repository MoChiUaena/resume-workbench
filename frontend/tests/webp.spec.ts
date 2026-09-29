import {test,expect,type APIRequestContext} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
let owned:string[]=[];
test.beforeEach(()=>{owned=[];test.skip(process.env.RESUME_TEST_ISOLATED!=='1','WebP verification uses an isolated workspace.');});
test.afterEach(async({request})=>{for(const id of owned){const response=await request.get('/api/resumes/'+id);if(response.ok())await request.delete('/api/resumes/'+id,{headers,data:{expectedRevision:(await response.json()).revision}});}});
async function current(request:APIRequestContext,id:string){return(await request.get('/api/resumes/'+id)).json();}

test('WebP images survive real form upload, EXIF correction, cropping, history restore and PDF export',async({page,request})=>{
 const created=await request.post('/api/resumes',{headers,data:{title:'WebP 图片导入 · 合成示例',sample:'two'}});expect(created.ok()).toBeTruthy();const resume=await created.json();owned.push(resume.id);
 await page.goto(`/?view=editor&resume=${resume.id}`);await expect(page.getByTestId('preview-status')).toContainText('预览已更新');await page.getByRole('button',{name:'照片与校徽',exact:true}).click();
 const photoResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/assets'&&r.request().method()==='POST');await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures/portrait-exif-6.webp'));const uploadedPhoto=await photoResponse;expect(uploadedPhoto.ok()).toBeTruthy();const photo=await uploadedPhoto.json();
 expect([photo.format,photo.exifOrientation,photo.width,photo.height]).toEqual(['WEBP',6,360,480]);await expect(page.getByTestId('photo-controls')).toContainText('已校正方向');await expect.poll(async()=>(await current(request,resume.id)).document.layout.photo.id).toBe(photo.id);
 const logoResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/assets'&&r.request().method()==='POST');await page.getByLabel('上传学校 Logo').setInputFiles({name:'content-identification.jpg',mimeType:'image/jpeg',buffer:await fs.readFile(path.join(root,'fixtures/university-logo-lossless.webp'))});const uploadedLogo=await logoResponse;expect(uploadedLogo.ok()).toBeTruthy();const logo=await uploadedLogo.json();expect(logo.format).toBe('WEBP');
 await expect.poll(async()=>(await current(request,resume.id)).document.layout.logo.id).toBe(logo.id);await expect(page.frameLocator('iframe').locator('[data-kind=logo] img')).toHaveAttribute('src',`/api/assets/${logo.id}/image`);await expect(page.frameLocator('iframe').locator('[data-kind=photo] img')).toHaveAttribute('src',`/api/assets/${photo.id}/image`);
 await fs.mkdir(path.join(root,'output/pdf'),{recursive:true});await page.screenshot({path:path.join(root,'output/webp-image-import.png'),animations:'disabled'});
 const controls=page.getByTestId('photo-controls');await controls.getByText('裁剪与旋转').click();await controls.getByRole('button',{name:'顺时针旋转 90°'}).click();await page.getByLabel('证件照缩放',{exact:true}).fill('1.2');
 await expect.poll(async()=>{const slot=(await current(request,resume.id)).document.layout.photo;return[slot.quarterTurns,slot.zoom];}).toEqual([1,1.2]);
 await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByLabel('快照名称').fill('WebP 图片基线');await page.getByRole('button',{name:'保存版本快照',exact:true}).click();await expect(page.getByRole('listitem').filter({hasText:'WebP 图片基线'})).toBeVisible();
 await page.getByRole('button',{name:'照片与校徽',exact:true}).click();const replacementResponse=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/assets'&&r.request().method()==='POST');await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures/portrait-lossy.webp'));const replacement=await(await replacementResponse).json();await expect.poll(async()=>(await current(request,resume.id)).document.layout.photo.id).toBe(replacement.id);
 await page.getByRole('button',{name:'历史版本',exact:true}).click();await page.getByRole('listitem').filter({hasText:'WebP 图片基线'}).getByRole('button',{name:'恢复',exact:true}).click();await expect.poll(async()=>(await current(request,resume.id)).document.layout.photo.id).toBe(photo.id);
 const restored=await current(request,resume.id);expect(restored.document.layout.logo.id).toBe(logo.id);expect([restored.document.layout.photo.quarterTurns,restored.document.layout.photo.zoom]).toEqual([1,1.2]);
 const download=page.waitForEvent('download');await page.getByRole('button',{name:'导出 PDF',exact:true}).click();await(await download).saveAs(path.join(root,'output/pdf/webp-ui.pdf'));expect((await fs.readFile(path.join(root,'output/pdf/webp-ui.pdf'))).subarray(0,5).toString()).toBe('%PDF-');
 await expect(page.frameLocator('iframe').locator('.sheet')).toHaveCount(2);
});

test('animated or corrupt WebP explains the failure and preserves both existing image slots',async({page,request})=>{
 const response=await request.post('/api/resumes',{headers,data:{title:'WebP 错误与重试 · 合成示例',sample:'one'}});const resume=await response.json();owned.push(resume.id);
 await page.goto(`/?view=editor&resume=${resume.id}`);await expect(page.getByTestId('preview-status')).toContainText('预览已更新');await page.getByRole('button',{name:'照片与校徽',exact:true}).click();
 for(const[filename,code]of[['animated.webp','ANIMATED_WEBP_UNSUPPORTED'],['corrupt.webp','CORRUPT_IMAGE'],['unsupported.svg','UNSUPPORTED_FORMAT']]){
  const failed=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/assets'&&r.request().method()==='POST');await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures',filename));expect((await(await failed).json()).code).toBe(code);await expect(page.getByTestId('photo-controls').getByRole('alert')).toContainText(code);
  expect(await current(request,resume.id)).toEqual(resume);await expect(page.frameLocator('iframe').locator('[data-kind=photo] img')).toHaveAttribute('src',`/api/assets/${resume.document.layout.photo.id}/image`);
 }
 const recovered=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/assets'&&r.request().method()==='POST');await page.getByLabel('上传证件照').setInputFiles(path.join(root,'fixtures/portrait-lossy.webp'));const photo=await(await recovered).json();expect(photo.format).toBe('WEBP');await expect(page.getByTestId('photo-controls').getByRole('alert')).toHaveCount(0);await expect.poll(async()=>(await current(request,resume.id)).document.layout.photo.id).toBe(photo.id);
 expect((await current(request,resume.id)).document.layout.logo).toEqual(resume.document.layout.logo);
});
