<template>
  <section class="pipeline-playground" aria-label="Interactive Pipeline Explorer">
    <!-- Header -->
    <header class="pp-header">
      <div class="pp-header__info">
        <h3 class="pp-header__title">Transducer Pipeline Explorer</h3>
        <p class="pp-header__subtitle">
          Compose stages with <code>&gt;&gt;&gt;</code> and <code>&amp;&amp;&amp;</code> — watch data flow in real time
        </p>
      </div>
      <div class="pp-header__controls">
        <button
          class="pp-btn"
          :class="{ active: running }"
          @click="toggleSimulation"
        >
          {{ running ? 'Pause' : 'Run Pipeline' }}
        </button>
        <button class="pp-btn pp-btn--ghost" @click="resetPipeline">
          Reset
        </button>
      </div>
    </header>

    <!-- Stage Selector -->
    <div class="pp-stage-selector">
      <p class="pp-stage-selector__label">Available Stages</p>
      <div class="pp-stage-chips">
        <button
          v-for="stage in availableStages"
          :key="stage.id"
          class="pp-stage-chip"
          :class="{ active: enabledStages.has(stage.id), disabled: stage.required }"
          @click="toggleStage(stage.id)"
          :disabled="stage.required"
        >
          <span class="pp-stage-chip__icon">{{ stage.icon }}</span>
          <span class="pp-stage-chip__name">{{ stage.name }}</span>
          <span v-if="stage.required" class="pp-stage-chip__badge">required</span>
        </button>
      </div>
    </div>

    <!-- Pipeline Visualization -->
    <div class="pp-pipeline" ref="pipelineRef">
      <!-- Composition expression -->
      <div class="pp-expression">
        <code class="pp-expression__code">{{ compositionExpression }}</code>
      </div>

      <!-- Pipeline stages -->
      <div class="pp-stages">
        <div
          v-for="(stage, idx) in activeStages"
          :key="stage.id"
          class="pp-stage-wrapper"
        >
          <!-- Connector arrow (between stages) -->
          <div v-if="idx > 0" class="pp-connector">
            <span class="pp-connector__op">{{ stage.fanout ? '&amp;&amp;&amp;' : '&gt;&gt;&gt;' }}</span>
            <div class="pp-connector__line">
              <div
                class="pp-connector__particle"
                v-for="p in particlesForConnector(idx)"
                :key="p.id"
                :style="{ left: p.x + '%', opacity: p.opacity }"
              />
            </div>
          </div>

          <!-- Stage card -->
          <div
            class="pp-stage"
            :class="{ active: stage.processing, highlight: stage.highlight }"
            :style="{ '--stage-hue': stage.hue }"
          >
            <div class="pp-stage__header">
              <span class="pp-stage__icon">{{ stage.icon }}</span>
              <span class="pp-stage__name">{{ stage.name }}</span>
            </div>

            <div class="pp-stage__type">
              <code>{{ stage.typeSignature }}</code>
            </div>

            <!-- Live metrics -->
            <div class="pp-stage__metrics" v-if="stage.metrics">
              <div
                v-for="(metric, mIdx) in stage.metrics"
                :key="mIdx"
                class="pp-stage__metric"
              >
                <span class="pp-stage__metric-label">{{ metric.label }}</span>
                <span class="pp-stage__metric-value" :class="{ pulse: metric.changed }">
                  {{ metric.displayValue }}
                </span>
              </div>
            </div>

            <!-- Throughput bar -->
            <div class="pp-stage__throughput">
              <div class="pp-stage__throughput-bar">
                <div
                  class="pp-stage__throughput-fill"
                  :style="{ width: stage.throughputPct + '%' }"
                />
              </div>
              <span class="pp-stage__throughput-label">{{ stage.throughputLabel }}</span>
            </div>
          </div>
        </div>

        <!-- Output terminal -->
        <div class="pp-connector" v-if="activeStages.length > 0">
          <span class="pp-connector__op">yield</span>
          <div class="pp-connector__line">
            <div
              class="pp-connector__particle"
              v-for="p in outputParticles"
              :key="p.id"
              :style="{ left: p.x + '%', opacity: p.opacity }"
            />
          </div>
        </div>

        <div class="pp-output">
          <div class="pp-output__icon">Summary</div>
          <div class="pp-output__fields">
            <div
              v-for="field in summaryFields"
              :key="field.name"
              class="pp-output__field"
              :class="{ fresh: field.fresh }"
            >
              <span class="pp-output__field-name">{{ field.name }}</span>
              <span class="pp-output__field-value">{{ field.value }}</span>
              <span class="pp-output__field-type">{{ field.type }}</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Data flow visualization -->
    <div class="pp-dataflow">
      <div class="pp-dataflow__header">
        <h4>Data Flow</h4>
        <div class="pp-dataflow__speed">
          <label>Speed</label>
          <input
            type="range"
            min="1"
            max="10"
            v-model.number="speed"
            class="pp-dataflow__slider"
          />
          <span>{{ speed }}x</span>
        </div>
      </div>
      <div class="pp-dataflow__stream">
        <div
          v-for="packet in visiblePackets"
          :key="packet.id"
          class="pp-dataflow__packet"
          :class="packet.type"
          :style="{
            left: packet.x + '%',
            top: packet.y + 'px',
            opacity: packet.opacity,
            transform: `scale(${packet.scale})`
          }"
        >
          <span class="pp-dataflow__packet-label">{{ packet.label }}</span>
        </div>
      </div>
      <div class="pp-dataflow__legend">
        <span class="pp-legend-item"><span class="pp-legend-dot bytes"></span> Raw Bytes</span>
        <span class="pp-legend-item"><span class="pp-legend-dot block"></span> Block</span>
        <span class="pp-legend-item"><span class="pp-legend-dot hash"></span> Hash</span>
        <span class="pp-legend-item"><span class="pp-legend-dot manifest"></span> Manifest</span>
      </div>
    </div>

    <!-- Scenario presets -->
    <div class="pp-scenarios">
      <p class="pp-scenarios__label">Try a scenario</p>
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

