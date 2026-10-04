#!/usr/bin/env bash
# Împachetează serverul standalone (după `npm run build`) pentru telefon: dist/tunebox-app.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."

rm -rf dist
mkdir -p dist/tunebox
cp -r .next/standalone/. dist/tunebox/
cp -r .next/static dist/tunebox/.next/static
cp -r public dist/tunebox/public

# nu luăm date locale și nici bibliotecile de imagini (native, doar pentru PC, nefolosite)
rm -rf dist/tunebox/downloads dist/tunebox/data dist/tunebox/.env \
       dist/tunebox/node_modules/@img dist/tunebox/node_modules/sharp

git rev-parse --short HEAD > dist/tunebox/VERSION 2>/dev/null || date +%s > dist/tunebox/VERSION
tar -C dist -czf dist/tunebox-app.tar.gz tunebox
echo "dist/tunebox-app.tar.gz ($(du -h dist/tunebox-app.tar.gz | cut -f1))"
