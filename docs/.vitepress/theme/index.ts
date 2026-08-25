import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import { h, onBeforeUnmount, onMounted } from 'vue'
import './custom.css'
import EvidenceHud from './components/EvidenceHud.vue'
import PipelinePlayground from './components/PipelinePlayground.vue'
import QuantumConsole from './components/QuantumConsole.vue'
import { mountMatrixRain } from './matrixRain'

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
    let disposeMatrix = () => undefined

    onMounted(() => {
      disposeMatrix = mountMatrixRain()
    })

    onBeforeUnmount(() => {
      disposeMatrix()
    })
  }
}

export default theme
