import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import './custom.css'
import { onBeforeUnmount, onMounted } from 'vue'
import NeonHud from './components/NeonHud.vue'
import QuantumConsole from './components/QuantumConsole.vue'

const cleanupCallbacks: Array<() => void> = []
const processedCodeBlocks = new WeakSet<Element>()

const registerCleanup = (callback: () => void) => {
  cleanupCallbacks.push(callback)
}

const runCleanup = () => {
  while (cleanupCallbacks.length > 0) {
    const callback = cleanupCallbacks.pop()
    try {
      callback?.()
    } catch (error) {
      console.error('[Graviton Docs] Cleanup failed', error)
    }
  }
}

const theme: Theme = {
  ...DefaultTheme,
  setup() {
    onMounted(() => {
      createMatrixRain()
      initAuroraBackground()
      initScrollAnimations()
    })

    onBeforeUnmount(() => {
      runCleanup()
    })
  },
  enhanceApp({ app }) {
    DefaultTheme.enhanceApp?.({ app })
    app.component('NeonHud', NeonHud)
    app.component('QuantumConsole', QuantumConsole)
  }
}

export default theme

// Matrix rain animation in background
function createMatrixRain() {
  if (document.getElementById('matrix-canvas')) {
    return
  }

  const canvas = document.createElement('canvas')
  canvas.id = 'matrix-canvas'
  canvas.style.cssText = `
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    z-index: -2;
    opacity: 0.08;
    pointer-events: none;
    mix-blend-mode: screen;
  `
  document.body.appendChild(canvas)

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  let animationFrame = 0

  const resize = () => {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
  }

  resize()

  const chars = '01アイウエオカキクケコサシスセソタチツテト'
  const fontSize = 14
  const columns = Math.floor(canvas.width / fontSize)
  const drops: number[] = Array(columns).fill(1)

  const draw = () => {
    ctx.fillStyle = 'rgba(10, 14, 20, 0.05)'
    ctx.fillRect(0, 0, canvas.width, canvas.height)

    ctx.fillStyle = '#00ff41'
    ctx.font = `${fontSize}px "JetBrains Mono", monospace`

    for (let i = 0; i < drops.length; i++) {
      const text = chars[Math.floor(Math.random() * chars.length)]
      ctx.fillText(text, i * fontSize, drops[i] * fontSize)

      if (drops[i] * fontSize > canvas.height && Math.random() > 0.975) {
        drops[i] = 0
      }
      drops[i]++
    }

    animationFrame = window.requestAnimationFrame(draw)
  }

  animationFrame = window.requestAnimationFrame(draw)

  window.addEventListener('resize', resize)

  registerCleanup(() => {
    window.cancelAnimationFrame(animationFrame)
    window.removeEventListener('resize', resize)
    canvas.remove()
  })
}

// Particle trail following cursor
function initParticleTrail() {
  const globalScope = window as typeof window & { __gravitonParticleTrailInitialized?: boolean }
  if (globalScope.__gravitonParticleTrailInitialized) {
    return
  }
  globalScope.__gravitonParticleTrailInitialized = true

  const particles: Array<{ x: number; y: number; vx: number; vy: number; life: number }> = []
  const canvas = document.createElement('canvas')
  canvas.id = 'particle-canvas'
  canvas.style.cssText = `
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    z-index: 9999;
    pointer-events: none;
    mix-blend-mode: screen;
  `
  canvas.width = window.innerWidth
  canvas.height = window.innerHeight
  document.body.appendChild(canvas)

  const ctx = canvas.getContext('2d')
  if (!ctx) {
    canvas.remove()
    return
  }

  let animationFrame = 0

  const handleMouseMove = (event: MouseEvent) => {
    if (Math.random() > 0.65) {
      particles.push({
        x: event.clientX,
        y: event.clientY,
        vx: (Math.random() - 0.5) * 2.4,
        vy: (Math.random() - 0.5) * 2.4,
        life: 1
      })
    }
  }

  const animateParticles = () => {
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    particles.forEach((particle, index) => {
      particle.x += particle.vx
      particle.y += particle.vy
      particle.life -= 0.02

      if (particle.life <= 0) {
        particles.splice(index, 1)
      } else {
        ctx.fillStyle = `rgba(0, 255, 65, ${particle.life * 0.45})`
        ctx.beginPath()
        ctx.arc(particle.x, particle.y, 2.3, 0, Math.PI * 2)
        ctx.fill()
      }
    })

    animationFrame = window.requestAnimationFrame(animateParticles)
  }

  const handleResize = () => {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
  }

  document.addEventListener('mousemove', handleMouseMove)
  window.addEventListener('resize', handleResize)

  animateParticles()

  registerCleanup(() => {
    document.removeEventListener('mousemove', handleMouseMove)
    window.removeEventListener('resize', handleResize)
    window.cancelAnimationFrame(animationFrame)
    canvas.remove()
    globalScope.__gravitonParticleTrailInitialized = false
  })
}

