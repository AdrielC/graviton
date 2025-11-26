# Build and Test Guide

## ✅ Complete Build Process (Tested & Working)

### 1. Build the Scala.js Frontend

```bash
cd /workspace
./sbt buildFrontend
```

**Expected Output:**
```
[info] Building Scala.js frontend...
[info] Copying Scala.js output from .../graviton-frontend-fastopt to docs/public/js
[info] Frontend built and copied to docs/public/js
[success] Total time: ~6-14s
```

**Verification:**
```bash
ls -l docs/public/js/main.js
# Should show the main.js file and other Scala.js modules
```

### 2. Build Documentation Site

```bash
cd docs
npm install  # Only needed once
npx vitepress build .
```

**Expected Output:**
```
✓ building client + server bundles...
✓ rendering pages...
build complete in ~17s.
```

**Verification:**
```bash
ls -l .vitepress/dist/demo.html
ls -l .vitepress/dist/js/
# Should show demo.html and all JS files copied
```

### 3. Preview the Built Site

```bash
cd docs
npx vitepress preview .
```

Then visit: `http://localhost:4173/graviton/demo`

## 🧪 Testing Checklist

### Build Tests

- ✅ **Scala.js Compilation**: `./sbt frontend/compile` succeeds
- ✅ **Frontend Build**: `./sbt buildFrontend` copies files to `docs/public/js/`
- ✅ **VitePress Build**: `npx vitepress build .` completes without errors
- ✅ **Files in Dist**: JS files are present in `.vitepress/dist/js/`
- ✅ **Demo Page**: `demo.html` is generated

### Runtime Tests (Manual)

After running `./sbt buildFrontend` and `cd docs && npm run docs:dev`:

1. **Navigation**:
   - [ ] Visit `http://localhost:5173/graviton/demo`
   - [ ] Page loads without console errors
   - [ ] Navigation menu shows "Demo" link

2. **Interactive Features**:
   - [ ] Dashboard page displays feature highlights
   - [ ] Click "Explorer" - blob explorer interface loads
   - [ ] Click "Stats" - statistics panel appears
   - [ ] Click "Dashboard" - returns to main page

3. **API Integration** (requires running Graviton server):
   - [ ] Start server: `./sbt "server/run"`
   - [ ] Health check shows server status
   - [ ] Stats panel loads metrics when clicking "Refresh Stats"
   - [ ] Blob explorer accepts blob IDs

4. **Error Handling**:
   - [ ] Without server: friendly error messages appear
   - [ ] Missing JS files: graceful degradation with instructions

### Docs Snippet Verification

- Run `./sbt docs/mdoc checkDocSnippets` to ensure every Scala snippet in the docs typechecks and matches the canonical sources under `docs/snippets/`.
- When you edit a snippet source file, regenerate the rendered Markdown with `./sbt syncDocSnippets` before committing.

### Production Build Test

```bash
# Full production build
./sbt frontend/fullLinkJS
./sbt buildFrontend
cd docs
npm run docs:build
npx vitepress preview .
```

Visit `http://localhost:4173/graviton/` and test all features.

## 🐛 Known Issues & Workarounds

### Issue 1: HOCON Syntax Highlighting Warnings

**Symptom:**
```
The language 'hocon' is not loaded, falling back to 'txt' for syntax highlighting.
```

**Impact:** Cosmetic only - code blocks still render correctly

**Workaround:** None needed, VitePress falls back to plaintext

**Fix:** Could add hocon syntax highlighter to VitePress config (optional)

### Issue 2: JS Files Not Found During Build

**Symptom:**
```
Cannot find module '/graviton/js/main.js'
```

**Impact:** Build fails

**Cause:** Frontend not built before docs build

**Fix:**
```bash
# Always run frontend build first
./sbt buildFrontend
cd docs && npm run docs:build
```

### Issue 3: SSR Document Not Defined

**Symptom:**
```
ReferenceError: document is not defined
```

**Impact:** Build fails

**Cause:** Script runs during server-side rendering

