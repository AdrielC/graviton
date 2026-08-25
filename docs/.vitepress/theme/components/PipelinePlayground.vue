<template>
  <section class="pipeline-playground" aria-labelledby="pipeline-playground-title">
    <header class="pp-header">
      <div>
        <p class="pp-eyebrow">LOCAL CONTENT LAB</p>
        <h2 id="pipeline-playground-title">Bytes become content IDs here</h2>
        <p>
          Edit the payload and block size. The linked graviton-shared Scala.js module computes every result.
        </p>
      </div>
      <div class="pp-runtime-stack">
        <span :class="['pp-runtime', runtimeState]">{{ runtimeLabel }}</span>
        <span class="pp-boundary">8 KiB refined boundary</span>
      </div>
    </header>

    <div class="pp-workbench">
      <div class="pp-input-panel">
        <label for="pp-payload">Payload</label>
        <textarea
          id="pp-payload"
          v-model="payload"
          rows="8"
          spellcheck="false"
          maxlength="2048"
          :aria-invalid="Boolean(error)"
        />

        <div class="pp-input-meter">
          <span>UTF-16 input</span>
          <strong>{{ payload.length }} / 2,048 code units</strong>
        </div>

        <div class="pp-presets">
          <button type="button" @click="loadRepeated">Load repeated blocks</button>
          <button type="button" @click="loadAvalanche">Change one byte</button>
          <button type="button" @click="payload = ''">Clear</button>
        </div>

        <label class="pp-slider">
          <span>Fixed block size</span>
          <input v-model.number="blockSize" type="range" min="16" max="128" step="16" />
          <strong>{{ blockSize }} bytes</strong>
        </label>

        <p v-if="error" class="pp-error" role="alert">{{ error }}</p>
        <p v-else class="pp-note">
          The lab uses fixed boundaries for clarity. Server ingest remains streaming and may use Fixed,
          FastCDC, or delimiter chunking without this interactive memory limit.
        </p>
      </div>

      <div class="pp-result-panel" aria-live="polite">
        <div :class="['pp-pipeline-flow', { processing }]">
          <div><span>1</span><strong>UTF-8</strong><small>{{ result.byteCount }} bytes</small></div>
          <b aria-hidden="true">›</b>
          <div><span>2</span><strong>CHUNK</strong><small>{{ result.blocks.length }} blocks</small></div>
          <b aria-hidden="true">›</b>
          <div><span>3</span><strong>SHA-256</strong><small>{{ result.blocks.length + (result.blobId ? 1 : 0) }} digests</small></div>
          <b aria-hidden="true">›</b>
          <div><span>4</span><strong>DEDUP</strong><small>{{ result.duplicateCount }} repeated</small></div>
        </div>

        <dl class="pp-summary">
          <div>
            <dt>Blob content ID</dt>
            <dd><code>{{ result.blobId || 'Loading Scala.js content lab…' }}</code></dd>
          </div>
          <div>
            <dt>Unique blocks</dt>
            <dd>{{ result.uniqueCount }}</dd>
          </div>
          <div>
            <dt>Duplicate blocks</dt>
            <dd>{{ result.duplicateCount }}</dd>
          </div>
          <div>
            <dt>Payload bytes</dt>
            <dd>{{ result.byteCount }}</dd>
          </div>
        </dl>
      </div>
    </div>

    <div v-if="result.blocks.length" class="pp-table-scroll">
      <table>
        <thead>
          <tr>
            <th>Index</th>
            <th>Offset</th>
            <th>Size</th>
            <th>Block content ID</th>
            <th>Disposition</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="block in result.blocks" :key="`${block.index}:${block.contentId}`">
            <td>{{ block.index }}</td>
            <td>{{ block.offset }}</td>
            <td>{{ block.size }}</td>
            <td><code>{{ block.contentId }}</code></td>
            <td>
              <span :class="['pp-disposition', block.duplicate ? 'duplicate' : 'fresh']">
                {{ block.duplicate ? 'duplicate' : 'first occurrence' }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'

type BlockResult = {
  index: number
  offset: number
  size: number
  contentId: string
  duplicate: boolean
}

type PlaygroundResult = {
  byteCount: number
  blobId: string
  blocks: BlockResult[]
  uniqueCount: number
  duplicateCount: number
  engine: string
}

type ContentLabModule = {
  analyzeGravitonContent(value: string, blockSize: number): Promise<PlaygroundResult>
}

const repeatedPayload = [
  'GRAVITON-BLOCK!!',
  'GRAVITON-BLOCK!!',
  'CONTENT-ADDRESS!',
  'GRAVITON-BLOCK!!'
].join('')

const payload = ref(repeatedPayload)
const blockSize = ref(16)
const processing = ref(false)
const error = ref('')
const mounted = ref(false)
const runtimeState = ref<'loading' | 'ready' | 'error'>('loading')
const runtimeLabel = ref('Loading Scala.js')
const result = ref<PlaygroundResult>({
  byteCount: 0,
  blobId: '',
  blocks: [],
  uniqueCount: 0,
  duplicateCount: 0,
  engine: ''
})

let revision = 0
let modulePromise: Promise<ContentLabModule> | undefined

function loadRepeated() {
  payload.value = repeatedPayload
  blockSize.value = 16
}

function loadAvalanche() {
  payload.value = repeatedPayload.replace('CONTENT-ADDRESS!', 'CONTENT-ADDRESX!')
  blockSize.value = 16
}

function loadContentLab(): Promise<ContentLabModule> {
  if (!modulePromise) {
    const base = import.meta.env.BASE_URL.endsWith('/')
      ? import.meta.env.BASE_URL
      : `${import.meta.env.BASE_URL}/`
    modulePromise = import(/* @vite-ignore */ `${base}content-lab/main.js`) as Promise<ContentLabModule>
  }
  return modulePromise
}

async function recompute() {
  if (!mounted.value) return

  const currentRevision = ++revision
  processing.value = true
  error.value = ''

  try {
    const contentLab = await loadContentLab()
    const nextResult = await contentLab.analyzeGravitonContent(payload.value, blockSize.value)

    if (currentRevision !== revision) return
    result.value = nextResult
    runtimeState.value = 'ready'
    runtimeLabel.value = nextResult.engine
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
    runtimeState.value = 'error'
    runtimeLabel.value = 'Scala.js unavailable'
  } finally {
    if (currentRevision === revision) processing.value = false
  }
}

watch([payload, blockSize], recompute)
onMounted(() => {
  mounted.value = true
  void recompute()
})
</script>
