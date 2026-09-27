import { test, expect } from '@playwright/test';
import fs from 'node:fs/promises';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '../..');
const output = path.join(root, 'output');
const headers = { 'X-Local-Resume': '1' };

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
