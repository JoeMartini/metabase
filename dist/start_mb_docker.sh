#!/bin/sh
# Read secrets from files and export as env vars for the Docker test container

if [ -r "/run/secrets/encryption_key" ]; then
  export MB_ENCRYPTION_SECRET_KEY=$(cat /run/secrets/encryption_key | tr -d '\n')
fi

if [ -r "/run/secrets/sf_api_key" ]; then
  export MB_LLM_CUSTOM_PROVIDER_API_KEY=$(cat /run/secrets/sf_api_key | tr -d '\n')
fi

if [ -r "/run/secrets/oidc_providers.json" ]; then
  export MB_OIDC_PROVIDERS=$(cat /run/secrets/oidc_providers.json)
fi

export MB_LLM_CUSTOM_PROVIDER_BASE_URL="https://api.siliconflow.cn/v1"
export MB_LLM_CUSTOM_PROVIDER_MODEL="deepseek-ai/DeepSeek-V3"
export MB_OIDC_AUTH_MODE="${MB_OIDC_AUTH_MODE:-oidc_full}"
export MB_OIDC_ENABLED=true
export MB_SITE_URL="${MB_SITE_URL:-https://bi.home.martini.wang:50443}"
export MB_LICENSE_TOKEN_MISSING_BANNER_DISMISSAL_TIMESTAMP='["2026-06-18T08:46:30.536Z"]'

exec java -cp "/app/classes-patch:/app/metabase.jar" metabase.core.bootstrap "$@"
