---
sidebar_label: Set up OIDC
---

# Set up OIDC single sign-on

This page connects S3 Web UI to your identity provider. Keep your provider's admin console open while you work through it. If you are new to the terms, start with [How OIDC sign-in works](OIDC-Concepts.md). For complete, copy-ready setups see [Keycloak and Authelia examples](OIDC-Examples.md).

## Checklist

1. Create a confidential OpenID Connect client at the identity provider.
2. Register the redirect URI `https://s3.example.com/login/oauth2/code/<registration-id>` at the provider. The registration ID is just a label, but it must match exactly: see [The registration ID](#the-registration-id).
3. Set the issuer URL, client ID, and secret as environment variables or Kubernetes Secret values.
4. Ensure the token exposes a stable e-mail address. Set `OIDC_*_USER_NAME_ATTRIBUTE` only when the provider requires another username claim.
5. Verify proxy headers and session affinity, then test sign-in in a private browser window.

## Configure the application

Set `OIDC_ENABLED=true` to show configured OIDC providers alongside the local sign-in form. The
application requests `openid`, `profile` and `email`; a token must provide an e-mail address (or a
compatible preferred username) because it is the identity used for local account matching and S3
key grants.

### The registration ID

Each provider has a **registration ID**, a short label that ends the redirect URI (`/login/oauth2/code/<registration-id>`) and the sign-in URL (`/oauth2/authorization/<registration-id>`). It does not have to match the provider's name or client ID, and users never see it. It only has to be **identical** to the last part of the redirect URI registered at the provider.

| Setup | Registration ID |
|---|---|
| Single provider (`OIDC_CLIENT_ID`, ...) | always `keycloak`, whichever provider you use |
| Indexed provider (`OIDC_PROVIDERS_0_...`) | `OIDC_PROVIDERS_0_REGISTRATION_ID`, or the provider name in lower case with other characters turned into `-` (`Company SSO` becomes `company-sso`) |

For anything other than Keycloak, prefer the indexed setup with an explicit registration ID so the redirect URI reads naturally, for example `.../login/oauth2/code/authelia`. IDs must be unique, and changing one means updating the redirect URI at the provider.

### Single provider

The legacy single-provider configuration uses the fixed registration ID `keycloak`:

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
`OIDC_PROVIDERS_0_REGISTRATION_ID` sets the [registration ID](#the-registration-id), and
`OIDC_PROVIDERS_0_USER_NAME_ATTRIBUTE` selects the username claim (default:
`preferred_username`).

### Request extra scopes

S3 Web UI always requests `openid`, `profile` and `email`. Some providers only release further claims when asked for them, for example Authelia, which sends the `groups` claim only when the `groups` scope is requested. Set `OIDC_ADDITIONAL_SCOPES` to a comma- or space-separated list to add scopes for every provider, for example `OIDC_ADDITIONAL_SCOPES=groups`. `OIDC_PROVIDERS_0_ADDITIONAL_SCOPES` replaces that list for one indexed provider. The default scopes cannot be removed, and the provider must also allow the client to use the extra scope. A full Authelia example is in [Keycloak and Authelia examples](OIDC-Examples.md#authelia).

### Account creation and roles

`OIDC_CREATEUSERS` defaults to `true`, which creates a local record when an unknown OIDC identity
signs in. Set it to `false` to require an administrator to create the account first. OIDC identities
keep roles and groups supplied by the provider for S3-key grant matching, but their application role
always comes from the local database. A provider claim called `admin` cannot grant administrator
access. `OIDC_REQUIRED_ROLE` can require a realm role for entry to the application; administrators
remain allowed.

After sign-in works, decide who may use which storage: [Map OIDC roles and groups to S3 keys](OIDC-Roles-And-Groups.md). If something fails, see [Troubleshoot OIDC sign-in](OIDC-Troubleshooting.md).
