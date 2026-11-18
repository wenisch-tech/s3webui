---
sidebar_label: Keycloak and Authelia examples
---

# Example: sign in with Keycloak or Authelia

These are complete, copy-and-adapt setups for two common identity providers. Each one has the provider side, the S3 Web UI side for Docker and for Helm, and the step that lets a provider group use a stored S3 key. For the background, read [How OIDC sign-in works](OIDC-Concepts.md) first.

Replace `s3webui.example.com` with the public address of your installation and `<client-secret>` with the secret you create.

## The registration ID, in one minute

Every OIDC provider in S3 Web UI has a **registration ID**. It is a short label that becomes part of two URLs:

| URL | Used for |
|---|---|
| `https://<host>/oauth2/authorization/<registration-id>` | the sign-in button starts here |
| `https://<host>/login/oauth2/code/<registration-id>` | the **redirect URI**: where the provider sends the user back after login |

What you need to know:

- It is **only a label**. It does not have to match the provider's name, the realm, or the client ID, and users never see it.
- The redirect URI you register at the provider must **match exactly**, including this last part. A mismatch is the most common cause of a "redirect URI" error.
- **Single-provider setup** (`OIDC_CLIENT_ID`, `OIDC_ISSUER_URI`, ...): the registration ID is always `keycloak`, whichever provider you use. This is a leftover from when Keycloak was the only option. It works with Authelia too, but then the redirect URI reads `.../login/oauth2/code/keycloak`, which looks odd.
- **Indexed setup** (`OIDC_PROVIDERS_0_...`): you choose it with `OIDC_PROVIDERS_0_REGISTRATION_ID`. If you leave it out, it is made from the provider name: lower-case, with anything other than letters and digits turned into `-`. The name `Company SSO` becomes `company-sso`.
- Each registration ID must be unique. Changing it later changes the redirect URI, so update the provider as well.

The examples below use the indexed setup with an explicit registration ID, so the redirect URI is readable and matches the provider. Single-provider users can keep `OIDC_CLIENT_ID` and friends and register `.../login/oauth2/code/keycloak`.

## Keycloak

### In Keycloak

1. Choose the realm and open **Clients → Create client**. Set **Client type** to `OpenID Connect` and the **Client ID** to `s3webui`.
2. Turn **Client authentication** on (a confidential client) and keep **Standard flow** enabled.
3. Under **Valid redirect URIs** enter `https://s3webui.example.com/login/oauth2/code/keycloak`. The last part is the registration ID you will set below.
4. Open **Credentials** and copy the **Client secret**.
5. To use groups: open **Clients → s3webui → Client scopes → s3webui-dedicated → Add mapper → By configuration → Group Membership**. Set **Token Claim Name** to `groups` and keep **Add to ID token** and **Add to userinfo** on. Leave **Full group path** on or off as you prefer. A leading `/` is ignored by S3 Web UI.
6. To use realm roles, no extra step is normally needed: S3 Web UI reads the standard `realm_access.roles` and `resource_access.<client>.roles` claims.

The issuer URL is `https://keycloak.example.com/realms/<realm>`.

### In S3 Web UI with Docker

```bash
# OIDC_PROVIDERS_0_NAME is the label on the sign-in button.
# OIDC_PROVIDERS_0_REGISTRATION_ID is only a label, but it must equal the last part of the
# redirect URI registered in Keycloak (.../login/oauth2/code/keycloak).
# It is optional: when omitted it is made from the name ("company-sso").
docker run -d -p 8080:8080 \
  -e OIDC_ENABLED=true \
  -e OIDC_PROVIDERS_0_NAME="Company SSO" \
  -e OIDC_PROVIDERS_0_REGISTRATION_ID=keycloak \
  -e OIDC_PROVIDERS_0_CLIENT_ID=s3webui \
  -e OIDC_PROVIDERS_0_CLIENT_SECRET='<client-secret>' \
  -e OIDC_PROVIDERS_0_ISSUER_URI=https://keycloak.example.com/realms/main \
  ghcr.io/wenisch-tech/s3webui:latest
```

Keycloak does not need `OIDC_ADDITIONAL_SCOPES`: with the Group Membership mapper above, groups are released with the default scopes.

### In S3 Web UI with Helm

```yaml
# values.yaml
env:
  OIDC_ENABLED: "true"
  # Label on the sign-in button.
  OIDC_PROVIDERS_0_NAME: "Company SSO"
  # Registration ID: only a label, but it must equal the last part of the redirect URI
  # registered in Keycloak (https://s3webui.example.com/login/oauth2/code/keycloak).
  # Optional: when omitted it is made from the name above ("company-sso").
  OIDC_PROVIDERS_0_REGISTRATION_ID: "keycloak"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui"
  OIDC_PROVIDERS_0_ISSUER_URI: "https://keycloak.example.com/realms/main"

secrets:
  # Kept in a Kubernetes Secret, not in the ConfigMap.
  OIDC_PROVIDERS_0_CLIENT_SECRET: "<client-secret>"
```

