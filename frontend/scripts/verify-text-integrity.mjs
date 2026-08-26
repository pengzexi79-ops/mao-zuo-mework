import { readFile } from 'node:fs/promises'

const path = new URL('../src/views/Studio.vue', import.meta.url)
const source = await readFile(path, 'utf8')

if (source.includes('\uFFFD')) {
  throw new Error('Text integrity check failed: Studio.vue contains replacement characters')
}

const required = [
  '半自动模式', '总时长', '仅本地素材', '已暂停', '已取消',
  '先设时长，再开始出片', '需要独立片尾卡', '建议复核',
  '已清除项目选择，参数恢复默认',
  '已载入项目默认参数；你可以继续修改',
  '请检查后端和素材状态',
  '将继续使用当前本地素材',
  '已开始连续出片，随时可点击暂停',
  '已继续出片'
]
const missing = required.filter((text) => !source.includes(text))
if (missing.length) {
  throw new Error(`Text integrity check failed: missing expected UI text: ${missing.join(', ')}`)
}

console.log('Studio text integrity verified')
