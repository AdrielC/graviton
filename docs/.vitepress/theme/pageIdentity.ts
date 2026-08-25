export type PageIdentity = {
  updateRoute(path: string): void
  dispose(): void
}

const normalizeRoutePath = (path: string) => {
  const withoutIndex = path.replace(/\/index\.html$/, '/')
  const withoutTrailingSlash = withoutIndex.replace(/\/+$/, '')
  return withoutTrailingSlash || '/'
}

const homeRoutePath = normalizeRoutePath(import.meta.env.BASE_URL)

/**
 * Restore the original route sigil and scroll trace as decorative UI. Both are
 * derived from real browser state and never imply server activity or telemetry.
 */
export function mountPageIdentity(initialPath: string): PageIdentity {
  const progress = document.createElement('div')
  progress.className = 'graviton-scroll-progress'
  progress.setAttribute('aria-hidden', 'true')

  const sigil = document.createElement('div')
  sigil.className = 'graviton-route-sigil'
  sigil.setAttribute('aria-hidden', 'true')

  const row = document.createElement('div')
  row.className = 'graviton-route-sigil__row'

  const label = document.createElement('span')
  label.className = 'graviton-route-sigil__label'
  label.textContent = 'CAS'

  const hash = document.createElement('span')
  hash.className = 'graviton-route-sigil__hash'

  const path = document.createElement('div')
  path.className = 'graviton-route-sigil__path'

  row.append(label, hash)
  sigil.append(row, path)
  document.body.append(progress, sigil)

  let animationFrame = 0

  const paintProgress = () => {
    animationFrame = 0
    const scrollable = Math.max(1, document.documentElement.scrollHeight - window.innerHeight)
    const ratio = Math.min(1, Math.max(0, window.scrollY / scrollable))
    progress.style.setProperty('--graviton-progress', ratio.toString())
  }

  const requestProgressPaint = () => {
    if (animationFrame === 0) {
      animationFrame = window.requestAnimationFrame(paintProgress)
    }
  }

  const updateRoute = (routePath: string) => {
    const routeHash = hashPath(routePath)
    const hue = 120 + (Math.abs(routeHash) % 201)
    const isHome = normalizeRoutePath(routePath) === homeRoutePath
    document.documentElement.style.setProperty('--graviton-accent-hue', hue.toString())
    document.body.classList.toggle('graviton-home', isHome)
    hash.textContent = (routeHash >>> 0).toString(16).padStart(8, '0')
    path.textContent = routePath.length > 40 ? `${routePath.slice(0, 37)}…` : routePath
  }

  window.addEventListener('scroll', requestProgressPaint, { passive: true })
  window.addEventListener('resize', requestProgressPaint, { passive: true })
  updateRoute(initialPath)
  paintProgress()

  return {
    updateRoute,
    dispose() {
      if (animationFrame !== 0) window.cancelAnimationFrame(animationFrame)
      window.removeEventListener('scroll', requestProgressPaint)
      window.removeEventListener('resize', requestProgressPaint)
      document.documentElement.style.removeProperty('--graviton-accent-hue')
      document.body.classList.remove('graviton-home')
      progress.remove()
      sigil.remove()
    }
  }
}

function hashPath(path: string): number {
  let hash = 0
  for (let index = 0; index < path.length; index += 1) {
    hash = ((hash << 5) - hash + path.charCodeAt(index)) | 0
  }
  return hash
}
