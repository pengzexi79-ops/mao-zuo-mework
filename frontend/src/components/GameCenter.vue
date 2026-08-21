<template>
  <el-dialog v-model="open" title="游戏中心" width="720px" append-to-body destroy-on-close class="game-center-dialog">
    <div class="game-tabs" role="tablist">
      <button v-for="game in games" :key="game.id" type="button" :class="{ active: activeGame === game.id }" @click="activeGame = game.id">
        {{ game.label }}
      </button>
    </div>

    <section v-if="activeGame === 'snake'" class="game-panel">
      <div class="game-heading"><span>贪吃蛇</span><b>分数 {{ snake.score }} · 最高 {{ scores.snake }}</b></div>
      <div ref="snakeBoard" class="snake-board" :style="snakeStyle" tabindex="0" @keydown.prevent="moveSnake">
        <i v-for="(cell, index) in snake.cells" :key="index" :class="cell.type" :style="cell.style"></i>
      </div>
      <p class="game-hint">使用方向键开始，撞墙或撞到自己会结束。</p>
      <el-button size="small" type="primary" @click="resetSnake">重新开始</el-button>
    </section>

    <section v-else-if="activeGame === '2048'" class="game-panel">
      <div class="game-heading"><span>2048</span><b>分数 {{ board2048.score }} · 最高 {{ scores.game2048 }}</b></div>
      <div ref="game2048Board" class="grid-2048" tabindex="0" @keydown.prevent="move2048">
        <div v-for="(value, index) in board2048.tiles" :key="index" class="tile-2048" :class="`v-${value}`">{{ value || '' }}</div>
      </div>
      <p class="game-hint">方向键合并相同数字。</p>
      <el-button size="small" type="primary" @click="reset2048">重新开始</el-button>
    </section>

    <section v-else-if="activeGame === 'tic'" class="game-panel">
      <div class="game-heading"><span>井字棋</span><b>胜场 {{ scores.tic }}</b></div>
      <div class="tic-board">
        <button v-for="(cell, index) in tic.cells" :key="index" type="button" :disabled="!!cell || tic.winner" @click="playTic(index)">{{ cell }}</button>
      </div>
      <p class="game-hint">{{ tic.winner ? `${tic.winner === '平局' ? '本局平局' : `${tic.winner} 获胜`}` : `轮到 ${tic.turn}` }}</p>
      <el-button size="small" type="primary" @click="resetTic">重新开始</el-button>
    </section>

    <section v-else-if="activeGame === 'memory'" class="game-panel">
      <div class="game-heading"><span>记忆配对</span><b>步数 {{ memory.moves }} · 最高 {{ scores.memory }}</b></div>
      <div class="memory-board">
        <button v-for="(card, index) in memory.cards" :key="card.id" type="button" :class="{ flipped: card.flipped || card.matched }" :disabled="card.flipped || card.matched || memory.locked" @click="flipMemory(index)">{{ card.flipped || card.matched ? card.value : '?' }}</button>
      </div>
      <p class="game-hint">翻开两张相同的牌，完成所有配对。</p>
      <el-button size="small" type="primary" @click="resetMemory">重新开始</el-button>
    </section>

    <section v-else class="game-panel">
      <div class="game-heading"><span>打砖块</span><b>最高 {{ scores.breakout }}</b></div>
      <div class="breakout-board" @mousemove="movePaddle" @touchmove.prevent="movePaddle">
        <i class="breakout-ball" :style="{ left: `${breakout.ball.x}%`, top: `${breakout.ball.y}%` }"></i>
        <i v-for="brick in breakout.bricks" :key="brick.id" class="brick" :style="{ left: `${brick.x}%`, top: `${brick.y}%` }"></i>
        <i class="paddle" :style="{ left: `${breakout.paddle}%` }"></i>
      </div>
      <p class="game-hint">点击开始，移动鼠标或手指控制挡板。分数 {{ breakout.score }}。</p>
      <el-button size="small" type="primary" @click="startBreakout">{{ breakout.running ? '进行中' : '开始游戏' }}</el-button>
    </section>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'

