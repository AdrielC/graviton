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

/** Keeps the stronger homepage Matrix treatment scoped to the homepage. */
export function mountPageIdentity(initialPath: string): PageIdentity {
  const updateRoute = (routePath: string) => {
    const isHome = normalizeRoutePath(routePath) === homeRoutePath
    document.body.classList.toggle('graviton-home', isHome)
  }

  updateRoute(initialPath)

  return {
    updateRoute,
    dispose() {
      document.body.classList.remove('graviton-home')
    }
  }
}
