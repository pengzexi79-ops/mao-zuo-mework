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
function change(value) {
  emit('update:modelValue', value || null)
  emit('changed')
}
</script>

<style scoped>
.provider-select { margin-bottom:14px; min-width:0; }
.provider-select label { display:block; margin-bottom:7px; color:var(--el-text-color-regular); font-size:14px; }
</style>