const props = defineProps({ modelValue: { type: Boolean, default: false } })
const emit = defineEmits(['update:modelValue'])
const open = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const games = [{ id: 'snake', label: '贪吃蛇' }, { id: '2048', label: '2048' }, { id: 'tic', label: '井字棋' }, { id: 'memory', label: '记忆配对' }, { id: 'breakout', label: '打砖块' }]
const activeGame = ref('snake')
const snakeBoard = ref(null)
const game2048Board = ref(null)
const scores = reactive(loadScores())
function loadScores () { try { return { snake: Number(localStorage.getItem('game-score-snake')) || 0, game2048: Number(localStorage.getItem('game-score-2048')) || 0, tic: Number(localStorage.getItem('game-score-tic')) || 0, memory: Number(localStorage.getItem('game-score-memory')) || 0, breakout: Number(localStorage.getItem('game-score-breakout')) || 0 } } catch { return { snake: 0, game2048: 0, tic: 0, memory: 0, breakout: 0 } } }
function saveScore (key, score) { if (score > scores[key]) { scores[key] = score; try { localStorage.setItem(`game-score-${key === 'game2048' ? '2048' : key}`, String(score)) } catch {} } }

const snake = reactive({ body: [{ x: 5, y: 5 }], food: { x: 12, y: 8 }, direction: { x: 1, y: 0 }, score: 0, timer: null, over: false })
const snakeStyle = computed(() => ({ '--cols': 18, '--rows': 12 }))
snake.cells = computed(() => { const cells = snake.body.map(item => ({ type: 'snake', style: { left: `${item.x / 18 * 100}%`, top: `${item.y / 12 * 100}%` } })); cells.push({ type: 'food', style: { left: `${snake.food.x / 18 * 100}%`, top: `${snake.food.y / 12 * 100}%` } }); return cells })
function resetSnake () { clearInterval(snake.timer); snake.body = [{ x: 5, y: 5 }]; snake.food = { x: 12, y: 8 }; snake.direction = { x: 1, y: 0 }; snake.score = 0; snake.over = false; snake.timer = setInterval(tickSnake, 180) }
function moveSnake (event) { const keys = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] }; if (!keys[event.key]) return; const [x, y] = keys[event.key]; if (x !== -snake.direction.x || y !== -snake.direction.y) snake.direction = { x, y } }
function tickSnake () { if (snake.over) return; const head = { x: snake.body[0].x + snake.direction.x, y: snake.body[0].y + snake.direction.y }; if (head.x < 0 || head.x >= 18 || head.y < 0 || head.y >= 12 || snake.body.some(cell => cell.x === head.x && cell.y === head.y)) { snake.over = true; clearInterval(snake.timer); saveScore('snake', snake.score); return } snake.body.unshift(head); if (head.x === snake.food.x && head.y === snake.food.y) { snake.score += 10; snake.food = { x: Math.floor(Math.random() * 18), y: Math.floor(Math.random() * 12) } } else snake.body.pop() }

const board2048 = reactive({ tiles: [], score: 0 })
function reset2048 () { board2048.tiles = Array(16).fill(0); board2048.score = 0; addTile2048(); addTile2048() }
function addTile2048 () { const empty = board2048.tiles.map((v, i) => v ? -1 : i).filter(i => i >= 0); if (empty.length) board2048.tiles[empty[Math.floor(Math.random() * empty.length)]] = Math.random() < .9 ? 2 : 4 }
function move2048 (event) { if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return; const horizontal = event.key === 'ArrowLeft' || event.key === 'ArrowRight'; let moved = false; for (let line = 0; line < 4; line++) { const indexes = Array.from({ length: 4 }, (_, i) => horizontal ? line * 4 + i : i * 4 + line); if (event.key === 'ArrowRight' || event.key === 'ArrowDown') indexes.reverse(); const values = indexes.map(i => board2048.tiles[i]).filter(Boolean); for (let i = 0; i < values.length - 1; i++) if (values[i] === values[i + 1]) { values[i] *= 2; board2048.score += values[i]; values.splice(i + 1, 1) } while (values.length < 4) values.push(0); indexes.forEach((index, i) => { if (board2048.tiles[index] !== values[i]) moved = true; board2048.tiles[index] = values[i] }) } if (moved) { addTile2048(); saveScore('game2048', board2048.score) } }

