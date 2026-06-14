# Metabase

> 🍴 **This fork adds Community OIDC SSO integration** — authenticate users via Keycloak (or any OIDC provider) without requiring an Enterprise license. See [OIDC Integration](#oidc-integration) below.

[Metabase](https://www.metabase.com) is the easy, open-source way for everyone in your company to ask questions and learn from data.

![Metabase Product Screenshot](https://www.metabase.com/images/metabase-product-screenshot-updated.png)

[![Latest Release](https://img.shields.io/github/release/metabase/metabase.svg?label=latest%20release)](https://github.com/metabase/metabase/releases)
[![codecov](https://codecov.io/gh/metabase/metabase/branch/master/graph/badge.svg)](https://codecov.io/gh/metabase/metabase)
![Docker Pulls](https://img.shields.io/docker/pulls/metabase/metabase)

## Get started

The easiest way to get started with Metabase is to sign up for a free trial of [Metabase Cloud](https://store.metabase.com/checkout).

You get expert support, backups, upgrades, an SMTP server, SSL certificate, SoC2 Type 2 security auditing, and more (plus your money goes toward improving a major open-source project). Check out our quick overview of [cloud vs self-hosting](https://www.metabase.com/docs/latest/cloud/cloud-vs-self-hosting). If you need to, you can always switch to [self-hosting](https://www.metabase.com/docs/latest/installation-and-operation/installing-metabase) Metabase at any time (or vice versa).

## Key Features

- [Set up in five minutes](https://www.metabase.com/docs/latest/configuring-metabase/setting-up-metabase) (we're not kidding), or have us [host Metabase for you](https://www.metabase.com/cloud/) so you don't even need to think about it.
- Let anyone on your team [ask questions](https://www.metabase.com/docs/latest/questions/introduction) without knowing SQL.
- Use the [SQL editor](https://www.metabase.com/docs/latest/questions/native-editor/writing-sql) for more complex queries.
- Ask AI: [Metabot](https://www.metabase.com/docs/latest/ai/metabot) gives you answers you can trust, helps you write queries, and more. Or build your own [AI agent](https://www.metabase.com/docs/latest/ai/agent-api) to query your data.
- Build handsome, interactive [dashboards](https://www.metabase.com/docs/latest/dashboards/introduction) with filters, auto-refresh, fullscreen, custom click behavior, and more.
- Use [documents](https://www.metabase.com/docs/latest/documents/introduction) for long-form data analysis, and invite people to comment.
- [Transform](https://www.metabase.com/docs/latest/data-studio/transforms/transforms-overview) raw data into analytics-ready tables, track down broken dependencies, and define canonical metrics in Metabase's [Data Studio](https://www.metabase.com/docs/latest/data-studio/overview).
- Set up [alerts on your data](https://www.metabase.com/docs/latest/questions/alerts), or schedule [dashboard subscriptions](https://www.metabase.com/docs/latest/dashboards/subscriptions) to email, Slack, or even a webhook.
- Curate content in a [Library](https://www.metabase.com/docs/latest/data-studio/library), and [version your work with Git](https://www.metabase.com/docs/latest/installation-and-operation/remote-sync).
- [Embed Metabase in your app](https://www.metabase.com/docs/latest/embedding/introduction), with components for charts, dashboards, data browser, AI chat, and more. You can even put [an entire Metabase](https://www.metabase.com/docs/latest/embedding/interactive-embedding) in your app.
- Set granular [permissions](https://www.metabase.com/docs/latest/permissions/introduction) that work for both internal teams and embedded analytics, whether you co-locate your customer data, or give each customer their own database.
- Dark mode, content translations, and way more stuff than we can list here.

Take a [tour of Metabase](https://www.metabase.com/learn/metabase-basics/overview/tour-of-metabase).

## Supported databases

- [Officially supported databases](./docs/databases/connecting.md#connecting-to-supported-databases)
- [Community drivers](./docs/developers-guide/community-drivers.md)

## Installation

Metabase can be run just about anywhere. Check out our [Installation Guides](https://www.metabase.com/docs/latest/installation-and-operation/installing-metabase).

## Documentation

The [Metabase handbook](https://www.metabase.com/docs/latest/).

## Contributing

To contribute to Metabase, see our [Developer docs](./docs/developers-guide/start.md).

## Extending Metabase

Hit our API to integrate analytics. Check out our guide, [Working with the Metabase API](https://www.metabase.com/learn/metabase-basics/administration/administration-and-operation/metabase-api).

## Internationalization

We want Metabase to be available in as many languages as possible. See which translations are available and help contribute to internationalization using our project over at [Crowdin](https://crowdin.com/project/metabase-i18n). You can also check out our [policies on translations](https://www.metabase.com/docs/latest/administration-guide/localization.html).

## Security Disclosure

See [SECURITY.md](./SECURITY.md) for details.

## License

This repository contains the source code for both the Open Source edition of Metabase, released under the AGPL, as well as the [commercial editions of Metabase](https://www.metabase.com/pricing/), which are released under the Metabase Commercial Software License.

See [LICENSE.txt](./LICENSE.txt) for details.

Unless otherwise noted, all files © 2026 Metabase, Inc.

## Community

- [Discourse](https://discourse.metabase.com/)
- [Twitter](https://x.com/metabase)
- [LinkedIn](https://www.linkedin.com/company/metabase/)
- [YouTube](https://www.youtube.com/@metabasedata)
- [Reddit](https://www.reddit.com/r/Metabase/)

## Metabase Experts

If you'd like more technical resources to set up your data stack with Metabase, connect with a [Metabase Expert](https://www.metabase.com/partners/?utm_source=readme&utm_medium=metabase-expetrs&utm_campaign=readme).

---

## OIDC Integration

This fork (`feature/community-oidc`) adds **OIDC Single Sign-On for the Community Edition**, removing the Enterprise license requirement.

### What was changed

- **Backend** (`src/metabase/sso/providers/oidc.clj`):
  - Fixed config lookup priority so `get-oidc-provider` (full config from settings) is used instead of the truncated `extract-oidc-config`
  - Fixed `redirect-uri` propagation through authorization and token-exchange flows
  - Ensures proper OIDC discovery and state cookie encryption

- **Frontend** (`frontend/src/metabase/auth/components/Login/Login.tsx`):
  - Hides the default "Sign in with email" block when OIDC providers are configured
  - Shows a single "统一身份登录 (Keycloak)" button for a clean SSO experience

- **Keycloak Client Configuration**:
  - Adds `groups` client scope for role mapping
  - Configures proper `redirect_uris` for the Metabase callback endpoint

### How to use

1. Build Metabase from this branch
2. Set environment variables:
   - `MB_SSO_OIDC_CLIENT_ID`
   - `MB_SSO_OIDC_CLIENT_SECRET`
   - `MB_SSO_OIDC_ISSUER_URI` (e.g., `https://auth.example.com/realms/myrealm`)
   - `MB_ENCRYPTION_SECRET_KEY` (for state cookie encryption)
3. Start Metabase and click the SSO button on the login page

### Branches in this fork

| Branch | Purpose |
|--------|---------|
| `master` | Tracks upstream Metabase master |
| `feature/community-oidc` | Community OIDC SSO integration |

### Author

Maintained by [Hermes-Martini-Home](https://github.com/JoeMartini) · [Upstream sync record](https://app.notion.com/p/37fc7ae4e85381f890e0f65a70cc8b29)