function initAuroraBackground() {
  if (document.getElementById('aurora-overlay')) {
    return
  }

  const overlay = document.createElement('div')
  overlay.id = 'aurora-overlay'
  overlay.innerHTML = `
    <div class="aurora-band" data-speed="0.8"></div>
    <div class="aurora-band" data-speed="1.2"></div>
    <div class="aurora-band" data-speed="1.6"></div>
  `

  document.body.appendChild(overlay)

  const bands = Array.from(overlay.querySelectorAll<HTMLElement>('.aurora-band'))

  let animationFrame = 0
  const animate = () => {
    bands.forEach((band, index) => {
      const base = performance.now() * (parseFloat(band.dataset.speed ?? '1') / 8000)
      band.style.setProperty('--offset', `${Math.sin(base + index) * 40}px`)
      band.style.setProperty('--glow', `${(Math.sin(base * 2 + index) + 1) / 2}`)
    })

    animationFrame = window.requestAnimationFrame(animate)
  }

  animationFrame = window.requestAnimationFrame(animate)

  registerCleanup(() => {
    window.cancelAnimationFrame(animationFrame)
    overlay.remove()
  })
}

// Scroll reveal animations
function initScrollAnimations() {
  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('revealed')
      }
    })
  }, { threshold: 0.1 })

  document.querySelectorAll('.vp-doc h2, .vp-doc h3, .vp-doc p, .vp-doc pre').forEach(el => {
    el.classList.add('reveal-element')
    observer.observe(el)
  })
}

// Enhanced code block interactions
function enhanceCodeBlocks() {
  document.querySelectorAll('div[class*="language-"]').forEach(block => {
    if (processedCodeBlocks.has(block)) {
      return
    }
    processedCodeBlocks.add(block)

    block.addEventListener('mouseenter', () => {
      (block as HTMLElement).style.boxShadow = '0 0 30px rgba(0, 255, 65, 0.2)'
    })
    block.addEventListener('mouseleave', () => {
      (block as HTMLElement).style.boxShadow = '0 0 20px rgba(0, 255, 65, 0.1)'
    })
  })
}

function attachNavigationGlow() {
  const navBar = document.querySelector('.VPNavBar') as HTMLElement | null
  if (!navBar || navBar.querySelector('.nav-glow')) {
    return
  }

  const glow = document.createElement('span')
  glow.className = 'nav-glow'
  navBar.appendChild(glow)

  const handleMove = (event: MouseEvent) => {
    const rect = navBar.getBoundingClientRect()
    const x = event.clientX - rect.left
    glow.style.setProperty('--x', `${x}px`)
  }

  const handleLeave = () => {
    glow.style.setProperty('--x', `${navBar.offsetWidth / 2}px`)
  }

  navBar.addEventListener('mousemove', handleMove)
  navBar.addEventListener('mouseleave', handleLeave)

  handleLeave()

  registerCleanup(() => {
    navBar.removeEventListener('mousemove', handleMove)
    navBar.removeEventListener('mouseleave', handleLeave)
    glow.remove()
  })
}

declare global {
  interface Window {
    __gravitonParticleTrailInitialized?: boolean
  }
}
