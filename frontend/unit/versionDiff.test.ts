import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareDrafts, type DraftState } from '../src/versionDiff.ts';
const slot = { id: null, visible: true, widthMm: 26, heightMm: 34, fit: 'cover' as const, quarterTurns: 0, zoom: 1, positionX: 50, positionY: 50 };
const baseline: DraftState = { title: '合成简历', document: { schemaVersion: 3,
  content: { name: '奶龙', headline: 'Java', phone: '', email: '', location: '', sections: [
    { id: 'a', type: 'project', title: '项目经历', visible: true, pageBreakBefore: false, entries: [
      { id: 'one', title: '项目一', meta: '2026', bulleted: true, bullets: ['原始正文'] },
      { id: 'two', title: '项目二', meta: '', bulleted: true, bullets: [] }
    ] },
    { id: 'b', type: 'skills', title: '专业技能', visible: true, pageBreakBefore: false, entries: [] }
  ] },
  layout: { template: 'classic', font: 'sans', fontSize: 10, lineHeight: 1.68, sectionGapMm: 4, marginMm: 16, swapImages: false,
    photo: { ...slot }, logo: { ...slot, fit: 'contain' }, presentation: { language: 'zh', accentColor: '#244f63', alignment: 'left', contactStyle: 'plain', headingStyle: 'template', marginHorizontalMm: 16, marginTopMm: 16, marginBottomMm: 16, entryGapMm: 3, paragraphGapMm: 1 }
  }
} };
test('identical drafts have no differences', () => {
  assert.deepEqual(compareDrafts(baseline, structuredClone(baseline)), []);
});
test('module and entry reordering follows stable IDs instead of reporting replaced text', () => {
  const next = structuredClone(baseline); next.document.content.sections.reverse(); next.document.content.sections[1].entries.reverse();
  const groups = compareDrafts(baseline, next);
  assert.equal(groups.length, 2); assert.equal(groups[0].changes[0].field, '排列顺序');
  assert.deepEqual(groups[1].changes.map(c => c.field), ['条目顺序']);
});
test('insertions, removals, hidden modules and raw text remain understandable and intact', () => {
  const next = structuredClone(baseline); next.title = '改名'; next.document.content.sections.pop();
  const section = next.document.content.sections[0]; section.visible = false; section.entries.pop();
  section.entries[0].bullets = ['<img src=x onerror=alert(1)>', '中文段落'];
  section.entries.push({ id: 'new', title: '新项目', meta: '', bulleted: false, bullets: ['新增正文'] });
  const groups = compareDrafts(baseline, next); const changes = groups.flatMap(g => g.changes);
  assert.ok(changes.some(c => c.field === '删除模块' && c.after === '（已删除）'));
  assert.ok(changes.some(c => c.field.includes('删除') && c.before.includes('项目二')));
  assert.ok(changes.some(c => c.field.includes('新增') && c.after.includes('新增正文')));
  assert.ok(changes.some(c => c.field.includes('正文') && c.after === '<img src=x onerror=alert(1)>\n中文段落'));
  assert.ok(changes.some(c => c.field === '显示模块' && c.after === '否'));
});
test('image replacement, crop changes, zero spacing and layout values are independently reported', () => {
  const next = structuredClone(baseline); next.document.layout.photo.id = 'photo-id';
  next.document.layout.logo.quarterTurns = 1; next.document.layout.logo.visible = false;
  next.document.layout.sectionGapMm = 0; next.document.layout.font = 'serif'; next.document.layout.presentation.marginTopMm = 22;
  const groups = compareDrafts(baseline, next);
  assert.deepEqual(groups.map(g => g.category), ['images', 'images', 'layout']);
  assert.deepEqual(groups[0].image, { before: null, after: 'photo-id' });
  const changes = groups.flatMap(g => g.changes);
  assert.ok(changes.some(c => c.field === '旋转' && c.after === '90°'));
  assert.ok(changes.some(c => c.field === '模块间距' && c.after === '0 mm'));
  assert.ok(changes.some(c => c.field === '字体' && c.after === '宋体'));
});
