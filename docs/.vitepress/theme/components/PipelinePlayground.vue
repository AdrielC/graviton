<template>
  <section class="pipeline-playground" aria-label="Content-addressing playground">
    <header class="pp-header">
      <p>Bounded to 8 KiB in this browser lab</p>
      <span :class="['pp-runtime', runtimeState]" role="status">{{ runtimeLabel }}</span>
    </header>

    <div class="pp-workbench">
      <div class="pp-input-panel">
        <label for="pp-payload">Text to hash</label>
        <textarea
          id="pp-payload"
          v-model="payload"
          name="content"
          rows="7"
          autocomplete="off"
          spellcheck="false"
          maxlength="2048"
          aria-describedby="pp-input-help pp-input-count"
          :aria-invalid="Boolean(error)"
        />

        <div class="pp-input-meter">
          <span id="pp-input-help">UTF-8 encoded locally</span>
          <strong id="pp-input-count">{{ formatNumber(payload.length) }} / 2,048 characters</strong>
        </div>

        <div class="pp-presets" aria-label="Example inputs">
          <button type="button" @click="loadRepeated">Repeat a block</button>
          <button type="button" @click="loadAvalanche">Change 1 byte</button>
          <button type="button" @click="payload = ''">Clear</button>
        </div>

        <label class="pp-slider">
          <span>Block size</span>
          <input v-model.number="blockSize" name="block-size" type="range" min="16" max="128" step="16" />
          <strong>{{ blockSize }} bytes</strong>
        </label>

        <p v-if="error" class="pp-error" role="alert">{{ error }} Refresh the page and try again.</p>
      </div>

      <div class="pp-result-panel" role="status" aria-live="polite" aria-atomic="false">
        <div class="pp-identity">
          <span>Blob content ID</span>
          <code>{{ result.blobId || 'Computing…' }}</code>
        </div>
        <dl class="pp-summary">
          <div>
            <dt>Payload</dt>
            <dd>{{ formatNumber(result.byteCount) }} bytes</dd>
          </div>
          <div>
            <dt>Blocks</dt>
            <dd>{{ formatNumber(result.blocks.length) }}</dd>
          </div>
          <div>
            <dt>Unique</dt>
            <dd>{{ formatNumber(result.uniqueCount) }}</dd>
          </div>
          <div>
            <dt>Repeated</dt>
            <dd>{{ formatNumber(result.duplicateCount) }}</dd>
          </div>
        </dl>
        <p class="pp-note">
          Fixed boundaries keep this example legible. Server ingest stays streaming and can use fixed,
          FastCDC, or delimiter chunking.
        </p>
      </div>
    </div>

    <div v-if="visibleBlocks.length" class="pp-table-scroll">
      <table>
        <caption>
          Block identities
          <span v-if="result.blocks.length > visibleBlocks.length">
            · showing {{ visibleBlocks.length }} of {{ formatNumber(result.blocks.length) }}
          </span>
        </caption>
        <thead>
          <tr>
            <th scope="col">Block</th>
            <th scope="col">Offset</th>
            <th scope="col">Bytes</th>
            <th scope="col">Content ID</th>
            <th scope="col">Result</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="block in visibleBlocks" :key="`${block.index}:${block.contentId}`">
            <td>{{ block.index + 1 }}</td>
            <td>{{ formatNumber(block.offset) }}</td>
            <td>{{ formatNumber(block.size) }}</td>
            <td><code>{{ block.contentId }}</code></td>
            <td>
              <span :class="['pp-disposition', block.duplicate ? 'duplicate' : 'fresh']">
                {{ block.duplicate ? 'reused' : 'new' }}
              </span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

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

const numberFormat = new Intl.NumberFormat()
const payload = ref(repeatedPayload)
const blockSize = ref(16)
const error = ref('')
const mounted = ref(false)
const runtimeState = ref<'loading' | 'ready' | 'error'>('loading')
const runtimeLabel = ref('Loading Scala.js…')
const result = ref<PlaygroundResult>({
  byteCount: 0,
  blobId: '',
  blocks: [],
  uniqueCount: 0,
  duplicateCount: 0,
  engine: ''
})
const visibleBlocks = computed(() => result.value.blocks.slice(0, 24))

let revision = 0
let modulePromise: Promise<ContentLabModule> | undefined

function formatNumber(value: number) {
  return numberFormat.format(value)
}

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
  }
}

watch([payload, blockSize], recompute)
onMounted(() => {
  mounted.value = true
  void recompute()
})
</script>
