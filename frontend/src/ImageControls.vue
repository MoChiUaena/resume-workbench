<script setup lang="ts">
import { ref, onUnmounted } from 'vue';
import { upload, type Asset, type Slot } from './api';
const props = defineProps<{ kind: 'photo' | 'logo'; title: string; slot: Slot; asset?: Asset; maxBytes: number }>();
const emit = defineEmits<{ imported: [Asset]; removed: [] }>();
const busy = ref(false), error = ref('');
let alive=true; onUnmounted(()=>{alive=false;});
async function select(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; if (!file) return;
  error.value = ''; busy.value = true; const targetSlot=props.slot;
  try { const asset=await upload(file, props.maxBytes); if(alive && props.slot===targetSlot) emit('imported', asset); }
  catch (e) { if(alive && props.slot===targetSlot) error.value = e instanceof Error ? e.message : '图片导入失败，请重试。'; }
  finally { busy.value = false; input.value = ''; }
}
</script>
<template>
 <section class="image-control" :data-testid="kind + '-controls'">
  <div class="card-title"><h3>{{ title }}</h3><label class="check"><input v-model="slot.visible" type="checkbox" :aria-label="'显示' + title">显示</label></div>
  <div class="upload-row">
   <div class="asset-thumb checker"><img v-if="slot.id" :src="'/api/assets/' + slot.id + '/image'" :alt="title"><span v-else>＋</span></div>
   <div class="upload-description"><label class="upload-button">{{ busy ? '正在导入…' : slot.id ? '替换图片' : '导入图片' }}<input type="file" accept="image/jpeg,image/png" :aria-label="'上传' + title" :disabled="busy" @change="select"></label>
    <p v-if="asset">{{ asset.format }} · {{ (asset.bytes / 1024).toFixed(1) }} KiB<br>{{ asset.width }} × {{ asset.height }} px<span v-if="asset.exifOrientation !== 1"> · 已校正方向</span></p>
    <p v-else>JPEG / PNG · ≤ {{ maxBytes / 1048576 }} MiB</p>
   </div>
   <button v-if="slot.id" class="text-button" :aria-label="'移除' + title" @click="emit('removed')">移除</button>
  </div>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <div class="size-row"><label>宽度 <span>mm</span><input v-model.number="slot.widthMm" type="number" min="16" max="36" :aria-label="title + '宽度'"></label><label>高度 <span>mm</span><input v-model.number="slot.heightMm" type="number" min="16" max="42" :aria-label="title + '高度'"></label><label>适配<select v-model="slot.fit" :aria-label="title + '适配'"><option value="contain">完整显示</option><option value="cover">填充裁剪</option></select></label></div>
  <details><summary>裁剪与旋转</summary><div class="fine-controls"><label>缩放 <output>{{ slot.zoom.toFixed(2) }}×</output><input v-model.number="slot.zoom" type="range" min="1" max="2" step="0.05" :aria-label="title + '缩放'"></label><label>水平位置<input v-model.number="slot.positionX" type="range" min="0" max="100" :aria-label="title + '水平位置'"></label><label>垂直位置<input v-model.number="slot.positionY" type="range" min="0" max="100" :aria-label="title + '垂直位置'"></label><button @click="slot.quarterTurns = (slot.quarterTurns + 1) % 4">顺时针旋转 90°</button><button class="text-button" @click="slot.zoom = 1; slot.quarterTurns = 0; slot.positionX = 50; slot.positionY = 50">重置裁剪</button></div></details>
 </section>
</template>
