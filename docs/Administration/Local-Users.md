---
sidebar_label: Local users
---

# Manage local user accounts

Local accounts are the default sign-in method. They stay available next to OIDC and are switched off only by `DISABLE_AUTHENTICATION=true` (see [Run without sign-in](Open-Access.md)).

## The first administrator

Local authentication is enabled unless `DISABLE_AUTHENTICATION=true`. On an empty user database,
S3 Web UI creates an administrator with `ADMIN_EMAIL` and `ADMIN_PASSWORD`; their defaults are
`admin@s3webui.local` and `admin`. Change the default password immediately, or set both variables
before the first start. The variables only seed an empty database and do not change an existing
administrator.

## Roles and passwords

Administrators manage local accounts under **Settings → Users**. A local account's role is either
`ADMIN` or `USER`. Administrators can manage S3 keys, IAM, local accounts and all audit history.
Users can only select S3 keys that have been granted to their e-mail address, role or identity
provider group. Local account passwords are stored as BCrypt hashes.

`ADMIN_EMAIL` and `ADMIN_PASSWORD` seed the account only when the user table is empty. They are not a mechanism to reset an existing administrator password. Set production values before the first start, and do not expose the default credentials to a network.

## Create and manage accounts

Use **Settings → Users → New user** to create a local email/password account and assign `USER` or `ADMIN`. Administrators may edit accounts, reset local passwords, and disable or remove accounts.

OIDC identities appear after their first successful sign-in when `OIDC_CREATEUSERS=true`. Their application administrator status still comes from this user table, never from an OIDC token claim. See [Authentication](Authentication.md) for the full model.
