---
sidebar_label: Troubleshooting
---

# Troubleshoot OIDC sign-in

Use this page when the sign-in button appears but signing in fails, or the wrong people get access.

## Callback fails with `authorization_request_not_found`

When a proxy terminates TLS, leave `SERVER_FORWARD_HEADERS_STRATEGY=framework` enabled so OIDC
redirect URIs use the public HTTPS origin. If the callback reports `authorization_request_not_found`,
ensure it returns to the same application instance and, when TLS is in front of the application, set
`SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=None` and
`SERVER_SERVLET_SESSION_COOKIE_SECURE=true`.

## Other symptoms

| Symptom | What to check |
|---|---|
| The sign-in button is missing | `OIDC_ENABLED` must be `true`, and `DISABLE_AUTHENTICATION` must not be `true`: it ignores OIDC. |
| The provider reports a wrong redirect URI | Register exactly `https://<public-host>/login/oauth2/code/<registration-id>`. The registration ID is `keycloak` for the single-provider setup, or the one you set for indexed providers. |
| Sign-in succeeds at the provider but the application rejects it | The token must expose a stable e-mail address. Set `OIDC_PROVIDERS_<n>_USER_NAME_ATTRIBUTE` only if the provider uses another username claim. |
| A new person is turned away | `OIDC_CREATEUSERS=false` requires an administrator to create the account first. `OIDC_REQUIRED_ROLE` also restricts entry to people with that role; administrators remain allowed. |
| A person signs in but sees no storage | They need a matching grant on an S3 key: see [Map OIDC roles and groups to S3 keys](OIDC-Roles-And-Groups.md). Role and group changes at the provider apply after their next sign-in. |
| Group grants never match (Authelia, others) | The provider may release `groups` only on request. Set `OIDC_ADDITIONAL_SCOPES=groups` and allow the scope on the client, then sign in again. |
| A person is not an administrator although their provider role is `admin` | By design. Administrator status comes only from the local user record: see [Manage local user accounts](Local-Users.md). |
| Certificate error talking to the provider | Repair the issuer's trust chain. Never enable `OIDC_INSECURE_SKIP_TLS_VERIFY` except while diagnosing a non-production issuer. |

Behind a reverse proxy or ingress, also read [Put S3 Web UI behind a reverse proxy](../Installation/Reverse-Proxy.md).