// --- Types ---

type StageMetric = {
  label: string
  value: number
  displayValue: string
  changed: boolean
}

type StageDefinition = {
  id: string
  name: string
  icon: string
  typeSignature: string
  hue: number
  required?: boolean
  fanout?: boolean
  summaryFields: Array<{ name: string; type: string }>
  baseMetrics: Array<{ label: string; base: number; unit: string; precision: number }>
}

type ActiveStage = StageDefinition & {
  processing: boolean
  highlight: boolean
  throughputPct: number
  throughputLabel: string
  metrics: StageMetric[]
}

type Particle = {
  id: number
  x: number
  opacity: number
}

type DataPacket = {
  id: number
  x: number
  y: number
  opacity: number
  scale: number
  label: string
  type: string
}

type Scenario = {
  id: string
  name: string
  icon: string
  description: string
  stages: string[]
}

// --- Stage Definitions ---

const allStages: StageDefinition[] = [
  {
    id: 'countBytes',
    name: 'Count Bytes',
    icon: '#',
    typeSignature: 'Chunk[Byte] => Chunk[Byte]',
    hue: 140,
    required: true,
    summaryFields: [{ name: 'totalBytes', type: 'Long' }],
    baseMetrics: [{ label: 'Total', base: 0, unit: 'B', precision: 0 }]
  },
  {
    id: 'hashBytes',
    name: 'Hash Bytes',
    icon: 'H',
    typeSignature: 'Chunk[Byte] => Chunk[Byte]',
    hue: 180,
    required: true,
    summaryFields: [
      { name: 'digestHex', type: 'String' },
      { name: 'hashBytes', type: 'Long' }
    ],
    baseMetrics: [
      { label: 'Digest', base: 0, unit: '', precision: 0 },
      { label: 'Hashed', base: 0, unit: 'B', precision: 0 }
    ]
  },
  {
    id: 'rechunk',
    name: 'Rechunk',
    icon: 'R',
    typeSignature: 'Chunk[Byte] => Chunk[Byte]',
    hue: 200,
    required: true,
    summaryFields: [
      { name: 'blockCount', type: 'Long' },
      { name: 'rechunkFill', type: 'Int' }
    ],
    baseMetrics: [
      { label: 'Blocks', base: 0, unit: '', precision: 0 },
      { label: 'Fill', base: 0, unit: '%', precision: 0 }
    ]
  },
  {
    id: 'blockKey',
    name: 'Block Key Deriver',
    icon: 'K',
    typeSignature: 'Chunk[Byte] => CanonicalBlock',
    hue: 260,
    summaryFields: [{ name: 'blocksKeyed', type: 'Long' }],
    baseMetrics: [{ label: 'Keyed', base: 0, unit: '', precision: 0 }]
  },
  {
    id: 'dedup',
    name: 'Deduplication',
    icon: 'D',
    typeSignature: 'CanonicalBlock => CanonicalBlock',
    hue: 300,
    summaryFields: [
      { name: 'fresh', type: 'Long' },
      { name: 'duplicate', type: 'Long' }
    ],
    baseMetrics: [
      { label: 'Fresh', base: 0, unit: '', precision: 0 },
      { label: 'Dupes', base: 0, unit: '', precision: 0 }
    ]
  },
  {
    id: 'compress',
    name: 'Compress',
    icon: 'Z',
    typeSignature: 'Chunk[Byte] => Chunk[Byte]',
    hue: 40,
    summaryFields: [
      { name: 'compressedBytes', type: 'Long' },
      { name: 'ratio', type: 'Double' }
    ],
    baseMetrics: [
      { label: 'Output', base: 0, unit: 'B', precision: 0 },
      { label: 'Ratio', base: 0, unit: 'x', precision: 2 }
    ]
  },
  {
    id: 'bombGuard',
    name: 'Bomb Guard',
    icon: 'G',
    typeSignature: 'Chunk[Byte] => Chunk[Byte]',
    hue: 0,
    summaryFields: [
      { name: 'totalSeen', type: 'Long' },
      { name: 'rejected', type: 'Boolean' }
    ],
    baseMetrics: [
      { label: 'Seen', base: 0, unit: 'B', precision: 0 },
      { label: 'Status', base: 0, unit: '', precision: 0 }
    ]
  },
  {
    id: 'verify',
    name: 'Block Verifier',
    icon: 'V',
    typeSignature: 'Chunk[Byte] => VerifyResult',
    hue: 120,
    fanout: true,
    summaryFields: [
      { name: 'verified', type: 'Long' },
      { name: 'failed', type: 'Long' }
    ],
    baseMetrics: [
      { label: 'OK', base: 0, unit: '', precision: 0 },
      { label: 'Fail', base: 0, unit: '', precision: 0 }
    ]
  }
]

