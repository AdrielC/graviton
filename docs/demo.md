---
layout: doc
title: Interactive Demo
---

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  // Only run in browser, not during SSR
  if (typeof window !== 'undefined' && typeof document !== 'undefined') {
    const rawBase = import.meta.env?.BASE_URL ?? '/'
    const normalizedBase = rawBase === '/' ? '' : rawBase.replace(/\/$/, '')
    if (typeof window !== 'undefined') {
      window.__GRAVITON_DOCS_BASE__ = normalizedBase
    }
    const jsPath = `${normalizedBase}/js/main.js`

    // Dynamically import the main module
    import(jsPath).catch(err => {
      console.warn('Interactive demo not loaded:', err.message);
      const appDiv = document.getElementById('graviton-app');
      if (appDiv) {
        appDiv.innerHTML = `
          <div class="error-message" style="padding: 2rem; text-align: center; background: rgba(255, 200, 0, 0.1); border: 1px solid rgba(255, 200, 0, 0.3); border-radius: 12px;">
            <h3>⚠️ Interactive Demo Not Available</h3>
            <p>The Scala.js frontend hasn't been built yet. To enable the interactive demo:</p>
            <ol style="text-align: left; display: inline-block; margin: 1rem auto;">
              <li>Build the frontend: <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">./sbt buildFrontend</code></li>
              <li>Rebuild the docs: <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">cd docs && npm run docs:build</code></li>
            </ol>
            <p style="margin-top: 1rem;">
              <small>For development: run <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">./sbt buildFrontend</code> then <code style="background: rgba(0,0,0,0.1); padding: 0.2rem 0.4rem; border-radius: 4px;">npm run docs:dev</code></small>
            </p>
          </div>
        `;
      }
    });
  }
})
</script>

