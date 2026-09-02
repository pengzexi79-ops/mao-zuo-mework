import { readFile } from 'node:fs/promises'

const source = await readFile(new URL('../src/views/AiCreate.vue', import.meta.url), 'utf8')
const required = [
  '@change="adaptVoiceSelection(voice)"',
  "const generateVoice = () => { adaptVoiceSelection(voice);",
  "protocol === 'dashscope_minimax_tts_http'",
  "protocol === 'dashscope_tts_http'",
  "provider?.providerMode === 'official' || isOpenAiVoiceModel(form.model)",
  "family === 'custom' && includesVoice([...openAiVoices, ...qwenVoices, ...miniMaxVoices], current)"
]

const missing = required.filter(item => !source.includes(item))
if (missing.length) throw new Error(`AI create routing check failed: ${missing.join(', ')}`)

console.log('AI create model and voice routing verified')
