#!/bin/bash
set -e

echo "Quick verification: docs console production assets"
echo "=============================================================="
echo ""

# Check build outputs exist
echo "Step 1: checking generated assets..."
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
echo "Step 2: checking production site output..."
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
    echo "   ✓ Live console page exists"
else
    echo "   ✗ Live console page missing"
    exit 1
fi

# Check live console page structure
echo ""
echo "Step 3: checking live console bootstrap..."
if grep -q "graviton-app" docs/.vitepress/dist/demo.html; then
    echo "   ✓ Live console has graviton-app div"
else
    echo "   ✗ Live console missing graviton-app div"
    exit 1
fi

if grep -q "js/main.js" docs/.vitepress/dist/assets/demo.*.js \
    && grep -q "graviton-console-bundle" docs/.vitepress/dist/assets/demo.*.js; then
    echo "   ✓ Live console installs the main.js module script"
else
    echo "   ✗ Live console doesn't install the main.js module script"
    exit 1
fi

echo ""
echo "PASS: production docs include the compiled console and Scaladoc."
echo "This check does not prove a deployed backend. Run scripts/verify-http-lifecycle.sh against a Graviton server for that proof."
echo ""
