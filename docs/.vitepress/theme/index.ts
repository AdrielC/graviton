import DefaultTheme from 'vitepress/theme'
import './custom.css'
import { onMounted } from 'vue'

export default {
  ...DefaultTheme,
  setup() {
    onMounted(() => {
      // Matrix rain effect
      createMatrixRain()
      
      // Particle cursor trail
      initParticleTrail()
      
      // Smooth scroll reveal animations
      initScrollAnimations()
      
      // Code block copy animation enhancement
      enhanceCodeBlocks()
    })
  },
  enhanceApp({ app }) {
    // App enhancements
  }
}

// Matrix rain animation in background
function createMatrixRain() {
  const canvas = document.createElement('canvas')
  canvas.id = 'matrix-canvas'
  canvas.style.cssText = `
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    z-index: -1;
    opacity: 0.03;
    pointer-events: none;
  `
  document.body.appendChild(canvas)

  const ctx = canvas.getContext('2d')
  if (!ctx) return

  canvas.width = window.innerWidth
  canvas.height = window.innerHeight

  const chars = '01アイウエオカキクケコサシスセソタチツテト'
  const fontSize = 14
  const columns = canvas.width / fontSize
  const drops: number[] = Array(Math.floor(columns)).fill(1)

  function draw() {
    if (!ctx) return
    ctx.fillStyle = 'rgba(10, 14, 20, 0.05)'
    ctx.fillRect(0, 0, canvas.width, canvas.height)

    ctx.fillStyle = '#00ff41'
    ctx.font = `${fontSize}px monospace`

    for (let i = 0; i < drops.length; i++) {
      const text = chars[Math.floor(Math.random() * chars.length)]
      ctx.fillText(text, i * fontSize, drops[i] * fontSize)

      if (drops[i] * fontSize > canvas.height && Math.random() > 0.975) {
        drops[i] = 0
      }
      drops[i]++
    }
  }

  const interval = setInterval(draw, 50)

  window.addEventListener('resize', () => {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
  })

  // Cleanup on page unload
  window.addEventListener('beforeunload', () => clearInterval(interval))
}

// Particle trail following cursor
function initParticleTrail() {
  const particles: Array<{x: number, y: number, vx: number, vy: number, life: number}> = []
  
  document.addEventListener('mousemove', (e) => {
    if (Math.random() > 0.7) {
      particles.push({
        x: e.clientX,
        y: e.clientY,
        vx: (Math.random() - 0.5) * 2,
        vy: (Math.random() - 0.5) * 2,
        life: 1
      })
    }
  })

  function animateParticles() {
    particles.forEach((p, i) => {
      p.x += p.vx
      p.y += p.vy
      p.life -= 0.02
      
      if (p.life <= 0) {
        particles.splice(i, 1)
      }
    })

    const canvas = document.getElementById('particle-canvas') as HTMLCanvasElement
    if (canvas) {
      const ctx = canvas.getContext('2d')
      if (ctx) {
        ctx.clearRect(0, 0, canvas.width, canvas.height)
        particles.forEach(p => {
          ctx.fillStyle = `rgba(0, 255, 65, ${p.life * 0.5})`
          ctx.beginPath()
          ctx.arc(p.x, p.y, 2, 0, Math.PI * 2)
          ctx.fill()
        })
      }
    }

    requestAnimationFrame(animateParticles)
  }

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
  `
  canvas.width = window.innerWidth
  canvas.height = window.innerHeight
  document.body.appendChild(canvas)

  animateParticles()

  window.addEventListener('resize', () => {
    canvas.width = window.innerWidth
    canvas.height = window.innerHeight
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
    block.addEventListener('mouseenter', () => {
      (block as HTMLElement).style.boxShadow = '0 0 30px rgba(0, 255, 65, 0.2)'
    })
    block.addEventListener('mouseleave', () => {
      (block as HTMLElement).style.boxShadow = '0 0 20px rgba(0, 255, 65, 0.1)'
    })
  })
}
