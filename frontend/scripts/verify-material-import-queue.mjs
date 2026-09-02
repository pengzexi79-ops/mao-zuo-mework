import assert from 'node:assert/strict'

const events = []

globalThis.CustomEvent = class {
  constructor (type, options = {}) {
    this.type = type
    this.detail = options.detail
  }
}

globalThis.window = {
  location: { search: '' },
  history: { replaceState () {} },
  sessionStorage: { getItem () { return null }, setItem () {} },
  setTimeout,
  clearTimeout,
  dispatchEvent (event) { events.push(event) }
}

globalThis.FormData = class {
  append () {}
}

class FakeXhr {
  static responses = []

  constructor () {
    this.upload = {}
    this.status = 0
    this.responseText = ''
  }

  open () {}
  setRequestHeader () {}

  send () {
    const response = FakeXhr.responses.shift()
    if (!response) throw new Error('Missing fake upload response')
    setTimeout(() => {
      this.upload.onprogress?.({ lengthComputable: true, loaded: 1, total: 1 })
      this.status = response.ok ? 200 : 500
      this.responseText = JSON.stringify(response.ok
        ? { ok: true, data: { id: response.id, status: 'ready' } }
        : { ok: false, message: 'expected upload failure' })
      this.onload?.()
    }, response.delay || 0)
  }
}

globalThis.XMLHttpRequest = FakeXhr

const queue = await import(new URL('../src/materialImportQueue.js', import.meta.url))
const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
const waitFor = async (predicate, label) => {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (predicate()) return
    await wait(5)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const finishedEvents = () => events.filter((event) => event.type === 'mework-material-import-finished')

FakeXhr.responses.push({ ok: false }, { ok: false })
queue.enqueueMaterialFiles([{ name: 'one.mp4' }, { name: 'two.mp4' }])
const fileBatch = queue.materialImportState.batches[0]
await waitFor(() => fileBatch.status === 'failed', 'initial file failures')

events.length = 0
FakeXhr.responses.push({ ok: true, id: 1, delay: 10 }, { ok: true, id: 2, delay: 70 })
assert.equal(queue.retryMaterialImportItem(fileBatch.id, fileBatch.items[0].id), true)
assert.equal(queue.retryMaterialImportItem(fileBatch.id, fileBatch.items[1].id), true)
await wait(30)
assert.equal(finishedEvents().length, 0, 'a batch must not finish while another retry is active')
await waitFor(() => fileBatch.status === 'done', 'retried file batch')
assert.equal(finishedEvents().length, 1, 'a retried batch should finish exactly once')

events.length = 0
FakeXhr.responses.push({ ok: false })
queue.enqueueMaterialPackage({ kind: 'archive', packageName: 'large-package', files: [{ name: 'large.zip' }] })
const packageBatch = queue.materialImportState.batches[0]
await waitFor(() => packageBatch.status === 'failed', 'failed package import')
assert.equal(packageBatch.items[0].file, null, 'failed packages must release their File reference')

console.log('Material import queue verification passed')
