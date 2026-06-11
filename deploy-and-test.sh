#!/bin/bash
set -euo pipefail

echo "=== Metabase OIDC Deployment & Test ==="

# 1. Stop existing Metabase
echo "[1/5] Stopping existing Metabase..."
docker stop metabase 2>/dev/null || true
docker rm metabase 2>/dev/null || true

# 2. Start new Metabase with OIDC
echo "[2/5] Starting Metabase OIDC..."
docker run -d \
  --name metabase-oidc-test \
  -p 127.0.0.1:3001:3000 \
  -v /app/metabase/data:/metabase-data \
  -e MB_DB_FILE=/metabase-data/metabase.db \
  -e MB_SITE_URL=https://metabase.home.martini.wang:50443 \
  metabase-oidc:test

# 3. Wait for startup
echo "[3/5] Waiting for Metabase to start..."
for i in {1..60}; do
  if curl -sf http://localhost:3001/api/health >/dev/null 2>&1; then
    echo "  ✅ Metabase is ready!"
    break
  fi
  echo -n "."
  sleep 2
done

# 4. Setup OpenResty config
echo "[4/5] Configuring OpenResty..."
sudo cp /tmp/metabase.nginx.conf /app/1Panel/1panel/apps/openresty/openresty/conf/conf.d/metabase.conf
sudo /app/1Panel/1panel/apps/openresty/openresty/nginx/sbin/nginx -t && sudo /app/1Panel/1panel/apps/openresty/openresty/nginx/sbin/nginx -s reload

# 5. Run tests
echo "[5/5] Running OIDC integration tests..."
python3 /app/metabase-oidc/test_oidc_integration.py

echo ""
echo "=== Deployment Complete ==="
echo "URL: https://metabase.home.martini.wang:50443"
