---
sidebar_label: How OIDC works
---

# How OIDC sign-in works

Read this first if OpenID Connect is new to you. It explains who does what during a single sign-on, and which values you will need from your identity provider.

## OIDC in plain language

OpenID Connect (OIDC) is an identity layer built on OAuth 2.0. Instead of S3 Web UI collecting and checking a person's password, it redirects them to an **identity provider** (IdP), such as Keycloak, Entra ID, Okta, or Authentik. The IdP authenticates the person and redirects the browser back with signed identity information. S3 Web UI verifies that information, starts its own browser session, and never receives the person's IdP password.

An OIDC client needs four pieces of information:

| Item | Meaning |
|---|---|
| Issuer URI | The IdP's stable OIDC address. S3 Web UI uses it to discover authorization, token, and signing-key endpoints. |
| Client ID | The IdP's identifier for this S3 Web UI installation. |
| Client secret | A credential held by the application to authenticate the authorization-code exchange; store it in a secret manager or Kubernetes Secret. |
| Redirect URI | The return address registered at the IdP, normally `https://<public-host>/login/oauth2/code/<registration-id>`. |

At sign-in, the browser is redirected to the IdP, the user authenticates there, and the IdP returns an authorization code to the redirect URI. S3 Web UI exchanges that code server-side for OIDC tokens and validates their signature, issuer, audience, and expiry. The e-mail claim is the application's stable user identity. The app requests the standard `openid`, `profile`, and `email` scopes.

OIDC authenticates the person; it does not grant S3 permissions by itself. S3 Web UI uses its own S3-key grants to decide which stored storage connections the signed-in person may select.

Next: [Set up OIDC single sign-on](OIDC-Setup.md).