const tic = reactive({ cells: Array(9).fill(''), turn: 'X', winner: '' })
function resetTic () { tic.cells = Array(9).fill(''); tic.turn = 'X'; tic.winner = '' }
function playTic (index) { if (tic.cells[index] || tic.winner) return; tic.cells[index] = tic.turn; const lines = [[0,1,2],[3,4,5],[6,7,8],[0,3,6],[1,4,7],[2,5,8],[0,4,8],[2,4,6]]; if (lines.some(line => line.every(i => tic.cells[i] === tic.turn))) { tic.winner = tic.turn; if (tic.turn === 'X') { scores.tic++; try { localStorage.setItem('game-score-tic', String(scores.tic)) } catch {} } } else if (tic.cells.every(Boolean)) tic.winner = '平局'; else tic.turn = tic.turn === 'X' ? 'O' : 'X' }

const memory = reactive({ cards: [], first: null, locked: false, moves: 0 })
function resetMemory () { memory.cards = [...'AABBCCDDEEFF'].sort(() => Math.random() - .5).map((value, id) => ({ id, value, flipped: false, matched: false })); memory.first = null; memory.locked = false; memory.moves = 0 }
function flipMemory (index) { const card = memory.cards[index]; if (memory.locked || card.flipped || card.matched) return; card.flipped = true; if (memory.first === null) memory.first = index; else { memory.moves++; const first = memory.cards[memory.first]; if (first.value === card.value) { first.matched = card.matched = true; memory.first = null; if (memory.cards.every(item => item.matched)) { saveScore('memory', Math.max(10, 100 - memory.moves * 2)) } } else { memory.locked = true; setTimeout(() => { first.flipped = card.flipped = false; memory.first = null; memory.locked = false }, 650) } } }

const breakout = reactive({ ball: { x: 50, y: 80, vx: 0.7, vy: -0.8 }, paddle: 50, bricks: [], score: 0, running: false, timer: null })
function resetBreakout () { breakout.bricks = Array.from({ length: 15 }, (_, id) => ({ id, x: 8 + (id % 5) * 18, y: 10 + Math.floor(id / 5) * 12 })); breakout.ball = { x: 50, y: 80, vx: .7, vy: -.8 }; breakout.paddle = 50; breakout.score = 0; breakout.running = false; clearInterval(breakout.timer) }
function startBreakout () { if (breakout.running) return; breakout.running = true; breakout.timer = setInterval(tickBreakout, 30) }
function movePaddle (event) { const rect = event.currentTarget.getBoundingClientRect(); const point = event.touches ? event.touches[0] : event; breakout.paddle = Math.max(10, Math.min(90, (point.clientX - rect.left) / rect.width * 100)) }
function tickBreakout () { const b = breakout.ball; b.x += b.vx; b.y += b.vy; if (b.x < 2 || b.x > 98) b.vx *= -1; if (b.y < 2) b.vy *= -1; if (b.y > 88 && Math.abs(b.x - breakout.paddle) < 13) b.vy = -Math.abs(b.vy); const hit = breakout.bricks.findIndex(brick => Math.abs(b.x - brick.x - 7) < 9 && Math.abs(b.y - brick.y - 4) < 7); if (hit >= 0) { breakout.bricks.splice(hit, 1); b.vy *= -1; breakout.score += 10; saveScore('breakout', breakout.score) } if (b.y > 100 || !breakout.bricks.length) { clearInterval(breakout.timer); breakout.running = false } }
function focusActiveGame () { nextTick(() => (activeGame.value === '2048' ? game2048Board.value : snakeBoard.value)?.focus()) }
watch(open, value => {
  if (value) { resetSnake(); reset2048(); resetTic(); resetMemory(); resetBreakout(); focusActiveGame() } else { clearInterval(snake.timer); clearInterval(breakout.timer) }
})
watch(activeGame, focusActiveGame)
onBeforeUnmount(() => { clearInterval(snake.timer); clearInterval(breakout.timer) })
</script>

