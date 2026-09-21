# Authentication

S3 Web UI has three authentication modes. By default it requires a local account. OIDC can add
single sign-on alongside local accounts. `DISABLE_AUTHENTICATION=true` is an explicit opt-out that
disables both of those mechanisms and must only be used behind a trusted network boundary.

| `DISABLE_AUTHENTICATION` | `OIDC_ENABLED` | Behaviour |
|---|---|---|
| `false` (default) | `false` (default) | Local e-mail/password sign-in |
| `false` | `true` | Local e-mail/password sign-in and one or more OIDC sign-in buttons |
| `true` | either value | No sign-in; every visitor is a virtual administrator. OIDC is ignored |

## Local accounts

Local authentication is enabled unless `DISABLE_AUTHENTICATION=true`. On an empty user database,
S3 Web UI creates an administrator with `ADMIN_EMAIL` and `ADMIN_PASSWORD`; their defaults are
`admin@s3webui.local` and `admin`. Change the default password immediately, or set both variables
before the first start. The variables only seed an empty database and do not change an existing
administrator.

Administrators manage local accounts under **Settings → Users**. A local account's role is either
`ADMIN` or `USER`. Administrators can manage S3 keys, IAM, local accounts and all audit history.
Users can only select S3 keys that have been granted to their e-mail address, role or identity
provider group. Local account passwords are stored as BCrypt hashes.

## OIDC single sign-on

Set `OIDC_ENABLED=true` to show configured OIDC providers alongside the local sign-in form. The
application requests `openid`, `profile` and `email`; a token must provide an e-mail address (or a
compatible preferred username) because it is the identity used for local account matching and S3
key grants.

The legacy single-provider configuration uses:

| Variable | Purpose |
|---|---|
| `OIDC_PROVIDER_NAME` | Label on the sign-in button |
| `OIDC_CLIENT_ID` | OIDC client ID |
| `OIDC_CLIENT_SECRET` | OIDC client secret |
| `OIDC_ISSUER_URI` | Issuer discovery URL |

For more than one provider, configure indexed variables such as `OIDC_PROVIDERS_0_NAME`,
`OIDC_PROVIDERS_0_CLIENT_ID`, `OIDC_PROVIDERS_0_CLIENT_SECRET` and
`OIDC_PROVIDERS_0_ISSUER_URI`; repeat with `_1_`, `_2_`, and so on. An optional
`OIDC_PROVIDERS_0_REGISTRATION_ID` sets the Spring Security registration ID, and
`OIDC_PROVIDERS_0_USER_NAME_ATTRIBUTE` selects the username claim (default:
`preferred_username`).

`OIDC_CREATEUSERS` defaults to `true`, which creates a local record when an unknown OIDC identity
signs in. Set it to `false` to require an administrator to create the account first. OIDC identities
keep roles and groups supplied by the provider for S3-key grant matching, but their application role
always comes from the local database. A provider claim called `admin` cannot grant administrator
access. `OIDC_REQUIRED_ROLE` can require a realm role for entry to the application; administrators
remain allowed.

When a proxy terminates TLS, leave `SERVER_FORWARD_HEADERS_STRATEGY=framework` enabled so OIDC
redirect URIs use the public HTTPS origin. If the callback reports `authorization_request_not_found`,
ensure it returns to the same application instance and, when TLS is in front of the application, set
`SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=None` and
`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`.

## Disabled authentication

Set `DISABLE_AUTHENTICATION=true` to bypass local and OIDC sign-in completely. S3 Web UI assigns
every request the virtual administrator identity `authentication-disabled@s3webui.local`. This keeps
the existing administrator-only operations available: S3-key management, audit history and IAM all
continue to work without changes to their permissions.

In this mode, no local administrator is seeded, the **Settings → Users** tab and its create/edit
controls are hidden, and the `/api/admin/users` endpoints return `403 Forbidden`. OIDC is not
configured even if `OIDC_ENABLED=true`, and the login route redirects to the bucket browser. The
application still uses browser sessions to remember the selected S3 key.

There is no application-level identity or access control in this mode. Anyone able to reach the
application can perform every administrator action and use every stored S3 key. Restrict access with
an ingress, reverse proxy, VPN, firewall or equivalent network control before enabling it.

### Configuration examples

For a local process or container:

```bash
export DISABLE_AUTHENTICATION=true
```

For Helm:

```yaml
env:
  DISABLE_AUTHENTICATION: "true"
```

The setting is read at startup, so restart the application after changing it. Its default is `false`.
