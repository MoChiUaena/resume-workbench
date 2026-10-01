import {test,expect,type Page} from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {requireIsolatedQuarantineWorkspace,seedCandidate,inspectFixture,sourceCanaries,purgePayloadInventory} from './quarantine-fixture';
const root=path.resolve(import.meta.dirname,'../..'),headers={'X-Local-Resume':'1'};
const id='a1111111-1111-4111-8111-000000000001',image='b1111111-1111-4111-8111-000000000002',exportId='c1111111-1111-4111-8111-000000000003',digest='a'.repeat(64),sha='b'.repeat(64);
const receipt=(state='quarantined')=>({id,state,createdAt:'2026-10-01T00:00:00Z',updatedAt:'2026-10-01T00:00:00Z',items:[{kind:'image',id:image,bytes:1024}],bytes:1024,backupId:null,digest,errorCode:state==='purging'?'QUARANTINE_PURGE_FAILED':null,cleanup:['purging','purged'].includes(state)?{exportId,sha256:sha,bytes:1500}:null});
const ticket=(requestId:string,downloaded=false)=>({id:requestId,operationId:id,digest,bytes:1500,sha256:sha,createdAt:'2026-10-01T00:00:00Z',expiresAt:'2099-10-01T00:10:00Z',downloaded});
const report=()=>({checkedAt:'2026-10-01T00:00:00Z',graceDays:30,referencesVerified:true,bytesComplete:true,kinds:[],statuses:[],items:[],digest:'c'.repeat(64)});
async function open(page:Page){await page.goto('/');await page.getByRole('button',{name:'备份与恢复',exact:true}).click();const d=page.getByRole('dialog',{name:'备份与恢复',exact:true});await d.getByRole('tab',{name:'空间检查',exact:true}).click();return d;}
async function setup(page:Page,state='quarantined'){
 await page.route('**/api/storage/preview',r=>r.fulfill({json:report()}));
 await page.route('**/api/storage/quarantine?page=*',r=>r.fulfill({json:{items:[receipt(state)],page:0,hasMore:false,unreadable:0}}));
 const d=await open(page);await d.getByRole('button',{name:state==='purging'?'继续永久清理':'查看永久清理清单',exact:true}).click();return d;
}
test.beforeEach(()=>{test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Disposable workspace required');requireIsolatedQuarantineWorkspace();});

test('download status then human saved copy and exact batch code gate irreversible cleanup',async({page})=>{
 const bodies:any[]=[];let requestId='',downloaded=false;
 await page.route('**/api/storage/quarantine/*/file-backups',r=>{bodies.push(r.request().postDataJSON());requestId=bodies[0].requestId;return r.fulfill({json:ticket(requestId)});});
 await page.route('**/api/storage/quarantine/*/file-backups/*',r=>r.fulfill({json:ticket(requestId,downloaded)}));
 const d=await setup(page);const purge=d.getByRole('button',{name:'确认永久清理',exact:true});await expect(purge).toBeDisabled();expect(bodies).toHaveLength(0);
 await d.getByRole('button',{name:'生成暂存文件 ZIP'}).click();await expect(d.getByRole('link',{name:'下载暂存文件 ZIP'})).toHaveAttribute('href',`/api/storage/quarantine/${id}/file-backups/${requestId}/download`);
 await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));await expect(purge).toBeDisabled();
 await d.getByRole('button',{name:'检查下载完成状态'}).click();await expect(purge).toBeDisabled();downloaded=true;await d.getByRole('button',{name:'检查下载完成状态'}).click();await expect(purge).toBeEnabled();await d.getByLabel('输入批次末六位').fill(' '+id.slice(-6));await expect(purge).toBeDisabled();
 expect(bodies).toEqual([{requestId,expectedDigest:digest,confirm:true}]);
});

