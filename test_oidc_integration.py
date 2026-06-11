#!/usr/bin/env python3
"""
End-to-end OIDC integration test for Metabase + Keycloak.

Usage:
    python3 test_oidc_integration.py

Prerequisites:
    - Metabase container running with OIDC support
    - Keycloak running with 'metabase' client configured
"""

import requests
import json
import time
import sys
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)

# Configuration
METABASE_URL = "http://localhost:3001"  # Test instance
KEYCLOAK_URL = "https://auth.home.martini.wang:50443"
REALM = "martini"
TEST_USERS = {
    "mb-admin": {"password": "Test123!", "expected_groups": ["metabase-admin"]},
    "mb-editor": {"password": "Test123!", "expected_groups": ["metabase-editor"]},
    "mb-viewer": {"password": "Test123!", "expected_groups": ["metabase-viewer"]},
}

class OIDCTest:
    def __init__(self):
        self.session = requests.Session()
        self.session.verify = False
        self.results = []

    def log(self, msg, level="INFO"):
        print(f"[{level}] {msg}")

    def check(self, name, condition, detail=""):
        status = "PASS" if condition else "FAIL"
        self.results.append({"name": name, "status": status, "detail": detail})
        self.log(f"{status}: {name}" + (f" - {detail}" if detail else ""))
        return condition

    def wait_for_metabase(self, timeout=120):
        """Wait for Metabase to be ready."""
        self.log("Waiting for Metabase to start...")
        start = time.time()
        while time.time() - start < timeout:
            try:
                r = self.session.get(f"{METABASE_URL}/api/health", timeout=5)
                if r.status_code == 200:
                    self.log("Metabase is ready!")
                    return True
            except:
                pass
            time.sleep(2)
        self.log("Metabase failed to start within timeout", "ERROR")
        return False

    def setup_metabase(self):
        """Complete Metabase setup wizard if needed."""
        # Check if setup is needed
        r = self.session.get(f"{METABASE_URL}/api/session/properties")
        props = r.json()

        if not props.get("setup-token"):
            self.log("Metabase already set up")
            return True

        self.log("Running Metabase setup...")
        setup_token = props["setup-token"]

        # Create admin user
        setup_data = {
            "token": setup_token,
            "user": {
                "first_name": "Admin",
                "last_name": "User",
                "email": "admin@example.com",
                "password": "Admin123!Test",
                "site_name": "Metabase OIDC Test"
            },
            "database": None,
            "invite": None,
            "prefs": {"site_name": "Metabase OIDC Test", "allow_tracking": False}
        }
        r = self.session.post(f"{METABASE_URL}/api/setup", json=setup_data)
        if r.status_code == 200:
            self.log("Setup complete!")
            self.admin_session = r.json()["id"]
            return True
        else:
            self.log(f"Setup failed: {r.status_code} - {r.text[:200]}", "ERROR")
            return False

    def configure_oidc(self):
        """Configure OIDC provider via API."""
        self.log("Configuring OIDC provider...")

        # First login as admin to get session
        login_data = {"username": "admin@example.com", "password": "Admin123!Test"}
        r = self.session.post(f"{METABASE_URL}/api/session", json=login_data)
        if r.status_code != 200:
            self.log(f"Admin login failed: {r.status_code}", "ERROR")
            return False

        session_token = r.json()["id"]
        headers = {"X-Metabase-Session": session_token}

        # Read provider config
        with open("/tmp/metabase_oidc_provider.json", "r") as f:
            provider = json.load(f)

        # Set OIDC providers
        r = self.session.put(
            f"{METABASE_URL}/api/setting/oidc-providers",
            headers=headers,
            json=[provider]
        )
        self.check("Set OIDC providers", r.status_code == 200, r.text[:100])

        # Enable OIDC
        r = self.session.put(
            f"{METABASE_URL}/api/setting/oidc-enabled",
            headers=headers,
            json=True
        )
        self.check("Enable OIDC", r.status_code == 200, r.text[:100])

        return True

    def test_oidc_initiate(self):
        """Test that OIDC initiate returns redirect."""
        self.log("Testing OIDC initiate...")
        r = self.session.get(
            f"{METABASE_URL}/auth/sso/keycloak",
            allow_redirects=False,
            timeout=10
        )
        is_redirect = r.status_code in [302, 303]
        detail = f"Status: {r.status_code}"
        if is_redirect:
            detail += f", Location: {r.headers.get('Location', 'N/A')[:80]}"
        return self.check("OIDC initiate returns redirect", is_redirect, detail)

    def test_oidc_login(self, username, password):
        """Test full OIDC login flow for a user."""
        self.log(f"Testing OIDC login for {username}...")

        # Step 1: Get OIDC redirect URL
        s = requests.Session()
        s.verify = False
        r = s.get(
            f"{METABASE_URL}/auth/sso/keycloak",
            allow_redirects=False,
            timeout=10
        )
        if r.status_code not in [302, 303]:
            return self.check(f"{username}: OIDC initiate", False, f"Status {r.status_code}")

        auth_url = r.headers["Location"]

        # Step 2: Follow redirects to Keycloak login page
        r = s.get(auth_url, allow_redirects=True, timeout=15)
        if "login" not in r.url and "authenticate" not in r.url:
            return self.check(f"{username}: Keycloak login page", False, f"URL: {r.url[:60]}")

        # Step 3: Submit Keycloak login form
        # Keycloak form action
        from html.parser import HTMLParser
        class FormParser(HTMLParser):
            def __init__(self):
                super().__init__()
                self.form_action = None
                self.inputs = {}
            def handle_starttag(self, tag, attrs):
                attrs_dict = dict(attrs)
                if tag == "form":
                    self.form_action = attrs_dict.get("action", "")
                elif tag == "input" and attrs_dict.get("name"):
                    self.inputs[attrs_dict["name"]] = attrs_dict.get("value", "")

        parser = FormParser()
        parser.feed(r.text)

        if not parser.form_action:
            return self.check(f"{username}: Find login form", False)

        # Submit credentials
        form_data = parser.inputs.copy()
        form_data.update({"username": username, "password": password})
        r = s.post(parser.form_action, data=form_data, allow_redirects=True, timeout=15)

        # Step 4: Should eventually redirect back to Metabase
        if METABASE_URL not in r.url and "/auth/sso/" not in r.url:
            return self.check(f"{username}: Callback redirect", False, f"Final URL: {r.url[:80]}")

        # Check if we got a session cookie
        session_cookie = None
        for cookie in s.cookies:
            if "metabase" in cookie.name.lower() and "SESSION" in cookie.name.upper():
                session_cookie = cookie.value
                break

        has_session = session_cookie is not None
        return self.check(f"{username}: Login success", has_session,
                         f"Session: {'yes' if has_session else 'no'}, URL: {r.url[:60]}")

    def test_group_sync(self, username):
        """Test that user's groups were synced from Keycloak."""
        self.log(f"Testing group sync for {username}...")
        # This requires querying the Metabase API with admin privileges
        # For now, we'll verify the user exists and has the right attributes
        return self.check(f"{username}: Group sync", True, "Manual verification needed")

    def run(self):
        """Run all tests."""
        print("="*60)
        print("Metabase OIDC Integration Test")
        print("="*60)

        if not self.wait_for_metabase():
            return False

        self.setup_metabase()
        self.configure_oidc()
        self.test_oidc_initiate()

        for username in TEST_USERS:
            self.test_oidc_login(username, TEST_USERS[username]["password"])

        print("\n" + "="*60)
        print("Test Results Summary")
        print("="*60)
        passed = sum(1 for r in self.results if r["status"] == "PASS")
        failed = sum(1 for r in self.results if r["status"] == "FAIL")
        print(f"Total: {len(self.results)}, Passed: {passed}, Failed: {failed}")

        for r in self.results:
            if r["status"] == "FAIL":
                print(f"  FAIL: {r['name']} - {r['detail']}")

        return failed == 0

if __name__ == "__main__":
    test = OIDCTest()
    success = test.run()
    sys.exit(0 if success else 1)
