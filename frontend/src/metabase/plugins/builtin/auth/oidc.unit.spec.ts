import { PLUGIN_AUTH_PROVIDERS } from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";
import type { OidcAuthProvider } from "metabase-types/api";

describe("OIDC builtin auth plugin", () => {
  const originalProviders = [...PLUGIN_AUTH_PROVIDERS.providers];

  beforeEach(() => {
    PLUGIN_AUTH_PROVIDERS.providers = [...originalProviders];
    MetabaseSettings.set("oidc-login-providers", []);
  });

  afterEach(() => {
    PLUGIN_AUTH_PROVIDERS.providers = [...originalProviders];
    MetabaseSettings.set("oidc-login-providers", []);
  });

  it("should add OIDC providers when oidc-login-providers is configured", () => {
    const oidcProviders: OidcAuthProvider[] = [
      {
        type: "oidc",
        key: "okta",
        "login-prompt": "Sign in with Okta",
        "sso-url": "/auth/sso/okta",
      },
    ];
    MetabaseSettings.set("oidc-login-providers", oidcProviders);

    // Re-import to trigger the provider push (module-level side-effect)
    jest.isolateModules(() => {
      require("./oidc");
    });

    const result = PLUGIN_AUTH_PROVIDERS.providers.reduce(
      (providers, getProviders) => getProviders(providers),
      [] as { name: string }[],
    );

    expect(result.some((p) => p.name === "oidc-okta")).toBe(true);
  });

  it("should not add OIDC providers when oidc-login-providers is empty", () => {
    MetabaseSettings.set("oidc-login-providers", []);

    jest.isolateModules(() => {
      require("./oidc");
    });

    const result = PLUGIN_AUTH_PROVIDERS.providers.reduce(
      (providers, getProviders) => getProviders(providers),
      [] as { name: string }[],
    );

    expect(result.some((p) => p.name.startsWith("oidc-"))).toBe(false);
  });

  it("should preserve existing providers (password, google) alongside OIDC", () => {
    const baseProviders = [{ name: "password" }, { name: "google" }];
    const oidcProviders: OidcAuthProvider[] = [
      {
        type: "oidc",
        key: "azure",
        "login-prompt": "Sign in with Azure",
        "sso-url": "/auth/sso/azure",
      },
    ];
    MetabaseSettings.set("oidc-login-providers", oidcProviders);

    jest.isolateModules(() => {
      require("./oidc");
    });

    const result = PLUGIN_AUTH_PROVIDERS.providers.reduce(
      (providers, getProviders) => getProviders(providers),
      baseProviders,
    );

    expect(result.some((p) => p.name === "password")).toBe(true);
    expect(result.some((p) => p.name === "google")).toBe(true);
    expect(result.some((p) => p.name === "oidc-azure")).toBe(true);
  });
});