test('unknown download GET cannot authorize cleanup and retains the same ticket',async({page})=>{
 let requestId='',status:any=null,raw=false,abort=false;const ids:string[]=[];
 await page.route('**/api/storage/quarantine/*/file-backups',r=>{requestId=r.request().postDataJSON().requestId;return r.fulfill({json:ticket(requestId)});});
 await page.route('**/api/storage/quarantine/*/file-backups/*',r=>{ids.push(r.request().url());if(abort)return r.abort();return r.fulfill(raw?{contentType:'application/json',body:'{'}:{json:status});});
 const d=await setup(page);await d.getByRole('button',{name:'生成暂存文件 ZIP'}).click();await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));
 const valid=ticket(requestId,true),invalid=[null,{}, {...valid,id:image},{...valid,operationId:image},{...valid,digest:'d'.repeat(64)},{...valid,sha256:'d'.repeat(64)},{...valid,bytes:1499},{...valid,downloaded:'true'}];
 for(let index=0;index<invalid.length+2;index++){
  status=valid;raw=false;abort=false;await d.getByRole('button',{name:'检查下载完成状态'}).click();await expect(d.getByRole('button',{name:'确认永久清理',exact:true})).toBeEnabled();
  status=invalid[index];raw=index===invalid.length;abort=index===invalid.length+1;await d.getByRole('button',{name:'检查下载完成状态'}).click();await expect(d.getByRole('alert').filter({hasText:'NETWORK_ERROR'})).toBeVisible();await expect(d.getByRole('button',{name:'确认永久清理',exact:true})).toBeDisabled();await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeEnabled();
 }
 expect(new Set(ids).size).toBe(1);await expect(d.getByRole('link',{name:'下载暂存文件 ZIP'})).toHaveAttribute('href',`/api/storage/quarantine/${id}/file-backups/${requestId}/download`);
});

test('new history states with absent or malformed durable proof offer no restore or purge',async({page})=>{
 const pageErrors:string[]=[];page.on('pageerror',e=>pageErrors.push(e.message));await page.route('**/api/storage/preview',r=>r.fulfill({json:report()}));await page.route('**/api/storage/quarantine?page=*',r=>r.fulfill({json:{items:[{...receipt('purging'),cleanup:null},{...receipt('purged'),cleanup:{exportId,sha256:'invalid',bytes:1500}},null],page:0,hasMore:false,unreadable:0}}));const d=await open(page);await expect(d.getByText('有 3 个暂存摘要无法读取', {exact:false})).toBeVisible();await expect(d.getByRole('button',{name:/查看恢复清单|查看永久清理清单|继续永久清理/})).toHaveCount(0);expect(pageErrors).toEqual([]);
});

test('expired download proof disables cleanup and a fresh ZIP requires fresh saved-copy confirmation',async({page})=>{
 await page.clock.install({time:new Date('2026-10-01T00:00:00Z')});const bodies:any[]=[];
 await page.route('**/api/storage/quarantine/*/file-backups',r=>{const body=r.request().postDataJSON();bodies.push(body);return r.fulfill({json:{...ticket(body.requestId,bodies.length===1),expiresAt:'2026-10-01T00:00:10Z'}});});
 const d=await setup(page);await d.getByRole('button',{name:'生成暂存文件 ZIP'}).click();await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));await expect(d.getByRole('button',{name:'确认永久清理',exact:true})).toBeEnabled();await page.clock.fastForward(11000);await expect(d.getByRole('button',{name:'确认永久清理',exact:true})).toBeDisabled();await d.getByRole('button',{name:'生成暂存文件 ZIP'}).click();await expect.poll(()=>bodies.length).toBe(2);await expect(d.getByLabel('我已将 ZIP 保存到其他位置并确认可用')).not.toBeChecked();await expect(d.getByLabel('输入批次末六位')).toHaveValue('');expect(bodies[1].requestId).not.toBe(bodies[0].requestId);
});