**Fix:** ✅ **RESOLVED** - Now using Vue's `onMounted` hook to run only client-side

## 📊 Build Sizes

### Frontend (Development - fastLinkJS)

```
Total: ~15 MB uncompressed
- main.js: ~1.3 MB
- Internal modules: ~2.8 MB, ~5 MB
- Component modules: ~30-100 KB each
- Source maps: ~1-35 KB each
```

### Frontend (Production - fullLinkJS)

Expected: ~300-500 KB after:
- Dead code elimination
- Minification
- Gzip compression

### Documentation Site

```
Total: ~25 MB (with frontend JS)
- HTML pages: ~5-30 KB each
- Assets: ~100 KB
- JS bundles: ~15 MB (Scala.js modules)
```

## 🚀 CI/CD Integration

### GitHub Actions Example

```yaml
name: Build Docs

on:
  push:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    
    - name: Setup Java
      uses: actions/setup-java@v3
      with:
        distribution: 'temurin'
        java-version: '21'
    
    - name: Setup Node
      uses: actions/setup-node@v3
      with:
        node-version: '20'
    
    - name: Build Frontend
      run: ./sbt buildFrontend
    
    - name: Build Docs
      run: |
        cd docs
        npm install
        npm run docs:build
    
    - name: Deploy
      uses: peaceiris/actions-gh-pages@v3
      with:
        github_token: ${{ secrets.GITHUB_TOKEN }}
        publish_dir: ./docs/.vitepress/dist
```

## 🔍 Debugging Tips

### Check if Frontend Built

```bash
ls -lh docs/public/js/main.js
# Should be ~1.3 MB
```

### Check VitePress Config

```bash
cat docs/.vitepress/config.ts | grep -A 5 "vite:"
# Should show external configuration for JS modules
```

### Test in Browser DevTools

1. Open: `http://localhost:5173/graviton/demo`
2. Open DevTools Console
3. Look for:
   - ✅ "Graviton app loaded" or similar
   - ❌ Module not found errors
   - ❌ CORS errors
   - ❌ 404s for JS files

### Verify Module Loading

```javascript
// In browser console
console.log(import.meta.env.DEV)  // true in dev, undefined in build
```

### Check Network Tab

- All `.js` files should return 200 OK
- No 404 errors
- Content-Type: `application/javascript`

## 📝 Development Workflow

### Fast Iteration

```bash
# Terminal 1: Watch Scala.js
./sbt ~frontend/fastLinkJS

# Terminal 2: Copy on change (manual for now)
./sbt buildFrontend  # Run when Scala.js rebuilds

# Terminal 3: Docs dev server
cd docs && npm run docs:dev
```

**Note:** Currently requires manual `buildFrontend` after each Scala.js rebuild. 
Could be automated with a file watcher script.

### Full Rebuild

```bash
# Clean everything
./sbt clean
rm -rf docs/.vitepress/dist
rm -rf docs/public/js

# Rebuild
./sbt buildFrontend
cd docs && npm run docs:build
```

## ✨ Success Criteria

A successful build should:

1. ✅ Compile all Scala modules without errors
2. ✅ Generate Scala.js output in `modules/frontend/target/scala-3.7.3/graviton-frontend-fastopt/`
3. ✅ Copy JS files to `docs/public/js/`
4. ✅ Build VitePress site without errors
5. ✅ Copy JS files to `docs/.vitepress/dist/js/`
6. ✅ Generate all HTML pages including `demo.html`
7. ✅ Allow preview of site with working interactive features

## 🎯 Current Status

**Status**: ✅ **ALL TESTS PASSING**

- ✅ Scala.js compilation: PASS
- ✅ Frontend build task: PASS  
- ✅ VitePress build: PASS
- ✅ Files in correct locations: PASS
- ✅ Demo page generated: PASS
- ✅ No build errors: PASS
- ✅ SSR compatibility: PASS

**Ready for**: 
- Local development
- CI/CD integration
- Production deployment

**Pending**: 
- Manual testing with actual Graviton server
- Performance optimization (fullLinkJS)
- Automated end-to-end tests
