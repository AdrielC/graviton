<template>
  <section class="pipeline-playground" aria-labelledby="pipeline-playground-title">
    <header class="pp-header">
      <div>
        <p class="pp-eyebrow">LOCAL CONTENT LAB</p>
        <h2 id="pipeline-playground-title">Bytes become content IDs here</h2>
        <p>
          Edit the payload and block size. Your browser computes every SHA-256 digest shown below.
        </p>
      </div>
      <span class="pp-boundary">Real hashes, browser memory only</span>
    </header>

    <div class="pp-workbench">
      <div class="pp-input-panel">
        <label for="pp-payload">Payload</label>
        <textarea
          id="pp-payload"
          v-model="payload"
          rows="8"
          spellcheck="false"
          :aria-invalid="Boolean(error)"
        />

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
          This worksheet uses fixed boundaries for clarity. A configured Graviton server may use Fixed,
          FastCDC, or delimiter chunking.
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
            <dd><code>{{ result.blobId || 'sha-256 of empty input' }}</code></dd>
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
            <dt>Manifest bytes</dt>
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
}

const MAX_BYTES = 8192
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
const result = ref<PlaygroundResult>({
  byteCount: 0,
  blobId: '',
  blocks: [],
  uniqueCount: 0,
  duplicateCount: 0
})

let revision = 0

function loadRepeated() {
  payload.value = repeatedPayload
  blockSize.value = 16
}

function loadAvalanche() {
  payload.value = repeatedPayload.replace('CONTENT-ADDRESS!', 'CONTENT-ADDRESX!')
  blockSize.value = 16
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer), byte => byte.toString(16).padStart(2, '0')).join('')
}

async function sha256(bytes: Uint8Array): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  return toHex(digest)
}

async function recompute() {
  if (!mounted.value) return

  const currentRevision = ++revision
  const bytes = new TextEncoder().encode(payload.value)
  processing.value = true
  error.value = ''

  if (!globalThis.crypto?.subtle) {
    error.value = 'This browser does not expose the Web Crypto SHA-256 API.'
    processing.value = false
    return
  }

  if (bytes.length > MAX_BYTES) {
    error.value = `Keep the worksheet payload at or below ${MAX_BYTES} UTF-8 bytes.`
    processing.value = false
    return
  }

  try {
    const slices: Array<{ index: number; offset: number; bytes: Uint8Array }> = []
    for (let offset = 0, index = 0; offset < bytes.length; offset += blockSize.value, index += 1) {
      slices.push({ index, offset, bytes: bytes.slice(offset, offset + blockSize.value) })
    }

    const [blobDigest, blockDigests] = await Promise.all([
      sha256(bytes),
      Promise.all(slices.map(slice => sha256(slice.bytes)))
    ])

    if (currentRevision !== revision) return

    const seen = new Set<string>()
    const blocks = slices.map((slice, index) => {
      const contentId = `sha-256:${blockDigests[index]}:${slice.bytes.length}`
      const duplicate = seen.has(contentId)
      seen.add(contentId)
      return {
        index: slice.index,
        offset: slice.offset,
        size: slice.bytes.length,
        contentId,
        duplicate
      }
    })

    const duplicateCount = blocks.filter(block => block.duplicate).length
    result.value = {
      byteCount: bytes.length,
      blobId: `sha-256:${blobDigest}:${bytes.length}`,
      blocks,
      uniqueCount: blocks.length - duplicateCount,
      duplicateCount
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause)
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
