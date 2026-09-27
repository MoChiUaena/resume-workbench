<script setup lang="ts">
defineProps<{ theme: 'a' | 'b'; dark: boolean }>();
const emit=defineEmits<{ 'update:theme':[value:'a'|'b']; 'update:dark':[value:boolean] }>();
</script>
<template>
 <div class="appearance-controls">
  <label>界面风格<select :value="theme" aria-label="界面风格" @change="emit('update:theme',($event.target as HTMLSelectElement).value as 'a'|'b')"><option value="a">A · 蓝白工具</option><option value="b">B · 纸张文档</option></select></label>
  <button type="button" role="switch" aria-label="深色模式" :aria-checked="dark" @click="emit('update:dark',!dark)"><span aria-hidden="true">{{dark?'☀':'☾'}}</span> {{dark?'浅色':'深色'}}</button>
 </div>
</template>
