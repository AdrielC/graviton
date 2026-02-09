# ✅ VERIFICATION GUIDE - Prove Scala.js Works Before Deploying

## TL;DR - Quick Test

```bash
# 1. Build everything
./sbt buildDocsAssets
cd docs && npm run docs:build

# 2. Serve the built site
npx serve .vitepress/dist

# 3. Open your browser to:
# → http://localhost:3000/graviton/demo
# → http://localhost:3000/graviton/test-scalajs-live.html (detailed test)
```

If you see interactive components and no console errors → **IT WILL WORK ON GITHUB PAGES!** ✅

---

## Detailed Verification Steps

### Step 1: Build Assets

```bash
# This is EXACTLY what CI will run
./sbt buildDocsAssets
```

**Expected output:**
```
[info] Generating Scaladoc for core modules...
[info] Scaladoc copied to docs/public/scaladoc
[info] Building Scala.js frontend...
[info] Frontend built and copied to docs/public/js
[success] Total time: 15 s
```

**Verify:**
```bash
ls -lh docs/public/js/main.js       # Should be ~1.3MB
ls docs/public/scaladoc/index.html  # Should exist
```

### Step 2: Build VitePress

```bash
cd docs
npm install  # if not already done
npm run docs:build
```

**Expected output:**
```
✓ building client + server bundles...
✓ rendering pages...
build complete in 17s
```

**Verify:**
```bash
ls -lh .vitepress/dist/js/main.js           # Should be ~1.3MB
ls .vitepress/dist/scaladoc/index.html      # Should exist
grep -o "graviton-app" .vitepress/dist/demo.html  # Should output "graviton-app"
```

### Step 3: Test Locally

```bash
# From docs directory
npx serve .vitepress/dist
```

Now open these URLs and verify each one:

#### 1. **Main Site** - `http://localhost:3000/graviton/`
✅ Should show the home page  
✅ Logo and navigation work  
✅ "Demo" link in nav bar  

#### 2. **Scaladoc** - `http://localhost:3000/graviton/scaladoc/`
✅ Should load Scaladoc UI  
✅ Can browse packages (`graviton.core`, `graviton.runtime`, etc.)  
✅ Search works  
✅ Links to classes work  

#### 3. **Demo Page** - `http://localhost:3000/graviton/demo`
✅ Page loads without errors  
✅ You see "⚡ Graviton" header  
✅ Navigation tabs appear (🏠 Dashboard, 🔍 Explorer, 📤 Upload, 📊 Stats)  
✅ Can click between tabs  
✅ "Interactive Demo" section visible  

#### 4. **Test Page** - `http://localhost:3000/graviton/test-scalajs-live.html`
✅ Shows "✅ Scala.js loaded and initialized successfully!"  
✅ Status box turns green  
✅ Interactive components render below  
✅ No errors in browser console  

#### 5. **Direct JS Access** - `http://localhost:3000/graviton/js/main.js`
✅ Downloads the JavaScript file (or shows in browser)  
✅ File is ~1.3MB  

### Step 4: Check Browser Console

Open DevTools (F12) → Console tab

**You should see:**
```
✅ No red errors
✅ Possibly some info logs from your app
```

**You should NOT see:**
```
❌ Failed to load module: /graviton/js/main.js
❌ Cannot find module './graviton.frontend...'
❌ TypeError: ...
❌ CORS errors
```

### Step 5: Test Interactive Features

On the demo page:

1. **Dashboard Tab** (default)
   - ✅ Intro text visible
   - ✅ Feature cards render
   - ✅ Can click to other tabs

2. **Explorer Tab**
   - ✅ Search input appears
   - ✅ "Search" button clickable
   - ✅ UI is interactive (type in input box)

3. **Upload Tab** 🔥 (THE IMPORTANT ONE!)
   - ✅ Chunker selection buttons appear
   - ✅ Can select different chunkers (Fixed 256B, FastCDC, etc.)
   - ✅ File input appears
   - ✅ Can click "Choose File"
   - ✅ **Try uploading a file!**
     - Select a small text file
     - Should see chunking analysis
     - Should see chunk table with hashes
     - Should see validation results

