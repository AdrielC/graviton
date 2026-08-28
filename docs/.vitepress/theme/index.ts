import DefaultTheme from 'vitepress/theme'
import { useRoute, type Theme } from 'vitepress'
import { onBeforeUnmount, onMounted, watch } from 'vue'
import './custom.css'
import GravitonHome from './components/GravitonHome.vue'
import PipelinePlayground from './components/PipelinePlayground.vue'
import { mountMatrixRain } from './matrixRain'
import { mountPageIdentity, type PageIdentity } from './pageIdentity'

const theme: Theme = {
  ...DefaultTheme,
  enhanceApp(context) {
    DefaultTheme.enhanceApp?.(context)
    context.app.component('GravitonHome', GravitonHome)
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
