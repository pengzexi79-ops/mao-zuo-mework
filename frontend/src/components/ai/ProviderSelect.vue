<template>
  <div class="provider-select">
    <label>服务商</label>
    <el-select
      :model-value="modelValue"
      filterable
      clearable
      teleported
      popper-class="ai-provider-popper"
      :placeholder="`选择支持${operationLabel}的 Provider`"
      style="width:100%"
      @update:model-value="change"
    >
      <el-option v-for="provider in providers" :key="provider.id" :label="provider.name" :value="provider.id" />
    </el-select>
    <div v-if="selected" class="provider-links">
      <a v-if="selected.setupUrl" :href="selected.setupUrl" target="_blank" rel="noopener noreferrer">官方接入 / API Key</a>
      <a v-if="selected.billingUrl" :href="selected.billingUrl" target="_blank" rel="noopener noreferrer">官方说明</a>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  modelValue: { type: [String, Number], default: null },
  providers: { type: Array, default: () => [] },
  operation: { type: String, default: 'image' }
})
const emit = defineEmits(['update:modelValue', 'changed'])
const operationLabel = computed(() => ({ image: '图片', video: '视频', voice: '配音' })[props.operation] || '媒体')
const selected = computed(() => props.providers.find(item => item.id === props.modelValue) || null)
function change(value) {
  emit('update:modelValue', value || null)
  emit('changed')
}
</script>

<style scoped>
.provider-select { margin-bottom:14px; min-width:0; }
.provider-select label { display:block; margin-bottom:7px; color:var(--el-text-color-regular); font-size:14px; }
.provider-links { display:flex; gap:14px; flex-wrap:wrap; margin-top:7px; font-size:13px; }
.provider-links a { color:var(--el-color-primary); }
</style>
