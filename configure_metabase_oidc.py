#!/usr/bin/env python3
"""Configure Metabase OIDC settings after initial setup."""
import requests
import json
import sys
import os

METABASE_URL = os.environ.get("MB_URL", "http://localhost:3001")
ADMIN_EMAIL = os.environ.get("MB_ADMIN", "admin@example.com")
ADMIN_PASS = os.environ.get("MB_ADMIN_PASS", "")

def login():
    if not ADMIN_PASS:
        print("Error: Set MB_ADMIN_PASS env var")
        sys.exit(1)
    r = requests.post(f"{METABASE_URL}/api/session", json={
        "username": ADMIN_EMAIL,
        "password": ADMIN_PASS
    })
    if r.status_code != 200:
        print(f"Login failed: {r.status_code} - {r.text}")
        sys.exit(1)
    return r.json()["id"]

def configure_oidc(session_token):
    headers = {"X-Metabase-Session": session_token}
    with open("/tmp/metabase_oidc_provider.json", "r") as f:
        provider = json.load(f)
    r = requests.put(f"{METABASE_URL}/api/setting/oidc-providers", headers=headers, json=[provider])
    print(f"Set OIDC providers: {r.status_code}")
    r = requests.put(f"{METABASE_URL}/api/setting/oidc-enabled", headers=headers, json=True)
    print(f"Enable OIDC: {r.status_code}")
    r = requests.put(f"{METABASE_URL}/api/setting/site-url", headers=headers, json="https://metabase.home.martini.wang:50443")
    print(f"Set site URL: {r.status_code}")
    print("\n✅ OIDC configuration complete!")

if __name__ == "__main__":
    token = login()
    configure_oidc(token)
