<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import ImageControls from './ImageControls.vue';
import { api, upload, type Asset, type Draft, type Slot } from './api';
const blank = (logo: boolean): Slot => ({ id: null, visible: true, widthMm: logo ? 26 : 26, heightMm: logo ? 26 : 34, fit: logo ? 'contain' : 'cover', quarterTurns: 0, zoom: 1, positionX: 50, positionY: 50 });
const draft = reactive<Draft>({ schemaVersion: 1, sample: 'one', name: '林知行', headline: 'Java 后端 / AI 应用开发实习', email: 'lin.zhixing@example.invalid', phone: '138 0000 0000', location: '杭州 · 2027 届', swapImages: false, photo: blank(false), logo: blank(true) });
const assets = reactive<{ photo?: Asset; logo?: Asset }>({});
const config = ref({ maxUploadBytes: 5242880 });
const previewUrl = ref(''), previewState = ref('准备预览'), problem = ref(''), exporting = ref(false), demoBusy = ref(false), initialized = ref(false);
const previewPane = ref<HTMLElement>(), previewFrame = ref<HTMLIFrameElement>(), scale = ref(.85);
const exportResult = ref<{ id: string; digest: string }>();
const pageCount = computed(() => draft.sample === 'two' ? 2 : 1);
let generation = 0, timer: ReturnType<typeof setTimeout>, observer: ResizeObserver | undefined;
async function refresh() {
  const current = ++generation; previewState.value = '更新预览中'; problem.value = '';
  try {
    const result = await api<{ url: string }>('/api/previews', JSON.parse(JSON.stringify(draft)));
    if (current === generation) previewUrl.value = result.url;
  } catch (e) { if (current === generation) { previewState.value = '预览失败'; problem.value = String(e instanceof Error ? e.message : e); } }
}
watch(draft, () => { if (!initialized.value) return; previewState.value = '更新预览中'; clearTimeout(timer); timer = setTimeout(refresh, 350); }, { deep: true });
async function frameReady() {
  const doc = previewFrame.value?.contentDocument;
  if (doc) { await doc.fonts.ready; await Promise.all(Array.from(doc.images).map(i => i.decode().catch(() => {}))); }
  previewState.value = '预览已更新';
}
function imported(kind: 'photo' | 'logo', asset: Asset) { assets[kind] = asset; draft[kind] = { ...blank(kind === 'logo'), id: asset.id }; }
function removed(kind: 'photo' | 'logo') { assets[kind] = undefined; draft[kind].id = null; }
async function loadDemo() {
  demoBusy.value = true; problem.value = '';
  try {
    for (const [kind, file] of [['photo', 'portrait-exif-6.jpg'], ['logo', 'university-logo.png']] as const) {
      const response = await fetch('/samples/' + file);
      if (!response.ok) throw new Error('合成样本加载失败。');
      imported(kind, await upload(new File([await response.blob()], file), config.value.maxUploadBytes));
    }
  } catch (e) { problem.value = e instanceof Error ? e.message : '样本导入失败。'; }
  finally { demoBusy.value = false; }
}
async function exportPdf() {
  exporting.value = true; problem.value = ''; exportResult.value = undefined;
  try {
    const snap = await api<{ id: string }>('/api/previews', JSON.parse(JSON.stringify(draft)));
    const result = await api<{ id: string; digest: string }>('/api/exports/' + snap.id, {});
    exportResult.value = result;
    const a = document.createElement('a'); a.href = '/api/exports/' + result.id + '/pdf'; a.download = 'local-resume.pdf'; a.click();
  } catch (e) { problem.value = e instanceof Error ? e.message : '导出失败，请重试。'; }
  finally { exporting.value = false; }
}
onMounted(async () => {
  await nextTick();
  observer = new ResizeObserver(entries => { scale.value = Math.min(1, (entries[0].contentRect.width - 56) / 794); });
  if (previewPane.value) observer.observe(previewPane.value);
  try { config.value = await api('/api/config'); await loadDemo(); initialized.value = true; await refresh(); }
  catch (e) { problem.value = e instanceof Error ? e.message : '本地服务不可用。'; }
});
onUnmounted(() => { observer?.disconnect(); clearTimeout(timer); });
</script>
<template>
 <div class="workbench">
  <header class="app-header"><a class="brand" href="/"><span class="brand-mark">纸</span><span>纸间<span class="brand-en">LOCAL RESUME</span></span></a><div class="header-middle"><span class="local-dot"></span>本地简历工作台<span class="stage-badge">阶段 A</span></div><button class="primary export-button" :disabled="exporting || !initialized" @click="exportPdf">{{ exporting ? '正在生成 PDF…' : '↓ 导出 PDF' }}</button></header>
  <div class="workspace-heading"><div><div class="eyebrow">YOUR NEXT CHAPTER</div><h1>把经历，写得清楚。</h1><p>独立配置学校 Logo 和证件照，让中文简历从预览到纸面保持一致。</p></div><div class="privacy-note"><span class="local-dot"></span>图片与 PDF 写入本机<br><small>编辑参数仅在本次页面内保留</small></div></div>
  <div class="workspace-grid">
   <aside class="settings-panel"><div class="panel-heading"><h2>简历设置</h2><button class="text-button" :disabled="demoBusy" @click="loadDemo">{{ demoBusy ? '加载中…' : '载入合成图片' }}</button></div>
    <section class="basic-section"><h3>基本信息</h3><label>姓名<input v-model="draft.name" maxlength="30" aria-label="姓名"></label><label>求职方向<input v-model="draft.headline" maxlength="70" aria-label="求职方向"></label><div class="two-inputs"><label>手机<input v-model="draft.phone" maxlength="30" aria-label="手机"></label><label>城市 / 毕业年份<input v-model="draft.location" maxlength="40" aria-label="城市和毕业年份"></label></div><label>邮箱<input v-model="draft.email" maxlength="100" aria-label="邮箱"></label></section>
    <div class="layout-setting"><h3>页眉布局</h3><select v-model="draft.swapImages" aria-label="页眉布局"><option :value="false">左侧证件照 · 右上角学校 Logo</option><option :value="true">左侧学校 Logo · 右上角证件照</option></select></div>
    <ImageControls kind="logo" title="学校 Logo" :slot="draft.logo" :asset="assets.logo" :max-bytes="config.maxUploadBytes" @imported="imported('logo', $event)" @removed="removed('logo')"/>
    <ImageControls kind="photo" title="证件照" :slot="draft.photo" :asset="assets.photo" :max-bytes="config.maxUploadBytes" @imported="imported('photo', $event)" @removed="removed('photo')"/>
    <p class="settings-footnote">PNG 透明背景会保留。证件照默认填充裁剪，Logo 默认完整显示。导入失败时保留原图。</p>
   </aside>
   <section class="preview-panel" ref="previewPane"><div class="preview-toolbar"><div class="segmented" aria-label="样本页数"><button :class="{ active: draft.sample === 'one' }" @click="draft.sample = 'one'">一页样本</button><button :class="{ active: draft.sample === 'two' }" @click="draft.sample = 'two'">两页样本</button></div><div class="preview-status" role="status"><span class="local-dot" :class="{pending: previewState !== '预览已更新'}"></span>{{ previewState }}<span class="zoom-label">{{ Math.round(scale * 100) }}%</span></div></div>
    <div v-if="problem" class="notice error" role="alert">{{ problem }}<button class="text-button" @click="refresh">刷新预览</button></div>
    <div v-if="exportResult" class="notice success" role="status">PDF 已生成，使用本次点击时的内容。<a :href="'/api/exports/' + exportResult.id + '/pdf'">重新下载</a></div>
    <div class="paper-caption"><span>中文技术岗 · 单栏</span><span>A4 · {{ pageCount }} {{ pageCount === 1 ? 'PAGE' : 'PAGES' }}</span></div>
    <div class="paper-wrap" :style="{width: 794 * scale + 'px', height: (1123 * pageCount + (pageCount - 1) * 23) * scale + 'px'}"><iframe v-if="previewUrl" ref="previewFrame" title="简历实时预览" :src="previewUrl" :style="{width: '794px', height: 1123 * pageCount + (pageCount - 1) * 23 + 'px', transform: `scale(${scale})`}" @load="frameReady"></iframe><div v-else class="empty-preview">正在准备中文字体与图片…</div></div>
    <p class="preview-footnote">合成简历与原创示例图，仅用于版式验证 · 导出保留可选择的中文文本</p>
   </section>
  </div>
 </div>
</template>
