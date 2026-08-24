<template>
  <section class="pipeline-playground" aria-label="Deterministic pipeline model">
    <header class="pp-header">
      <div class="pp-header__info">
        <h3 class="pp-header__title">Transducer Pipeline Explorer</h3>
        <p class="pp-header__subtitle">
          Inspect composition with <code>&gt;&gt;&gt;</code> and <code>&amp;&amp;&amp;</code> using a deterministic browser worksheet.
        </p>
        <p class="pp-header__subtitle"><strong>MODEL ONLY • NO RUNTIME TELEMETRY</strong></p>
      </div>
      <div class="pp-header__controls">
        <button class="pp-btn" :class="{ active: running }" @click="toggleModel">
          {{ running ? 'Pause Model' : 'Run Model' }}
        </button>
        <button class="pp-btn pp-btn--ghost" @click="resetModel">Reset</button>
      </div>
    </header>

    <div class="pp-stage-selector">
      <p class="pp-stage-selector__label">Catalog stages</p>
      <div class="pp-stage-chips">
        <button
          v-for="stage in allStages"
          :key="stage.id"
          class="pp-stage-chip"
          :class="{ active: enabledStages.has(stage.id), disabled: stage.required }"
          :disabled="stage.required"
          @click="toggleStage(stage.id)"
        >
          <span class="pp-stage-chip__icon">{{ stage.icon }}</span>
          <span class="pp-stage-chip__name">{{ stage.name }}</span>
          <span v-if="stage.required" class="pp-stage-chip__badge">required</span>
        </button>
      </div>
    </div>

    <div class="pp-pipeline">
      <div class="pp-expression">
        <code class="pp-expression__code">{{ compositionExpression }}</code>
      </div>

      <div class="pp-stages">
        <div v-for="(stage, idx) in activeStages" :key="stage.id" class="pp-stage-wrapper">
          <div class="pp-stage" :class="{ active: running }" :style="{ '--stage-hue': stage.hue }">
            <div class="pp-stage__header">
              <span v-if="idx > 0" class="pp-connector__op">{{ stage.fanout ? '&amp;&amp;&amp;' : '&gt;&gt;&gt;' }}</span>
              <span class="pp-stage__icon">{{ stage.icon }}</span>
              <span class="pp-stage__name">{{ stage.name }}</span>
            </div>
            <div class="pp-stage__type"><code>{{ stage.typeSignature }}</code></div>
            <p class="pp-header__subtitle">{{ stage.status }}</p>

            <div class="pp-stage__metrics">
              <div v-for="field in stage.summaryFields" :key="field.name" class="pp-stage__metric">
                <span class="pp-stage__metric-label">{{ field.name }}</span>
                <span class="pp-stage__metric-value">{{ modeledValue(field.name) }}</span>
              </div>
            </div>

            <div class="pp-stage__throughput">
              <div class="pp-stage__throughput-bar">
                <div class="pp-stage__throughput-fill" :style="{ width: activityPct(idx) + '%' }" />
              </div>
              <span class="pp-stage__throughput-label">{{ running ? 'model active' : 'model idle' }}</span>
            </div>
          </div>
        </div>

        <div class="pp-output">
          <div class="pp-output__icon">Modeled summary</div>
          <div class="pp-output__fields">
            <div v-for="field in summaryFields" :key="field.key" class="pp-output__field">
              <span class="pp-output__field-name">{{ field.name }}</span>
              <span class="pp-output__field-value">{{ modeledValue(field.name) }}</span>
              <span class="pp-output__field-type">{{ field.type }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="pp-dataflow">
      <div class="pp-dataflow__header">
        <div>
          <h4>Worksheet assumptions</h4>
          <p class="pp-header__subtitle">
            Each step adds 64 KiB. Blocks are 2 MiB. With dedup enabled, every fourth modeled block is treated as a duplicate.
            Digests, compression, guard decisions, and verification are deliberately not fabricated.
          </p>
        </div>
        <div class="pp-dataflow__speed">
          <label>Model tick rate</label>
          <input v-model.number="speed" type="range" min="1" max="10" class="pp-dataflow__slider" />
          <span>{{ speed }}x</span>
        </div>
      </div>
    </div>

    <div class="pp-scenarios">
      <p class="pp-scenarios__label">Catalog scenarios</p>
      <div class="pp-scenarios__grid">
        <button
          v-for="scenario in scenarios"
          :key="scenario.id"
          class="pp-scenario"
          :class="{ active: activeScenario === scenario.id }"
          @click="loadScenario(scenario)"
        >
          <span class="pp-scenario__icon">{{ scenario.icon }}</span>
          <span class="pp-scenario__name">{{ scenario.name }}</span>
          <small class="pp-scenario__desc">{{ scenario.description }}</small>
        </button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'

type SummaryField = { name: string; type: string }

type StageDefinition = {
  id: string
  name: string
  icon: string
  typeSignature: string
  hue: number
  status: string
  required?: boolean
  fanout?: boolean
  summaryFields: SummaryField[]
}

type Scenario = {
  id: string
  name: string
  icon: string
  description: string
  stages: string[]
}

const allStages: StageDefinition[] = [
  {
    id: 'countBytes', name: 'Count Bytes', icon: '#', hue: 140, required: true,
    typeSignature: 'Chunk[Byte] => Chunk[Byte]', status: 'Implemented transducer',
    summaryFields: [{ name: 'totalBytes', type: 'Long' }]
  },
  {
    id: 'hashBytes', name: 'Hash Bytes', icon: 'H', hue: 180, required: true,
    typeSignature: 'Chunk[Byte] => Chunk[Byte]', status: 'Implemented transducer',
    summaryFields: [{ name: 'digestHex', type: 'String' }, { name: 'hashBytes', type: 'Long' }]
  },
  {
    id: 'rechunk', name: 'Rechunk', icon: 'R', hue: 200, required: true,
    typeSignature: 'Chunk[Byte] => Chunk[Byte]', status: 'Implemented transducer',
    summaryFields: [{ name: 'blockCount', type: 'Long' }, { name: 'rechunkFill', type: 'Int' }]
  },
  {
    id: 'blockKey', name: 'Block Key Deriver', icon: 'K', hue: 260,
    typeSignature: 'Chunk[Byte] => CanonicalBlock', status: 'Implemented transducer',
    summaryFields: [{ name: 'blocksKeyed', type: 'Long' }]
  },
  {
    id: 'dedup', name: 'Deduplication', icon: 'D', hue: 300,
    typeSignature: 'CanonicalBlock => CanonicalBlock', status: 'Implemented generic transducer',
    summaryFields: [{ name: 'uniqueCount', type: 'Long' }, { name: 'duplicateCount', type: 'Long' }]
  },
  {
    id: 'compress', name: 'Compress', icon: 'Z', hue: 40,
    typeSignature: 'Chunk[Byte] => Chunk[Byte]', status: 'Descriptor only, not wired',
    summaryFields: [{ name: 'compressedBytes', type: 'Long' }, { name: 'ratio', type: 'Double' }]
  },
  {
    id: 'bombGuard', name: 'Bomb Guard', icon: 'G', hue: 0,
    typeSignature: 'Chunk[Byte] => Chunk[Byte]', status: 'Implemented transducer',
    summaryFields: [{ name: 'totalSeen', type: 'Long' }, { name: 'rejected', type: 'Boolean' }]
  },
  {
    id: 'verify', name: 'Block Verifier', icon: 'V', hue: 120, fanout: true,
    typeSignature: 'Chunk[Byte] => VerifyResult', status: 'Implemented transducer',
    summaryFields: [{ name: 'verified', type: 'Long' }, { name: 'failed', type: 'Long' }]
  }
]

const scenarios: Scenario[] = [
  { id: 'basic-ingest', name: 'Basic Ingest', icon: 'IN', description: 'count >>> hash >>> rechunk', stages: ['countBytes', 'hashBytes', 'rechunk'] },
  { id: 'full-cas', name: 'Full CAS Pipeline', icon: 'CAS', description: 'count >>> hash >>> rechunk >>> key >>> dedup', stages: ['countBytes', 'hashBytes', 'rechunk', 'blockKey', 'dedup'] },
  { id: 'safe-ingest', name: 'Safe Ingest Model', icon: 'SFE', description: 'guard >>> count >>> hash >>> rechunk >>> compression descriptor', stages: ['bombGuard', 'countBytes', 'hashBytes', 'rechunk', 'compress'] },
  { id: 'verify-pipeline', name: 'Verify + Hash', icon: 'VFY', description: 'count &&& hash &&& verify', stages: ['countBytes', 'hashBytes', 'verify'] }
]

const running = ref(false)
const speed = ref(3)
const activeScenario = ref('basic-ingest')
const enabledStages = ref(new Set<string>())
const tick = ref(0)
const model = ref({ totalBytes: 0, blockCount: 0, blocksKeyed: 0, uniqueCount: 0, duplicateCount: 0 })

let animFrame = 0
let lastStep = 0

const activeStages = computed(() => allStages.filter(stage => enabledStages.value.has(stage.id)))

const compositionExpression = computed(() => activeStages.value.map((stage, index) => {
  const operator = index === 0 ? '' : stage.fanout ? ' &&& ' : ' >>> '
  return `${operator}${stage.name.replace(/\s+/g, '')}`
}).join('') || 'Select stages above')

const summaryFields = computed(() => activeStages.value.flatMap(stage =>
  stage.summaryFields.map(field => ({ ...field, key: `${stage.id}:${field.name}` }))
))

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1048576) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1048576).toFixed(1)} MiB`
}

function modeledValue(name: string): string {
  switch (name) {
    case 'totalBytes':
    case 'hashBytes':
    case 'totalSeen': return formatBytes(model.value.totalBytes)
    case 'blockCount': return `${model.value.blockCount}`
    case 'rechunkFill': return formatBytes(model.value.totalBytes % (2 * 1048576))
    case 'blocksKeyed': return `${model.value.blocksKeyed}`
    case 'uniqueCount': return `${model.value.uniqueCount}`
    case 'duplicateCount': return `${model.value.duplicateCount}`
    default: return 'not computed'
  }
}

function activityPct(index: number): number {
  return running.value ? 20 + ((tick.value * 7 + index * 19) % 76) : 0
}

function toggleStage(id: string) {
  const stage = allStages.find(candidate => candidate.id === id)
  if (stage?.required) return
  const next = new Set(enabledStages.value)
  next.has(id) ? next.delete(id) : next.add(id)
  enabledStages.value = next
  activeScenario.value = ''
  resetCounters()
}

function toggleModel() {
  running.value = !running.value
  if (running.value) {
    lastStep = performance.now()
    animFrame = requestAnimationFrame(animate)
  } else if (animFrame) {
    cancelAnimationFrame(animFrame)
  }
}

function resetCounters() {
  model.value = { totalBytes: 0, blockCount: 0, blocksKeyed: 0, uniqueCount: 0, duplicateCount: 0 }
  tick.value = 0
}

function resetModel() {
  running.value = false
  if (animFrame) cancelAnimationFrame(animFrame)
  resetCounters()
}

function loadScenario(scenario: Scenario) {
  resetModel()
  enabledStages.value = new Set(scenario.stages)
  activeScenario.value = scenario.id
}

function stepModel() {
  const blockSize = 2 * 1048576
  const before = model.value.totalBytes
  const totalBytes = before + 65536
  const crossedBlock = Math.floor(totalBytes / blockSize) > Math.floor(before / blockSize)
  const next = { ...model.value, totalBytes }

  if (crossedBlock) {
    next.blockCount += 1
    if (enabledStages.value.has('blockKey')) next.blocksKeyed += 1
    if (enabledStages.value.has('dedup')) {
      if (next.blockCount % 4 === 0) next.duplicateCount += 1
      else next.uniqueCount += 1
    }
  }

  model.value = next
  tick.value += 1
}

function animate(now: number) {
  if (!running.value) return
  if (now - lastStep >= 240 / speed.value) {
    stepModel()
    lastStep = now
  }
  animFrame = requestAnimationFrame(animate)
}

onMounted(() => loadScenario(scenarios[0]))
onUnmounted(() => {
  running.value = false
  if (animFrame) cancelAnimationFrame(animFrame)
})
</script>
