#!/usr/bin/env bash
set -euo pipefail

# build-docs.sh
# Builds the complete Graviton documentation site

SITE_DIR="docs/target/site"

echo "🔨 Building Graviton Documentation..."
echo ""

# 1. Build markdown with mdoc
echo "📝 Building markdown with mdoc..."
./sbt docs/mdoc

# 2. Generate unified ScalaDoc for all modules
echo "📚 Generating ScalaDoc API documentation..."
./sbt docs/Compile/doc

# 3. Copy ScalaDoc to site
echo "📋 Copying ScalaDoc to site..."
mkdir -p "$SITE_DIR/api"

# Try different possible locations
if [ -d "docs/target/scala-3.7.3/api" ]; then
  cp -r docs/target/scala-3.7.3/api/* "$SITE_DIR/api/"
  echo "✓ Copied ScalaDoc from docs/target"
elif [ -d "target/scala-3.7.3/unidoc" ]; then
  cp -r target/scala-3.7.3/unidoc/* "$SITE_DIR/api/"
  echo "✓ Copied unified ScalaDoc"
else
  echo "⚠ ScalaDoc not found - run './sbt docs/Compile/doc' first"
fi

# 4. Copy static resources if they exist
if [ -d "docs/static" ]; then
  echo "📁 Copying static resources..."
  cp -r docs/static/* "$SITE_DIR/"
  echo "✓ Copied static resources"
fi

echo ""
echo "✅ Documentation site built!"
echo "📍 Location: $SITE_DIR"
echo ""
echo "Preview with:"
echo "  cd $SITE_DIR && python3 -m http.server 8000"
echo "  Then visit: http://localhost:8000"