const malformed=[
 {name:'aborted',status:200,value:()=>null,abort:true},
 {name:'truncated',status:200,value:()=>'{',raw:true},
 {name:'empty object',status:200,value:()=>({})},
 {name:'null',status:200,value:()=>null},
 {name:'mismatched identity',status:200,value:(v:any)=>({...v,id:image})},
 {name:'mismatched token',status:200,value:(v:any)=>({...v,digest:'d'.repeat(64)})},
 {name:'mismatched proof',status:200,value:(v:any)=>({...v,operationId:image,cleanup:v.cleanup&&{...v.cleanup,sha256:'d'.repeat(64)}})},
 {name:'missing proof',status:200,value:(v:any)=>({...v,cleanup:undefined,sha256:undefined})},
 {name:'invalid error object',status:503,value:()=>({})},
 {name:'invalid error null',status:503,value:()=>null},
 {name:'unknown error code',status:409,value:()=>({code:'UNKNOWN_OPERATION',message:'未知结果'})}
];
for(const action of ['export','purge'] as const)for(const failure of malformed)test(`${action} ${failure.name} keeps immutable request and parent locked until explicit retry`,async({page})=>{
 const bodies:any[]=[],urls:string[]=[],pageErrors:string[]=[];page.on('pageerror',error=>pageErrors.push(error.message));
 await page.route(action==='export'?'**/api/storage/quarantine/*/file-backups':'**/api/storage/quarantine/*/purge',async r=>{
  const body=r.request().postDataJSON();bodies.push(body);urls.push(r.request().url());const valid=action==='export'?ticket(body.requestId):receipt('purged');
  if(bodies.length===1){if(failure.abort)return r.abort();return r.fulfill({status:failure.status,contentType:'application/json',body:failure.raw?failure.value(valid) as string:JSON.stringify(failure.value(valid))});}return r.fulfill({json:valid});
 });
 const d=await setup(page,action==='export'?'quarantined':'purging');
 if(action==='purge'){await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));}
 await d.getByRole('button',{name:action==='export'?'生成暂存文件 ZIP':'确认永久清理',exact:true}).click();
 await expect(d.getByRole('alert')).toBeVisible();
 await expect(d.getByRole('button',{name:action==='export'?'用同一请求重试生成 ZIP':'用同一请求重试永久清理'})).toBeVisible();
 await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeDisabled();for(let i=0;i<4;i++)await expect(d.getByRole('tab').nth(i)).toBeDisabled();await expect(d.getByRole('button',{name:'取消永久清理选择'})).toBeDisabled();await expect(d.getByRole('button',{name:'重新检查空间'})).toBeDisabled();
 await expect(d.getByLabel('我已将 ZIP 保存到其他位置并确认可用')).toBeDisabled();await expect(d.getByLabel('输入批次末六位')).toBeDisabled();await page.keyboard.press('Escape');await expect(d).toBeVisible();
 await d.getByRole('button',{name:action==='export'?'用同一请求重试生成 ZIP':'用同一请求重试永久清理'}).click();await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeEnabled();expect(bodies).toHaveLength(2);expect(bodies[1]).toEqual(bodies[0]);expect(urls[1]).toBe(urls[0]);expect(pageErrors).toEqual([]);
 if(action==='purge'){expect(bodies[0]).toEqual({expectedDigest:digest,exportId,archiveSha256:sha,confirm:true,backupSaved:true,confirmation:id.slice(-6)});await expect(d.locator('[aria-label="永久清理确认"]')).toContainText('当前占用 0 B');}
});

for(const action of ['export','purge'] as const)test(`${action} valid failure refreshes before parent unlock`,async({page})=>{
 let scans=0,reads=0;await page.route(action==='export'?'**/api/storage/quarantine/*/file-backups':'**/api/storage/quarantine/*/purge',r=>r.fulfill({status:409,json:{code:'QUARANTINE_REFERENCED',message:'文件仍有引用，操作未完成。'}}));
 const d=await setup(page,action==='export'?'quarantined':'purging');await page.unroute('**/api/storage/preview');await page.unroute('**/api/storage/quarantine?page=*');
 await page.route('**/api/storage/preview',r=>{scans++;return r.fulfill({json:report()});});await page.route('**/api/storage/quarantine?page=*',r=>{reads++;return r.fulfill({json:{items:[receipt(action==='export'?'quarantined':'purging')],page:0,hasMore:false,unreadable:0}});});
 if(action==='purge'){await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));}await d.getByRole('button',{name:action==='export'?'生成暂存文件 ZIP':'确认永久清理',exact:true}).click();await expect(d.getByRole('alert').filter({hasText:'QUARANTINE_REFERENCED'})).toBeVisible();await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeEnabled();expect(scans).toBeGreaterThan(0);expect(reads).toBeGreaterThan(0);
});

