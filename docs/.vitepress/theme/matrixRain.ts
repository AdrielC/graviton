const CANVAS_ID = 'graviton-matrix-rain'

/**
 * Mount a low-cost Matrix rain layer behind the documentation UI.
 *
 * This is intentionally visual only. It never represents runtime events,
 * throughput, health, or stored data. Visitors who prefer reduced motion do
 * not receive the canvas at all.
 */
export function mountMatrixRain(): () => void {
  if (typeof window === 'undefined' || typeof document === 'undefined') {
    return () => undefined
  }

  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    return () => undefined
  }

  document.getElementById(CANVAS_ID)?.remove()

  const canvas = document.createElement('canvas')
  canvas.id = CANVAS_ID
  canvas.className = 'graviton-matrix-rain'
  canvas.setAttribute('aria-hidden', 'true')

  const context = canvas.getContext('2d')
  if (!context) {
    return () => undefined
  }

  document.body.appendChild(canvas)
  document.body.classList.add('graviton-matrix-active')

  const glyphs = '01λΣ⋈⌁アイウエオカキクケコサシスセソタチツテト'
  const fontSize = 16
  let width = 0
  let height = 0
  let drops: number[] = []
  let animationFrame = 0
  let lastPaint = 0
  let startedAt = 0

  const resize = () => {
    const pixelRatio = Math.min(window.devicePixelRatio || 1, 2)
    width = window.innerWidth
    height = window.innerHeight
    canvas.width = Math.floor(width * pixelRatio)
    canvas.height = Math.floor(height * pixelRatio)
    canvas.style.width = `${width}px`
    canvas.style.height = `${height}px`
    context.setTransform(pixelRatio, 0, 0, pixelRatio, 0, 0)

    const columnCount = Math.max(1, Math.ceil(width / fontSize))
    drops = Array.from({ length: columnCount }, (_, index) =>
      drops[index] ?? Math.random() * -(height / fontSize)
    )
  }

  const paint = (timestamp: number) => {
    if (startedAt === 0) startedAt = timestamp
    if (timestamp - startedAt >= 4800) {
      animationFrame = 0
      return
    }

    animationFrame = window.requestAnimationFrame(paint)

    if (document.hidden || timestamp - lastPaint < 48) {
      return
    }
    lastPaint = timestamp

    context.globalCompositeOperation = 'destination-out'
    context.fillStyle = 'rgba(0, 0, 0, 0.12)'
    context.fillRect(0, 0, width, height)
    context.globalCompositeOperation = 'source-over'

    const dark = document.documentElement.classList.contains('dark')
    const hyperspace = document.body.classList.contains('graviton-hyperspace')
    const palette = dark
      ? ['rgba(99, 230, 190, 0.78)', 'rgba(102, 217, 239, 0.74)', 'rgba(177, 151, 252, 0.7)', 'rgba(247, 131, 172, 0.68)']
      : ['rgba(8, 127, 91, 0.58)', 'rgba(0, 124, 145, 0.54)', 'rgba(112, 72, 232, 0.48)', 'rgba(194, 37, 92, 0.46)']
    context.font = `${fontSize}px var(--vp-font-family-mono, monospace)`

    for (let column = 0; column < drops.length; column += 1) {
      const glyph = glyphs[Math.floor(Math.random() * glyphs.length)]
      context.fillStyle = palette[Math.random() < 0.86 ? 0 : 1 + Math.floor(Math.random() * 3)]
      const x = column * fontSize
      const y = drops[column] * fontSize
      context.fillText(glyph, x, y)

      if (y > height + fontSize && Math.random() > 0.975) {
        drops[column] = Math.random() * -12
      } else {
        drops[column] += hyperspace ? 2.8 : 0.8
      }
    }
  }

  resize()
  window.addEventListener('resize', resize, { passive: true })
  animationFrame = window.requestAnimationFrame(paint)

  return () => {
    if (animationFrame !== 0) window.cancelAnimationFrame(animationFrame)
    window.removeEventListener('resize', resize)
    document.body.classList.remove('graviton-matrix-active')
    canvas.remove()
  }
}
