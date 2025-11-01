<template>
  <section class="neon-hud" aria-labelledby="neon-hud-heading" role="status" aria-live="polite">
    <header class="neon-hud__header">
      <div class="neon-hud__title">
        <span class="neon-hud__glyph">🛰️</span>
        <div>
          <p id="neon-hud-heading">Telemetry Feed</p>
          <small>Real-time runtime signals</small>
        </div>
      </div>
      <time class="neon-hud__clock">{{ clock }}</time>
    </header>

    <div class="neon-hud__grid">
      <article
        v-for="metric in metrics"
        :key="metric.id"
        class="neon-hud__card"
      >
        <header>
          <span class="neon-hud__icon">{{ metric.icon }}</span>
          <p class="neon-hud__label">{{ metric.label }}</p>
        </header>
        <p class="neon-hud__value">
          <span>{{ metric.value }}</span>
          <small>{{ metric.unit }}</small>
        </p>
        <div class="neon-hud__bar" role="meter" :aria-valuenow="metric.progress" aria-valuemin="0" aria-valuemax="100">
          <span :style="{ width: metric.progress + '%' }" />
        </div>
        <p class="neon-hud__delta" :class="metric.trend">
          <span>{{ metric.trend === 'up' ? '▲' : '▼' }}</span>
          <span>{{ metric.delta }}%</span>
        </p>
      </article>
    </div>

    <ul class="neon-hud__ticker" aria-label="Latest events">
      <li v-for="(event, index) in events" :key="index">
        <span class="neon-hud__ticker-dot" />
        <span>{{ event }}</span>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'

type Trend = 'up' | 'down'

type MetricDefinition = {
  id: string
  label: string
  unit: string
  icon: string
  base: number
  variance: number
  precision?: number
}

type MetricSnapshot = {
  id: string
  label: string
  unit: string
  icon: string
  value: string
  progress: number
  trend: Trend
  delta: string
}

const metricDefinitions: MetricDefinition[] = [
  {
    id: 'throughput',
    label: 'Ingest Throughput',
    unit: 'GB/s',
    icon: '⚡',
    base: 12.4,
    variance: 3.6,
    precision: 2
  },
  {
    id: 'dedup',
    label: 'Deduplication Ratio',
    unit: '×',
    icon: '♻️',
    base: 4.3,
    variance: 0.8,
    precision: 2
  },
  {
    id: 'latency',
    label: 'Stream Latency',
    unit: 'ms',
    icon: '🛰️',
    base: 18,
    variance: 9,
    precision: 0
  },
  {
    id: 'replication',
    label: 'Replica Health',
    unit: '%',
    icon: '🛡️',
    base: 99.2,
    variance: 0.6,
    precision: 2
  }
]

const hudEvents = [
  'Quantum CDC anchor stabilized at sector delta-7',
  'Prometheus scrape completed (42 targets • 0 anomalies)',
  'S3 cold tier synced ↔️ active hot tier in 128 ms',
  'FastCDC window recalibrated • chunk drift ±4 bytes',
  'ZIO fiber swarm balanced • 0 saturation alerts',
  'Manifest forward-compat negotiated • handshake v5',
  'Holographic cache warmed • 94% hit ratio sustained',
  'Edge replicator spun up new graviton in ap-southeast-2'
]

const metrics = ref<MetricSnapshot[]>([])
const events = ref<string[]>([])
const clock = ref<string>('')

let metricTimer: number | undefined
let eventTimer: number | undefined
let clockTimer: number | undefined

const sample = (definition: MetricDefinition): MetricSnapshot => {
  const drift = (Math.random() * definition.variance * 2) - definition.variance
  const valueRaw = Math.max(definition.base + drift, 0)
  const precision = definition.precision ?? 1
  const trend: Trend = drift >= 0 ? 'up' : 'down'
  const delta = Math.abs((drift / Math.max(definition.base, 0.001)) * 100)

  return {
    id: definition.id,
    label: definition.label,
    unit: definition.unit,
    icon: definition.icon,
    value: valueRaw.toFixed(precision),
    progress: Math.min(100, Math.max(0, Math.round((valueRaw / (definition.base + definition.variance)) * 100))),
    trend,
    delta: delta.toFixed(1)
  }
}

const updateMetrics = () => {
  metrics.value = metricDefinitions.map(sample)
}

const pushEvent = () => {
  const message = hudEvents[Math.floor(Math.random() * hudEvents.length)]
  events.value = [message, ...events.value].slice(0, 4)
}

const updateClock = () => {
  const now = new Date()
  clock.value = now.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

onMounted(() => {
  updateMetrics()
  pushEvent()
  updateClock()

  metricTimer = window.setInterval(updateMetrics, 2600)
  eventTimer = window.setInterval(pushEvent, 3400)
  clockTimer = window.setInterval(updateClock, 1000)
})

onUnmounted(() => {
  if (metricTimer) window.clearInterval(metricTimer)
  if (eventTimer) window.clearInterval(eventTimer)
  if (clockTimer) window.clearInterval(clockTimer)
})
</script>