const scenarios: Scenario[] = [
  {
    id: 'basic-ingest',
    name: 'Basic Ingest',
    icon: 'IN',
    description: 'count >>> hash >>> rechunk',
    stages: ['countBytes', 'hashBytes', 'rechunk']
  },
  {
    id: 'full-cas',
    name: 'Full CAS Pipeline',
    icon: 'CAS',
    description: 'count >>> hash >>> rechunk >>> key >>> dedup',
    stages: ['countBytes', 'hashBytes', 'rechunk', 'blockKey', 'dedup']
  },
  {
    id: 'safe-ingest',
    name: 'Safe Ingest',
    icon: 'SFE',
    description: 'guard >>> count >>> hash >>> rechunk >>> compress',
    stages: ['bombGuard', 'countBytes', 'hashBytes', 'rechunk', 'compress']
  },
  {
    id: 'verify-pipeline',
    name: 'Verify + Hash',
    icon: 'VFY',
    description: 'count &&& hash &&& verify',
    stages: ['countBytes', 'hashBytes', 'verify']
  }
]

// --- State ---

const running = ref(false)
const speed = ref(3)
const activeScenario = ref('basic-ingest')
const enabledStages = ref(new Set(['countBytes', 'hashBytes', 'rechunk']))
const tick = ref(0)
const pipelineRef = ref<HTMLElement | null>(null)

