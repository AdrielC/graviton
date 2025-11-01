<template>
  <div class="graviton-layout">
    <canvas ref="matrixCanvas" class="matrix-canvas" aria-hidden="true" />
    <canvas ref="particleCanvas" class="particle-canvas" aria-hidden="true" />
    <div class="scanline-overlay" aria-hidden="true" />
    <Layout />
  </div>
</template>

<script setup lang="ts">
import DefaultTheme from 'vitepress/theme'
import { onMounted, onUnmounted, ref } from 'vue'
import { createMatrixRain, initParticleTrail, initScrollAnimations } from './effects'

const Layout = DefaultTheme.Layout

const matrixCanvas = ref<HTMLCanvasElement | null>(null)
const particleCanvas = ref<HTMLCanvasElement | null>(null)

const cleanups: Array<() => void> = []

onMounted(() => {
  if (typeof window === 'undefined') {
    return
  }

  if (matrixCanvas.value) {
    cleanups.push(createMatrixRain(matrixCanvas.value))
  }

  if (particleCanvas.value) {
    cleanups.push(initParticleTrail(particleCanvas.value))
  }

  cleanups.push(initScrollAnimations())
})

onUnmounted(() => {
  cleanups.splice(0, cleanups.length).forEach(cleanup => cleanup())
})
</script>
