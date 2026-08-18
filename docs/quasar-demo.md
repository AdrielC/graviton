---
layout: doc
title: Quasar Demo
---

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  // Only run in browser, not during SSR
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    const rawBase = import.meta.env?.BASE_URL ?? '/'
    const normalizedBase = rawBase === '/' ? '' : rawBase.replace(/\/$/, '')
    if (typeof window !== 'undefined') {
      // Shared with the Graviton demo so Scala.js can build correct doc links.
      window.__GRAVITON_DOCS_BASE__ = normalizedBase
    }
    const jsPath = `${normalizedBase}/quasar/js/main.js`

    import(jsPath).catch(err => {
      console.warn('Quasar demo not loaded:', err.message);
      const appDiv = document.getElementById('quasar-app');
      if (appDiv) {
        appDiv.innerHTML = `
          <div class="error-message" style="padding: 2rem; text-align: center; background: rgba(255, 200, 0, 0.1); border: 1px solid rgba(255, 200, 0, 0.3); border-radius: 12px;">
            <h3>Interactive Quasar Demo Not Available</h3>
            <p>The Scala.js frontend hasn't been built yet. To enable the Quasar demo:</p>
            <ol style="text-align: left; display: inline-block; margin: 1rem auto;">
              <li>Build docs assets: <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">./sbt buildDocsAssets</code></li>
              <li>Rebuild the docs: <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">cd docs && npm run docs:build</code></li>
            </ol>
          </div>
        `;
      }
    });
  }
})
</script>

## Quasar Demo

<meta name="quasar-api-url" content="http://localhost:8081" />

<div id="quasar-app"></div>

::: info Backend URL
By default, the demo targets `http://localhost:8081`. Update the `<meta name="quasar-api-url" />` tag if your server runs elsewhere.
:::

