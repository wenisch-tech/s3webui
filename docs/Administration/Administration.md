# Administration

Only application administrators can open **Settings**. The panel has S3 keys, Users, Options, and IAM tabs. In disabled-authentication mode, the Users tab is intentionally absent.

## Create and grant an S3 key

1. Open **Settings → S3 keys → New key**.
2. Give the connection a recognisable name, then supply its endpoint URL, region, access key, and secret key. Secrets are encrypted at rest with AES-256-GCM.
3. Keep the key enabled and leave TLS-verification bypass off for normal deployments.
4. Under **Who may use this key**, add one or more grants, then save.

| Grant | Who may select the key |
|---|---|
| Everyone signed in | Every authenticated local or OIDC user |
| A user (e-mail) | The matching e-mail identity |
| A role | Users whose application role or OIDC role matches it |
| A group | Users whose OIDC `groups` claim matches it |
| No grants | Administrators only |

Grants are checked on each request. Removing a grant therefore takes effect immediately, including for a session that had already selected the key. Administrators can always select stored keys.

For an OIDC role or group grant, enter the unprefixed value delivered by the identity provider. S3 Web UI recognises Keycloak-style realm roles, client roles, and the standard `groups` array; see [Map OIDC roles and groups to S3 keys](Authentication.md#map-oidc-roles-and-groups-to-s3-keys) for the exact claim shapes and examples.

The configured built-in key (`S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_ENDPOINT_URL`) appears in the picker for everyone but cannot be changed in the settings panel.

## Manage local users

Use **Settings → Users → New user** to create a local email/password account and assign `USER` or `ADMIN`. Administrators may edit accounts, reset local passwords, and disable or remove accounts.

OIDC identities appear after their first successful sign-in when `OIDC_CREATEUSERS=true`. Their application administrator status still comes from this user table, never from an OIDC token claim. See [Authentication](Authentication.md) for the full model.

## Runtime options

**Settings → Options** controls two policy decisions stored in the application database:

- **Let users connect with their own S3 credentials**: on by default. Turn it off to require every non-administrator connection to be an administrator-created and granted key. Existing own-credential sessions lose access on their next request.
- **Allow users to reveal keys**: off by default. Turn it on only if it is acceptable for a user assigned a stored S3 or IAM key to copy that access key and secret key for use outside S3 Web UI.

## IAM for the storage backend

The **IAM** tab manages identities on the selected storage backend, not S3 Web UI's local users. Select an S3 key with the backend's IAM permissions, then open the tab. Capability probing explains which operations that backend and key permit. See the dedicated [IAM management guide](IAM-Management.md) for supported backends, users, generated access keys, groups, policies, provider limitations, and safety guidance.

## Operational checklist

- Use a dedicated, least-privilege S3 key for each intended workload.
- Audit every broad `Everyone signed in` grant.
- Back up the database and the encryption key together.
- Keep the selected IAM key separate from routine data-access keys where practical.
- Review History after credential, policy, and destructive object changes.
