<template>
  <div class="quantum-console">
    <button
      class="quantum-console__toggle"
      type="button"
      @click="toggleConsole"
      aria-haspopup="dialog"
      :aria-expanded="open"
    >
      Command Center
    </button>

    <Teleport to="body">
      <transition name="quantum-console__fade">
        <div
          v-if="open"
          class="quantum-console__overlay"
          role="dialog"
          aria-modal="true"
          aria-labelledby="quantum-console-heading"
          @keydown.stop
        >
          <div class="quantum-console__panel">
            <header>
              <div>
                <p id="quantum-console-heading">Quantum Command Center</p>
                <small>Press ⎋ to close • Navigate with ↑ ↓ • Enter to launch</small>
              </div>
              <button type="button" class="quantum-console__close" @click="toggleConsole">
                Close
              </button>
            </header>

            <div class="quantum-console__input">
              <span>❯</span>
              <input
                ref="inputRef"
                v-model="query"
                type="text"
                placeholder="Warp to guide, manifest docs, streaming tips…"
                @keydown.stop.prevent="handleInputKey"
              />
            </div>

            <ul class="quantum-console__list" role="listbox">
              <li
                v-for="(item, index) in filteredEntries"
                :key="item.href"
                :class="['quantum-console__item', { active: index === activeIndex }]"
                role="option"
                :aria-selected="index === activeIndex"
                @mouseenter="activeIndex = index"
                @mousedown.prevent="navigate(item.href)"
              >
                <div class="quantum-console__item-title">
                  <span class="quantum-console__item-icon">{{ item.icon }}</span>
                  <span>{{ item.label }}</span>
                </div>
                <small>{{ item.description }}</small>
                <kbd>{{ item.shortcut }}</kbd>
              </li>
            </ul>

            <footer class="quantum-console__footer">
              <p>
                Bonus: enter the Konami code anywhere to unlock hyperspace mode.
              </p>
            </footer>
          </div>
        </div>
      </transition>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { Teleport, computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

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
    label: 'Pipeline Explorer',
    description: 'Compose transducer stages interactively and watch data flow',
    href: '/pipeline-explorer',
    icon: 'PE',
    shortcut: 'X',
    keywords: ['pipeline', 'transducer', 'interactive', 'compose', 'explorer', 'playground']
  },
  {
    label: 'Getting Started',
    description: 'Spin up Graviton locally and ingest your first payload',
    href: '/guide/getting-started',
    icon: 'GS',
    shortcut: 'G',
    keywords: ['start', 'guide', 'intro', 'quick']
  },
  {
    label: 'Transducer Algebra',
    description: 'Typed composable pipeline stages with Record summaries',
    href: '/core/transducers',
    icon: 'TA',
    shortcut: 'R',
    keywords: ['transducer', 'algebra', 'compose', 'pipeline', 'record', 'summary']
  },
  {
    label: 'Architecture',
    description: 'Deep dive into modular ingestion, replication, and stores',
    href: '/architecture',
    icon: 'AR',
    shortcut: 'A',
    keywords: ['design', 'core', 'diagram', 'system']
  },
  {
    label: 'Ingest Pipeline',
    description: 'Follow blobs from chunking to manifest emission',
    href: '/end-to-end-upload',
    icon: 'IP',
    shortcut: 'I',
    keywords: ['ingest', 'upload', 'pipeline']
  },
  {
    label: 'API Reference',
    description: 'HTTP, gRPC, and protocol guarantees in one place',
    href: '/api',
    icon: 'API',
    shortcut: 'P',
    keywords: ['api', 'grpc', 'http', 'reference']
  },
  {
    label: 'Chunking Strategies',
    description: 'Master FastCDC knobs for golden dedup ratios',
    href: '/ingest/chunking',
    icon: 'CS',
    shortcut: 'C',
    keywords: ['chunking', 'fastcdc', 'dedup']
  },
  {
    label: 'Binary Streaming',
    description: 'Blocks, blobs, manifests, attributes — end to end',
    href: '/guide/binary-streaming',
    icon: 'BS',
    shortcut: 'B',
    keywords: ['binary', 'streaming', 'blocks', 'manifest', 'attributes']
  },
  {
    label: 'Testing Toolkit',
    description: 'ZIO test suites, fibers, and golden-data harnesses',
    href: '/dev/testing',
    icon: 'TT',
    shortcut: 'T',
    keywords: ['test', 'dev', 'ci', 'tooling']
  },
  {
    label: 'Contributing',
    description: 'Coding style, docs build, and how to join the crew',
    href: '/dev/contributing',
    icon: 'CT',
    shortcut: 'H',
    keywords: ['contribute', 'community', 'style']
  }
]

const open = ref(false)
const query = ref('')
const activeIndex = ref(0)
const inputRef = ref<HTMLInputElement | null>(null)

const filteredEntries = computed(() => {
  if (!query.value.trim()) {
    return entries
  }

  const fuse = query.value.trim().toLowerCase()

  return entries.filter(entry =>
    entry.label.toLowerCase().includes(fuse) ||
    entry.description.toLowerCase().includes(fuse) ||
    entry.keywords.some(keyword => keyword.includes(fuse))
  )
})

const navigate = (href: string) => {
  open.value = false
  query.value = ''
  activeIndex.value = 0
  window.setTimeout(() => {
    window.location.href = href
  }, 120)
}

const toggleConsole = () => {
  open.value = !open.value
}

const handleInputKey = (event: KeyboardEvent) => {
  if (event.key === 'Enter') {
    const entry = filteredEntries.value[activeIndex.value]
    if (entry) navigate(entry.href)
    return
  }

  if (event.key === 'ArrowDown') {
    activeIndex.value = (activeIndex.value + 1) % filteredEntries.value.length
    return
  }

  if (event.key === 'ArrowUp') {
    activeIndex.value = (activeIndex.value - 1 + filteredEntries.value.length) % filteredEntries.value.length
    return
  }

  if (event.key === 'Escape') {
    open.value = false
    return
  }
}

const sequence: string[] = []
const konami = ['ArrowUp', 'ArrowUp', 'ArrowDown', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'ArrowLeft', 'ArrowRight', 'b', 'a']

const handleGlobalKeydown = (event: KeyboardEvent) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open.value = true
    return
  }

  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'p') {
    event.preventDefault()
    open.value = true
    return
  }

  if (event.key === 'Escape' && open.value) {
    open.value = false
    return
  }

  sequence.push(event.key)
  if (sequence.length > konami.length) {
    sequence.shift()
  }

  if (konami.every((key, index) => sequence[index]?.toLowerCase() === key.toLowerCase())) {
    document.body.classList.add('konami-mode')
    window.setTimeout(() => document.body.classList.remove('konami-mode'), 4000)
    sequence.length = 0
  }
}

const handleGlobalKeypress = (event: KeyboardEvent) => {
  const shortcut = event.key.toLowerCase()
  if (!open.value) {
    return
  }

  const entry = entries.find(item => item.shortcut.toLowerCase() === shortcut)
  if (entry) {
    event.preventDefault()
    navigate(entry.href)
  }
}

watch(open, async value => {
  if (value) {
    await nextTick()
    inputRef.value?.focus()
  } else {
    query.value = ''
    activeIndex.value = 0
  }
})

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown)
  window.addEventListener('keypress', handleGlobalKeypress)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown)
  window.removeEventListener('keypress', handleGlobalKeypress)
})
</script>
