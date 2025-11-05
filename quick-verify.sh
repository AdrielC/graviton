#!/bin/bash
set -e

echo "🔍 Quick Verification - Will Scala.js work on GitHub Pages?"
echo "=============================================================="
echo ""

# Check build outputs exist
echo "✅ Step 1: Checking built files..."
if [ -f "docs/public/js/main.js" ]; then
    SIZE=$(du -h docs/public/js/main.js | cut -f1)
    echo "   ✓ main.js exists ($SIZE)"
else
    echo "   ✗ main.js missing - run: ./sbt buildDocsAssets"
    exit 1
fi

if [ -f "docs/public/scaladoc/index.html" ]; then
    echo "   ✓ Scaladoc exists"
else
    echo "   ✗ Scaladoc missing - run: ./sbt buildDocsAssets"
    exit 1
fi

# Check dist outputs exist
echo ""
echo "✅ Step 2: Checking dist files..."
if [ -f "docs/.vitepress/dist/js/main.js" ]; then
    SIZE=$(du -h docs/.vitepress/dist/js/main.js | cut -f1)
    echo "   ✓ main.js in dist ($SIZE)"
else
    echo "   ✗ main.js not in dist - run: cd docs && npm run docs:build"
    exit 1
fi

if [ -f "docs/.vitepress/dist/scaladoc/index.html" ]; then
    echo "   ✓ Scaladoc in dist"
else
    echo "   ✗ Scaladoc not in dist"
    exit 1
fi

if [ -f "docs/.vitepress/dist/demo.html" ]; then
    echo "   ✓ Demo page exists"
else
    echo "   ✗ Demo page missing"
    exit 1
fi

# Check demo page structure
echo ""
echo "✅ Step 3: Checking demo page structure..."
if grep -q "graviton-app" docs/.vitepress/dist/demo.html; then
    echo "   ✓ Demo has graviton-app div"
else
    echo "   ✗ Demo missing graviton-app div"
    exit 1
fi

if grep -q "/graviton/js/main.js" docs/.vitepress/dist/assets/demo.*.js; then
    echo "   ✓ Demo loads main.js"
else
    echo "   ✗ Demo doesn't load main.js"
    exit 1
fi

echo ""
echo "🎉 SUCCESS! Everything is ready!"
echo ""
echo "📋 What to do next:"
echo "   1. Test locally: npx serve docs/.vitepress/dist"
echo "   2. Open: http://localhost:3000/graviton/demo"
echo "   3. Verify interactive components work"
echo "   4. Push to main to deploy!"
echo ""
echo "🌐 After deployment, visit:"
echo "   https://your-username.github.io/graviton/demo"
echo ""
