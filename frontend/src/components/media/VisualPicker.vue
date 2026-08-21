<template>
  <div class="visual-picker">
    <div class="picker-title">
      <span>{{ title }}</span>
      <span class="picker-selection">{{ selected ? `已选：${selected.name}` : `共 ${(items || []).length} 个素材` }}</span>
    </div>
    <button
      v-for="item in (items || []).slice(0, 24)"
      :key="item.id"
      type="button"
      class="visual-item"
      :class="{ selected: selectedId === item.id }"
      @click="$emit('select', item.id)"
      @dblclick="$emit('preview', item)"
    >
      <span v-if="item.fileType === 'audio'" class="audio-art">音频</span>
      <img
        v-else
        :src="api.protectedUrl(item.thumbnailUrl || item.thumbnail || `/api/materials/${item.id}/preview`)"
        alt=""
        @error="hideImage"
      />
      <b>{{ item.name }}</b>
      <small>{{ `${item.role || '未分类'}${item.durationSec ? ` · ${Number(item.durationSec).toFixed(1)} 秒` : ''}` }}</small>
    </button>
    <span v-if="!(items || []).length" class="muted">暂无可用素材</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { api } from '../../api'

const props = defineProps({
  items: { type: Array, default: () => [] },
  selected: { type: [String, Number], default: null },
  title: { type: String, default: '选择素材' }
})

defineEmits(['select', 'preview'])

const selectedId = computed(() => props.selected)
const selected = computed(() => (props.items || []).find(item => item.id === props.selected) || null)

function hideImage (event) {
  event.currentTarget.style.display = 'none'
}
</script>

<style scoped>
.visual-picker {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  grid-auto-rows: 122px;
  align-content: start;
  gap: 8px;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: 224px;
  min-height: 224px;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 30px 2px 2px 0;
  box-sizing: border-box;
  position: relative;
}

.picker-title {
  position: absolute;
  inset: 0 0 auto 0;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  height: 24px;
  padding-right: 6px;
  color: var(--el-text-color-secondary);
  background: var(--el-bg-color-overlay, #fff);
  border-bottom: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
}

.picker-selection {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-color-primary);
}

.visual-item {
  width: 100%;
  min-width: 0;
  height: 122px;
  overflow: hidden;
  border: 1px solid #dcdfe6;
  background: #fff;
  border-radius: 4px;
  padding: 5px;
  text-align: left;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  box-sizing: border-box;
}

.visual-item:hover,
.visual-item.selected {
  border-color: #409eff;
  background: #ecf5ff;
}

.visual-item img,
.visual-item .audio-art {
  display: block;
  width: 100%;
  max-width: 100%;
  height: 70px;
  min-height: 70px;
  max-height: 70px;
  object-fit: cover;
  aspect-ratio: auto;
  background: #f3f5f8;
}

.audio-art {
  display: grid !important;
  place-items: center;
  color: #409eff;
  font-size: 12px;
}

.visual-item b,
.visual-item small {
  display: block;
  width: 100%;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.visual-item b {
  height: 16px;
  font-size: 10px;
  line-height: 16px;
}

.visual-item small {
  height: 14px;
  font-size: 9px;
  line-height: 14px;
  color: #909399;
}

@media (max-width: 720px) {
  .visual-picker {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    grid-auto-rows: 108px;
    height: 208px;
    min-height: 208px;
  }

  .visual-item { height: 108px; padding: 4px; }

  .visual-item img,
  .visual-item .audio-art {
    height: 60px;
    min-height: 60px;
    max-height: 60px;
  }
}
</style>
