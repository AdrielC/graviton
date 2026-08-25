<template>
  <button
    class="quantum-console__toggle"
    type="button"
    aria-haspopup="dialog"
    :aria-expanded="open"
    @click="toggle"
  >
    <span aria-hidden="true">⌘</span>
    Explore
  </button>

  <Teleport to="body">
    <Transition name="quantum-console__fade">
      <div
        v-if="open"
        class="quantum-console__overlay"
        role="dialog"
        aria-modal="true"
        aria-labelledby="quantum-console-title"
        @mousedown.self="close"
      >
        <div class="quantum-console__panel">
          <header>
            <div>
              <p class="quantum-console__eyebrow">GRAVITON NAVIGATOR</p>
              <h2 id="quantum-console-title">Jump into the system</h2>
              <small>Search, use ↑ and ↓, then press Enter</small>
            </div>
            <button type="button" class="quantum-console__close" @click="close">Close</button>
          </header>

          <label class="quantum-console__input">
            <span aria-hidden="true">❯</span>
            <input
              ref="inputRef"
              v-model="query"
              type="search"
              placeholder="CAS, hashing, streams, API…"
              @keydown="handleInputKey"
            />
          </label>

          <ul class="quantum-console__list" role="listbox">
            <li
              v-for="(entry, index) in filteredEntries"
              :key="entry.href"
              :class="['quantum-console__item', { active: index === activeIndex }]"
              role="option"
              :aria-selected="index === activeIndex"
              @mouseenter="activeIndex = index"
              @mousedown.prevent="navigate(entry.href)"
            >
              <span class="quantum-console__item-icon">{{ entry.icon }}</span>
              <span>
                <strong>{{ entry.label }}</strong>
                <small>{{ entry.description }}</small>
              </span>
              <kbd>{{ entry.shortcut }}</kbd>
            </li>
            <li v-if="filteredEntries.length === 0" class="quantum-console__empty">
              No matching route. Try CAS, server, streams, or API.
            </li>
          </ul>

          <footer>
            <span>Shortcut: ⌘⇧P or Ctrl⇧P</span>
            <span>Try the classic Konami code</span>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { withBase } from 'vitepress'

type ConsoleEntry = {
  label: string
  description: string
  href: string
  icon: string
  shortcut: string
  keywords: string[]
}

const entries: ConsoleEntry[] = [
  {
    label: 'CAS Playground',
    description: 'Hash and chunk your own bytes entirely in the browser',
    href: '/cas-playground',
    icon: 'CAS',
    shortcut: 'C',
    keywords: ['cas', 'playground', 'hash', 'chunk', 'dedup', 'interactive']
  },
  {
    label: 'Pipeline Explorer',
    description: 'Inspect the real byte-to-content-ID stages interactively',
    href: '/pipeline-explorer',
    icon: '>>>',
    shortcut: 'P',
    keywords: ['pipeline', 'transducer', 'composition', 'stages']
  },
  {
    label: 'Run Graviton',
    description: 'Start the durable filesystem CAS locally',
    href: '/guide/run-locally',
    icon: 'RUN',
    shortcut: 'R',
    keywords: ['run', 'server', 'local', 'filesystem', 'quickstart']
  },
  {
    label: 'Connect Your Server',
    description: 'Operate a Graviton HTTP endpoint you provide',
    href: '/demo',
    icon: 'HTTP',
    shortcut: 'L',
    keywords: ['live', 'console', 'server', 'upload', 'verify']
  },
  {
    label: 'Transducer Algebra',
    description: 'Typed sequential and fanout stream composition',
    href: '/core/transducers',
    icon: '&&&',
    shortcut: 'T',
    keywords: ['transducer', 'algebra', 'record', 'scan']
  },
  {
    label: 'HTTP API',
    description: 'Upload, inventory, inspect, verify, retrieve, and delete',
    href: '/api/http',
    icon: 'API',
    shortcut: 'A',
    keywords: ['api', 'http', 'endpoint', 'curl']
  },
  {
    label: 'Chunking Strategies',
    description: 'Fixed, FastCDC, and delimiter chunk boundaries',
    href: '/ingest/chunking',
    icon: 'CUT',
    shortcut: 'K',
    keywords: ['chunk', 'fastcdc', 'fixed', 'delimiter']
  },
  {
    label: 'Architecture',
    description: 'Follow bytes through stores, manifests, and verification',
    href: '/architecture',
    icon: 'MAP',
    shortcut: 'M',
    keywords: ['architecture', 'storage', 'manifest', 'design']
  }
]

const open = ref(false)
const query = ref('')
const activeIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

const filteredEntries = computed(() => {
  const needles = query.value.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (needles.length === 0) return entries
  return entries.filter(entry => {
    const haystack = [entry.label, entry.description, ...entry.keywords].join(' ').toLowerCase()
    return needles.every(needle => haystack.includes(needle))
  })
})

function toggle() {
  open.value ? close() : (open.value = true)
}

function close() {
  open.value = false
}

function navigate(href: string) {
  close()
  window.location.href = withBase(href)
}

function handleInputKey(event: KeyboardEvent) {
  const count = filteredEntries.value.length
  if (event.key === 'Escape') {
    event.preventDefault()
    close()
  } else if (event.key === 'ArrowDown' && count > 0) {
    event.preventDefault()
    activeIndex.value = (activeIndex.value + 1) % count
  } else if (event.key === 'ArrowUp' && count > 0) {
    event.preventDefault()
    activeIndex.value = (activeIndex.value - 1 + count) % count
  } else if (event.key === 'Enter') {
    event.preventDefault()
    const entry = filteredEntries.value[activeIndex.value]
    if (entry) navigate(entry.href)
  }
}

const sequence: string[] = []
const konami = ['arrowup', 'arrowup', 'arrowdown', 'arrowdown', 'arrowleft', 'arrowright', 'arrowleft', 'arrowright', 'b', 'a']

function handleGlobalKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.shiftKey && event.key.toLowerCase() === 'p') {
    event.preventDefault()
    open.value = true
    return
  }

  if (event.key === 'Escape' && open.value) {
    close()
    return
  }

  sequence.push(event.key.toLowerCase())
  if (sequence.length > konami.length) sequence.shift()
  if (konami.every((key, index) => sequence[index] === key)) {
    document.body.classList.add('graviton-hyperspace')
    window.setTimeout(() => document.body.classList.remove('graviton-hyperspace'), 4200)
    sequence.length = 0
  }
}

watch(query, () => {
  activeIndex.value = 0
})

watch(open, async isOpen => {
  if (isOpen) {
    await nextTick()
    inputRef.value?.focus()
  } else {
    query.value = ''
    activeIndex.value = 0
  }
})

onMounted(() => window.addEventListener('keydown', handleGlobalKeydown))
onUnmounted(() => window.removeEventListener('keydown', handleGlobalKeydown))
</script>
