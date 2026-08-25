import DefaultTheme from 'vitepress/theme'
import { useRoute, type Theme } from 'vitepress'
import { h, onBeforeUnmount, onMounted, watch } from 'vue'
import './custom.css'
import EvidenceHud from './components/EvidenceHud.vue'
import PipelinePlayground from './components/PipelinePlayground.vue'
import QuantumConsole from './components/QuantumConsole.vue'
import { mountMatrixRain } from './matrixRain'
import { mountPageIdentity, type PageIdentity } from './pageIdentity'

const theme: Theme = {
  ...DefaultTheme,
  Layout: () =>
    h(DefaultTheme.Layout, null, {
      'layout-bottom': () => h(QuantumConsole)
    }),
  enhanceApp(context) {
    DefaultTheme.enhanceApp?.(context)
    context.app.component('EvidenceHud', EvidenceHud)
    context.app.component('PipelinePlayground', PipelinePlayground)
  },
  setup() {
    DefaultTheme.setup?.()
    const route = useRoute()
    let disposeMatrix = () => undefined
    let pageIdentity: PageIdentity | undefined

    const stopRouteWatch = watch(
      () => route.path,
      path => pageIdentity?.updateRoute(path)
    )

    onMounted(() => {
      disposeMatrix = mountMatrixRain()
      pageIdentity = mountPageIdentity(route.path)
    })

    onBeforeUnmount(() => {
      stopRouteWatch()
      pageIdentity?.dispose()
      disposeMatrix()
    })
  }
}

export default theme