4. **Stats Tab**
   - ✅ "Refresh Stats" button appears
   - ✅ Empty state message shows (if no server)
   - ✅ UI is responsive

### Step 6: Test Routing

The app uses hash-based routing:

- `#/` → Dashboard ✅
- `#/explorer` → Explorer ✅
- `#/upload` → Upload ✅
- `#/stats` → Stats ✅

**Verify:**
1. Manually type `http://localhost:3000/graviton/demo#/upload` in address bar
2. Should jump directly to Upload tab
3. Click "Dashboard" tab
4. URL should change to `#/`

---

## What This Proves

If all the above works, then:

✅ **Scala.js compiles correctly**  
✅ **ES modules load properly**  
✅ **Code splitting works**  
✅ **Laminar renders components**  
✅ **Routing functions**  
✅ **Event handlers work**  
✅ **File upload and chunking works**  
✅ **VitePress bundles everything correctly**  
✅ **Scaladoc is embedded and accessible**  

**Therefore: IT WILL WORK ON GITHUB PAGES! 🎉**

---

## Troubleshooting

### "Scala.js loaded but app may not have rendered yet"

**Try:**
1. Refresh the page
2. Check console for errors
3. Verify `<div id="graviton-app">` exists in HTML
4. Wait a second - Laminar might be slow to mount

### "Failed to load module: /graviton/js/main.js"

**This means:**
- Build didn't complete successfully
- Files not copied to dist

**Fix:**
```bash
# Rebuild from scratch
rm -rf docs/public/js docs/.vitepress/dist
./sbt clean buildDocsAssets
cd docs && npm run docs:build
```

### Components appear but don't respond to clicks

**Check:**
1. Any JavaScript errors in console?
2. Try a hard refresh (Ctrl+Shift+R)
3. Check if event handlers are attached (inspect elements in DevTools)

### Scaladoc not found

**Fix:**
```bash
# Regenerate scaladoc
./sbt generateDocs
cd docs && npm run docs:build
ls .vitepress/dist/scaladoc/  # Should have files
```

---

## What GitHub Pages Will Do

When you push to main:

```yaml
1. ./sbt buildDocsAssets
   → Compiles Scala.js to docs/public/js/main.js
   → Generates Scaladoc to docs/public/scaladoc/

2. npm install --prefix docs
   → Installs VitePress

3. npm run docs:build --prefix docs
   → Builds to docs/.vitepress/dist/
   → Copies public/* to dist/*

4. actions/upload-pages-artifact
   → Uploads docs/.vitepress/dist/

5. actions/deploy-pages
   → Deploys to: your-username.github.io/graviton/
```

**Your local test URL**: `http://localhost:3000/graviton/demo`  
**GitHub Pages URL**: `https://your-username.github.io/graviton/demo`

**THE PATHS ARE IDENTICAL!** So if it works locally, it WILL work on GitHub Pages.

---

## Final Checklist

Before pushing to main:

- [ ] `./sbt buildDocsAssets` completes successfully
- [ ] `docs/public/js/main.js` exists and is ~1.3MB
- [ ] `docs/public/scaladoc/index.html` exists
- [ ] `npm run docs:build` completes successfully
- [ ] `docs/.vitepress/dist/js/main.js` exists
- [ ] `docs/.vitepress/dist/scaladoc/index.html` exists
- [ ] Demo page shows interactive components locally
- [ ] No console errors in browser
- [ ] File upload and chunking works
- [ ] Scaladoc is browsable
- [ ] GitHub Pages source set to "GitHub Actions" in repo settings

---

## After Deployment

Once the "Publish Docs" workflow completes:

1. **Visit**: `https://your-username.github.io/graviton/demo`
2. **Check**: Interactive components render
3. **Test**: Upload a file, see chunking work
4. **Verify**: No console errors
5. **Browse**: `https://your-username.github.io/graviton/scaladoc/`

---

## Success! 🎉

If you can interact with the demo locally, **it's guaranteed to work on GitHub Pages** because:

- Same build process
- Same file structure  
- Same paths
- Same base URL (`/graviton/`)
- Same ES modules

**Just push and it will work!** 🚀
