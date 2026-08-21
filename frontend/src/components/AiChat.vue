<template>
  <teleport to="body">
    <div class="ai-chat" :class="{ dragging: drag.active }" :style="launcherPosition">
      <button v-if="!visible" class="ai-chat-launcher" type="button" title="打开猫作 AI 助手" @pointerdown="startDrag" @click="openFromClick">
        <img src="/icon-512.png" alt="猫作 AI 助手" draggable="false" />
      </button>

      <el-dialog v-model="visible" class="ai-chat-dialog" title="猫作 AI 助手" width="560px" destroy-on-close append-to-body
        z-index="3100" :close-on-click-modal="true" :close-on-press-escape="true" @open="refreshReady" @closed="closeChat">
        <div class="ai-chat-intro">
          <img src="/icon-512.png" alt="猫作 AI 助手头像" draggable="false" />
          <div><b>素材、脚本、镜头和出片修复都可以问我</b><div class="form-hint">我只会引导到应用内已接入的素材、规划、质检与官方授权能力；不会显示或保存 API Key。</div></div>
        </div>
        <div class="ai-chat-tools">
          <el-button size="small" @click="go('/projects')">项目资料</el-button><el-button size="small" @click="go('/materials')">整理素材</el-button><el-button size="small" @click="go('/crawl')">合规找素材</el-button><el-button size="small" @click="go('/studio')">规划出片</el-button><el-button size="small" @click="go('/outputs')">查看成片</el-button><el-button size="small" @click="go('/resource-center')">资源中心</el-button><el-button size="small" @click="newChallenge">创作挑战</el-button>
        </div>
        <el-alert v-if="challenge" class="ai-chat-challenge" type="success" :closable="true" show-icon :title="challenge.title" @close="challenge = null"><template #default>{{ challenge.prompt }}</template></el-alert>
        <el-alert v-if="!aiReady" type="warning" :closable="false" show-icon title="AI 尚未配置"><template #default>先配置 AI 接口，完成后再回来对话。</template></el-alert>
        <div ref="messageBox" class="ai-chat-messages" @scroll="onMessageScroll"><div v-if="!messages.length" class="ai-chat-empty">例如：帮我把护肤素材整理成 60 秒的出片方案</div><div v-for="(message, index) in messages" :key="index" class="ai-chat-message" :class="message.role"><img v-if="message.role === 'assistant'" src="/icon-512.png" alt="猫作" draggable="false" /><div class="ai-chat-bubble">{{ message.content }}</div></div><div v-if="sending" class="ai-chat-message assistant"><img src="/icon-512.png" alt="猫作" draggable="false" /><div class="ai-chat-bubble muted">正在思考…</div></div></div>
        <el-input v-model="draft" type="textarea" :rows="3" maxlength="4000" show-word-limit :disabled="sending" placeholder="输入你想咨询的混剪、素材或音频问题" @keydown.ctrl.enter.prevent="send" />
        <template #footer><el-button @click="clear">清空对话</el-button><el-button @click="visible = false">关闭</el-button><el-button v-if="!aiReady" type="warning" @click="goSettings">去配置 AI</el-button><el-button v-else type="primary" :loading="sending" :disabled="!draft.trim()" @click="send">发送</el-button></template>
      </el-dialog>
      <GameCenter v-model="gameVisible" />
    </div>
  </teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import GameCenter from './GameCenter.vue'