## Authelia

Authelia only releases a claim such as `groups` when the client is **allowed** the scope in Authelia **and** the application **asks** for it. S3 Web UI always asks for `openid`, `profile` and `email`, so for groups you add `groups` with `OIDC_PROVIDERS_0_ADDITIONAL_SCOPES`.

### In Authelia

Generate the client secret and its hash. The command prints both: the plain secret is for S3 Web UI, the hash goes into Authelia.

```bash
docker run --rm authelia/authelia:latest authelia crypto hash generate pbkdf2 \
  --variant sha512 --random --random.length 72 --random.charset rfc3986
```

Add the client to the Authelia configuration (OIDC itself, with `hmac_secret` and a signing key under `jwks`, must already be set up):

```yaml
identity_providers:
  oidc:
    clients:
      - client_id: 's3webui'
        client_name: 'S3 Web UI'
        # The HASH printed by the command above, not the plain secret.
        client_secret: '$pbkdf2-sha512$...'
        public: false
        authorization_policy: 'two_factor'    # or 'one_factor'
        redirect_uris:
          # The last part is the registration ID you set in S3 Web UI (here: authelia).
          - 'https://s3webui.example.com/login/oauth2/code/authelia'
        # Authelia's default scopes already include 'groups'. List them only if you
        # restrict the client; the three standard scopes and 'groups' must be allowed.
        scopes:
          - 'openid'
          - 'profile'
          - 'email'
          - 'groups'
```

The issuer URL is the public address of Authelia, for example `https://auth.example.com`.

### In S3 Web UI with Docker

```bash
# OIDC_PROVIDERS_0_REGISTRATION_ID is only a label, but it must equal the last part of the
# redirect URI registered in Authelia (.../login/oauth2/code/authelia).
# It is optional: when omitted it is made from the name ("authelia").
# OIDC_PROVIDERS_0_ADDITIONAL_SCOPES asks Authelia for the groups claim on top of
# openid, profile and email.
docker run -d -p 8080:8080 \
  -e OIDC_ENABLED=true \
  -e OIDC_PROVIDERS_0_NAME="Authelia" \
  -e OIDC_PROVIDERS_0_REGISTRATION_ID=authelia \
  -e OIDC_PROVIDERS_0_CLIENT_ID=s3webui \
  -e OIDC_PROVIDERS_0_CLIENT_SECRET='<client-secret>' \
  -e OIDC_PROVIDERS_0_ISSUER_URI=https://auth.example.com \
  -e OIDC_PROVIDERS_0_ADDITIONAL_SCOPES=groups \
  ghcr.io/wenisch-tech/s3webui:latest
```

### In S3 Web UI with Helm

```yaml
# values.yaml
env:
  OIDC_ENABLED: "true"
  OIDC_PROVIDERS_0_NAME: "Authelia"
  # Registration ID: only a label, but it must equal the last part of the redirect URI
  # registered in Authelia (https://s3webui.example.com/login/oauth2/code/authelia).
  # Optional: when omitted it is made from the name above ("authelia").
  OIDC_PROVIDERS_0_REGISTRATION_ID: "authelia"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui"
  OIDC_PROVIDERS_0_ISSUER_URI: "https://auth.example.com"
  # Ask Authelia for the groups claim on top of openid, profile and email.
  OIDC_PROVIDERS_0_ADDITIONAL_SCOPES: "groups"

secrets:
  OIDC_PROVIDERS_0_CLIENT_SECRET: "<client-secret>"
```

If you only run one provider, `OIDC_ADDITIONAL_SCOPES: "groups"` has the same effect. The per-provider variable takes priority when both are set.

### If groups do not arrive

By default Authelia delivers scope-based claims such as `groups` at its UserInfo endpoint, and S3 Web UI reads both the ID token and UserInfo, so this normally just works. If the claim is still missing, give the client a claims policy that copies it into the ID token. Authelia describes this as an escape hatch for edge cases:

```yaml
identity_providers:
  oidc:
    claims_policies:
      s3webui:
        id_token: ['groups', 'email', 'preferred_username']
    clients:
      - client_id: 's3webui'
        claims_policy: 's3webui'
        # ... the rest of the client as above
```

## Let a provider group use a stored S3 key

The same step works for both providers.

1. Sign in once with an account that belongs to the group.
2. As an administrator, open **Settings → S3 keys**, create or edit a key, and add a **group** grant.
3. Enter the plain group name as the provider reports it, for example `storage-team`. Do not add a `GROUP_` prefix.
4. Sign in as a member of that group. The key now appears in the session key picker.

Changes to a person's groups at the provider apply after their next sign-in. See [Map OIDC roles and groups to S3 keys](OIDC-Roles-And-Groups.md) for details, and [Troubleshoot OIDC sign-in](OIDC-Troubleshooting.md) if something fails.