<style>
  /* Graviton App Styles */
  .graviton-app {
    width: 100%;
    min-height: 600px;
    margin: 2rem 0;
  }

  .app-header {
    background: linear-gradient(135deg, rgba(0, 255, 65, 0.1) 0%, rgba(0, 200, 255, 0.1) 100%);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
    padding: 2rem;
    margin-bottom: 2rem;
  }

  .header-content {
    text-align: center;
  }

  .app-title {
    font-size: 2.5rem;
    margin: 0;
    background: linear-gradient(135deg, #00ff41 0%, #00c8ff 100%);
    -webkit-background-clip: text;
    -webkit-text-fill-color: transparent;
    background-clip: text;
  }

  .app-subtitle {
    color: var(--vp-c-text-2);
    margin: 0.5rem 0 0;
  }

  .app-nav {
    display: flex;
    justify-content: center;
    gap: 1rem;
    margin-top: 1.5rem;
    flex-wrap: wrap;
  }

  .nav-link {
    padding: 0.5rem 1.5rem;
    border-radius: 8px;
    background: rgba(0, 255, 65, 0.05);
    border: 1px solid var(--vp-c-brand-soft);
    text-decoration: none;
    color: var(--vp-c-text-1);
    transition: all 0.3s ease;
  }

  .nav-link:hover {
    background: rgba(0, 255, 65, 0.1);
    border-color: var(--vp-c-brand-1);
    transform: translateY(-2px);
  }

  .nav-link.active {
    background: var(--vp-c-brand-1);
    color: white;
    border-color: var(--vp-c-brand-1);
  }

  .header-health {
    text-align: center;
    margin-top: 1rem;
  }

  .health-check {
    display: inline-block;
  }

  .health-status {
    display: flex;
    align-items: center;
    gap: 1rem;
  }

  .status-badge {
    padding: 0.5rem 1rem;
    border-radius: 20px;
    font-weight: 600;
    display: inline-block;
  }

  .status-healthy, .status-ok {
    background: rgba(0, 255, 0, 0.2);
    color: #00ff00;
    border: 1px solid #00ff00;
  }

  .status-demo {
    background: rgba(0, 200, 255, 0.15);
    color: #00c8ff;
    border: 1px solid rgba(0, 200, 255, 0.8);
  }

  .status-degraded {
    background: rgba(255, 200, 0, 0.2);
    color: #ffc800;
    border: 1px solid #ffc800;
  }

  .status-error {
    background: rgba(255, 0, 0, 0.2);
    color: #ff0000;
    border: 1px solid #ff0000;
  }

  .health-details {
    font-size: 0.9rem;
    color: var(--vp-c-text-2);
  }

  .app-content {
    margin: 2rem 0;
  }

  .page-intro {
    font-size: 1.1rem;
    color: var(--vp-c-text-2);
    text-align: center;
    margin: 2rem 0;
  }

  .dashboard-grid {
    display: grid;
    gap: 2rem;
    margin-top: 2rem;
  }

  .feature-highlight {
    padding: 2rem;
    background: rgba(0, 255, 65, 0.03);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
  }

  .feature-highlight h3 {
    margin-top: 0;
    color: var(--vp-c-brand-1);
  }

  .feature-highlight ul {
    list-style: none;
    padding: 0;
  }

  .feature-highlight li {
    padding: 0.5rem 0;
    border-bottom: 1px solid var(--vp-c-divider);
  }

  .feature-highlight li:last-child {
    border-bottom: none;
  }

  .quick-links {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
    gap: 1rem;
  }

  .quick-links h3 {
    grid-column: 1 / -1;
    color: var(--vp-c-brand-1);
  }

  .feature-card-link {
    text-decoration: none;
  }

  .feature-card {
    padding: 1.5rem;
    background: rgba(0, 255, 65, 0.03);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
    transition: all 0.3s ease;
    cursor: pointer;
    height: 100%;
  }

  .feature-card:hover {
    background: rgba(0, 255, 65, 0.08);
    border-color: var(--vp-c-brand-1);
    transform: translateY(-4px);
    box-shadow: 0 10px 30px rgba(0, 255, 65, 0.2);
  }

  .feature-card p {
    color: var(--vp-c-text-2);
    margin: 0.5rem 0 0;
  }

  /* Stats Panel */
  .stats-panel h2, .blob-explorer h2 {
    color: var(--vp-c-brand-1);
    margin-top: 0;
  }

  .stats-controls {
    margin: 1.5rem 0;
  }

  .btn-primary, .btn-secondary {
    padding: 0.75rem 1.5rem;
    border-radius: 8px;
    border: none;
    font-weight: 600;
    cursor: pointer;
    transition: all 0.3s ease;
    font-size: 1rem;
  }

  .btn-primary {
    background: var(--vp-c-brand-1);
    color: white;
  }

  .btn-primary:hover:not(:disabled) {
    background: var(--vp-c-brand-2);
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0, 255, 65, 0.3);
  }

  .btn-primary:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .btn-secondary {
    background: rgba(0, 255, 65, 0.1);
    color: var(--vp-c-brand-1);
    border: 1px solid var(--vp-c-brand-soft);
  }

  .btn-secondary:hover:not(:disabled) {
    background: rgba(0, 255, 65, 0.2);
    border-color: var(--vp-c-brand-1);
  }

  .stats-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 1.5rem;
    margin-top: 2rem;
  }

  .stat-card {
    position: relative;
    padding: 1.5rem;
    background: rgba(0, 255, 65, 0.03);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
    text-align: center;
  }

  .stat-icon {
    font-size: 2.5rem;
    margin-bottom: 0.5rem;
  }

  .stat-label {
    color: var(--vp-c-text-2);
    font-size: 0.9rem;
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .stat-value {
    font-size: 2rem;
    font-weight: 700;
    color: var(--vp-c-brand-1);
    margin: 0.5rem 0;
  }

  .stats-empty {
    text-align: center;
    padding: 3rem;
    color: var(--vp-c-text-2);
    font-style: italic;
  }

  /* Blob Explorer */
  .search-box {
    display: flex;
    gap: 1rem;
    margin: 1.5rem 0;
    flex-wrap: wrap;
  }

  .blob-id-input {
    flex: 1;
    min-width: 300px;
    padding: 0.75rem;
    border: 1px solid var(--vp-c-border);
    border-radius: 8px;
    font-family: 'Courier New', monospace;
    font-size: 0.95rem;
    background: var(--vp-c-bg);
    color: var(--vp-c-text-1);
  }

  .blob-id-input:focus {
    outline: none;
    border-color: var(--vp-c-brand-1);
    box-shadow: 0 0 0 3px rgba(0, 255, 65, 0.1);
  }

  .blob-details {
    margin-top: 2rem;
    padding: 2rem;
    background: rgba(0, 255, 65, 0.03);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
  }

  .blob-details h3 {
    margin-top: 0;
    color: var(--vp-c-brand-1);
  }

  .metadata-grid {
    margin: 1.5rem 0;
  }

  .metadata-row {
    display: flex;
    gap: 1rem;
    padding: 0.75rem 0;
    border-bottom: 1px solid var(--vp-c-divider);
  }

  .metadata-label {
    font-weight: 600;
    color: var(--vp-c-text-2);
    min-width: 120px;
  }

  .metadata-value {
    color: var(--vp-c-text-1);
    word-break: break-all;
  }

  .metadata-checksums {
    margin-top: 1.5rem;
  }

  .metadata-checksums h4 {
    color: var(--vp-c-brand-1);
  }

  .checksum-row {
    display: flex;
    gap: 1rem;
    padding: 0.5rem;
    background: var(--vp-c-bg-soft);
    border-radius: 4px;
    margin: 0.5rem 0;
    font-family: 'Courier New', monospace;
    font-size: 0.9rem;
  }

  .checksum-algo {
    font-weight: 600;
    color: var(--vp-c-text-2);
  }

  .checksum-value {
    color: var(--vp-c-text-1);
    word-break: break-all;
  }

  .manifest-view {
    margin-top: 2rem;
    padding: 2rem;
    background: rgba(0, 200, 255, 0.03);
    border: 1px solid rgba(0, 200, 255, 0.3);
    border-radius: 12px;
  }

  .manifest-view h3 {
    margin-top: 0;
    color: #00c8ff;
  }

  .manifest-summary {
    margin: 1rem 0;
    color: var(--vp-c-text-2);
  }

  .chunks-list {
    margin-top: 1.5rem;
  }

  .chunks-list table {
    width: 100%;
    border-collapse: collapse;
  }

  .chunks-list th {
    text-align: left;
    padding: 0.75rem;
    background: rgba(0, 200, 255, 0.1);
    border-bottom: 2px solid #00c8ff;
    color: var(--vp-c-text-1);
    font-weight: 600;
  }

  .chunks-list td {
    padding: 0.75rem;
    border-bottom: 1px solid var(--vp-c-divider);
    color: var(--vp-c-text-2);
  }

  .chunks-list tr:hover {
    background: rgba(0, 200, 255, 0.05);
  }

  .error-message {
    margin: 1.5rem 0;
    padding: 1rem;
    background: rgba(255, 0, 0, 0.1);
    border: 1px solid rgba(255, 0, 0, 0.3);
    border-radius: 8px;
    color: #ff4444;
  }

  .loading-spinner {
    text-align: center;
    padding: 2rem;
    color: var(--vp-c-text-2);
    font-style: italic;
  }

  .app-footer {
    margin-top: 3rem;
    padding: 2rem;
    text-align: center;
    border-top: 1px solid var(--vp-c-divider);
    color: var(--vp-c-text-2);
  }

  .app-footer a {
    color: var(--vp-c-brand-1);
    text-decoration: none;
  }

  .app-footer a:hover {
    text-decoration: underline;
  }

  code {
    background: var(--vp-c-bg-soft);
    padding: 0.2rem 0.4rem;
    border-radius: 4px;
    font-family: 'Courier New', monospace;
    font-size: 0.9em;
  }

  .demo-banner {
    margin-top: 1rem;
    padding: 0.75rem 1rem;
    border-radius: 8px;
    background: rgba(0, 200, 255, 0.08);
    border: 1px solid rgba(0, 200, 255, 0.4);
    display: flex;
    align-items: center;
    gap: 0.75rem;
  }

  .demo-icon {
    font-size: 1.4rem;
  }

  .demo-text {
    color: var(--vp-c-text-2);
    font-size: 0.95rem;
  }

  .demo-hint {
    margin: 1.5rem 0;
    padding: 1rem 1.25rem;
    background: rgba(0, 255, 65, 0.05);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 10px;
    color: var(--vp-c-text-2);
  }

  .sample-id-list {
    display: flex;
    flex-wrap: wrap;
    gap: 0.75rem;
    margin-top: 0.75rem;
  }

  .sample-id-btn {
    padding: 0.4rem 0.75rem;
    border-radius: 6px;
    border: 1px solid var(--vp-c-brand-soft);
    background: rgba(0, 255, 65, 0.08);
    color: var(--vp-c-text-1);
    cursor: pointer;
    transition: all 0.2s ease;
    font-family: 'Courier New', monospace;
    font-size: 0.85rem;
  }

  .sample-id-btn:hover:not(:disabled) {
    background: rgba(0, 255, 65, 0.15);
    border-color: var(--vp-c-brand-1);
  }

  .sample-id-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .fastcdc-config {
    margin-top: 1.5rem;
    padding: 1.5rem;
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
    background: rgba(0, 200, 255, 0.05);
  }

  .fastcdc-config h4 {
    margin: 0 0 0.75rem 0;
    color: var(--vp-c-brand-1);
  }

  .config-help {
    margin: 0 0 1.25rem 0;
    color: var(--vp-c-text-3);
    font-size: 0.9rem;
  }

  .config-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 1rem;
  }

  .config-field {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    padding: 1rem;
    border-radius: 10px;
    background: rgba(0, 255, 65, 0.04);
    border: 1px solid var(--vp-c-brand-soft);
  }

  .config-field input[type="range"] {
    width: 100%;
  }

  .config-field input[type="number"] {
    padding: 0.4rem;
    border-radius: 6px;
    border: 1px solid var(--vp-c-border);
    background: var(--vp-c-bg);
    color: var(--vp-c-text-1);
  }

  .config-label {
    font-weight: 600;
    color: var(--vp-c-text-1);
  }

  .config-value {
    font-size: 0.85rem;
    color: var(--vp-c-text-3);
  }

  .config-summary {
    margin-top: 1rem;
    font-size: 0.9rem;
    color: var(--vp-c-text-2);
  }

  .chunk-visualizer-section {
    margin: 2rem 0;
  }

  .chunk-visualization {
    display: flex;
    flex-direction: column;
    gap: 0.75rem;
  }

  .chunk-bars {
    display: flex;
    height: 32px;
    border-radius: 8px;
    overflow: hidden;
    border: 1px solid var(--vp-c-divider);
    background: rgba(0, 255, 65, 0.06);
  }

  .chunk-bar {
    background: rgba(0, 255, 65, 0.45);
  }

  .chunk-bar.shared {
    background: rgba(0, 200, 255, 0.45);
  }

  .chunk-legend {
    display: flex;
    flex-wrap: wrap;
    gap: 1rem;
    font-size: 0.85rem;
    color: var(--vp-c-text-3);
  }

  .legend-item {
    display: flex;
    align-items: center;
    gap: 0.4rem;
  }

  .legend-swatch {
    width: 12px;
    height: 12px;
    border-radius: 3px;
    display: inline-block;
  }

  .legend-swatch.unique {
    background: rgba(0, 255, 65, 0.75);
  }

  .legend-swatch.shared {
    background: rgba(0, 200, 255, 0.75);
  }

  .chunk-visualization-empty {
    font-style: italic;
    color: var(--vp-c-text-3);
  }

  .fastcdc-used {
    margin-top: 1rem;
    font-size: 0.9rem;
    color: var(--vp-c-text-2);
  }

  .fastcdc-used .stat-label {
    font-weight: 600;
    margin-right: 0.5rem;
  }

  /* File Upload Styles */
  .file-upload {
    padding: 2rem;
  }

  .file-upload h2 {
    color: var(--vp-c-brand-1);
    margin-top: 0;
  }

  .upload-intro {
    color: var(--vp-c-text-2);
    margin: 1rem 0 2rem;
    text-align: center;
  }

  .chunker-selection {
    margin: 2rem 0;
    padding: 1.5rem;
    background: rgba(0, 255, 65, 0.03);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
  }

  .chunker-selection h3 {
    color: var(--vp-c-brand-1);
    margin-top: 0;
  }

  .chunker-buttons {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 1rem;
    margin-top: 1rem;
  }

  .chunker-btn {
    padding: 1rem;
    background: rgba(0, 255, 65, 0.05);
    border: 2px solid var(--vp-c-brand-soft);
    border-radius: 8px;
    cursor: pointer;
    transition: all 0.3s ease;
    text-align: left;
  }

  .chunker-btn:hover {
    background: rgba(0, 255, 65, 0.1);
    border-color: var(--vp-c-brand-1);
    transform: translateY(-2px);
  }

  .chunker-btn.active {
    background: rgba(0, 255, 65, 0.15);
    border-color: var(--vp-c-brand-1);
    box-shadow: 0 0 0 3px rgba(0, 255, 65, 0.2);
  }

  .chunker-name {
    font-weight: 700;
    color: var(--vp-c-brand-1);
    margin-bottom: 0.5rem;
  }

  .chunker-desc {
    font-size: 0.85rem;
    color: var(--vp-c-text-2);
  }

  .upload-area {
    display: flex;
    gap: 1rem;
    align-items: center;
    margin: 2rem 0;
    padding: 2rem;
    background: rgba(0, 200, 255, 0.03);
    border: 2px dashed var(--vp-c-brand-soft);
    border-radius: 12px;
    transition: all 0.3s ease;
  }

  .upload-area:hover {
    border-color: var(--vp-c-brand-1);
    background: rgba(0, 200, 255, 0.08);
  }

  .file-input {
    flex: 1;
    padding: 0.75rem;
    border: 1px solid var(--vp-c-border);
    border-radius: 8px;
    background: var(--vp-c-bg);
    color: var(--vp-c-text-1);
    cursor: pointer;
  }

  .clear-btn {
    white-space: nowrap;
  }

  .global-stats {
    margin: 2rem 0;
    padding: 2rem;
    background: linear-gradient(135deg, rgba(0, 255, 65, 0.1) 0%, rgba(0, 200, 255, 0.1) 100%);
    border: 1px solid var(--vp-c-brand-1);
    border-radius: 12px;
  }

  .global-stats h3 {
    color: var(--vp-c-brand-1);
    margin-top: 0;
  }

  .stats-grid-compact {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 1rem;
    margin-top: 1rem;
  }

  .stat-item {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
    padding: 1rem;
    background: rgba(255, 255, 255, 0.05);
    border-radius: 8px;
  }

  .stat-item .stat-label {
    font-size: 0.85rem;
    color: var(--vp-c-text-2);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  .stat-item .stat-value {
    font-size: 1.5rem;
    font-weight: 700;
    color: var(--vp-c-brand-1);
  }

  .analyses-list {
    display: flex;
    flex-direction: column;
    gap: 1.5rem;
    margin-top: 2rem;
  }

  .file-analysis {
    background: rgba(0, 255, 65, 0.03);
    border: 1px solid var(--vp-c-brand-soft);
    border-radius: 12px;
    overflow: hidden;
  }

  .file-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding: 1.5rem;
    cursor: pointer;
    transition: background 0.2s ease;
  }

  .file-header:hover {
    background: rgba(0, 255, 65, 0.08);
  }

  .file-info {
    flex: 1;
  }

  .file-name {
    margin: 0 0 0.5rem 0;
    color: var(--vp-c-text-1);
  }

  .file-meta {
    font-size: 0.9rem;
    color: var(--vp-c-text-2);
  }

  .expand-icon {
    font-size: 1.2rem;
    color: var(--vp-c-brand-1);
    transition: transform 0.2s ease;
  }

  .file-details-expanded {
    padding: 1.5rem;
    border-top: 1px solid var(--vp-c-divider);
  }

  .validations-section,
  .chunk-stats,
  .chunks-section {
    margin-bottom: 2rem;
  }

  .validations-section h5,
  .chunk-stats h5,
  .chunks-section h5 {
    color: var(--vp-c-brand-1);
    margin: 0 0 1rem 0;
  }

  .validations-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
  }

  .validation-item {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    padding: 0.75rem;
    background: var(--vp-c-bg-soft);
    border-radius: 6px;
  }

  .validation-item.error {
    background: rgba(255, 0, 0, 0.05);
    border-left: 3px solid #ff0000;
  }

  .validation-item.success {
    background: rgba(0, 255, 0, 0.05);
    border-left: 3px solid #00ff00;
  }

  .validation-icon {
    font-size: 1.2rem;
  }

  .chunks-table-wrapper {
    overflow-x: auto;
  }

  .chunks-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.9rem;
  }

  .chunks-table th {
    text-align: left;
    padding: 0.75rem;
    background: rgba(0, 255, 65, 0.1);
    border-bottom: 2px solid var(--vp-c-brand-1);
    color: var(--vp-c-text-1);
    font-weight: 600;
  }

  .chunks-table td {
    padding: 0.75rem;
    border-bottom: 1px solid var(--vp-c-divider);
    color: var(--vp-c-text-2);
  }

  .chunks-table tr:hover {
    background: rgba(0, 255, 65, 0.05);
  }

  .chunks-table tr.shared-chunk {
    background: rgba(0, 200, 255, 0.05);
  }

  .chunks-table tr.shared-chunk:hover {
    background: rgba(0, 200, 255, 0.1);
  }

  .hash-value {
    font-family: 'Courier New', monospace;
    font-size: 0.85rem;
    color: var(--vp-c-text-1);
  }

  .no-sharing {
    color: var(--vp-c-text-3);
    font-style: italic;
  }

  .shared-files {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    position: relative;
  }

  .share-indicator {
    padding: 0.25rem 0.5rem;
    background: rgba(0, 200, 255, 0.2);
    border: 1px solid rgba(0, 200, 255, 0.5);
    border-radius: 4px;
    color: #00c8ff;
    font-size: 0.85rem;
    font-weight: 600;
    cursor: help;
  }

  .share-tooltip {
    display: none;
    position: absolute;
    left: 100%;
    top: 50%;
    transform: translateY(-50%);
    margin-left: 0.5rem;
    padding: 0.5rem 0.75rem;
    background: var(--vp-c-bg);
    border: 1px solid var(--vp-c-brand-1);
    border-radius: 6px;
    white-space: nowrap;
    z-index: 100;
    font-size: 0.85rem;
    color: var(--vp-c-text-1);
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.2);
  }

  .shared-files:hover .share-tooltip {
    display: block;
  }

  .truncation-notice {
    margin-top: 1rem;
    padding: 0.75rem;
    background: rgba(255, 200, 0, 0.1);
    border: 1px solid rgba(255, 200, 0, 0.3);
    border-radius: 6px;
    color: var(--vp-c-text-2);
    text-align: center;
    font-style: italic;
  }