const router = useRouter(); const visible = ref(false); const gameVisible = ref(false); const draft = ref(''); const sending = ref(false); const aiReady = ref(false); const messages = ref([]); const messageBox = ref(null); const challenge = ref(null); const stickToBottom = ref(true)
const launcher = reactive({ right: 24, bottom: 96 }); const drag = reactive({ active: false, over: false, moved: false, pointerId: null, startX: 0, startY: 0, startRight: 24, startBottom: 96 })
const challenges = [{ title: '三秒钩子挑战', prompt: '用项目里已经能证明的一个画面事实，写一句不夸大、不承诺功效的前三秒开场。' }, { title: '镜头排序挑战', prompt: '把“问题、证据、产品、使用场景、收尾”排成 5 个镜头，并说出每一段最少需要几秒。' }, { title: '节奏诊断挑战', prompt: '挑一条当前成片，找出一个可能重复或切断动作的位置，再决定是延长镜头还是替换素材。' }]
const launcherPosition = computed(() => ({ right: `${launcher.right}px`, bottom: `${launcher.bottom}px` }))
function readPosition () { try { const value = JSON.parse(localStorage.getItem('ai-chat-launcher-position')); if (value && Number.isFinite(value.right) && Number.isFinite(value.bottom)) { launcher.right = value.right; launcher.bottom = value.bottom } } catch {} clampPosition() }
function savePosition () { try { localStorage.setItem('ai-chat-launcher-position', JSON.stringify({ right: launcher.right, bottom: launcher.bottom })) } catch {} }
function clampPosition () { const size = window.innerWidth <= 640 ? 44 : 52; launcher.right = Math.max(8, Math.min(window.innerWidth - size - 8, launcher.right)); launcher.bottom = Math.max(8, Math.min(window.innerHeight - size - 8, launcher.bottom)) }
const GAME_DRAG_DISTANCE = 120
function clearDrag () { drag.active = false; drag.over = false; drag.pointerId = null; window.removeEventListener('pointermove', moveDrag); window.removeEventListener('pointerup', endDrag); window.removeEventListener('pointercancel', cancelDrag) }
function startDrag (event) {
  if (event.button !== 0) return
  drag.active = true; drag.over = false; drag.moved = false; drag.pointerId = event.pointerId
  drag.startX = event.clientX; drag.startY = event.clientY; drag.startRight = launcher.right; drag.startBottom = launcher.bottom
  window.addEventListener('pointermove', moveDrag); window.addEventListener('pointerup', endDrag); window.addEventListener('pointercancel', cancelDrag)
  try { event.currentTarget.setPointerCapture?.(event.pointerId) } catch {}
}
function moveDrag (event) {
  if (!drag.active || event.pointerId !== drag.pointerId) return
  const dx = event.clientX - drag.startX; const dy = event.clientY - drag.startY
  const distance = Math.hypot(dx, dy)
  if (distance > 6) drag.moved = true
  launcher.right = drag.startRight - dx; launcher.bottom = drag.startBottom - dy; clampPosition(); drag.over = distance >= GAME_DRAG_DISTANCE
}
function endDrag (event) { if (!drag.active || event.pointerId !== drag.pointerId) return; const openGame = drag.moved && drag.over; savePosition(); clearDrag(); if (openGame) { visible.value = false; gameVisible.value = true } }
function cancelDrag (event) { if (event.pointerId === drag.pointerId) { savePosition(); clearDrag() } }
function openFromClick () { if (!drag.moved) visible.value = true; drag.moved = false }
function onResize () { clampPosition(); if (!drag.active) savePosition() }
async function refreshReady () { try { aiReady.value = !!(await api.aiReady()) } catch { aiReady.value = false } }
function goSettings () { visible.value = false; router.push('/ai') }; function go (path) { visible.value = false; router.push(path) }; function newChallenge () { challenge.value = challenges[Math.floor(Math.random() * challenges.length)] }; function clear () { messages.value = []; draft.value = '' }
function closeChat () { draft.value = ''; sending.value = false }
function onMessageScroll () { const box = messageBox.value; if (box) stickToBottom.value = box.scrollHeight - box.scrollTop - box.clientHeight < 36 }
async function scrollIfNeeded () { await nextTick(); const box = messageBox.value; if (box && stickToBottom.value) box.scrollTop = box.scrollHeight }
async function send () { const content = draft.value.trim(); if (!content || sending.value) return; messages.value.push({ role: 'user', content }); draft.value = ''; sending.value = true; await scrollIfNeeded(); try { const result = await api.chat({ messages: messages.value.map(({ role, content: text }) => ({ role, content: text })) }); messages.value.push({ role: 'assistant', content: result.text || 'AI 没有返回内容，请重试。' }); await scrollIfNeeded() } catch (error) { ElMessage.error(`AI 对话失败：${error.message}`) } finally { sending.value = false } }
onMounted(() => { readPosition(); window.addEventListener('resize', onResize) }); onBeforeUnmount(() => { window.removeEventListener('resize', onResize); clearDrag() })
</script>

<style>
.ai-chat img,.ai-chat .ai-chat-launcher,.ai-chat .ai-chat-message img{ -webkit-user-drag:none;user-drag:none }.ai-chat{position:fixed;z-index:3000}.ai-chat-launcher{display:block;width:52px;height:52px;padding:5px;border:0;border-radius:50%;background:#409eff;cursor:pointer;box-shadow:0 4px 14px rgba(31,36,48,.22);touch-action:none}.ai-chat-launcher img{width:100%;height:100%;border-radius:50%;object-fit:cover}.ai-chat.dragging{z-index:3200}.ai-chat-dialog{z-index:3100!important}.ai-chat-dialog .el-dialog{z-index:3100!important}.ai-chat-intro{display:flex;align-items:center;gap:10px;margin-bottom:12px}.ai-chat-intro img{width:42px;height:42px;border-radius:12px;object-fit:cover}.ai-chat-tools{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:10px}.ai-chat-challenge{margin-bottom:10px}.ai-chat-messages{min-height:180px;max-height:330px;overflow:auto;padding:12px 2px}.ai-chat-empty{color:#8b93a5;text-align:center;padding:48px 16px}.ai-chat-message{display:flex;gap:8px;margin:10px 0;align-items:flex-start}.ai-chat-message.user{justify-content:flex-end}.ai-chat-message img{width:28px;height:28px;border-radius:8px;object-fit:cover}.ai-chat-bubble{max-width:82%;white-space:pre-wrap;line-height:1.6;padding:8px 11px;border-radius:8px;background:#f2f5f9}.ai-chat-message.user .ai-chat-bubble{background:#ecf5ff}.ai-chat-bubble.muted{color:#8b93a5}@media(max-width:640px){.ai-chat-launcher{width:44px;height:44px;padding:4px}.ai-chat-dialog{width:calc(100vw - 20px)!important;max-width:560px;margin-top:2vh!important}.ai-chat-dialog .el-dialog{width:100%!important;margin-top:2vh!important}.ai-chat-messages{max-height:240px}.ai-chat-intro{flex-direction:column;text-align:center}.ai-chat-intro img{width:36px;height:36px}}
</style>