test('known purge failure adopts matching persisted proof and renews human confirmation',async({page})=>{
 let started=false;await page.route('**/api/storage/quarantine/*/file-backups',r=>r.fulfill({json:ticket(r.request().postDataJSON().requestId,true)}));
 await page.route('**/api/storage/quarantine/*/purge',r=>{started=true;return r.fulfill({status:503,json:{code:'QUARANTINE_IO_FAILED',message:'合成清理中断，请核对记录。'}});});
 const d=await setup(page);await page.unroute('**/api/storage/quarantine?page=*');let proofId='';
 await page.route('**/api/storage/quarantine?page=*',r=>r.fulfill({json:{items:[started?{...receipt('purging'),cleanup:{exportId:proofId,sha256:sha,bytes:1500}}:receipt()],page:0,hasMore:false,unreadable:0}}));
 await d.getByRole('button',{name:'生成暂存文件 ZIP'}).click();const href=await d.getByRole('link',{name:'下载暂存文件 ZIP'}).getAttribute('href');proofId=href!.split('/').at(-2)!;
 await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));await d.getByRole('button',{name:'确认永久清理',exact:true}).click();
 await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeEnabled();await expect(d.getByLabel('我已将 ZIP 保存到其他位置并确认可用')).not.toBeChecked();await expect(d.getByLabel('输入批次末六位')).toHaveValue('');await expect(d.getByRole('link',{name:'下载暂存文件 ZIP'})).toHaveCount(0);await expect(d.locator('[aria-label="永久清理确认"]')).toContainText('先前下载证明已保留');
});

test('persisted partial proof requires renewed confirmation and locks refresh; dark mobile and focus',async({page})=>{
 let calls=0,release!:()=>void,started!:()=>void;const held=new Promise<void>(r=>release=r),scan=new Promise<void>(r=>started=r);const bodies:any[]=[];
 await page.route('**/api/storage/quarantine/*/purge',r=>{calls++;bodies.push(r.request().postDataJSON());return r.fulfill({json:receipt(calls===1?'purging':'purged')});});
 const d=await setup(page,'purging');await expect(d.getByRole('button',{name:'查看恢复清单'})).toHaveCount(0);await expect(d.getByRole('button',{name:'确认永久清理',exact:true})).toBeDisabled();await expect(d.getByRole('link',{name:'下载暂存文件 ZIP'})).toHaveCount(0);
 await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));await d.getByRole('button',{name:'确认永久清理',exact:true}).click();await expect(d.getByRole('alert').filter({hasText:'QUARANTINE_PURGE_FAILED'})).toBeVisible();await expect(d.getByLabel('我已将 ZIP 保存到其他位置并确认可用')).not.toBeChecked();await expect(d.getByLabel('输入批次末六位')).toHaveValue('');
 await page.unroute('**/api/storage/preview');await page.route('**/api/storage/preview',async r=>{started();await held;await r.fulfill({json:{...report(),kinds:[{key:'image',count:1,bytes:0}],statuses:[{key:'purged',count:1,bytes:0}],items:[{kind:'image',id:image,status:'purged',reason:'purged_file',bytes:0,lastModified:'2026-10-01T00:00:00Z'}]}});});
 await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(id.slice(-6));await d.getByRole('button',{name:'确认永久清理',exact:true}).click();await scan;try{await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeDisabled();await page.keyboard.press('Escape');await expect(d).toBeVisible();}finally{release();}await expect(d.getByRole('button',{name:'关闭备份与恢复'})).toBeEnabled();expect(bodies[1]).toEqual(bodies[0]);await d.getByLabel('空间检查文件范围').selectOption('purged');await expect(d.getByTestId('storage-item-'+image)).toContainText('0 B');
 await page.keyboard.press('Escape');await page.getByLabel('界面风格').selectOption('b');await page.getByRole('switch',{name:'深色模式'}).click();await page.setViewportSize({width:390,height:1000});const mobile=await open(page);await mobile.getByRole('button',{name:'继续永久清理'}).click();await mobile.locator('[aria-label="永久清理确认"]').scrollIntoViewIfNeeded();expect(await mobile.evaluate(el=>el.scrollWidth<=el.clientWidth+1)).toBe(true);await fs.mkdir(path.join(root,'output'),{recursive:true});await page.screenshot({path:path.join(root,'output/purge-dark-mobile.png'),animations:'disabled'});await mobile.getByRole('button',{name:'关闭备份与恢复'}).focus();await page.keyboard.press('Shift+Tab');await expect(mobile.getByRole('button',{name:'关闭',exact:true})).toBeFocused();await page.keyboard.press('Escape');await expect(page.getByRole('button',{name:'备份与恢复',exact:true})).toBeFocused();
});

