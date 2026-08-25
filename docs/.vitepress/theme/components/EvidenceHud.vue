<template>
  <section class="evidence-hud" aria-labelledby="evidence-hud-title">
    <header class="evidence-hud__header">
      <div>
        <p class="evidence-hud__eyebrow">SOURCE-BACKED SYSTEM</p>
        <h2 id="evidence-hud-title">What Graviton can prove</h2>
      </div>
      <span class="evidence-hud__boundary">No generated telemetry</span>
    </header>

    <div class="evidence-hud__grid">
      <button
        v-for="capability in capabilities"
        :key="capability.id"
        type="button"
        :class="['evidence-hud__card', { active: capability.id === activeId }]"
        :aria-pressed="capability.id === activeId"
        @click="activeId = capability.id"
      >
        <span class="evidence-hud__icon">{{ capability.icon }}</span>
        <span class="evidence-hud__label">{{ capability.label }}</span>
        <strong>{{ capability.value }}</strong>
        <small>{{ capability.summary }}</small>
      </button>
    </div>

    <div class="evidence-hud__detail" aria-live="polite">
      <div>
        <p>{{ activeCapability.evidence }}</p>
        <code>{{ activeCapability.command }}</code>
      </div>
      <a :href="withBase(activeCapability.href)">Inspect the proof</a>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { withBase } from 'vitepress'

type Capability = {
  id: string
  icon: string
  label: string
  value: string
  summary: string
  evidence: string
  command: string
  href: string
}

const capabilities: Capability[] = [
  {
    id: 'durability',
    icon: 'FS',
    label: 'Filesystem CAS',
    value: 'RESTART-SAFE',
    summary: 'Blocks and framed manifests survive fresh processes.',
    evidence: 'The lifecycle script ingests once, then runs stat, get, and verify in separate JVMs before comparing bytes.',
    command: './scripts/verify-local-lifecycle.sh',
    href: '/guide/run-locally'
  },
  {
    id: 'integrity',
    icon: '256',
    label: 'Content integrity',
    value: 'REHASHED',
    summary: 'Verification reads persisted bytes instead of trusting metadata.',
    evidence: 'The verification endpoint streams every stored block, reconstructs the blob, and compares the resulting content key.',
    command: 'POST /api/v1/blobs/{id}/verify',
    href: '/api/http'
  },
  {
    id: 'streaming',
    icon: 'ZIO',
    label: 'Bounded streaming',
    value: 'BACKPRESSURED',
    summary: 'Uploads and downloads operate through ZIO Streams.',
    evidence: 'Chunkers and stores consume bounded chunks without requiring the complete payload in application memory.',
    command: 'Chunker.fixed(size).pipeline',
    href: '/guide/binary-streaming'
  },
  {
    id: 'backends',
    icon: 'CI',
    label: 'External backends',
    value: 'INTEGRATION-TESTED',
    summary: 'PostgreSQL manifests and S3-compatible blocks run in CI.',
    evidence: 'The main test workflow starts PostgreSQL and MinIO, applies the schema, and executes the external-backend round trip.',
    command: 'GRAVITON_IT=1 GRAVITON_MINIO_IT=1 ./sbt test',
    href: '/guide/storage-backends'
  }
]

const activeId = ref(capabilities[0].id)
const activeCapability = computed(() =>
  capabilities.find(capability => capability.id === activeId.value) ?? capabilities[0]
)
</script>
