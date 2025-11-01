type Cleanup = () => void

const noop: Cleanup = () => {}

export function createMatrixRain(canvas: HTMLCanvasElement): Cleanup {
  const ctx = canvas.getContext('2d')
  if (!ctx || typeof window === 'undefined') {
    return noop
  }

  let animationFrame = 0
  const characters = 'アカサタナハマヤラワ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ'
  const fontSize = 18
  let columns = 0
  let drops: number[] = []

  const drawBackground = () => {
    ctx.fillStyle = 'rgba(4, 8, 18, 0.18)'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
  }

  const drawCharacters = () => {
    ctx.fillStyle = 'rgba(0, 255, 163, 0.85)'
    ctx.font = `600 ${fontSize}px "JetBrains Mono", monospace`

    for (let i = 0; i < columns; i += 1) {
      const text = characters.charAt(Math.floor(Math.random() * characters.length))
      const x = i * fontSize
      const y = drops[i] * fontSize

      ctx.fillText(text, x, y)

      if (y > canvas.height && Math.random() > 0.975) {
        drops[i] = 0
      }

      drops[i] += 1
    }
  }

  const render = () => {
    drawBackground()
    drawCharacters()
    animationFrame = window.requestAnimationFrame(render)
  }

  const resize = () => {
    const dpr = window.devicePixelRatio || 1
    canvas.width = window.innerWidth * dpr
    canvas.height = window.innerHeight * dpr
    canvas.style.width = '100%'
    canvas.style.height = '100%'

    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.scale(dpr, dpr)

    columns = Math.ceil(window.innerWidth / fontSize)
    drops = Array(columns).fill(0)
  }

  resize()
  render()

  window.addEventListener('resize', resize, { passive: true })

  return () => {
    window.cancelAnimationFrame(animationFrame)
    window.removeEventListener('resize', resize)
  }
}

interface Particle {
  x: number
  y: number
  vx: number
  vy: number
  life: number
  ttl: number
  size: number
  hue: number
}

export function initParticleTrail(canvas: HTMLCanvasElement): Cleanup {
  if (typeof window === 'undefined' || window.matchMedia('(pointer: coarse)').matches) {
    return noop
  }

  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return noop
  }

  let animationFrame = 0
  let pointerX = window.innerWidth / 2
  let pointerY = window.innerHeight / 2
  let pointerActive = false
  const particles: Particle[] = []

  const resize = () => {
    const dpr = window.devicePixelRatio || 1
    canvas.width = window.innerWidth * dpr
    canvas.height = window.innerHeight * dpr
    canvas.style.width = '100%'
    canvas.style.height = '100%'

    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.scale(dpr, dpr)
  }

  const spawnParticles = (x: number, y: number) => {
    for (let i = 0; i < 6; i += 1) {
      particles.push({
        x,
        y,
        vx: (Math.random() - 0.5) * 0.8,
        vy: (Math.random() - 0.5) * 0.8,
        life: 0,
        ttl: 80 + Math.random() * 60,
        size: 1.4 + Math.random() * 1.5,
        hue: 150 + Math.random() * 90
      })
    }
  }

  const update = () => {
    ctx.fillStyle = 'rgba(5, 10, 18, 0.18)'
    ctx.fillRect(0, 0, canvas.width, canvas.height)

    if (pointerActive && particles.length < 220) {
      spawnParticles(pointerX, pointerY)
    }

    for (let i = particles.length - 1; i >= 0; i -= 1) {
      const particle = particles[i]
      particle.life += 1
      if (particle.life >= particle.ttl) {
        particles.splice(i, 1)
        continue
      }

      particle.x += particle.vx * 16
      particle.y += particle.vy * 16
      particle.vx *= 1.015
      particle.vy *= 1.015

      const alpha = 1 - particle.life / particle.ttl
      ctx.beginPath()
      ctx.fillStyle = `hsla(${particle.hue}, 100%, 65%, ${alpha})`
      ctx.shadowBlur = 14
      ctx.shadowColor = `hsla(${particle.hue}, 100%, 65%, 0.8)`
      ctx.arc(particle.x, particle.y, particle.size * (1 + alpha * 2), 0, Math.PI * 2)
      ctx.fill()
      ctx.closePath()
    }
  }

  const loop = () => {
    update()
    animationFrame = window.requestAnimationFrame(loop)
  }

  const handlePointerMove = (event: PointerEvent) => {
    pointerActive = true
    pointerX = event.clientX
    pointerY = event.clientY
    spawnParticles(pointerX, pointerY)
  }

  const handlePointerLeave = () => {
    pointerActive = false
  }

  const handlePointerDown = (event: PointerEvent) => {
    pointerActive = true
    pointerX = event.clientX
    pointerY = event.clientY
    spawnParticles(pointerX, pointerY)
  }

  const handlePointerUp = () => {
    pointerActive = false
  }

  resize()
  loop()

  window.addEventListener('resize', resize, { passive: true })
  window.addEventListener('pointermove', handlePointerMove, { passive: true })
  window.addEventListener('pointerdown', handlePointerDown, { passive: true })
  window.addEventListener('pointerup', handlePointerUp, { passive: true })
  window.addEventListener('pointerleave', handlePointerLeave, { passive: true })

  return () => {
    window.cancelAnimationFrame(animationFrame)
    window.removeEventListener('resize', resize)
    window.removeEventListener('pointermove', handlePointerMove)
    window.removeEventListener('pointerdown', handlePointerDown)
    window.removeEventListener('pointerup', handlePointerUp)
    window.removeEventListener('pointerleave', handlePointerLeave)
    particles.splice(0, particles.length)
  }
}

export function initScrollAnimations(): Cleanup {
  if (typeof window === 'undefined' || typeof IntersectionObserver === 'undefined') {
    return noop
  }

  const selectors = [
    '.VPHero',
    '.VPHero .tagline',
    '.VPHero .actions .VPButton',
    '.VPFeatures .VPFeature',
    '.home-link-card',
    '.vp-doc h1',
    '.vp-doc h2',
    '.vp-doc h3',
    '.vp-doc p',
    '.vp-doc ul',
    '.vp-doc ol',
    '.vp-doc pre',
    '.vp-doc blockquote',
    '.vp-doc table'
  ]

  const elements = Array.from(document.querySelectorAll<HTMLElement>(selectors.join(',')))
  if (!elements.length) {
    return noop
  }

  elements.forEach((element, index) => {
    element.dataset.animate = 'true'
    element.style.setProperty('--gr-animation-delay', `${Math.min(index * 0.045, 0.6)}s`)
  })

  const observer = new IntersectionObserver(
    entries => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          const target = entry.target as HTMLElement
          target.dataset.animateVisible = 'true'
          observer.unobserve(target)
        }
      })
    },
    {
      threshold: 0.18,
      rootMargin: '0px 0px -60px 0px'
    }
  )

  elements.forEach(element => observer.observe(element))

  return () => observer.disconnect()
}
