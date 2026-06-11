#!/bin/bash
# Run Metabase with OIDC support for testing

set -euo pipefail

echo "=== Metabase OIDC Test Runner ==="

# Stop existing Metabase container
echo "Stopping existing Metabase..."
docker stop metabase 2>/dev/null || true
docker rm metabase 2>/dev/null || true

# Run new Metabase with OIDC support
echo "Starting Metabase OIDC..."
docker run -d \
  --name metabase-oidc-test \
  -p 127.0.0.1:3001:3000 \
  -v /app/metabase/data:/metabase-data \
  -e MB_DB_FILE=/metabase-data/metabase.db \
  -e MB_SITE_URL=https://metabase.home.martini.wang:50443 \
  -e MB_APPLICATION_NAME="Metabase OIDC Test" \
  metabase-oidc:test

echo "Metabase OIDC started on http://localhost:3001"
echo "Waiting for startup..."

# Wait for health check
for i in {1..60}; do
  if curl -sf http://localhost:3001/api/health >/dev/null 2>&1; then
    echo "Metabase is ready!"
    break
  fi
  echo -n "."
  sleep 2
done

echo ""
echo "Next steps:"
echo "1. Complete setup at http://localhost:3001/setup"
echo "2. Configure OIDC via Admin Settings"
echo "3. Run: python3 test_oidc_integration.py"
