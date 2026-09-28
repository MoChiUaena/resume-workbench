import type { Entry, ResumeDocument, Section, Slot } from './api';

export type DiffCategory = 'content' | 'images' | 'layout';
export type Change = { field: string; before: string; after: string };
export type DiffGroup = { id: string; title: string; category: DiffCategory; changes: Change[]; image?: { before: string | null; after: string | null } };
export type DraftState = { title: string; document: ResumeDocument };
const sectionTypes = { education: '教育背景', experience: '工作 / 实习经历', project: '项目经历', skills: '专业技能', custom: '自定义文本' };
const text = (value: unknown) => value === null || value === undefined || value === '' ? '（空）' : String(value);
function change(group: DiffGroup, field: string, before: unknown, after: unknown, format = text) {
  if (JSON.stringify(before) !== JSON.stringify(after)) group.changes.push({ field, before: format(before), after: format(after) });
}
const yesNo = (value: unknown) => value ? '是' : '否';
const mm = (value: unknown) => `${value} mm`;
const entryText = (entry: Entry) => [entry.title, entry.meta, entry.bulleted ? '项目列表' : '普通段落', ...entry.bullets].filter(Boolean).join('\n');
const sectionText = (section: Section) => [section.title, sectionTypes[section.type], section.visible ? '显示' : '隐藏', section.pageBreakBefore ? '另起一页' : '接续排版', ...section.entries.map(entryText)].join('\n\n');

