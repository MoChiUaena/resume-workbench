export const resumeTemplates = [
  { id: 'classic', name: '经典单栏', description: '照片、姓名与校徽分区' },
  { id: 'banner', name: '并列页眉', description: '信息在左，图片在右' },
  { id: 'card', name: '横栏名片', description: '姓名与图片在上，联系方式横排' },
  { id: 'rail', name: '侧栏标题', description: '模块标题在左，经历正文在右' }
] as const;
export type TemplateId = typeof resumeTemplates[number]['id'];
export function templateName(id?:string) { return resumeTemplates.find(template => template.id === id)?.name || '未识别模板'; }
