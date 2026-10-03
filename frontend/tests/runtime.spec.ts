import {test,expect} from '@playwright/test';
const details={version:'0.8.0-SNAPSHOT',buildTime:'2026-10-03T00:00:00Z',pid:23456,startedAt:'2026-10-03T02:00:00Z',instanceId:'bce32e31-f72e-4ca2-879f-1b6b707de6a7',javaVersion:'21.0.12',jarPath:'C:\\fixture\\target\\resume-workbench.jar',jarSha256:'a'.repeat(64),dataDirectory:'C:\\fixture\\data',logsDirectory:'C:\\fixture\\.tools\\runtime\\logs',port:18765};
test.beforeEach(async({page})=>{
 await page.route('**/api/resumes',route=>route.fulfill({json:[]}));
 await page.route('**/api/health',route=>route.fulfill({json:{status:'ok'}}));
});
test('packaged application reports its own process and artifact through the local runtime endpoint',async({request})=>{
 test.skip(process.env.RESUME_TEST_ISOLATED!=='1','Uses a named isolated application instance.');
 const response=await request.get('/api/runtime');expect(response.ok()).toBeTruthy();
 const info=await response.json();expect(info.version).toBe('0.8.0-SNAPSHOT');expect(info.pid).toBeGreaterThan(0);
 expect(info.jarPath).toMatch(/resume-workbench\.jar$/);expect(info.jarSha256).toMatch(/^[a-f0-9]{64}$/);
 expect(Number.isFinite(Date.parse(info.startedAt))).toBeTruthy();expect(Number.isFinite(Date.parse(info.buildTime))).toBeTruthy();
 expect(info.dataDirectory).toBeTruthy();expect(await(await request.get('/api/health')).json()).toEqual({status:'ok'});
});
test('runtime information identifies the running build and refreshes without writing workspace data',async({page})=>{
 let reads=0;const writes:string[]=[];
 page.on('request',request=>{if(new URL(request.url()).pathname.startsWith('/api/')&&!['GET','HEAD'].includes(request.method()))writes.push(request.method()+' '+request.url());});
 await page.route('**/api/runtime',route=>route.fulfill({json:{...details,pid:23456+reads++}}));
 await page.goto('/');const opener=page.getByRole('button',{name:'运行信息',exact:true});await opener.click();
 const dialog=page.getByRole('dialog',{name:'运行状态',exact:true});
 expect(await page.locator('.rw-app').evaluate(element=>(element as HTMLElement).inert)).toBe(true);
 await expect(dialog.getByTestId('runtime-version')).toHaveText('0.8.0-SNAPSHOT');
 await expect(dialog.getByTestId('runtime-pid')).toHaveText('23456');
 await expect(dialog.getByText(details.dataDirectory,{exact:true})).toBeVisible();
 await expect(dialog.getByText(details.logsDirectory,{exact:true})).toBeVisible();
 await expect(dialog.getByTestId('runtime-health')).toContainText('正常');
 await dialog.getByRole('button',{name:'刷新运行信息',exact:true}).click();
 await expect(dialog.getByTestId('runtime-pid')).toHaveText('23457');
 expect(writes).toEqual([]);await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(opener).toBeFocused();
});
test('failed refresh marks previous runtime details as stale and can recover',async({page})=>{
 let failed=false;
 await page.route('**/api/runtime',route=>failed?route.fulfill({status:503,json:{code:'RUNTIME_UNAVAILABLE',message:'Unavailable'}}):route.fulfill({json:details}));
 await page.goto('/');await page.getByRole('button',{name:'运行信息',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'运行状态',exact:true});await expect(dialog.getByTestId('runtime-version')).toBeVisible();
 failed=true;await dialog.getByRole('button',{name:'刷新运行信息',exact:true}).click();
 await expect(dialog.getByRole('alert')).toContainText('上次读取');await expect(dialog.getByTestId('runtime-health')).not.toContainText('正常');
 failed=false;await dialog.getByRole('button',{name:'刷新运行信息',exact:true}).click();await expect(dialog.getByRole('alert')).toHaveCount(0);await expect(dialog.getByTestId('runtime-health')).toContainText('正常');
});
test('runtime details support paper dark appearance, narrow screens and console-only logs',async({page})=>{
 await page.route('**/api/runtime',route=>route.fulfill({json:{...details,buildTime:null,jarPath:null,jarSha256:null,logsDirectory:null}}));
 await page.goto('/');await page.getByLabel('界面风格',{exact:true}).selectOption('b');await page.getByRole('switch',{name:'深色模式',exact:true}).check();
 await page.setViewportSize({width:390,height:844});await page.getByRole('button',{name:'运行信息',exact:true}).click();
 const dialog=page.getByRole('dialog',{name:'运行状态',exact:true});await expect(dialog.getByText('日志输出到启动终端或容器日志。',{exact:true})).toBeVisible();
 const size=await dialog.evaluate(element=>({width:element.getBoundingClientRect().width,scroll:element.scrollWidth,client:element.clientWidth}));expect(size.width).toBeLessThanOrEqual(390);expect(size.scroll).toBeLessThanOrEqual(size.client);
});
