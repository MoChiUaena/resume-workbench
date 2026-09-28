import { defineConfig } from '@playwright/test';
import path from 'node:path';
process.env.PLAYWRIGHT_BROWSERS_PATH = path.resolve(import.meta.dirname, '../.tools/ms-playwright');
process.env.PLAYWRIGHT_SKIP_BROWSER_GC = '1';
export default defineConfig({ testDir: './tests', timeout: 120_000, expect: { timeout: 15_000 }, fullyParallel: false, workers: 1, reporter: [['list'], ['json', { outputFile: '../output/e2e-results.json' }]], use: { baseURL: process.env.RESUME_TEST_BASE_URL || 'http://127.0.0.1:18765', viewport: { width: 1480, height: 1120 }, screenshot: 'only-on-failure', trace: 'retain-on-failure' } });
