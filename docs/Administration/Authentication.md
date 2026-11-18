---
sidebar_label: Choose a sign-in method
---

# Choose a sign-in method

S3 Web UI can use local accounts, OIDC single sign-on, or a deliberately unprotected mode for an already trusted network. Pick the method that fits your environment, then follow its guide. Which storage connection a person may *select* is a separate step: see [Share storage connections with S3 keys](S3-Key-Management.md).

| Method | Use it when | Guide |
|---|---|---|
| Local accounts | you have a small team or no identity provider | [Manage local user accounts](Local-Users.md) |
| OIDC single sign-on | you already use Keycloak, Entra ID, Okta, Authentik or a similar identity provider | [How OIDC sign-in works](OIDC-Concepts.md), then [Set up OIDC single sign-on](OIDC-Setup.md) |
| No sign-in | a trusted network where everyone may administer everything | [Run without sign-in](Open-Access.md) |

## How the modes combine

S3 Web UI has three authentication modes. By default it requires a local account. OIDC can add
single sign-on alongside local accounts. `DISABLE_AUTHENTICATION=true` is an explicit opt-out that
disables both of those mechanisms and must only be used behind a trusted network boundary.

| `DISABLE_AUTHENTICATION` | `OIDC_ENABLED` | Behaviour |
|---|---|---|
| `false` (default) | `false` (default) | Local e-mail/password sign-in |
| `false` | `true` | Local e-mail/password sign-in and one or more OIDC sign-in buttons |
| `true` | either value | No sign-in; every visitor is a virtual administrator. OIDC is ignored |