let animFrame = 0
let lastTime = 0
let packetCounter = 0
let particleCounter = 0

// Simulation state
const simState = ref({
  totalBytes: 0,
  hashBytes: 0,
  blockCount: 0,
  rechunkFill: 0,
  blocksKeyed: 0,
  fresh: 0,
  duplicate: 0,
  compressedBytes: 0,
  ratio: 0,
  totalSeen: 0,
  verified: 0,
  failed: 0,
  digestProgress: 0
})

const connectorParticles = ref<Map<number, Particle[]>>(new Map())
const outputParticles = ref<Particle[]>([])
const dataPackets = ref<DataPacket[]>([])

// --- Computed ---

const availableStages = computed(() => allStages)

const activeStages = computed<ActiveStage[]>(() => {
  return allStages
    .filter(s => enabledStages.value.has(s.id))
    .map((s, idx) => {
      const state = simState.value
      const isProcessing = running.value && ((tick.value + idx) % 3 < 2)
      const throughput = running.value ? 40 + Math.sin(tick.value * 0.1 + idx) * 30 + Math.random() * 20 : 0

      const metrics: StageMetric[] = s.baseMetrics.map((m, mIdx) => {
        let value = 0
        let display = '0'
        const changed = running.value && Math.random() > 0.4

        switch (s.id) {
          case 'countBytes':
            value = state.totalBytes
            display = formatBytes(value)
            break
          case 'hashBytes':
            if (mIdx === 0) {
              const hex = state.digestProgress > 0
                ? hashToHex(state.digestProgress).slice(0, 16) + '...'
                : '---'
              display = hex
              value = state.digestProgress
            } else {
              value = state.hashBytes
              display = formatBytes(value)
            }
            break
          case 'rechunk':
            if (mIdx === 0) {
              value = state.blockCount
              display = `${value}`
            } else {
              value = state.rechunkFill
              display = `${Math.min(100, value)}%`
            }
            break
          case 'blockKey':
            value = state.blocksKeyed
            display = `${value}`
            break
          case 'dedup':
            if (mIdx === 0) {
              value = state.fresh
              display = `${value}`
            } else {
              value = state.duplicate
              display = `${value}`
            }
            break
          case 'compress':
            if (mIdx === 0) {
              value = state.compressedBytes
              display = formatBytes(value)
            } else {
              value = state.ratio
              display = value > 0 ? `${value.toFixed(2)}x` : '---'
            }
            break
          case 'bombGuard':
            if (mIdx === 0) {
              value = state.totalSeen
              display = formatBytes(value)
            } else {
              display = state.totalSeen > 10_000_000_000 ? 'REJECTED' : 'OK'
              value = state.totalSeen > 10_000_000_000 ? 1 : 0
            }
            break
          case 'verify':
            if (mIdx === 0) {
              value = state.verified
              display = `${value}`
            } else {
              value = state.failed
              display = `${value}`
            }
            break
        }

        return { label: m.label, value, displayValue: display, changed }
      })

      return {
        ...s,
        processing: isProcessing,
        highlight: isProcessing && Math.random() > 0.7,
        throughputPct: Math.min(100, Math.max(0, throughput)),
        throughputLabel: running.value ? `${(throughput * 0.12).toFixed(1)} GB/s` : 'idle',
        metrics
      }
    })
})