/** Compare saved content with the current draft; metadata and revision numbers are not edits. */
export function compareDrafts(before: DraftState, after: DraftState): DiffGroup[] {
  const groups: DiffGroup[] = [];
  const basics: DiffGroup = { id: 'basic', title: '基本信息', category: 'content', changes: [] };
  change(basics, '简历名称', before.title, after.title);
  for (const [key, label] of Object.entries({ name: '姓名', headline: '求职方向', phone: '联系电话', email: '邮箱', location: '城市 / 毕业年份' })) {
    change(basics, label, before.document.content[key as keyof Omit<ResumeDocument['content'], 'sections'>], after.document.content[key as keyof Omit<ResumeDocument['content'], 'sections'>]);
  }
  groups.push(basics);
  const oldSections = before.document.content.sections, newSections = after.document.content.sections;
  const order: DiffGroup = { id: 'order', title: '模块顺序', category: 'content', changes: [] };
  // Added/removed modules have their own rows; this row only reports reordered shared modules.
  const sharedBefore = oldSections.filter(s => newSections.some(n => n.id === s.id));
  const sharedAfter = newSections.filter(s => oldSections.some(o => o.id === s.id));
  if (sharedBefore.map(s => s.id).join() !== sharedAfter.map(s => s.id).join()) {
    order.changes.push({ field: '排列顺序', before: sharedBefore.map((s, i) => `${i + 1}. ${s.title}`).join('\n'), after: sharedAfter.map((s, i) => `${i + 1}. ${s.title}`).join('\n') });
  }
  groups.push(order);
  const ids = [...new Set([...newSections.map(s => s.id), ...oldSections.map(s => s.id)])];
  for (const id of ids) {
    const old = oldSections.find(s => s.id === id), next = newSections.find(s => s.id === id);
    const group: DiffGroup = { id: `section-${id}`, title: next?.title || old!.title, category: 'content', changes: [] };
    if (!old || !next) group.changes.push({ field: old ? '删除模块' : '新增模块', before: old ? sectionText(old) : '（未添加）', after: next ? sectionText(next) : '（已删除）' });
    else {
      change(group, '模块标题', old.title, next.title);
      change(group, '模块类型', sectionTypes[old.type], sectionTypes[next.type]);
      change(group, '显示模块', old.visible, next.visible, yesNo);
      change(group, '另起一页', old.pageBreakBefore, next.pageBreakBefore, yesNo);
      const oldShared = old.entries.filter(e => next.entries.some(n => n.id === e.id));
      const newShared = next.entries.filter(e => old.entries.some(o => o.id === e.id));
      if (oldShared.map(e => e.id).join() !== newShared.map(e => e.id).join()) group.changes.push({ field: '条目顺序', before: oldShared.map((e, i) => `${i + 1}. ${e.title}`).join('\n'), after: newShared.map((e, i) => `${i + 1}. ${e.title}`).join('\n') });
      for (const entryId of new Set([...next.entries.map(e => e.id), ...old.entries.map(e => e.id)])) {
        const a = old.entries.find(e => e.id === entryId), b = next.entries.find(e => e.id === entryId);
        const index = (b ? next.entries : old.entries).findIndex(e => e.id === entryId) + 1;
        if (!a || !b) group.changes.push({ field: `第 ${index} 条 · ${a ? '删除' : '新增'}`, before: a ? entryText(a) : '（未添加）', after: b ? entryText(b) : '（已删除）' });
        else {
          change(group, `第 ${index} 条 · 标题`, a.title, b.title);
          change(group, `第 ${index} 条 · 补充说明`, a.meta, b.meta);
          change(group, `第 ${index} 条 · 项目列表`, a.bulleted, b.bulleted, yesNo);
          change(group, `第 ${index} 条 · 正文`, a.bullets.join('\n'), b.bullets.join('\n'));
        }
      }
    }
    groups.push(group);
  }
  const a = before.document.layout, b = after.document.layout;
  for (const kind of ['photo', 'logo'] as const) {
    const old = a[kind], next = b[kind];
    const group: DiffGroup = { id: kind, title: kind === 'photo' ? '证件照' : '学校 Logo', category: 'images', changes: [], image: { before: old.id, after: next.id } };
    if (old.id !== next.id) group.changes.push({ field: '图片', before: old.id ? '原有图片' : '（无图片）', after: next.id ? old.id ? '替换后的图片' : '新增图片' : '（已移除）' });
    const fields: [keyof Slot, string, (value: unknown) => string][] = [
      ['visible', '显示图片', yesNo], ['widthMm', '宽度', mm], ['heightMm', '高度', mm],
      ['fit', '适配方式', v => v === 'cover' ? '填充裁剪' : '完整显示'], ['quarterTurns', '旋转', v => `${Number(v) * 90}°`],
      ['zoom', '缩放', v => `${v}×`], ['positionX', '水平位置', v => `${v}%`], ['positionY', '垂直位置', v => `${v}%`]
    ];
    for (const [key, label, format] of fields) change(group, label, old[key], next[key], format);
    groups.push(group);
  }
  const layout: DiffGroup = { id: 'layout', title: '文字、样式与间距', category: 'layout', changes: [] };
  change(layout, '模板', a.template, b.template, v => v === 'classic' ? '经典单栏' : '并列页眉');
  change(layout, '字体', a.font, b.font, v => v === 'sans' ? '黑体' : '宋体');
  change(layout, '字号', a.fontSize, b.fontSize, v => `${v} pt`);
  change(layout, '正文行距', a.lineHeight, b.lineHeight);
  change(layout, '模块间距', a.sectionGapMm, b.sectionGapMm, mm);
  change(layout, '照片与 Logo 交换位置', a.swapImages, b.swapImages, yesNo);
  const labels = { language: '信息标签语言', accentColor: '主题色', alignment: '文字对齐', contactStyle: '联系方式样式', headingStyle: '模块标题样式', marginHorizontalMm: '左右页边距', marginTopMm: '上页边距', marginBottomMm: '下页边距', entryGapMm: '条目间距', paragraphGapMm: '段落间距' };
  const values: Record<string, string> = { zh: '中文', en: 'English', left: '居左', center: '居中', justify: '两端对齐', labels: '信息标签', icons: '图标', plain: '纯内容', template: '跟随模板', line: '下划线', bar: '色块' };
  for (const [key, label] of Object.entries(labels)) {
    const field = key as keyof typeof a.presentation;
    change(layout, label, a.presentation[field], b.presentation[field], v => key.endsWith('Mm') ? mm(v) : key === 'headingStyle' && v === 'plain' ? '无装饰' : values[String(v)] || text(v));
  }
  groups.push(layout);
  return groups.filter(group => group.changes.length);
}
