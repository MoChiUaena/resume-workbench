const root = document.getElementById('prototype');
const params = new URLSearchParams(location.search);
const styles = new Set(['a', 'b', 'c']);
const views = new Set(['list', 'editor']);
let style = styles.has(params.get('style')) ? params.get('style') : 'a';
let view = views.has(params.get('view')) ? params.get('view') : 'list';
let listMode = 'list';
let section = '项目经历';
let draft = {
  name: '林知行', direction: 'Java 后端 / AI 应用开发实习',
  phone: '138 0000 0000', email: 'lin.zhixing@example.invalid',
  school: '示例理工大学 · 软件工程', educationPeriod: '2023.09 — 2027.06',
  educationDetail: '主修课程：数据结构、操作系统、数据库系统与软件工程。',
  skillsHeading: 'Java 后端 / AI 应用',
  skillsDetail: 'Spring Boot、PostgreSQL、Git、Docker；了解 Spring AI 与工具调用。',
  project: '本地简历工作台', period: '2025.06 — 至今',
  detail: '设计可配置的页眉，让学校 Logo 与证件照同时呈现；使用统一模板预览并导出中文 PDF。',
  experience: '任务管理 API · 课程实践', experiencePeriod: '2024.10 — 2025.01',
  experienceDetail: '设计 REST 接口，为输入校验和异常处理编写集成测试。',
  customHeading: '补充说明', customDetail: '这份简历和图片均为合成示例。'
};

const escapeText = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
const photo = '/samples/portrait-exif-6.jpg';
const logo = '/samples/university-logo.png';

