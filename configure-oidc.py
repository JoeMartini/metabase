#!/usr/bin/env python3
"""Configure Metabase OIDC settings via API."""
import requests
import json
import sys

METABASE_URL = "http://localhost:3001"
ADMIN_EMAIL = "admin@martini.local"
ADMIN_PASSWORD = "Admin123!"

# OIDC Provider Configuration
OIDC_CONFIG = {
    "oidc-providers": json.dumps([{
        "key": "keycloak",
        "login-prompt": "统一身份登录 (Keycloak)",
        "issuer-uri": "https://auth.home.martini.wang:50443/realms/martini",
        "client-id": "metabase",
        "client-secret": "YOUR_CLIENT_SECRET_HERE",
        "scopes": ["openid", "email", "profile", "groups"],
        "enabled": True,
        "group-sync-enabled": True,
        "attribute-email": "email",
        "attribute-firstname": "given_name",
        "attribute-lastname": "family_name",
        "attribute-groups": "groups"
    }]),
    "oidc-enabled": True
}

def get_session_token():
    """Authenticate and get session token."""
    resp = requests.post(
        f"{METABASE_URL}/api/session",
        json={"username": ADMIN_EMAIL, "password": ADMIN_PASSWORD}
    )
    resp.raise_for_status()
    return resp.json()["id"]

def configure_oidc(token):
    """Configure OIDC settings."""
    headers = {"X-Metabase-Session": token}
    
    for setting, value in OIDC_CONFIG.items():
        resp = requests.put(
            f"{METABASE_URL}/api/setting/{setting}",
            headers=headers,
            json={"value": value}
        )
        if resp.status_code == 204:
            print(f"✅ Set {setting}")
        else:
            print(f"❌ Failed to set {setting}: {resp.status_code} {resp.text}")
            return False
    
    return True

if __name__ == "__main__":
    print("Configuring Metabase OIDC...")
    try:
        token = get_session_token()
        print(f"Authenticated, token: {token[:10]}...")
        
        if configure_oidc(token):
            print("\n✅ OIDC configuration complete!")
            print("\nNext steps:")
            print("1. Update client secret in Keycloak")
            print("2. Test login at http://localhost:3001")
        else:
            print("\n❌ Configuration failed")
            sys.exit(1)
    except Exception as e:
        print(f"❌ Error: {e}")
        sys.exit(1)