</style>

# 🎮 Interactive Demo

Experience Graviton's capabilities through this interactive Scala.js application!

::: info Implementation Note
The chunking algorithms demonstrated here use the **same FastCDC implementation** as the server-side code in `graviton-streams`. Upload multiple files to see real content-defined chunking and block-level deduplication in action!
:::

<meta name="graviton-api-url" content="http://localhost:8080" />

<div id="graviton-app"></div>

::: tip Note
By default the demo looks for a Graviton instance at `http://localhost:8080`. Update the `<meta name="graviton-api-url" />` tag if your server runs elsewhere.
:::

::: info Demo Mode
When this page cannot reach a live server (such as on GitHub Pages), the UI automatically switches to a simulated dataset. You can still explore chunking, manifests, and stats without any backend.
:::

## Features

This interactive demo showcases:

- **🏠 Dashboard**: Overview of Graviton's capabilities
- **🔍 Blob Explorer**: Search and inspect blob metadata and manifests
- **📤 File Upload**: Interactive chunking visualization with multiple strategies
  - Compare Fixed-size vs FastCDC (content-defined) chunking
  - See block sharing and deduplication across files in real-time
  - Tune FastCDC bounds with the CAS Chunk Tuner to visualize breakpoints and explore dedup sensitivity
  - View validation results and chunk-level details
- **📊 Statistics**: Real-time system metrics and deduplication ratios

## Try it yourself

1. **Start a Graviton server**:
   ```bash
   sbt "server/run"
   ```

2. **Upload some blobs** using the API or CLI

3. **Explore** the blobs using the interactive UI above!

## Architecture

This frontend is built with:

- **Scala.js**: Type-safe JavaScript from Scala
- **Laminar**: Reactive UI with FRP (Functional Reactive Programming)
- **ZIO**: Effect system for async operations
- **Shared Models**: Cross-compiled protocol models between JVM and JS