const compositionExpression = computed(() => {
  const stages = activeStages.value
  if (stages.length === 0) return 'Select stages above'

  const parts: string[] = []
  for (let i = 0; i < stages.length; i++) {
    if (i > 0) {
      parts.push(stages[i].fanout ? ' &&& ' : ' >>> ')
    }
    parts.push(stages[i].name.replace(/\s+/g, ''))
  }
  return parts.join('')
})

const summaryFields = computed(() => {
  const fields: Array<{ name: string; value: string; type: string; fresh: boolean }> = []
  const state = simState.value

  for (const stage of activeStages.value) {
    for (const f of stage.summaryFields) {
      let value = '---'
      let fresh = false

      switch (f.name) {
        case 'totalBytes': value = formatBytes(state.totalBytes); fresh = running.value; break
        case 'digestHex': value = state.digestProgress > 0 ? hashToHex(state.digestProgress).slice(0, 20) + '...' : '---'; fresh = running.value; break
        case 'hashBytes': value = formatBytes(state.hashBytes); fresh = running.value; break
        case 'blockCount': value = `${state.blockCount}`; fresh = running.value; break
        case 'rechunkFill': value = `${state.rechunkFill}%`; break
        case 'blocksKeyed': value = `${state.blocksKeyed}`; fresh = running.value; break
        case 'fresh': value = `${state.fresh}`; fresh = running.value; break
        case 'duplicate': value = `${state.duplicate}`; break
        case 'compressedBytes': value = formatBytes(state.compressedBytes); fresh = running.value; break
        case 'ratio': value = state.ratio > 0 ? `${state.ratio.toFixed(2)}` : '---'; break
        case 'totalSeen': value = formatBytes(state.totalSeen); break
        case 'rejected': value = state.totalSeen > 10_000_000_000 ? 'true' : 'false'; break
        case 'verified': value = `${state.verified}`; fresh = running.value; break
        case 'failed': value = `${state.failed}`; break
        case 'entries': value = `${state.blockCount}`; break
        case 'manifestSize': value = formatBytes(state.blockCount * 48); break
      }

      fields.push({ name: f.name, value, type: f.type, fresh })
    }
  }

  return fields
})

const visiblePackets = computed(() => dataPackets.value.filter(p => p.opacity > 0.05))

// --- Methods ---

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1073741824) return `${(n / 1048576).toFixed(1)} MB`
  return `${(n / 1073741824).toFixed(2)} GB`
}

function hashToHex(seed: number): string {
  let h = seed | 0
  const hex: string[] = []
  for (let i = 0; i < 32; i++) {
    h = ((h << 5) - h + (i * 7 + 13)) | 0
    hex.push(((h >>> 0) % 16).toString(16))
  }
  return hex.join('')
}

function toggleStage(id: string) {
  const stage = allStages.find(s => s.id === id)
  if (stage?.required) return

  const set = new Set(enabledStages.value)
  if (set.has(id)) {
    set.delete(id)
  } else {
    set.add(id)
  }
  enabledStages.value = set
  activeScenario.value = ''
}

function toggleSimulation() {
  running.value = !running.value
  if (running.value) {
    lastTime = performance.now()
    animate()
  }
}

function resetPipeline() {
  running.value = false
  simState.value = {
    totalBytes: 0,
    hashBytes: 0,
    blockCount: 0,
    rechunkFill: 0,
    blocksKeyed: 0,
    fresh: 0,
    duplicate: 0,
    compressedBytes: 0,
    ratio: 0,
    totalSeen: 0,
    verified: 0,
    failed: 0,
    digestProgress: 0
  }
  dataPackets.value = []
  connectorParticles.value = new Map()
  outputParticles.value = []
  tick.value = 0
}

function loadScenario(scenario: Scenario) {
  resetPipeline()
  enabledStages.value = new Set(scenario.stages)
  activeScenario.value = scenario.id
}