<style scoped>
.game-tabs { display:flex; gap:6px; flex-wrap:wrap; border-bottom:1px solid #ebeef5; padding-bottom:10px; margin-bottom:14px; }
.game-tabs button { border:1px solid #dcdfe6; background:#fff; color:#606266; border-radius:6px; padding:7px 11px; cursor:pointer; }
.game-tabs button.active { color:#409eff; border-color:#409eff; background:#ecf5ff; }
.game-panel { text-align:center; min-height:300px; }
.game-heading { display:flex; justify-content:space-between; max-width:420px; margin:0 auto 10px; color:#303133; }
.game-heading b { color:#909399; font-size:13px; font-weight:500; }
.game-hint { color:#909399; font-size:13px; margin:12px 0; }
.snake-board { position:relative; width:min(100%, 420px); aspect-ratio:3 / 2; margin:auto; background:#182333; border:5px solid #303d52; border-radius:6px; overflow:hidden; }
.snake-board i, .breakout-board i { position:absolute; display:block; }
.snake { width:5.55%; height:8.33%; background:#67c23a; border-radius:2px; }
.food { width:5.55%; height:8.33%; background:#f56c6c; border-radius:50%; }
.grid-2048 { width:min(100%, 320px); aspect-ratio:1; margin:auto; display:grid; grid-template-columns:repeat(4,1fr); gap:7px; padding:7px; background:#bbada0; border-radius:6px; outline:none; }
.tile-2048 { display:grid; place-items:center; border-radius:4px; background:#cdc1b4; color:#776e65; font-weight:700; font-size:22px; }
.tile-2048:not(.v-0) { background:#eee4da; }.v-4{background:#ede0c8}.v-8{background:#f2b179;color:#fff}.v-16{background:#f59563;color:#fff}.v-32,.v-64{background:#f67c5f;color:#fff}.v-128,.v-256,.v-512,.v-1024,.v-2048{background:#edcf72;color:#fff;font-size:18px}
.tic-board { display:grid; grid-template-columns:repeat(3,80px); gap:6px; justify-content:center; }.tic-board button { height:80px; font-size:30px; color:#409eff; background:#f5f7fa; border:1px solid #dcdfe6; border-radius:5px; cursor:pointer; }.tic-board button:disabled { cursor:default; }
.memory-board { display:grid; grid-template-columns:repeat(6,52px); gap:7px; justify-content:center; }.memory-board button { height:58px; font-size:20px; border:1px solid #dcdfe6; background:#eef5ff; color:#409eff; border-radius:5px; cursor:pointer; }.memory-board button.flipped { background:#fff; }
.breakout-board { position:relative; width:min(100%, 420px); aspect-ratio:5 / 3; margin:auto; background:#17202e; border-radius:6px; overflow:hidden; }.brick { width:14%; height:7%; background:#e6a23c; border-radius:2px; }.breakout-ball { width:12px; height:12px; transform:translate(-50%,-50%); border-radius:50%; background:#f56c6c; }.paddle { bottom:5%; width:25%; height:5%; transform:translateX(-50%); background:#67c23a; border-radius:3px; }
@media (max-width:640px) { .game-center-dialog { width:calc(100vw - 20px) !important; } .memory-board { grid-template-columns:repeat(6, minmax(34px, 52px)); gap:4px; }.memory-board button { height:48px; } .tic-board { grid-template-columns:repeat(3, minmax(64px,80px)); }.tic-board button { height:64px; } }
</style>
