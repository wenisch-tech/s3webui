# S3 key management

An S3 key in S3 Web UI is a named storage connection: endpoint URL, region, access key, secret key, TLS setting, and grants. It is not an application user. See [Authentication](Authentication.md) for who can sign in and [IAM management](IAM-Management.md) for creating backend identities.

## Create, edit, disable, and delete

Use **Settings → S3 keys** to create or edit a stored connection. The secret is encrypted with AES-256-GCM before it is stored.

- When editing, leave **Secret key** blank to retain the existing secret.
- Disable a key to prevent selection without deleting its encrypted configuration.
- Delete a key to remove the configuration from S3 Web UI; this does not delete a storage-backend access key.
- Keep **Skip TLS certificate verification** disabled in production. It suppresses certificate and hostname verification for that one connection.

The environment-configured key (`S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_ENDPOINT_URL`) is always listed first and is not editable in Settings. It is offered to every authenticated user when all three variables are set.

## Grants and revocation

Grant a key to everyone signed in, a specific e-mail address, an application/OIDC role, or an OIDC group. A key with no grants is administrator-only. Authorization is rechecked for each request, so disabling a key or removing its grant takes effect without waiting for the user to sign out.

For exact OIDC claim mappings, see [Map OIDC roles and groups to S3 keys](Authentication.md#map-oidc-roles-and-groups-to-s3-keys).

## Own credentials and reveal

By default, users may enter their own S3 credentials in the session picker. The values live only in that browser session. An administrator can turn this off in **Settings → Options** to require users to select an assigned stored key.

Administrators can also enable **Allow users to reveal keys**. When it is enabled, a user who currently has a matching grant can use the eye button beside an assigned key in the session picker to copy its access key and secret key. The reveal response is marked not to be cached; it remains a high-risk feature because copied credentials work outside S3 Web UI.

Enable reveal only for a deliberate credential-distribution workflow. Prefer granting users a least-privilege key or using OIDC mapping instead of distributing broad shared secrets.

## Recovery and backup

Stored secrets can be decrypted only with the same `APP_ENCRYPTION_KEY`. Back up the database and that key together. With the default H2 database, both are normally under `APP_DATA_DIR`; persist `/app/data` in the container. See [Deployment](../Installation/Deployment.md#persistence-and-database) for PostgreSQL and encryption-key guidance.
