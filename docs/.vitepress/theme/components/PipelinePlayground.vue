<template>
  <!--
    THESIS: Files become a byte atlas; the page refuses a text-hash worksheet or metric-card dashboard.
    OWN-WORLD: Graviton's quiet surfaces, orbital green, cyan shared ranges, violet exact matches, and measured rules.
    STORY: Add real files, inspect their block maps, select exact overlaps, then create a bounded ZIO PDF font variant.
    FIRST VIEWPORT: File drop and chunking controls lead directly into aligned range tracks; evidence owns the width.
    FORM: Operational byte cartography, shaped directly from the user's specified workflow.
    FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review and docs/design/cas-playground.md
  -->
  <section :class="['cas-lab', { 'cas-lab--compact': compact, 'has-files': files.length }]" aria-label="Content-addressed file comparison">
    <header class="cas-lab__bar">
      <div class="cas-lab__title">
        <h2>{{ compact ? 'Compare files by content' : files.length ? 'Content comparison' : 'Drop files. Find the overlap.' }}</h2>
        <span :class="['cas-runtime', runtimeState]" role="status" :title="runtimeLabel">{{ runtimeStatus }}</span>
      </div>

      <details v-if="!compact" class="cas-settings">
        <summary>
          <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M4 7h10m4 0h2M4 17h2m4 0h10M14 4v6M6 14v6" /></svg>
          <span>Chunking</span>
          <strong>{{ profileLabel }}</strong>
        </summary>
        <div class="cas-profile" aria-label="Chunking profile">
          <label>
            <span>Strategy</span>
            <select v-model="strategy" :disabled="isBusy">
              <option value="auto">Automatic</option>
              <option value="pdf">PDF structures</option>
              <option value="fastcdc">FastCDC</option>
              <option value="fixed">Fixed ranges</option>
            </select>
          </label>
          <label>
            <span>Target block</span>
            <select v-model.number="targetBytes" :disabled="isBusy">
              <option :value="16384">16 KiB</option>
              <option :value="65536">64 KiB</option>
              <option :value="262144">256 KiB</option>
              <option :value="1048576">1 MiB</option>
            </select>
          </label>
          <button v-if="files.length" type="button" class="cas-button" :disabled="isBusy" @click="reanalyzeAll">
            Apply
          </button>
        </div>
      </details>
    </header>

    <label
      :class="['cas-drop', { 'cas-drop--empty': !files.length, 'is-dragging': dragging }]"
      @dragenter.prevent="dragging = true"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="handleDrop"
    >
      <input type="file" multiple @change="handleFileInput" />
      <span v-if="!files.length" class="cas-drop__mark" aria-hidden="true">
        <img :src="withBase('/logo.svg')" alt="" />
      </span>
      <svg v-else class="cas-drop__upload" aria-hidden="true" viewBox="0 0 32 32">
        <path d="M16 21V6m0 0-6 6m6-6 6 6M7 19v7h18v-7" />
      </svg>
      <span class="cas-drop__copy">
        <strong>{{ files.length ? 'Add files' : 'Choose files to compare' }}</strong>
        <span>{{ files.length ? 'PDF, binary, image, archive, or text' : 'PDFs, binaries, images, archives, and text all work.' }}</span>
      </span>
      <span class="cas-drop__action">{{ files.length ? 'Browse' : 'Choose files' }}</span>
    </label>

    <ul v-if="!files.length" class="cas-trust" aria-label="Local analysis guarantees">
      <li>Runs locally</li>
      <li>Streaming SHA-256</li>
      <li>PDF-aware</li>
    </ul>

    <template v-if="files.length">
      <section class="cas-overview" aria-label="Deduplication summary">
        <p class="cas-verdict" aria-live="polite">{{ comparisonStatement }}</p>
        <dl class="cas-totals">
          <div class="cas-totals__reuse"><dt>Reusable</dt><dd>{{ formatBytes(reusedBytes) }} <span>{{ formatPercent(reuseRatio) }}</span></dd></div>
          <div><dt>Unique</dt><dd>{{ formatBytes(uniqueBytes) }}</dd></div>
          <div><dt>Logical</dt><dd>{{ formatBytes(logicalBytes) }}</dd></div>
          <div><dt>Files</dt><dd>{{ readyFiles.length }}<span v-if="isBusy"> / {{ files.length }}</span></dd></div>
        </dl>
      </section>

      <section class="cas-map" aria-labelledby="cas-map-title">
        <header class="cas-map__header">
          <h3 id="cas-map-title">Byte map</h3>
          <div class="cas-legend" aria-label="Byte map legend">
            <span><i class="is-shared" />Shared</span>
            <span><i class="is-unique" />Unique</span>
            <span><i class="is-match" />Selected</span>
          </div>
        </header>
        <article
          v-for="record in files"
          :key="record.id"
          :class="['cas-file', { 'is-selected': record.id === selectedFileId }]"
          @click="selectedFileId = record.id"
        >
          <header>
            <button type="button" class="cas-file__name" @click.stop="selectedFileId = record.id">
              <span class="cas-file__kind">{{ record.analysis?.pdfSignature ? 'PDF' : fileKind(record) }}</span>
              <span>
                <strong>{{ record.file.name }}</strong>
                <small>
                  {{ formatBytes(record.file.size) }}
                  <template v-if="record.analysis"> · {{ record.analysis.strategyLabel }}</template>
                  <template v-if="record.variantRole === 'canonical'"> · canonical source</template>
                  <template v-else-if="record.variantRole === 'edited'"> · edited variant</template>
                </small>
              </span>
            </button>
            <div class="cas-file__result">
              <template v-if="record.state === 'ready'">
                <strong>{{ formatPercent(fileSharedRatio(record)) }}</strong><span>shared</span>
              </template>
              <template v-else-if="record.state === 'error'"><strong class="is-error">Failed</strong></template>
              <template v-else><strong>{{ formatPercent(record.progress) }}</strong><span>reading</span></template>
            </div>
            <div class="cas-file__actions">
              <button
                v-if="record.variantRole !== 'source'"
                type="button"
                class="cas-icon-button"
                :aria-label="`Download ${record.file.name}`"
                title="Download generated PDF"
                @click.stop="downloadFile(record)"
              >
                <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 4v11m0 0-4-4m4 4 4-4M5 20h14" /></svg>
              </button>
              <button type="button" class="cas-icon-button" :aria-label="`Remove ${record.file.name}`" @click.stop="removeFile(record.id)">
                <svg aria-hidden="true" viewBox="0 0 24 24"><path d="m6 6 12 12M18 6 6 18" /></svg>
              </button>
            </div>
          </header>

          <div v-if="record.analysis" class="cas-track-wrap">
            <svg
              class="cas-track"
              viewBox="0 0 1000 28"
              preserveAspectRatio="none"
              aria-hidden="true"
              @click.stop="selectTrackBlock(record, $event)"
            >
              <rect class="cas-track__base" x="0" y="5" width="1000" height="18" rx="3" />
              <rect
                v-for="block in record.blocks"
                :key="`${record.id}:${block.index}`"
                :class="blockClass(record, block)"
                :x="trackX(record, block)"
                y="5"
                :width="trackWidth(record, block)"
                height="18"
              >
                <title>{{ rangeLabel(block) }} · {{ shortHash(block.digestHex) }}</title>
              </rect>
            </svg>
            <span v-if="record.analysis.mediaTypeMismatch" class="cas-mismatch">
              {{ record.analysis.advertisedMediaType }} did not match the bytes
            </span>
          </div>
          <div v-else-if="record.state === 'error'" class="cas-file__error" role="alert">
            {{ record.error }} <button type="button" @click.stop="queueAnalysis(record)">Try again</button>
          </div>
          <div v-else class="cas-progress" aria-hidden="true"><span :style="{ transform: `scaleX(${record.progress})` }" /></div>
        </article>
      </section>

      <section v-if="selectedBlock" class="cas-selection" aria-labelledby="cas-selection-title">
        <div>
          <h3 id="cas-selection-title">{{ rangeLabel(selectedBlock.block) }}</h3>
          <code>{{ selectedBlock.block.contentId }}</code>
        </div>
        <ul>
          <li v-for="match in selectedMatches" :key="`${match.record.id}:${match.block.index}`">
            <strong>{{ match.record.file.name }}</strong><span>{{ rangeLabel(match.block) }}</span>
          </li>
        </ul>
      </section>

      <section
        v-if="!compact && selectedRecord?.analysis?.pdfSignature && selectedRecord.variantRole === 'source'"
        class="cas-pdf"
        aria-labelledby="cas-pdf-title"
      >
        <header>
          <div>
            <h3 id="cas-pdf-title">Make a font variant</h3>
            <p>Choose two embedded resources. Unsafe replacements stop before a PDF is written.</p>
          </div>
          <span :class="['cas-pdf__state', selectedRecord.fontState]">{{ fontStateLabel(selectedRecord) }}</span>
        </header>

        <details v-if="selectedRecord.fontState === 'ready' && selectedRecord.fonts.length" class="cas-font-inventory">
          <summary>{{ selectedRecord.fonts.length }} embedded font resources</summary>
          <ul>
            <li v-for="font in selectedRecord.fonts" :key="`font:${font.objectNumber}`">
              <code>{{ cleanFontName(font.baseFont) }}</code>
              <span>#{{ font.objectNumber }} /{{ font.subtype || 'unknown' }}</span>
              <small>{{ font.remapCandidate ? 'remap candidate' : 'inspect only' }}</small>
            </li>
          </ul>
        </details>

        <div v-if="selectedRecord.fontState === 'ready' && fontOptions(selectedRecord).length" class="cas-fonts">
          <label>
            <span>Source font</span>
            <select v-model="selectedRecord.sourceFont" @change="selectDefaultTarget(selectedRecord)">
              <option v-for="font in fontOptions(selectedRecord)" :key="`source:${font.baseFont}`" :value="font.baseFont">
                {{ fontOptionLabel(font) }}
              </option>
            </select>
          </label>
          <label>
            <span>Replacement font</span>
            <select v-model="selectedRecord.targetFont" @change="selectedRecord.transformError = ''">
              <option
                v-for="font in targetFonts(selectedRecord)"
                :key="`target:${font.baseFont}`"
                :value="font.baseFont"
              >
                {{ fontOptionLabel(font) }}
              </option>
            </select>
          </label>
          <button
            type="button"
            class="cas-button cas-button--primary"
            :disabled="!canTransform(selectedRecord) || selectedRecord.transformState === 'running'"
            @click="createFontVariant(selectedRecord)"
          >
            {{ selectedRecord.transformState === 'running' ? 'Building variant…' : 'Build variant' }}
          </button>
        </div>
        <p v-else-if="selectedRecord.fontState === 'ready'" class="cas-pdf__empty">
          {{ selectedRecord.fonts.length ? 'Embedded fonts were found, but none are eligible for a safe existing-resource remap.' : 'No embedded font resources were found in this PDF.' }}
        </p>
        <p v-if="selectedRecord.file.size > MAX_EDITABLE_PDF_BYTES" class="cas-pdf__empty">
          Font inventory is streamed, but browser rewriting is capped at 32 MiB.
        </p>
        <p v-if="selectedRecord.transformError" class="cas-file__error" role="alert">{{ selectedRecord.transformError }}</p>
      </section>

      <section v-if="!compact && selectedRecord?.analysis" class="cas-ranges" aria-labelledby="cas-ranges-title">
        <header>
          <h3 id="cas-ranges-title">{{ selectedRecord.file.name }}</h3>
          <div class="cas-range-filter" aria-label="Range filter">
            <button type="button" :class="{ active: rangeFilter === 'all' }" :aria-pressed="rangeFilter === 'all'" @click="setRangeFilter('all')">All</button>
            <button type="button" :class="{ active: rangeFilter === 'shared' }" :aria-pressed="rangeFilter === 'shared'" @click="setRangeFilter('shared')">Shared</button>
            <button type="button" :class="{ active: rangeFilter === 'unique' }" :aria-pressed="rangeFilter === 'unique'" @click="setRangeFilter('unique')">Unique</button>
          </div>
        </header>
        <div class="cas-range-table-wrap">
          <table>
            <thead><tr><th>Range</th><th>Bytes</th><th>Cut</th><th>SHA-256</th><th>Matches</th></tr></thead>
            <tbody>
              <tr
                v-for="block in visibleRanges"
                :key="`range:${block.index}`"
                :class="{ selected: selectedBlockKey === block.contentId }"
              >
                <td><button type="button" class="cas-range-button" @click="selectBlock(selectedRecord, block)">{{ rangeLabel(block) }}</button></td>
                <td>{{ formatBytes(block.length) }}</td>
                <td>{{ cutLabel(block.cut) }}</td>
                <td><code>{{ shortHash(block.digestHex) }}</code></td>
                <td>{{ otherFileCount(selectedRecord, block) || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav v-if="rangePageCount > 1" class="cas-range-pages" aria-label="Block range pages">
          <button type="button" :disabled="currentRangePage === 0" @click="rangePage = currentRangePage - 1">Previous</button>
          <span aria-live="polite">{{ rangeStart.toLocaleString() }}–{{ rangeEnd.toLocaleString() }} of {{ filteredRanges.length.toLocaleString() }}</span>
          <button type="button" :disabled="currentRangePage >= rangePageCount - 1" @click="rangePage = currentRangePage + 1">Next</button>
        </nav>
      </section>
    </template>

  </section>
</template>

<script setup lang="ts">
import { withBase } from 'vitepress'
import { computed, onBeforeUnmount, reactive, ref } from 'vue'

type BlockResult = {
  index: number
  start: number
  endExclusive: number
  length: number
  digestHex: string
  contentId: string
  cut: string
  duplicateWithinFile: boolean
}

type FileAnalysis = {
  byteCount: number
  contentId: string
  digestHex: string
  advertisedMediaType: string
  confirmedMediaType: string
  pdfSignature: boolean
  mediaTypeMismatch: boolean
  strategy: string
  strategyLabel: string
  targetBytes: number
  maximumBlockBytes: number
  maximumOwnedBytes: number
  uniqueBlocks: number
  duplicateBlocks: number
  blocks: BlockResult[]
  engine: string
}

type FontInfo = { objectNumber: number; baseFont: string; subtype: string | null; remapCandidate: boolean }
type FontOption = { baseFont: string; subtype: string | null; objectNumbers: number[] }
type FontInventory = { fonts: FontInfo[]; engine: string }
type FontReplacement = {
  canonicalBytes: Uint8Array
  bytes: Uint8Array
  sourceBaseFont: string
  targetBaseFont: string
  sourceObjectNumbers: number[]
  targetObjectNumber: number
  resourceBindingsRewritten: number
  engine: string
}

type ContentLabModule = {
  analyzeGravitonFile(file: File, strategy: string, targetBytes: number, onBlock: (block: BlockResult) => void): Promise<FileAnalysis>
}
type PdfLabModule = {
  inspectGravitonPdf(file: File): Promise<FontInventory>
  remapGravitonPdfFont(file: File, fromBaseFont: string, toBaseFont: string): Promise<FontReplacement>
}

type FileState = 'queued' | 'analyzing' | 'ready' | 'error'
type VariantRole = 'source' | 'canonical' | 'edited'
type FontState = 'idle' | 'loading' | 'ready' | 'error'
type TransformState = 'idle' | 'running' | 'error'
type FileRecord = {
  id: string
  file: File
  variantRole: VariantRole
  originId?: string
  state: FileState
  progress: number
  error: string
  analysis?: FileAnalysis
  blocks: BlockResult[]
  fontState: FontState
  fonts: FontInfo[]
  sourceFont: string
  targetFont: string
  fontError: string
  transformState: TransformState
  transformError: string
}

const props = withDefaults(defineProps<{ compact?: boolean }>(), { compact: false })
const compact = computed(() => props.compact)
const files = ref<FileRecord[]>([])
const selectedFileId = ref('')
const selectedBlockKey = ref('')
const strategy = ref('auto')
const targetBytes = ref(65536)
const dragging = ref(false)
const rangeFilter = ref<'all' | 'shared' | 'unique'>('all')
const rangePage = ref(0)
const runtimeState = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
const runtimeLabel = ref('Starts locally when you choose a file')
const MAX_EDITABLE_PDF_BYTES = 32 * 1024 * 1024
const queue: FileRecord[] = []
let running = 0
let nextId = 0
let disposed = false
let modulePromise: Promise<ContentLabModule> | undefined
let pdfModulePromise: Promise<PdfLabModule> | undefined

const readyFiles = computed(() => files.value.filter(record => record.state === 'ready' && record.analysis))
const isBusy = computed(() => files.value.some(record => record.state === 'queued' || record.state === 'analyzing'))
const occurrences = computed(() => {
  const result = new Map<string, Array<{ record: FileRecord; block: BlockResult }>>()
  for (const record of readyFiles.value) {
    for (const block of record.blocks) {
      const group = result.get(block.contentId) || []
      group.push({ record, block })
      result.set(block.contentId, group)
    }
  }
  return result
})
const logicalBytes = computed(() => readyFiles.value.reduce((total, record) => total + (record.analysis?.byteCount || 0), 0))
const uniqueBytes = computed(() => {
  let total = 0
  for (const group of occurrences.value.values()) total += group[0]?.block.length || 0
  return total
})
const reusedBytes = computed(() => Math.max(0, logicalBytes.value - uniqueBytes.value))
const reuseRatio = computed(() => (logicalBytes.value ? reusedBytes.value / logicalBytes.value : 0))
const runtimeStatus = computed(() => {
  if (runtimeState.value === 'loading') return 'Reading locally'
  if (runtimeState.value === 'ready') return 'Local runtime ready'
  if (runtimeState.value === 'error') return 'Analysis failed'
  return 'Local, nothing uploaded'
})
const profileLabel = computed(() => {
  const strategies: Record<string, string> = { auto: 'Auto', pdf: 'PDF', fastcdc: 'FastCDC', fixed: 'Fixed' }
  return `${strategies[strategy.value] || strategy.value} · ${formatBytes(targetBytes.value)}`
})
const comparisonStatement = computed(() => {
  if (isBusy.value) return `Reading ${files.value.length === 1 ? '1 file' : `${files.value.length} files`} without loading them all into memory.`
  if (readyFiles.value.length < 2) {
    return selectedRecord.value?.analysis?.pdfSignature && !compact.value
      ? 'Add another file, or build a font variant below.'
      : 'Add another file to reveal exact reusable content.'
  }
  if (reusedBytes.value > 0) return `${formatBytes(reusedBytes.value)} is already shared across this set.`
  return 'No exact block overlap at this profile.'
})
const selectedRecord = computed(() => files.value.find(record => record.id === selectedFileId.value))
const selectedBlock = computed(() => {
  const record = selectedRecord.value
  if (!record || !selectedBlockKey.value) return undefined
  const block = record.blocks.find(candidate => candidate.contentId === selectedBlockKey.value)
  return block ? { record, block } : undefined
})
const selectedMatches = computed(() => selectedBlock.value ? occurrences.value.get(selectedBlock.value.block.contentId) || [] : [])
const filteredRanges = computed(() => {
  const record = selectedRecord.value
  if (!record) return []
  return record.blocks.filter(block => {
    const shared = otherFileCount(record, block) > 0
    return rangeFilter.value === 'all' || (rangeFilter.value === 'shared' ? shared : !shared)
  })
})
const RANGE_PAGE_SIZE = 240
const rangePageCount = computed(() => Math.max(1, Math.ceil(filteredRanges.value.length / RANGE_PAGE_SIZE)))
const currentRangePage = computed(() => Math.min(rangePage.value, rangePageCount.value - 1))
const rangeStart = computed(() => filteredRanges.value.length ? currentRangePage.value * RANGE_PAGE_SIZE + 1 : 0)
const rangeEnd = computed(() => Math.min(filteredRanges.value.length, rangeStart.value + RANGE_PAGE_SIZE - 1))
const visibleRanges = computed(() => {
  const start = currentRangePage.value * RANGE_PAGE_SIZE
  return filteredRanges.value.slice(start, start + RANGE_PAGE_SIZE)
})

function loadContentLab(): Promise<ContentLabModule> {
  if (!modulePromise) {
    // Generated by sbt before Vite builds the documentation site.
    // @ts-expect-error generated Scala.js module has no handwritten declaration file
    modulePromise = import('../../generated/content-lab/main.js') as Promise<ContentLabModule>
  }
  return modulePromise
}

function loadPdfLab(): Promise<PdfLabModule> {
  if (!pdfModulePromise) {
    // Generated separately so ordinary file analysis does not load the document editor.
    // @ts-expect-error generated Scala.js module has no handwritten declaration file
    pdfModulePromise = import('../../generated/pdf-lab/main.js') as Promise<PdfLabModule>
  }
  return pdfModulePromise
}

function makeRecord(file: File, variantRole: VariantRole = 'source', originId?: string): FileRecord {
  return {
    id: `file-${++nextId}`, file, variantRole, originId, state: 'queued', progress: 0, error: '', blocks: [],
    fontState: 'idle', fonts: [], sourceFont: '', targetFont: '', fontError: '',
    transformState: 'idle', transformError: ''
  }
}

function addFiles(incoming: File[], variantRole: VariantRole = 'source', originId?: string) {
  const records = incoming.map(file => reactive(makeRecord(file, variantRole, originId)) as FileRecord)
  files.value.push(...records)
  if (!selectedFileId.value && records[0]) selectedFileId.value = records[0].id
  for (const record of records) queueAnalysis(record)
}

function queueAnalysis(record: FileRecord) {
  if (!files.value.some(candidate => candidate.id === record.id)) return
  record.state = 'queued'
  record.progress = 0
  record.error = ''
  record.analysis = undefined
  record.blocks = []
  queue.push(record)
  pumpQueue()
}

function pumpQueue() {
  while (!disposed && running < 2 && queue.length) {
    const record = queue.shift()!
    if (!files.value.some(candidate => candidate.id === record.id)) continue
    running += 1
    void analyze(record).finally(() => { running -= 1; pumpQueue() })
  }
}

async function analyze(record: FileRecord) {
  record.state = 'analyzing'
  runtimeState.value = 'loading'
  runtimeLabel.value = 'Loading Scala.js…'
  try {
    const contentLab = await loadContentLab()
    if (!files.value.some(candidate => candidate.id === record.id)) return
    runtimeState.value = 'ready'
    runtimeLabel.value = 'Scala.js ready'
    const analysis = await contentLab.analyzeGravitonFile(record.file, strategy.value, targetBytes.value, block => {
      if (!files.value.some(candidate => candidate.id === record.id)) return
      record.blocks.push(block)
      record.progress = record.file.size ? Math.min(1, block.endExclusive / record.file.size) : 1
    })
    if (!files.value.some(candidate => candidate.id === record.id)) return
    record.analysis = analysis
    record.blocks = analysis.blocks
    record.progress = 1
    record.state = 'ready'
    runtimeLabel.value = analysis.engine
    if (analysis.pdfSignature && !compact.value && record.variantRole === 'source') void inspectFonts(record)
  } catch (cause) {
    if (!files.value.some(candidate => candidate.id === record.id)) return
    record.state = 'error'
    record.error = errorMessage(cause)
    runtimeState.value = 'error'
    runtimeLabel.value = 'Analysis failed'
  }
}

async function inspectFonts(record: FileRecord) {
  record.fontState = 'loading'
  record.fontError = ''
  try {
    const pdfLab = await loadPdfLab()
    const inventory = await pdfLab.inspectGravitonPdf(record.file)
    if (!files.value.some(candidate => candidate.id === record.id)) return
    record.fonts = inventory.fonts
    selectInitialFonts(record)
    record.fontState = 'ready'
  } catch (cause) {
    record.fontState = 'error'
    record.fontError = errorMessage(cause)
  }
}

async function createFontVariant(record: FileRecord) {
  if (!canTransform(record)) return
  record.transformState = 'running'
  record.transformError = ''
  try {
    const pdfLab = await loadPdfLab()
    const replacement = await pdfLab.remapGravitonPdfFont(record.file, record.sourceFont, record.targetFont)
    const stem = record.file.name.replace(/\.pdf$/i, '')
    const target = cleanFontName(replacement.targetBaseFont).replace(/[^A-Za-z0-9._-]+/g, '-')
    const canonical = new File([replacement.canonicalBytes], `${stem}-canonical.pdf`, { type: 'application/pdf' })
    const variant = new File([replacement.bytes], `${stem}-${target}.pdf`, { type: 'application/pdf' })
    files.value = files.value.filter(candidate => candidate.originId !== record.id)
    strategy.value = 'auto'
    targetBytes.value = 16384
    for (const candidate of files.value) queueAnalysis(candidate)
    addFiles([canonical], 'canonical', record.id)
    addFiles([variant], 'edited', record.id)
    selectedFileId.value = files.value.at(-1)?.id || record.id
    record.transformState = 'idle'
  } catch (cause) {
    record.transformState = 'error'
    record.transformError = transformErrorMessage(cause)
  }
}

function canTransform(record: FileRecord) {
  return record.file.size <= MAX_EDITABLE_PDF_BYTES && Boolean(record.sourceFont && record.targetFont && record.sourceFont !== record.targetFont)
}
function setRangeFilter(filter: 'all' | 'shared' | 'unique') { rangeFilter.value = filter; rangePage.value = 0 }
function fontOptions(record: FileRecord): FontOption[] {
  const grouped = new Map<string, FontOption>()
  for (const font of record.fonts.filter(candidate => candidate.remapCandidate)) {
    const existing = grouped.get(font.baseFont)
    if (existing) existing.objectNumbers.push(font.objectNumber)
    else grouped.set(font.baseFont, { baseFont: font.baseFont, subtype: font.subtype, objectNumbers: [font.objectNumber] })
  }
  return Array.from(grouped.values())
}
function targetFonts(record: FileRecord) {
  return fontOptions(record).filter(font => font.baseFont !== record.sourceFont && font.objectNumbers.length === 1)
}
function selectDefaultTarget(record: FileRecord) {
  const targets = targetFonts(record)
  record.targetFont = targets.find(font => cleanFontName(font.baseFont) === cleanFontName(record.sourceFont))?.baseFont || targets[0]?.baseFont || ''
  record.transformError = ''
}
function selectInitialFonts(record: FileRecord) {
  const sources = fontOptions(record)
  const matchingSource = sources.find(source =>
    sources.some(target => target.baseFont !== source.baseFont && target.objectNumbers.length === 1 && cleanFontName(target.baseFont) === cleanFontName(source.baseFont))
  )
  record.sourceFont = matchingSource?.baseFont || sources[0]?.baseFont || ''
  selectDefaultTarget(record)
}
function fontOptionLabel(font: FontOption) {
  const resource = font.objectNumbers.length === 1 ? `#${font.objectNumbers[0]}` : `${font.objectNumbers.length} resources`
  return `${cleanFontName(font.baseFont)} · ${resource} /${font.subtype || 'unknown'}`
}
function fontStateLabel(record: FileRecord) {
  if (record.fontState === 'loading') return 'Reading fonts…'
  if (record.fontState === 'ready') return `${record.fonts.length} font resources`
  if (record.fontState === 'error') return record.fontError || 'Font inspection failed'
  return 'Waiting'
}
function handleFileInput(event: Event) {
  const input = event.currentTarget as HTMLInputElement
  if (input.files?.length) addFiles(Array.from(input.files))
  input.value = ''
}
function handleDrop(event: DragEvent) {
  dragging.value = false
  if (event.dataTransfer?.files.length) addFiles(Array.from(event.dataTransfer.files))
}
function removeFile(id: string) {
  files.value = files.value.filter(record => record.id !== id)
  if (selectedFileId.value === id) selectedFileId.value = files.value[0]?.id || ''
  if (!files.value.length) selectedBlockKey.value = ''
}
function downloadFile(record: FileRecord) {
  const url = URL.createObjectURL(record.file)
  const link = document.createElement('a')
  link.href = url
  link.download = record.file.name
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}
function reanalyzeAll() { selectedBlockKey.value = ''; for (const record of files.value) queueAnalysis(record) }
function selectTrackBlock(record: FileRecord, event: MouseEvent) {
  if (!record.analysis || !record.blocks.length) return
  const rect = (event.currentTarget as SVGElement).getBoundingClientRect()
  const offset = ((event.clientX - rect.left) / rect.width) * record.analysis.byteCount
  const block = record.blocks.find(candidate => offset >= candidate.start && offset < candidate.endExclusive)
  if (block) selectBlock(record, block)
}
function selectBlock(record: FileRecord, block: BlockResult) { selectedFileId.value = record.id; selectedBlockKey.value = block.contentId }
function otherFileCount(record: FileRecord, block: BlockResult) {
  const group = occurrences.value.get(block.contentId) || []
  return new Set(group.filter(item => item.record.id !== record.id).map(item => item.record.id)).size
}
function fileSharedBytes(record: FileRecord) { return record.blocks.reduce((total, block) => total + (otherFileCount(record, block) ? block.length : 0), 0) }
function fileSharedRatio(record: FileRecord) { return record.analysis?.byteCount ? fileSharedBytes(record) / record.analysis.byteCount : 0 }
function blockClass(record: FileRecord, block: BlockResult) {
  return {
    'cas-track__block': true,
    'is-shared': otherFileCount(record, block) > 0,
    'is-match': selectedBlockKey.value === block.contentId,
    'is-unique': otherFileCount(record, block) === 0
  }
}
function trackX(record: FileRecord, block: BlockResult) { return record.analysis?.byteCount ? (block.start / record.analysis.byteCount) * 1000 : 0 }
function trackWidth(record: FileRecord, block: BlockResult) { return record.analysis?.byteCount ? Math.max(0.65, (block.length / record.analysis.byteCount) * 1000) : 0 }
function formatBytes(value: number) {
  if (!Number.isFinite(value) || value < 0) return '—'
  if (value < 1024) return `${Math.round(value)} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let scaled = value
  let index = -1
  do { scaled /= 1024; index += 1 } while (scaled >= 1024 && index < units.length - 1)
  return `${scaled >= 100 ? scaled.toFixed(0) : scaled >= 10 ? scaled.toFixed(1) : scaled.toFixed(2)} ${units[index]}`
}
function formatPercent(value: number) {
  if (!Number.isFinite(value)) return '0%'
  return `${(Math.max(0, Math.min(1, value)) * 100).toFixed(value > 0 && value < 0.01 ? 1 : 0)}%`
}
function rangeLabel(block: BlockResult) { return `${block.start.toLocaleString()}–${block.endExclusive.toLocaleString()}` }
function shortHash(value: string) { return value ? `${value.slice(0, 12)}…${value.slice(-8)}` : '—' }
function cleanFontName(value: string) { return value.replace(/^\/+/, '').replace(/^[A-Z]{6}\+/, '') }
function fileKind(record: FileRecord) { return record.file.name.split('.').pop()?.slice(0, 4).toUpperCase() || 'FILE' }
function cutLabel(value: string) {
  return ({ 'pdf-object': 'PDF object', 'pdf-fallback': 'PDF fallback', 'content-defined': 'FastCDC', fixed: 'Fixed', maximum: 'Maximum', remainder: 'Remainder' } as Record<string, string>)[value] || value
}
function errorMessage(cause: unknown) {
  if (cause instanceof Error) return cause.message
  if (typeof cause === 'object' && cause && 'message' in cause) return String((cause as { message: unknown }).message)
  return String(cause)
}
function transformErrorMessage(cause: unknown) {
  const detail = errorMessage(cause)
  const field = detail.match(/not code-and-metric compatible .* at \/([^\s]+)/)?.[1]
  if (field) return `No PDF was created. These resources differ at /${field}, so rebinding would change text or layout.`
  if (detail.includes('resolves to more than one document font')) {
    return 'No PDF was created. The replacement name is ambiguous in this document.'
  }
  return detail
}
onBeforeUnmount(() => { disposed = true; queue.splice(0) })
</script>

<style scoped>
.cas-lab {
  --cas-line: color-mix(in srgb, var(--graviton-border) 82%, var(--vp-c-brand-1));
  container-type: inline-size;
  position: relative;
  margin: 2rem 0;
  border: 1px solid var(--cas-line);
  border-radius: 14px;
  background: color-mix(in srgb, var(--graviton-panel) 97%, var(--vp-c-brand-soft));
  box-shadow: 0 28px 72px -52px color-mix(in srgb, var(--vp-c-brand-1) 48%, transparent);
  color: var(--graviton-ink);
}
.cas-lab__bar { display: flex; align-items: center; justify-content: space-between; gap: 1.5rem; min-height: 88px; padding: 1.15rem 1.35rem; border-bottom: 1px solid var(--cas-line); }
.cas-lab__bar h2, .cas-map h3, .cas-selection h3, .cas-pdf h3, .cas-ranges h3 { margin: 0; border: 0; color: var(--graviton-ink); letter-spacing: -0.025em; line-height: 1.1; }
.cas-lab__bar h2 { max-width: 22ch; font-size: clamp(1.25rem, 2vw, 1.7rem); text-wrap: balance; }
.cas-runtime { display: inline-flex; align-items: center; gap: 0.45rem; margin-top: 0.42rem; color: color-mix(in srgb, var(--graviton-muted) 92%, var(--graviton-ink)); font-size: 0.72rem; }
.cas-runtime::before { width: 0.42rem; height: 0.42rem; flex: 0 0 auto; border-radius: 50%; background: var(--graviton-gold); content: ''; }
.cas-runtime.ready::before { background: var(--graviton-success); }
.cas-runtime.error::before { background: var(--graviton-danger); }
.cas-settings { position: relative; flex: 0 0 auto; }
.cas-settings summary { display: grid; grid-template-columns: 18px auto; gap: 0.05rem 0.55rem; min-height: 44px; padding: 0.45rem 0.65rem; border: 1px solid var(--cas-line); border-radius: 8px; color: var(--graviton-ink); cursor: pointer; list-style: none; }
.cas-settings summary::-webkit-details-marker { display: none; }
.cas-settings summary svg { grid-row: 1 / 3; align-self: center; width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-width: 1.6; }
.cas-settings summary span { font-size: 0.7rem; font-weight: 760; line-height: 1.2; }
.cas-settings summary strong { color: var(--graviton-muted); font-family: var(--vp-font-family-mono); font-size: 0.61rem; font-weight: 550; line-height: 1.2; }
.cas-settings[open] summary, .cas-settings summary:hover { border-color: var(--vp-c-brand-1); color: var(--vp-c-brand-1); }
.cas-profile { position: absolute; z-index: 12; top: calc(100% + 0.45rem); right: 0; display: grid; grid-template-columns: 150px 125px; gap: 0.7rem; width: max-content; padding: 0.85rem; border: 1px solid var(--cas-line); border-radius: 10px; background: var(--graviton-panel); box-shadow: 0 18px 42px -25px rgba(0, 0, 0, 0.72); }
.cas-profile label, .cas-fonts label { display: grid; gap: 0.28rem; }
.cas-profile label > span, .cas-fonts label > span { color: var(--graviton-muted); font-size: 0.66rem; font-weight: 750; }
.cas-profile select, .cas-fonts select { min-height: 44px; padding: 0.5rem 2rem 0.5rem 0.65rem; border: 1px solid var(--cas-line); border-radius: 7px; background: var(--graviton-panel); color: var(--graviton-ink); font: inherit; font-size: 0.76rem; }
.cas-profile .cas-button { grid-column: 1 / -1; }
.cas-button { min-height: 44px; padding: 0.5rem 0.8rem; border: 1px solid var(--cas-line); border-radius: 8px; background: var(--graviton-panel); color: var(--graviton-ink); cursor: pointer; font: inherit; font-size: 0.74rem; font-weight: 750; }
.cas-button:hover:not(:disabled) { border-color: var(--vp-c-brand-1); color: var(--vp-c-brand-1); }
.cas-button:disabled { cursor: not-allowed; opacity: 0.45; }
.cas-button--primary { border-color: var(--vp-c-brand-1); background: var(--vp-c-brand-1); color: var(--vp-c-bg); }
.cas-button--primary:hover:not(:disabled) { color: var(--vp-c-bg); filter: brightness(1.08); }
.cas-drop { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 0.9rem; align-items: center; min-height: 80px; padding: 1rem 1.35rem; border-bottom: 1px solid var(--cas-line); background: var(--graviton-panel-muted); cursor: pointer; transition: background-color 180ms ease, box-shadow 180ms ease; }
.cas-drop--empty { grid-template-columns: 64px minmax(0, 1fr) auto; min-height: 176px; background: linear-gradient(118deg, color-mix(in srgb, var(--vp-c-brand-soft) 78%, transparent), transparent 62%), var(--graviton-panel-muted); }
.cas-drop:hover, .cas-drop.is-dragging, .cas-drop:focus-within { background-color: color-mix(in srgb, var(--vp-c-brand-soft) 32%, var(--graviton-panel-muted)); box-shadow: inset 0 0 0 2px color-mix(in srgb, var(--vp-c-brand-1) 55%, transparent); }
.cas-drop input { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); white-space: nowrap; }
.cas-drop__mark { display: grid; width: 58px; height: 58px; place-items: center; }
.cas-drop__mark img { width: 54px; height: 54px; filter: drop-shadow(0 10px 18px color-mix(in srgb, var(--vp-c-brand-1) 25%, transparent)); }
.cas-drop__upload { width: 30px; height: 30px; fill: none; stroke: var(--vp-c-brand-1); stroke-linecap: round; stroke-linejoin: round; stroke-width: 1.7; }
.cas-drop__copy { display: grid; gap: 0.22rem; min-width: 0; }
.cas-drop__copy strong { color: var(--graviton-ink); font-size: clamp(0.95rem, 2vw, 1.15rem); letter-spacing: -0.015em; }
.cas-drop__copy > span { color: var(--graviton-muted); font-size: 0.73rem; line-height: 1.45; }
.cas-drop__action { min-height: 44px; padding: 0.7rem 0.9rem; border-radius: 7px; background: var(--vp-c-brand-1); color: var(--vp-c-bg); font-size: 0.72rem; font-weight: 800; line-height: 1.4; }
.cas-trust { display: flex; flex-wrap: wrap; gap: 0.45rem 1.4rem; margin: 0; padding: 0.82rem 1.35rem; color: var(--graviton-muted); font-size: 0.68rem; list-style: none; }
.cas-trust li { display: inline-flex; align-items: center; gap: 0.45rem; }
.cas-trust li::before { width: 4px; height: 4px; border-radius: 50%; background: var(--graviton-success); content: ''; }
.cas-overview { display: flex; align-items: center; justify-content: space-between; gap: 1.25rem; padding: 1rem 1.35rem; border-bottom: 1px solid var(--cas-line); background: color-mix(in srgb, var(--graviton-panel) 96%, var(--vp-c-brand-soft)); }
.cas-verdict { max-width: 34ch; margin: 0; color: var(--graviton-ink); font-size: 0.92rem; font-weight: 720; line-height: 1.35; text-wrap: balance; }
.cas-totals { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 0.65rem 1.15rem; margin: 0; }
.cas-totals > div { min-width: 62px; }
.cas-totals dt { color: var(--graviton-muted); font-size: 0.64rem; }
.cas-totals dd { margin: 0.22rem 0 0; color: var(--graviton-ink); font-family: var(--vp-font-family-mono); font-size: 0.82rem; font-variant-numeric: tabular-nums; }
.cas-totals__reuse dd { color: var(--graviton-cyan); }
.cas-totals__reuse dd span { margin-left: 0.3rem; color: var(--graviton-violet); }
.cas-map { padding: 0 1.35rem 0.2rem; }
.cas-map__header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 0.92rem 0 0.6rem; }
.cas-map h3 { font-size: 0.82rem; }
.cas-legend { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 0.8rem; color: var(--graviton-muted); font-size: 0.62rem; }
.cas-legend span { display: inline-flex; align-items: center; gap: 0.32rem; }
.cas-legend i { display: inline-block; width: 14px; height: 5px; border-radius: 2px; background: color-mix(in srgb, var(--graviton-muted) 34%, transparent); }
.cas-legend i.is-shared { background: var(--graviton-cyan); }
.cas-legend i.is-match { background: var(--graviton-violet); }
.cas-file { padding: 1rem 0; border-bottom: 1px solid var(--cas-line); }
.cas-file:last-child { border-bottom: 0; }
.cas-file.is-selected { background: linear-gradient(90deg, color-mix(in srgb, var(--vp-c-brand-soft) 76%, transparent), transparent 74%); }
.cas-file > header { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 0.85rem; align-items: center; }
.cas-file__name { display: flex; min-width: 0; gap: 0.75rem; align-items: center; padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; text-align: left; }
.cas-file__name > span:last-child { min-width: 0; }
.cas-file__name strong { display: block; overflow: hidden; font-size: 0.88rem; text-overflow: ellipsis; white-space: nowrap; }
.cas-file__name small { display: block; margin-top: 0.14rem; color: var(--graviton-muted); font-size: 0.67rem; }
.cas-file__kind { display: grid; width: 42px; height: 34px; flex: 0 0 auto; place-items: center; border: 1px solid color-mix(in srgb, var(--vp-c-brand-1) 28%, var(--cas-line)); border-radius: 8px; background: var(--vp-c-brand-soft); color: var(--vp-c-brand-1); font-family: var(--vp-font-family-mono); font-size: 0.66rem; font-weight: 800; }
.cas-file__result { display: grid; justify-items: end; min-width: 70px; }
.cas-file__result strong { font-family: var(--vp-font-family-mono); font-size: 0.8rem; font-variant-numeric: tabular-nums; }
.cas-file__result strong.is-error { color: var(--graviton-danger); }
.cas-file__result span { color: var(--graviton-muted); font-size: 0.62rem; }
.cas-file__actions { display: flex; gap: 0.2rem; }
.cas-icon-button { display: grid; width: 44px; height: 44px; place-items: center; border: 1px solid transparent; border-radius: 8px; background: transparent; color: var(--graviton-muted); cursor: pointer; }
.cas-icon-button:hover { border-color: var(--cas-line); color: var(--graviton-danger); }
.cas-icon-button svg { width: 18px; height: 18px; fill: none; stroke: currentColor; stroke-linecap: round; stroke-width: 1.7; }
.cas-track-wrap { margin: 0.72rem 0 0 3.55rem; }
.cas-track { display: block; width: 100%; height: 30px; cursor: crosshair; }
.cas-track__base { fill: color-mix(in srgb, var(--graviton-border) 62%, transparent); }
.cas-track__block { stroke: var(--graviton-panel); stroke-width: 0.75; vector-effect: non-scaling-stroke; }
.cas-track__block.is-unique { fill: color-mix(in srgb, var(--graviton-muted) 26%, transparent); }
.cas-track__block.is-shared { fill: var(--graviton-cyan); }
.cas-track__block.is-match { fill: var(--graviton-violet); stroke: var(--graviton-ink); stroke-width: 1.5; }
.cas-mismatch { color: var(--graviton-danger); font-size: 0.64rem; }
.cas-progress { height: 3px; margin: 0.8rem 0 0 3.55rem; overflow: hidden; background: var(--graviton-border); }
.cas-progress span { display: block; width: 100%; height: 100%; transform-origin: left; background: var(--vp-c-brand-1); transition: transform 160ms ease-out; }
.cas-file__error { margin: 0.75rem 0 0 3.55rem; color: var(--graviton-danger); font-size: 0.72rem; line-height: 1.5; }
.cas-file__error button { padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; font: inherit; font-weight: 750; text-decoration: underline; text-underline-offset: 3px; }
.cas-selection, .cas-pdf, .cas-ranges { border-top: 1px solid var(--cas-line); }
.cas-selection { display: grid; grid-template-columns: minmax(0, 1fr) minmax(220px, 0.65fr); gap: 1.5rem; padding: 1.25rem; background: color-mix(in srgb, var(--graviton-violet) 7%, var(--graviton-panel-muted)); }
.cas-selection h3 { font-size: 1rem; }
.cas-selection code { display: block; margin-top: 0.45rem; color: var(--graviton-violet); font-size: 0.66rem; overflow-wrap: anywhere; }
.cas-selection ul { display: grid; gap: 0.4rem; margin: 0; padding: 0; list-style: none; }
.cas-selection li { display: flex; justify-content: space-between; gap: 0.8rem; font-size: 0.68rem; }
.cas-selection li strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cas-selection li span { flex: 0 0 auto; color: var(--graviton-muted); font-family: var(--vp-font-family-mono); }
.cas-pdf { padding: 1.35rem; }
.cas-pdf > header, .cas-ranges > header { display: flex; justify-content: space-between; gap: 1rem; align-items: flex-start; }
.cas-pdf h3, .cas-ranges h3 { font-size: 1.05rem; }
.cas-pdf header p { max-width: 66ch; margin: 0.35rem 0 0; color: var(--graviton-muted); font-size: 0.71rem; line-height: 1.5; }
.cas-pdf__state { max-width: 240px; color: var(--graviton-muted); font-family: var(--vp-font-family-mono); font-size: 0.66rem; text-align: right; }
.cas-pdf__state.ready { color: var(--graviton-success); }
.cas-pdf__state.error { color: var(--graviton-danger); }
.cas-font-inventory { margin-top: 0.9rem; border: 1px solid var(--cas-line); border-radius: 8px; background: var(--graviton-panel-muted); }
.cas-font-inventory summary { min-height: 44px; padding: 0.72rem 0.8rem; color: var(--graviton-muted); cursor: pointer; font-size: 0.7rem; font-weight: 750; }
.cas-font-inventory ul { max-height: 250px; margin: 0; padding: 0 0.8rem 0.8rem; overflow: auto; list-style: none; }
.cas-font-inventory li { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 0.7rem; align-items: center; padding: 0.48rem 0; border-top: 1px solid var(--cas-line); font-size: 0.65rem; }
.cas-font-inventory code { overflow: hidden; color: var(--graviton-ink); text-overflow: ellipsis; white-space: nowrap; }
.cas-font-inventory span, .cas-font-inventory small { color: var(--graviton-muted); font-family: var(--vp-font-family-mono); }
.cas-font-inventory small { color: var(--graviton-cyan); }
.cas-fonts { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: 0.75rem; align-items: end; margin-top: 1rem; }
.cas-fonts select { width: 100%; }
.cas-pdf__empty { margin: 1rem 0 0; color: var(--graviton-muted); font-size: 0.74rem; }
.cas-ranges { padding: 1.35rem; background: var(--graviton-panel-muted); }
.cas-range-filter { display: flex; gap: 0.25rem; }
.cas-range-filter button { min-height: 44px; padding: 0.35rem 0.75rem; border: 1px solid transparent; border-radius: 7px; background: transparent; color: var(--graviton-muted); cursor: pointer; font: inherit; font-size: 0.68rem; font-weight: 750; }
.cas-range-filter button.active { border-color: var(--cas-line); background: var(--graviton-panel); color: var(--graviton-ink); }
.cas-range-table-wrap { width: 100%; margin-top: 0.8rem; overflow-x: auto; }
.cas-ranges table { width: 100%; min-width: 700px; margin: 0; border-collapse: collapse; font-variant-numeric: tabular-nums; }
.cas-ranges th, .cas-ranges td { padding: 0.62rem 0.7rem; border: 0; border-bottom: 1px solid var(--cas-line); text-align: left; }
.cas-ranges th { color: var(--graviton-muted); font-size: 0.62rem; font-weight: 750; }
.cas-ranges td { color: var(--graviton-muted); font-family: var(--vp-font-family-mono); font-size: 0.65rem; }
.cas-ranges tbody tr:hover, .cas-ranges tbody tr.selected { background: color-mix(in srgb, var(--graviton-violet) 9%, transparent); }
.cas-range-button { min-height: 44px; padding: 0; border: 0; background: transparent; color: inherit; cursor: pointer; font: inherit; text-decoration: underline; text-decoration-color: transparent; text-underline-offset: 3px; }
.cas-range-button:hover, .cas-range-button:focus-visible { color: var(--graviton-ink); text-decoration-color: currentColor; }
.cas-ranges code { color: var(--graviton-ink); font-size: inherit; }
.cas-range-pages { display: flex; align-items: center; justify-content: flex-end; gap: 0.75rem; margin-top: 0.75rem; }
.cas-range-pages button { min-height: 44px; padding: 0.35rem 0.75rem; border: 1px solid var(--cas-line); border-radius: 7px; background: var(--graviton-panel); color: var(--graviton-ink); cursor: pointer; font: inherit; font-size: 0.68rem; font-weight: 750; }
.cas-range-pages button:disabled { cursor: not-allowed; opacity: 0.45; }
.cas-range-pages span { color: var(--graviton-muted); font-family: var(--vp-font-family-mono); font-size: 0.67rem; font-variant-numeric: tabular-nums; }
.cas-lab--compact { margin: 0; }
.cas-lab--compact .cas-lab__bar { min-height: 80px; }
.cas-lab--compact .cas-drop--empty { min-height: 150px; }
@container (max-width: 760px) {
  .cas-overview { align-items: flex-start; flex-direction: column; }
  .cas-totals { justify-content: flex-start; }
  .cas-map { padding: 0 0.9rem; }
  .cas-file > header { gap: 0.55rem; }
  .cas-file__kind { width: 36px; }
  .cas-track-wrap, .cas-progress { margin-left: 3rem; }
  .cas-file__result { min-width: 54px; }
  .cas-selection { grid-template-columns: 1fr; }
  .cas-fonts { grid-template-columns: 1fr; }
  .cas-pdf > header { flex-direction: column; }
  .cas-pdf__state { max-width: none; text-align: left; }
}
@container (max-width: 480px) {
  .cas-lab { margin-right: -8px; margin-left: -8px; border-radius: 12px; }
  .cas-lab__bar, .cas-drop, .cas-overview, .cas-selection, .cas-pdf, .cas-ranges { padding: 1rem; }
  .cas-lab__bar { align-items: flex-start; }
  .cas-settings { margin-top: 0.05rem; }
  .cas-settings summary { grid-template-columns: 18px; width: 44px; padding: 0.45rem; place-content: center; }
  .cas-settings summary span, .cas-settings summary strong { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); clip-path: inset(50%); }
  .cas-profile { right: 0; grid-template-columns: minmax(118px, 1fr) minmax(105px, 0.8fr); max-width: calc(100cqi - 2rem); }
  .cas-drop--empty { grid-template-columns: 1fr; justify-items: start; min-height: 232px; }
  .cas-drop__mark { width: 52px; height: 52px; }
  .cas-drop__mark img { width: 48px; height: 48px; }
  .cas-drop__action { justify-self: stretch; width: 100%; text-align: center; }
  .cas-drop:not(.cas-drop--empty) { grid-template-columns: auto minmax(0, 1fr); }
  .cas-drop:not(.cas-drop--empty) .cas-drop__action { grid-column: 1 / -1; }
  .cas-trust { gap: 0.4rem 0.85rem; padding: 0.78rem 1rem; }
  .cas-totals { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); width: 100%; }
  .cas-map__header { align-items: flex-start; flex-direction: column; }
  .cas-legend { justify-content: flex-start; }
  .cas-ranges > header { align-items: stretch; flex-direction: column; }
  .cas-range-pages { justify-content: space-between; }
  .cas-file__name small { max-width: 42vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .cas-file__result span { display: none; }
  .cas-icon-button { width: 44px; height: 44px; }
  .cas-font-inventory li { grid-template-columns: minmax(0, 1fr) auto; gap: 0.25rem 0.6rem; }
  .cas-font-inventory small { grid-column: 1 / -1; }
  .cas-selection li { align-items: flex-start; flex-direction: column; gap: 0.1rem; }
}
@media (prefers-reduced-motion: reduce) { .cas-drop, .cas-progress span { transition: none; } }
</style>