function particlesForConnector(idx: number): Particle[] {
  return connectorParticles.value.get(idx) ?? []
}

function animate() {
  if (!running.value) return

  const now = performance.now()
  const dt = Math.min(50, now - lastTime)
  lastTime = now
  const spd = speed.value

  tick.value++

  // Update simulation state
  const chunkSize = 65536 * spd
  const state = { ...simState.value }
  state.totalBytes += chunkSize
  state.hashBytes += chunkSize
  state.totalSeen += chunkSize
  state.digestProgress += chunkSize

  if (state.totalBytes % (1048576 * 2) < chunkSize) {
    state.blockCount++
    state.rechunkFill = Math.floor(Math.random() * 30) + 70

    if (enabledStages.value.has('blockKey')) {
      state.blocksKeyed++
    }
    if (enabledStages.value.has('dedup')) {
      if (Math.random() > 0.25) {
        state.fresh++
      } else {
        state.duplicate++
      }
    }
    if (enabledStages.value.has('compress')) {
      state.compressedBytes += Math.floor(chunkSize * 0.62)
      state.ratio = state.totalBytes > 0 ? state.totalBytes / Math.max(1, state.compressedBytes) : 0
    }
    if (enabledStages.value.has('verify')) {
      if (Math.random() > 0.02) {
        state.verified++
      } else {
        state.failed++
      }
    }
  }

  simState.value = state

  // Spawn connector particles
  const newParticles = new Map(connectorParticles.value)
  for (let i = 1; i <= activeStages.value.length; i++) {
    const existing = newParticles.get(i) ?? []
    if (Math.random() < 0.3 * spd) {
      existing.push({ id: particleCounter++, x: 0, opacity: 1 })
    }
    const updated = existing
      .map(p => ({ ...p, x: p.x + 2.5 * spd, opacity: Math.max(0, p.opacity - 0.015 * spd) }))
      .filter(p => p.x < 100 && p.opacity > 0)
    newParticles.set(i, updated)
  }
  connectorParticles.value = newParticles

  // Output particles
  const outP = [...outputParticles.value]
  if (Math.random() < 0.2 * spd) {
    outP.push({ id: particleCounter++, x: 0, opacity: 1 })
  }
  outputParticles.value = outP
    .map(p => ({ ...p, x: p.x + 3 * spd, opacity: Math.max(0, p.opacity - 0.02 * spd) }))
    .filter(p => p.x < 100 && p.opacity > 0)

  // Spawn data packets
  if (Math.random() < 0.08 * spd) {
    const types = ['bytes', 'block', 'hash', 'manifest']
    const type = types[Math.floor(Math.random() * types.length)]
    const labels: Record<string, string[]> = {
      bytes: ['0xFF', '0x42', '0xAB', '0x01', '0xCD'],
      block: ['blk:1', 'blk:2', 'blk:3', 'blk:4'],
      hash: ['sha3', 'blake3', 'b3:f0a', 'b3:c1e'],
      manifest: ['mfst', 'span', 'entry', 'offset']
    }
    dataPackets.value.push({
      id: packetCounter++,
      x: Math.random() * 10,
      y: 8 + Math.random() * 40,
      opacity: 1,
      scale: 0.6 + Math.random() * 0.4,
      label: labels[type][Math.floor(Math.random() * labels[type].length)],
      type
    })
  }

  dataPackets.value = dataPackets.value
    .map(p => ({
      ...p,
      x: p.x + (0.15 + Math.random() * 0.1) * spd,
      opacity: p.x > 85 ? p.opacity - 0.05 * spd : p.opacity
    }))
    .filter(p => p.x < 105 && p.opacity > 0)
    .slice(-60)

  animFrame = requestAnimationFrame(animate)
}

// --- Lifecycle ---

onMounted(() => {
  loadScenario(scenarios[0])
})

onUnmounted(() => {
  running.value = false
  if (animFrame) cancelAnimationFrame(animFrame)
})
</script>
