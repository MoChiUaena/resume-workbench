import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '../..');
const output = path.join(root, 'output');
const headers = { 'X-Local-Resume': '1' };

test('complete local UI flow: independent images, same template, one/two-page PDF', async ({ page }) => {
  const external: string[] = [], errors: string[] = [];
  await page.context().route('**/*', async route => {
    if (new URL(route.request().url()).hostname !== '127.0.0.1') { external.push(route.request().url()); await route.abort(); }
    else await route.continue();
  });
  page.on('pageerror', e => errors.push(e.message));
  await fs.mkdir(path.join(output, 'pdf'), { recursive: true });
  await page.goto('/');
  await expect(page.getByRole('status').first()).toContainText('预览已更新');
  const frame = page.frameLocator('iframe');
  await expect(frame.locator('[data-kind=photo] img')).toBeVisible();
  await expect(frame.locator('[data-kind=logo] img')).toBeVisible();
  await expect(page.getByTestId('photo-controls')).toContainText('已校正方向');
  await expect(page.getByTestId('logo-controls')).toContainText('10.8 KiB');
  await page.screenshot({ path: path.join(output, 'workbench-one-page.png'), fullPage: true });
  for (const sample of ['one', 'two']) {
    if (sample === 'two') {
      await page.getByRole('button', { name: '两页样本' }).click();
      await expect(frame.locator('.sheet')).toHaveCount(2);
      await expect(page.getByRole('status').first()).toContainText('预览已更新');
    }
    const rects = await frame.locator('.sheet').first().evaluate(sheet => {
      const r = (selector: string) => { const x = sheet.querySelector(selector)!.getBoundingClientRect(); return { left: x.left, right: x.right, top: x.top, bottom: x.bottom }; };
      return { photo: r('[data-kind=photo]'), logo: r('[data-kind=logo]'), text: r('.identity') };
    });
    expect(rects.photo.right).toBeLessThan(rects.text.left);
    expect(rects.text.right).toBeLessThan(rects.logo.left);
    const allFit = await frame.locator('.sheet').evaluateAll(sheets => sheets.every(s => s.querySelector('.page-content')!.getBoundingClientRect().bottom < s.querySelector('.page-footer')!.getBoundingClientRect().top - 8));
    expect(allFit).toBeTruthy();
    const download = page.waitForEvent('download');
    await page.getByRole('button', { name: '↓ 导出 PDF' }).click();
    await (await download).saveAs(path.join(output, 'pdf', `resume-${sample}-page.pdf`));
    await expect(page.getByText('PDF 已生成，使用本次点击时的内容。')).toBeVisible();
  }
  const photoSrc = await frame.locator('[data-kind=photo] img').getAttribute('src');
  await page.getByTestId('photo-controls').getByText('裁剪与旋转').click();
  await page.getByTestId('photo-controls').getByRole('button', { name: '顺时针旋转 90°' }).click();
  await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('style', /rotate\(90deg\)/);
  await page.getByLabel('证件照缩放', { exact: true }).fill('1.5');
  await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('style', /scale\(1.5\)/);
  await page.getByTestId('photo-controls').getByRole('button', { name: '重置裁剪' }).click();
  await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('style', /scale\(1.0\)/);
  await page.getByLabel('显示学校 Logo').uncheck();
  await expect(frame.locator('[data-kind=logo]')).toHaveCount(0);
  await expect(frame.locator('[data-kind=photo] img')).toHaveAttribute('src', photoSrc!);
  await page.getByLabel('显示学校 Logo').check();
  await expect(frame.locator('[data-kind=logo]')).toBeVisible();
  await page.getByLabel('上传学校 Logo').setInputFiles(path.join(root, 'fixtures/corrupt.png'));
  await expect(page.getByTestId('logo-controls')).toContainText('CORRUPT_IMAGE');
  await expect(frame.locator('[data-kind=logo]')).toBeVisible();
  await page.getByLabel('上传学校 Logo').setInputFiles(path.join(root, 'fixtures/university-logo.png'));
  await expect(page.getByTestId('logo-controls').getByRole('alert')).toHaveCount(0);
  await page.getByLabel('姓名', { exact: true }).fill('陈明远');
  await expect(frame.locator('h1')).toHaveText('陈明远');
  await page.getByLabel('页眉布局').selectOption({ label: '左侧学校 Logo · 右上角证件照' });
  await expect(frame.locator('.left-cell [data-kind=logo]')).toBeVisible();
  await expect(frame.locator('.right-cell [data-kind=photo]')).toBeVisible();
  await page.getByLabel('移除证件照').click();
  await expect(frame.locator('[data-kind=photo]')).toHaveCount(0);
  await expect(frame.locator('[data-kind=logo]')).toBeVisible();
  expect(external).toEqual([]); expect(errors).toEqual([]);
  await fs.writeFile(path.join(output, 'network-check.json'), JSON.stringify({ externalRequests: external, browserErrors: errors, scope: 'Frontend context denies every non-loopback request. Exporter uses its own strict URL allowlist. This is not an OS-wide offline test.' }, null, 2));
});

test('HTTP content validation, byte boundary, rejection reasons, and escaped input', async ({ request }) => {
  const fixture = await fs.readFile(path.join(root, 'fixtures/university-logo.png'));
  const disguised = await request.post('/api/assets', { headers, multipart: { file: { name: 'renamed.svg', mimeType: 'application/octet-stream', buffer: fixture } } });
  expect(disguised.ok()).toBeTruthy(); expect((await disguised.json()).format).toBe('PNG');
  for (const [name, status, code] of [['unsupported.svg', 415, 'UNSUPPORTED_FORMAT'], ['corrupt.png', 422, 'CORRUPT_IMAGE']] as const) {
    const response = await request.post('/api/assets', { headers, multipart: { file: { name, mimeType: 'image/png', buffer: await fs.readFile(path.join(root, 'fixtures', name)) } } });
    expect(response.status()).toBe(status); expect((await response.json()).code).toBe(code);
  }
  for (const size of [5242880, 5242881]) {
    const buffer = Buffer.alloc(size); fixture.copy(buffer);
    const response = await request.post('/api/assets', { headers, multipart: { file: { name: 'boundary.png', mimeType: 'image/png', buffer } } });
    expect(response.status()).toBe(size === 5242880 ? 200 : 413);
    if (size > 5242880) expect((await response.json()).code).toBe('FILE_TOO_LARGE');
  }
  const cross = await request.post('/api/previews', { headers: { ...headers, Origin: 'https://evil.invalid' }, data: {} });
  expect(cross.status()).toBe(403);
  const noHeader = await request.post('/api/previews', { data: {} }); expect(noHeader.status()).toBe(403);
  const slot = { id: null, visible: false, widthMm: 26, heightMm: 34, fit: 'cover', quarterTurns: 0, zoom: 1, positionX: 50, positionY: 50 };
  const draft = { schemaVersion: 1, sample: 'one', name: '<img src=x onerror=alert(1)>', headline: 'Java', phone: '', email: '', location: '', swapImages: false, photo: slot, logo: slot };
  const preview = await request.post('/api/previews', { headers, data: draft }); expect(preview.ok()).toBeTruthy();
  const html = await (await request.get((await preview.json()).url)).text(); expect(html).toContain('&lt;img'); expect(html).not.toContain('<img src=x');
  const invalid = await request.post('/api/previews', { headers, data: { ...draft, schemaVersion: 99 } }); expect(invalid.status()).toBe(400);
});
