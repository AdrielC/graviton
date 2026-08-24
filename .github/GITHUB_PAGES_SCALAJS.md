# GitHub Pages + Scala.js Setup

This repository deploys Scala.js interactive components to GitHub Pages!

## How It Works

### Workflows

1. **`docs.yaml`** - Main deployment workflow (on push to `main`)
   - ✅ Builds Scaladoc
   - ✅ Compiles Scala.js frontend to ES modules
   - ✅ Copies everything to `docs/public/`
   - ✅ Builds VitePress site with Scala.js assets
   - ✅ Deploys to GitHub Pages

2. **`ci.yml`** - PR checks (on PRs and pushes)
   - ✅ Runs scalafmt checks
   - ✅ Runs all tests
   - ✅ Verifies docs build (no deployment)

### Build Steps

```yaml
# 1. Compile Scala.js + Generate Scaladoc
./sbt buildDocsAssets

# This runs:
#   - generateDocs → Creates scaladoc in docs/public/scaladoc/
#   - buildFrontend → Compiles frontend/Main.scala to docs/public/js/main.js
#     - frontend/fastLinkJS → Scala.js compilation (ES modules)
#     - Copies all .js files to docs/public/js/

# 2. Build VitePress site
npm run docs:build --prefix docs

# This:
#   - Bundles VitePress app
#   - Copies docs/public/** to docs/.vitepress/dist/
#   - Includes all Scala.js modules (main.js + split modules)
#   - Preserves module structure for dynamic imports

# 3. Deploy
# uploads docs/.vitepress/dist/ to GitHub Pages
```

## Deployed Structure

```
https://your-username.github.io/graviton/
├── index.html              # Home page
├── demo.html               # Live Scala.js operations console
├── js/                     # Scala.js modules
│   ├── main.js             # Main entry (1.3MB, code-split)
│   ├── graviton.frontend.-Main.js
│   ├── graviton.frontend.-GravitonApp$.js
│   ├── graviton.frontend.components.-*.js
│   └── ...                 # All split modules
├── scaladoc/               # API documentation
│   └── index.html
└── assets/                 # VitePress bundles
    ├── app.*.js
    └── demo.md.*.js        # Installs the Scala.js module script
```

## What Happens on Console Page Load

1. User navigates to `/graviton/demo`
2. VitePress loads `demo.html`
3. Page includes `<div id="graviton-app"></div>`
4. Vue component's `onMounted` hook executes
5. Adds `/graviton/js/main.js` as a module script
6. Scala.js `Main.main()` executes
7. Laminar renders the interactive app
8. The user uploads bytes, lists persisted manifests, inspects block layouts, verifies and downloads stored bytes, or deletes a manifest through the live HTTP API.

## Verification

After deployment, check these URLs:

```bash
# Main site
https://your-username.github.io/graviton/

# Live operations console
https://your-username.github.io/graviton/demo

# Scala.js main module
https://your-username.github.io/graviton/js/main.js

# Scaladoc
https://your-username.github.io/graviton/scaladoc/
```

## Local Testing

Test the exact same build that will run in CI:

```bash
# Run the full build
./sbt buildDocsAssets
cd docs && npm install && npm run docs:build

# Serve the built site
npm run docs:preview

# Check these paths work:
# - http://localhost:4173/graviton/demo
# - http://localhost:4173/graviton/js/main.js
```

## Troubleshooting

### Scala.js not loading

Check browser console for:
```
Failed to load module: /graviton/js/main.js
```

**Fix**: Make sure `./sbt buildDocsAssets` ran successfully in CI. Check the Actions logs.

### Interactive components not appearing

1. Check `demo.html` has `<div id="graviton-app"></div>`
2. Check browser console for import errors
3. Verify `main.js` is present at `/graviton/js/main.js`
4. Check Vue onMounted hook is executing

### Module not found errors

**Symptom**: `Cannot find module './graviton.frontend.-Main.js'`

**Fix**: This means code splitting isn't working. Check:
- All `.js` files copied from `frontend/target/` to `docs/public/js/`
- VitePress didn't try to bundle them (external config is correct)

### Build failing in CI

Check the order:
1. ✅ `./sbt buildDocsAssets` FIRST
2. ✅ `npm install --prefix docs` 
3. ✅ `npm run docs:build --prefix docs`

If you run npm build before sbt build, the JS files won't be there!

## Configuration Files

### `build.sbt`
```scala
// Enable Scala.js with main module initialization
scalaJSUseMainModuleInitializer := true

// ES modules for modern browsers
scalaJSLinkerConfig ~= {
  _.withModuleKind(ModuleKind.ESModule)
    .withModuleSplitStyle(ModuleSplitStyle.SmallModulesFor(List("graviton.frontend")))
}

// Task to build and copy frontend
lazy val buildFrontend = taskKey[Unit]("Build Scala.js frontend and copy to docs")
buildFrontend := {
  val log = Keys.streams.value.log
  val report = (frontend / Compile / fastLinkJS).value
  val sourceDir = (frontend / Compile / fastLinkJS / scalaJSLinkerOutputDirectory).value
  val targetDir = file("docs/public/js")
  
  IO.delete(targetDir)
  IO.createDirectory(targetDir)
  IO.copyDirectory(sourceDir, targetDir, overwrite = true)
  
  log.info(s"Frontend built and copied to $targetDir")
}

// Combined task
lazy val buildDocsAssets = taskKey[Unit]("Build all documentation assets")
buildDocsAssets := Def.sequential(
  generateDocs,
  buildFrontend
).value
```

### `.vitepress/config.ts`
```typescript
export default defineConfig({
  base: '/graviton/',  // GitHub repo name
  vite: {
    build: {
      rollupOptions: {
        external: [
          // Don't bundle Scala.js - keep as external modules
          /^\/graviton\/js\/.+\.js$/,
          /^\/js\/.+\.js$/
        ]
      }
    }
  },
  ignoreDeadLinks: [
    // Ignore dynamic Scala.js imports
    /^\/graviton\/js\/.+/,
    /^\/js\/.+/,
  ]
})
```

### `docs/demo.md`
```vue
<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  if (typeof window !== 'undefined') {
    const isDev = import.meta.env?.DEV;
    const jsPath = isDev ? '/js/main.js' : '/graviton/js/main.js';
    
    import(jsPath).catch(err => {
      console.warn('Scala.js not loaded:', err.message);
    });
  }
})
</script>

<div id="graviton-app"></div>
```

## GitHub Pages Settings

Make sure in your repo settings:

1. **Settings** → **Pages**
2. **Source**: GitHub Actions (not "Deploy from branch")
3. **Custom domain**: (optional)

The workflow uses the new `actions/deploy-pages@v4` action which requires this setting.

## Success Indicators

✅ CI workflow completes successfully  
✅ `main.js` appears in deployed `/graviton/js/`  
✅ Demo page loads without console errors  
✅ Interactive components render  
✅ Routing works (Dashboard, Explorer, Upload, Stats)  
✅ File upload shows chunking visualization  

## Performance

The Scala.js bundle is ~1.3MB (fastLinkJS):
- Code splitting reduces initial load
- Gzip compression in GitHub Pages helps
- Consider `fullLinkJS` for production (optimized, ~800KB)

To use fullLinkJS:
```diff
- val report = (frontend / Compile / fastLinkJS).value
+ val report = (frontend / Compile / fullLinkJS).value
```

---

**Status**: ✅ Ready to deploy!

Your Scala.js interactive components will work perfectly on GitHub Pages! 🚀