test('real isolated ZIP extracts every exact file before explicit purge and terminal same-body retry',async({page,request})=>{
 const created=await request.post('/api/resumes',{headers,data:{title:'Permanent cleanup unrelated canary',sample:'one'}});expect(created.ok()).toBeTruthy();const resume=await created.json();const sourceAssetId=resume.document.layout.photo.id;
 const candidate=seedCandidate(sourceAssetId),original=inspectFixture(candidate.id);const preview=await(await request.get('/api/storage/preview')).json(),operationId=crypto.randomUUID();
 const staged=await request.post('/api/storage/quarantine',{headers,data:{operationId,previewDigest:preview.digest,items:[{kind:'image',id:candidate.id}],confirm:true}});expect(staged.ok()).toBeTruthy();const held=await staged.json();expect(held.state).toBe('quarantined');expect(inspectFixture(candidate.id).exists).toBe(false);expect(inspectFixture(candidate.id,operationId).files).toEqual(original.files);
 const canaries=sourceCanaries(),modelBefore=await(await request.get('/api/models')).json();expect(Object.keys(canaries.files).some(name=>name.startsWith('attachments/'+sourceAssetId+'/'))).toBe(true);expect(Object.keys(canaries.files).some(name=>name.startsWith('backups/'))).toBe(true);
 let exported:any,purged:any;const purgeBodies:any[]=[];
 await page.route('**/api/storage/quarantine/*/file-backups',async r=>{const response=await r.fetch();exported=await response.json();return r.fulfill({response});});
 await page.route('**/api/storage/quarantine/*/purge',async r=>{purgeBodies.push(r.request().postDataJSON());const response=await r.fetch();purged=await response.json();return r.fulfill({response});});
 const d=await open(page);await d.getByTestId('quarantine-'+operationId).getByRole('button',{name:'查看永久清理清单'}).click();await d.getByRole('button',{name:'生成暂存文件 ZIP'}).click();await expect(d.getByRole('link',{name:'下载暂存文件 ZIP'})).toBeVisible();expect(exported.operationId).toBe(operationId);expect(exported.downloaded).toBe(false);expect(inspectFixture(candidate.id,operationId).files).toEqual(original.files);
 const body={expectedDigest:held.digest,exportId:exported.id,archiveSha256:exported.sha256,confirm:true,backupSaved:true,confirmation:operationId.slice(-6)};
 const early=await request.post(`/api/storage/quarantine/${operationId}/purge`,{headers,data:body});expect(early.status()).toBe(409);expect((await early.json()).code).toBe('QUARANTINE_DOWNLOAD_REQUIRED');expect(inspectFixture(candidate.id,operationId).files).toEqual(original.files);
 await fs.mkdir(path.join(root,'output'),{recursive:true});const zipPath=path.join(root,`output/purge-files-${exported.id}.zip`),download=page.waitForEvent('download');await d.getByRole('link',{name:'下载暂存文件 ZIP'}).click();await(await download).saveAs(zipPath);const zip=await fs.readFile(zipPath);expect(zip.length).toBe(exported.bytes);expect(createHash('sha256').update(zip).digest('hex')).toBe(exported.sha256);
 const extraction=path.join(root,'output','purge-unpacked-'+exported.id);
 const python=`import json,sys,zipfile,hashlib,pathlib
source=pathlib.Path(sys.argv[1]); target=pathlib.Path(sys.argv[2]); target.mkdir()
with zipfile.ZipFile(source) as z:
 names=z.namelist(); assert len(names)==len(set(names)); assert z.testzip() is None
 manifest=json.loads(z.read('manifest.json')); assert manifest['format']=='resume-workbench-quarantine-files'
 expected={'files/'+f['path']:f for f in manifest['files']}; assert set(names)==set(expected)|{'manifest.json','说明.txt'}
 hashes={}; sizes={}
 for name in names:
  p=target/name; assert p.resolve().is_relative_to(target.resolve()); assert not name.endswith('/'); b=z.read(name); hashes[name]=hashlib.sha256(b).hexdigest(); sizes[name]=len(b)
  if name in expected: assert len(b)==expected[name]['bytes'] and hashes[name]==expected[name]['sha256']
  p.parent.mkdir(parents=True,exist_ok=True); p.write_bytes(b); assert hashlib.sha256(p.read_bytes()).hexdigest()==hashes[name]
 print(json.dumps({'manifest':manifest,'entrySHA256':hashes,'entryBytes':sizes},ensure_ascii=False))`;
 const checked=spawnSync(process.env.RESUME_TEST_PYTHON||'C:/Users/96581/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',['-c',python,zipPath,extraction],{encoding:'utf8'});expect(checked.status,checked.stderr).toBe(0);const zipEvidence=JSON.parse(checked.stdout);expect(zipEvidence.manifest.operationId).toBe(operationId);expect(zipEvidence.manifest.parentDigest).toBe(held.digest);expect(Object.keys(zipEvidence.entrySHA256)).toHaveLength(Object.keys(original.files).length+2);for(const [name,hash] of Object.entries(original.files))expect(zipEvidence.entrySHA256[`files/attachments/${candidate.id}/${name}`]).toBe(hash);
 const missingHuman=await request.post(`/api/storage/quarantine/${operationId}/purge`,{headers,data:{...body,backupSaved:false}});expect(missingHuman.status()).toBe(400);expect((await missingHuman.json()).code).toBe('QUARANTINE_BACKUP_REQUIRED');const wrongCode=await request.post(`/api/storage/quarantine/${operationId}/purge`,{headers,data:{...body,confirmation:' '+body.confirmation}});expect(wrongCode.status()).toBe(400);expect((await wrongCode.json()).code).toBe('QUARANTINE_CONFIRMATION_MISMATCH');expect(inspectFixture(candidate.id,operationId).files).toEqual(original.files);
 await d.getByRole('button',{name:'检查下载完成状态'}).click();await expect(d.getByRole('status').filter({hasText:'服务端已确认完整下载'})).toBeVisible();await d.getByLabel('我已将 ZIP 保存到其他位置并确认可用').check();await d.getByLabel('输入批次末六位').fill(operationId.slice(-6));await d.locator('[aria-label="永久清理确认"]').scrollIntoViewIfNeeded();await page.screenshot({path:path.join(root,'output/purge-confirmation.png'),animations:'disabled'});await d.getByRole('button',{name:'确认永久清理',exact:true}).click();await expect(d.getByRole('status').filter({hasText:'已永久清理。当前占用 0 B'})).toBeVisible();expect(purged.state).toBe('purged');expect(purgeBodies).toEqual([body]);expect(inspectFixture(candidate.id,operationId).exists).toBe(false);expect(inspectFixture(candidate.id).exists).toBe(false);
 const terminal=await request.post(`/api/storage/quarantine/${operationId}/purge`,{headers,data:body});expect(terminal.ok()).toBeTruthy();expect(await terminal.json()).toEqual(purged);expect(sourceCanaries()).toEqual(canaries);expect(await(await request.get('/api/models')).json()).toEqual(modelBefore);
 const payload=purgePayloadInventory(operationId);expect(payload).toEqual({exists:false,bytes:0,files:0});const inventory=await(await request.get('/api/storage/preview')).json();expect(inventory.items.find((item:any)=>item.id===candidate.id)).toMatchObject({status:'purged',reason:'purged_file',bytes:0});await expect(d.getByTestId('quarantine-'+operationId).getByRole('button',{name:'查看恢复清单'})).toHaveCount(0);
 await fs.writeFile(path.join(root,'output/purge-persistence.json'),JSON.stringify({schemaVersion:1,container:requireIsolatedQuarantineWorkspace(),operationId,recoveryToken:held.digest,imageId:candidate.id,originalBatchBytes:held.bytes,exportId:exported.id,archiveSHA256:exported.sha256,archiveBytes:exported.bytes,purgeBody:body,receipt:purged,originalFilesSHA256:original.files,zipEntrySHA256:zipEvidence.entrySHA256,zipEntryBytes:zipEvidence.entryBytes,zipPath,extractionPath:extraction,sourceCanaries:canaries,modelPublicSHA256:createHash('sha256').update(JSON.stringify(modelBefore)).digest('hex'),sourceResumeId:resume.id,sourceAssetId,earlyPurge:{status:early.status(),code:'QUARANTINE_DOWNLOAD_REQUIRED'},remainingPayloadBytes:payload.bytes,payloadInventory:payload,originalPathAbsent:true},null,2));
});