function shellHeader(active) {
  return `<header class="site-header"><div class="site-width site-header-inner">
    <a class="site-brand" href="?style=${style}&view=list"><span class="site-mark">简</span><span>简历工作台<small>RESUME WORKBENCH</small></span></a>
    <nav aria-label="主要页面"><a class="${active === 'list' ? 'current' : ''}" href="?style=${style}&view=list">我的简历</a><a class="${active === 'editor' ? 'current' : ''}" href="?style=${style}&view=editor">简历编辑</a><span>使用说明</span></nav>
    <div class="local-indicator"><span></span>数据保存在本机</div>
  </div></header>`;
}
function miniResume() {
  return `<div class="mini-paper" aria-hidden="true"><div class="mini-name">林知行 <i></i></div>
    <div class="mini-contact"></div><div class="mini-line"></div>
    <strong>教育背景</strong><div class="mini-line short"></div><div class="mini-line"></div>
    <strong>专业技能</strong><div class="mini-line"></div><div class="mini-line medium"></div><div class="mini-line"></div>
    <strong>项目经历</strong><div class="mini-line"></div><div class="mini-line short"></div><div class="mini-line medium"></div>
  </div>`;
}
function resumeCard(title, role, updated, version) {
  return `<article class="resume-card"><div class="card-thumb">${miniResume()}</div><div class="card-info">
    <div class="card-kicker">${escapeText(role)} <span>· ${version}</span></div><h2>${escapeText(title)}</h2>
    <p>最后修改：${escapeText(updated)} <span class="card-dot">·</span> 经典单栏模板</p>
    <div class="card-actions"><button class="action-primary" data-open-editor>继续编辑</button><button>重命名</button><button>复制</button><button class="subtle-action">更多 ···</button></div>
  </div></article>`;
}
function listPage() {
  return `${shellHeader('list')}<div class="list-page site-width">
    <div class="page-heading"><div><div class="overline">简历管理 / MY RESUMES</div><h1>我的简历 <span>02</span></h1><p>从已有版本继续编辑，也可以为新岗位复制一份。</p></div><button class="action-primary create-action">＋ 新建简历</button></div>
    <div class="list-toolbar"><div class="filter-tabs"><button class="selected">全部简历 <b>2</b></button><button>最近修改</button></div><div class="view-mode"><button data-mode="list" aria-label="列表视图">☰ 列表</button><button data-mode="grid" aria-label="网格视图">▦ 网格</button></div></div>
    <div class="resume-collection">${resumeCard('Java 后端实习 · 基础版', '后端开发', '今天 10:42', '版本 03')}${resumeCard('AI 应用开发 · 投递版', 'AI 应用', '9 月 25 日 16:18', '版本 02')}</div>
    <div class="list-foot">当前展示的是合成示例。正式页面将读取本机保存的简历。</div>
  </div>`;
}
const modules = ['基本信息', '教育背景', '工作经历', '项目经历', '专业技能', '自定义模块', '照片与校徽'];
function resumePaper() {
  return `<article class="paper"><div class="paper-inner"><div class="paper-head"><img class="paper-photo" src="${photo}" alt="合成证件照">
    <div class="paper-identity"><h2 data-mirror="name">${escapeText(draft.name)}</h2><strong data-mirror="direction">${escapeText(draft.direction)}</strong><p><span data-mirror="phone">${escapeText(draft.phone)}</span>　 ·　 <span data-mirror="email">${escapeText(draft.email)}</span>　 ·　 杭州</p></div>
    <img class="paper-logo" src="${logo}" alt="合成学校 Logo"></div>
    <section class="paper-section" data-paper-section="教育背景"><h3>教育背景</h3><p><b data-mirror="school">${escapeText(draft.school)}</b><span data-mirror="educationPeriod">${escapeText(draft.educationPeriod)}</span></p><div class="body-line" data-mirror="educationDetail">${escapeText(draft.educationDetail)}</div></section>
    <section class="paper-section" data-paper-section="专业技能"><h3>专业技能</h3><p><b data-mirror="skillsHeading">${escapeText(draft.skillsHeading)}</b></p><div class="body-line" data-mirror="skillsDetail">${escapeText(draft.skillsDetail)}</div></section>
    <section class="paper-section" data-paper-section="项目经历"><h3>项目经历</h3><p><b data-mirror="project">${escapeText(draft.project)}</b><span data-mirror="period">${escapeText(draft.period)}</span></p><div class="body-line" data-mirror="detail">${escapeText(draft.detail)}</div></section>
    <section class="paper-section" data-paper-section="工作经历"><h3>工作经历</h3><p><b data-mirror="experience">${escapeText(draft.experience)}</b><span data-mirror="experiencePeriod">${escapeText(draft.experiencePeriod)}</span></p><div class="body-line" data-mirror="experienceDetail">${escapeText(draft.experienceDetail)}</div></section>
    <section class="paper-section" data-paper-section="自定义模块"><h3>自定义模块</h3><p><b data-mirror="customHeading">${escapeText(draft.customHeading)}</b></p><div class="body-line" data-mirror="customDetail">${escapeText(draft.customDetail)}</div></section>
    <div class="paper-foot">合成示例 · A4 实时预览 <span>1 / 1</span></div></div></article>`;
}
const formConfig = {
  '教育背景': [['学校与专业','school'],['起止时间','educationPeriod'],['教育经历描述','educationDetail']],
  '工作经历': [['公司 / 职位','experience'],['起止时间','experiencePeriod'],['工作内容','experienceDetail']],
  '项目经历': [['项目名称','project'],['起止时间','period'],['项目描述','detail']],
  '专业技能': [['技能方向','skillsHeading'],['技能说明','skillsDetail']],
  '自定义模块': [['模块标题','customHeading'],['模块正文','customDetail']]
};
function formFields() {
  if (section === '照片与校徽') {
    return `<div class="photo-controls"><div><img src="${photo}" alt="合成证件照"><span><strong>证件照</strong><small>独立上传与裁剪</small></span></div><div><img src="${logo}" alt="合成学校 Logo"><span><strong>学校 Logo</strong><small>透明背景，独立配置</small></span></div></div><p class="field-note">正式编辑页将连接已有的图片处理功能。</p>`;
  }
  const fields = section === '基本信息'
    ? [['姓名','name'],['求职方向','direction'],['联系电话','phone'],['邮箱','email']]
    : formConfig[section];
  return fields.map(([label,key]) => `<div class="field-group"><label for="draft-${key}">${label}</label>${/Detail$|^detail$/.test(key)
    ? `<textarea id="draft-${key}" data-field="${key}" rows="6">${escapeText(draft[key])}</textarea><small>输入时，右侧简历同步更新。</small>`
    : `<input id="draft-${key}" data-field="${key}" value="${escapeText(draft[key])}">`}</div>`).join('');
}
function editorPage() {
  return `<div class="editor-page"><div class="editor-top"><button data-back>← 我的简历</button><div class="editor-title">Java 后端实习 · 基础版 <span>已保存到本机</span></div><div class="editor-top-actions"><button>保存版本</button><button class="action-primary">导出 PDF</button></div></div>
    <div class="editor-layout"><aside class="editor-sidebar"><div class="sidebar-title"><strong>修改简历</strong><small>选择一部分，右边实时预览</small></div>
      <div class="module-menu">${modules.map((label, i) => `<button data-section="${label}"><span class="module-number">${String(i + 1).padStart(2, '0')}</span>${label}<span class="module-chevron">›</span></button>`).join('')}</div>
      <div class="sidebar-footer">版式设置　·　历史版本</div></aside>
      <section class="form-panel"><div class="form-heading"><div class="overline">简历内容 / EDITOR</div><h1 data-form-heading>${section}</h1><p data-form-help>左侧选择模块，右侧随输入更新。</p></div>
        ${formFields()}
        <div class="form-actions"><button>＋ 添加内容</button><span>输入时更新预览</span></div>
      </section><section class="preview-area"><div class="preview-heading"><span>实时预览</span><span>A4 · 经典单栏</span></div>${resumePaper()}</section>
    </div></div>`;
}
function render() {
  document.body.dataset.style = style;
  document.body.dataset.view = view;
  document.querySelectorAll('[data-choice]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.choice === style)));
  document.querySelectorAll('[data-page]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.page === view)));
  root.innerHTML = view === 'list' ? listPage() : editorPage();
  root.querySelectorAll('[data-mode]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.mode === listMode)));
  root.querySelector('.resume-collection')?.classList.toggle('as-grid', listMode === 'grid');
  root.querySelectorAll('[data-section]').forEach(button => button.setAttribute('aria-current', String(button.dataset.section === section)));
  root.querySelectorAll('[data-paper-section]').forEach(item => item.classList.toggle('is-active', item.dataset.paperSection === section));
}
function setPage(next) { view = next; history.replaceState(null, '', `?style=${style}&view=${view}`); render(); }

document.addEventListener('click', event => {
  const target = event.target.closest('button,a');
  if (!target) return;
  if (target.matches('.site-brand, .site-header nav a')) { event.preventDefault(); setPage(target.href.includes('view=editor') ? 'editor' : 'list'); }
  else if (target.dataset.choice) { style = target.dataset.choice; history.replaceState(null, '', `?style=${style}&view=${view}`); render(); }
  else if (target.dataset.page) setPage(target.dataset.page);
  else if (target.dataset.openEditor !== undefined) setPage('editor');
  else if (target.dataset.back !== undefined) setPage('list');
  else if (target.dataset.mode) { listMode = target.dataset.mode; render(); }
  else if (target.dataset.section) { section = target.dataset.section; render(); }
  else if (target.tagName === 'BUTTON') {
    document.querySelector('.prototype-note').textContent = '这是样式预览；保存、复制和导出会在选定风格后接入正式页面。';
  }
});
document.addEventListener('input', event => {
  const key = event.target.dataset.field;
  if (!key) return;
  draft[key] = event.target.value;
  document.querySelectorAll(`[data-mirror="${key}"]`).forEach(node => node.textContent = event.target.value);
});
render();
