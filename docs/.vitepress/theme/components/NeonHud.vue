<template>
  <section class="neon-hud" aria-labelledby="neon-hud-heading">
    <header class="neon-hud__header">
      <div class="neon-hud__title">
        <span class="neon-hud__glyph">PROOF</span>
        <div>
          <p id="neon-hud-heading">Operational Capability</p>
          <small>Source-backed, runnable, and explicit about limits</small>
        </div>
      </div>
      <span class="neon-hud__clock">NO SIMULATED METRICS</span>
    </header>

    <div class="neon-hud__grid">
      <article
        v-for="capability in capabilities"
        :key="capability.id"
        class="neon-hud__card"
      >
        <header>
          <span class="neon-hud__icon">{{ capability.icon }}</span>
          <p class="neon-hud__label">{{ capability.label }}</p>
        </header>
        <p class="neon-hud__value">
          <span>{{ capability.value }}</span>
          <small>{{ capability.unit }}</small>
        </p>
        <div
          class="neon-hud__bar"
          role="meter"
          :aria-valuenow="capability.progress"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <span :style="{ width: capability.progress + '%' }" />
        </div>
        <p class="neon-hud__delta up">
          <span>✓</span>
          <span>{{ capability.evidence }}</span>
        </p>
      </article>
    </div>

    <ul class="neon-hud__ticker" aria-label="Runnable evidence">
      <li v-for="item in evidence" :key="item">
        <span class="neon-hud__ticker-dot" />
        <span>{{ item }}</span>
      </li>
    </ul>
  </section>
</template>

<script setup lang="ts">
type Capability = {
  id: string
  label: string
  value: string
  unit: string
  icon: string
  progress: number
  evidence: string
}

const capabilities: Capability[] = [
  {
    id: 'durable-cas',
    label: 'Durable Local CAS',
    value: 'READY',
    unit: 'FS',
    icon: 'CAS',
    progress: 100,
    evidence: 'restart tested'
  },
  {
    id: 'http-lifecycle',
    label: 'HTTP Blob Lifecycle',
    value: '4',
    unit: 'verbs',
    icon: 'HTTP',
    progress: 100,
    evidence: 'contract tested'
  },
  {
    id: 'integrity',
    label: 'Content Integrity',
    value: 'E2E',
    unit: 'hash',
    icon: 'KEY',
    progress: 100,
    evidence: 'round-trip tested'
  },
  {
    id: 'delivery',
    label: 'Delivery Gates',
    value: '4',
    unit: 'checks',
    icon: 'CI',
    progress: 100,
    evidence: 'automated'
  }
]

const evidence = [
  'FsBlobManifestRepoSpec recreates the store before retrieval',
  'HttpApiSpec covers POST, GET, HEAD, DELETE, 400, and 404 paths',
  'Framed manifests are validated and atomically replaced',
  'Scala tests, mdoc snippets, Scala.js, and VitePress are CI gates'
]
</script>
