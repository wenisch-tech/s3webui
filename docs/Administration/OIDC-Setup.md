---
sidebar_label: Set up OIDC
---

# Set up OIDC single sign-on

This page connects S3 Web UI to your identity provider. Keep your provider's admin console open while you work through it. If you are new to the terms, start with [How OIDC sign-in works](OIDC-Concepts.md).

## Checklist

1. Create a confidential OpenID Connect client at the identity provider.
2. Register the public callback pattern, for example `https://s3.example.com/login/oauth2/code/<registration-id>`. For the legacy provider its registration ID is `keycloak`; for indexed providers it is the supplied registration ID or a sanitized provider name.
3. Set the issuer URL, client ID, and secret as environment variables or Kubernetes Secret values.
4. Ensure the token exposes a stable e-mail address. Set `OIDC_*_USER_NAME_ATTRIBUTE` only when the provider requires another username claim.
5. Verify proxy headers and session affinity, then test sign-in in a private browser window.

## Configure the application

Set `OIDC_ENABLED=true` to show configured OIDC providers alongside the local sign-in form. The
application requests `openid`, `profile` and `email`; a token must provide an e-mail address (or a
compatible preferred username) because it is the identity used for local account matching and S3
key grants.

### Single provider

The legacy single-provider configuration uses:

| Variable | Purpose |
|---|---|
| `OIDC_PROVIDER_NAME` | Label on the sign-in button |
| `OIDC_CLIENT_ID` | OIDC client ID |
| `OIDC_CLIENT_SECRET` | OIDC client secret |
| `OIDC_ISSUER_URI` | Issuer discovery URL |

### Multiple providers

For more than one provider, configure indexed variables such as `OIDC_PROVIDERS_0_NAME`,
`OIDC_PROVIDERS_0_CLIENT_ID`, `OIDC_PROVIDERS_0_CLIENT_SECRET` and
`OIDC_PROVIDERS_0_ISSUER_URI`; repeat with `_1_`, `_2_`, and so on. An optional
`OIDC_PROVIDERS_0_REGISTRATION_ID` sets the Spring Security registration ID, and
`OIDC_PROVIDERS_0_USER_NAME_ATTRIBUTE` selects the username claim (default:
`preferred_username`).

### Request extra scopes

S3 Web UI always requests `openid`, `profile` and `email`. Some providers only release further claims when asked for them, for example Authelia, which sends the `groups` claim only when the `groups` scope is requested. Set `OIDC_ADDITIONAL_SCOPES` to a comma- or space-separated list to add scopes for every provider, for example `OIDC_ADDITIONAL_SCOPES=groups`. `OIDC_PROVIDERS_0_ADDITIONAL_SCOPES` replaces that list for one indexed provider. The default scopes cannot be removed, and the provider must also allow the client to use the extra scope.

### Account creation and roles

`OIDC_CREATEUSERS` defaults to `true`, which creates a local record when an unknown OIDC identity
signs in. Set it to `false` to require an administrator to create the account first. OIDC identities
keep roles and groups supplied by the provider for S3-key grant matching, but their application role
always comes from the local database. A provider claim called `admin` cannot grant administrator
access. `OIDC_REQUIRED_ROLE` can require a realm role for entry to the application; administrators
remain allowed.

After sign-in works, decide who may use which storage: [Map OIDC roles and groups to S3 keys](OIDC-Roles-And-Groups.md). If something fails, see [Troubleshoot OIDC sign-in](OIDC-Troubleshooting.md).
