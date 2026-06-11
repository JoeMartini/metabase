import { PLUGIN_AUTH_PROVIDERS } from "metabase/plugins";
import MetabaseSettings from "metabase/utils/settings";

PLUGIN_AUTH_PROVIDERS.providers.push((providers) => {
  const oidcProviders = MetabaseSettings.get("oidc-login-providers") ?? [];

  for (const oidcProvider of oidcProviders) {
    // circular dependencies
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    const { createOidcAuthProvider } = require("metabase/auth/components/OidcButton");
    providers = [createOidcAuthProvider(oidcProvider), ...providers];
  }

  return providers;
});
