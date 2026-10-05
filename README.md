# S3 Web UI

[![CI](https://github.com/wenisch-tech/s3webui/actions/workflows/ci.yml/badge.svg)](https://github.com/wenisch-tech/s3webui/actions/workflows/ci.yml)
[![GitHub Release](https://img.shields.io/github/v/release/wenisch-tech/s3webui?logo=github)](https://github.com/wenisch-tech/Kairos/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPLv3-blue.svg)](LICENSE)
[![Container](https://img.shields.io/badge/container-ghcr.io-blue?logo=github)](https://github.com/wenisch-tech/s3webui/pkgs/container/s3webui)
[![Signed](https://img.shields.io/badge/signed-cosign-green?logo=data:image/svg+xml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0xMiAxTDMgNXY2YzAgNS41NSAzLjg0IDEwLjc0IDkgMTIgNS4xNi0xLjI2IDktNi40NSA5LTEyVjVsLTktNHoiLz48L3N2Zz4=)](https://github.com/wenisch-tech/Kairos/releases)

A modern, clean graphical web interface for S3-compatible object storage, with local and optional OIDC authentication, audit history, administration, and client-side multipart upload. Built with Spring Boot, Tailwind CSS, Alpine.js, and Lucide.


> [!IMPORTANT]
> **By default, the application requires a sign-in.**
> Earlier versions were open to everyone when `OIDC_ENABLED=false`. On first start a default
> administrator `admin@s3webui.local` / `admin` is created and a warning is logged; change that
> password immediately, or set `ADMIN_EMAIL` / `ADMIN_PASSWORD` before the first start.
> S3 credentials are now managed in the administration panel rather than only through
> `S3_ACCESS_KEY` / `S3_SECRET_KEY`. Set `DISABLE_AUTHENTICATION=true` only when the deployment is
> protected by a trusted network boundary; it makes every visitor an administrator.

## Table of contents

- [How access works](#how-access-works)
- [Quick start with RustFS](#quick-start-with-rustfs)
- [Features](#features)
- [Upload flow](#upload-flow)
- [Configuration](#configuration)
  - [Database](#database)
  - [Administrator and secret encryption](#administrator-and-secret-encryption)
  - [Authentication](#authentication)
  - [S3 connection](#s3-connection)
  - [IAM](#iam-optional)
  - [OIDC](#oidc-optional)
- [Docker](#docker)
- [Helm chart](#helm-chart)
- [Running locally](#running-locally)
- [Contributing](#contributing)
- [License](#license)

## How access works

1. A user signs in — with e-mail and password, or through an OIDC provider.
2. They pick which **S3 key** to use for this session. A key is an S3 connection an administrator
   configured under **Settings → S3 keys**, and each key is granted to *everyone signed in*, to a
   *named user* (by e-mail), to a *role*, or to a *group* — roles and groups come from the claims
   the identity provider sends. Administrators see every key.
3. The bucket browser loads with that key. The navbar switcher changes key without signing out.

Users may also connect with credentials they type in themselves; administrators can switch that off
under **Settings → General**. Grants are re-checked on every request, so revoking one takes effect
immediately.

Set `DISABLE_AUTHENTICATION=true` to skip sign-in entirely. Every visitor then runs as a virtual
administrator, while local user management and OIDC are disabled; IAM management continues to work.
See [Authentication](docs/Authentication.md) for the complete configuration and security guidance.

## Quick start with RustFS

Start a local RustFS instance with dedicated development credentials:

```bash
docker run -d --name rustfs -p 9000:9000 -p 9001:9001 \
  -v rustfs-data:/data \
  -e RUSTFS_ACCESS_KEY=S3WEBUI \
  -e RUSTFS_SECRET_KEY=s3webui-secret \
  -e RUSTFS_ADDRESS=":9000" \
  -e RUSTFS_CONSOLE_ADDRESS=":9001" \
  -e RUSTFS_CONSOLE_ENABLE=true \
  rustfs/rustfs:latest /data
```

Then start S3 Web UI and point it at RustFS:

```bash
docker run -d --name s3webui -p 8080:8080 \
  --add-host=host.docker.internal:host-gateway \
  -v s3webui-data:/app/data \
  -e S3_ACCESS_KEY=S3WEBUI \
  -e S3_SECRET_KEY=s3webui-secret \
  -e S3_ENDPOINT_URL=http://host.docker.internal:9000 \
  ghcr.io/wenisch-tech/s3webui:latest
```

Open <http://localhost:8080> and sign in as `admin@s3webui.local` / `admin`; change that password
immediately. RustFS's S3 API is available on port 9000 and its console on <http://localhost:9001>.
The selected RustFS root credentials also enable **Settings → IAM**, where S3 Web UI can manage
RustFS users, groups, access keys and policies. RustFS 1.0.0 or newer is required. This example is
for local development only; use unique credentials, scoped administration permissions and TLS for
a real deployment.

## Features

-  **Browse buckets and folders** — list buckets with creation dates, navigate objects with breadcrumbs, and create virtual prefix-based folders
-  **Search and sort objects** — filter the current folder's files and folders as you type; sort by name, size, or last modified date
-  **File management** — upload with multipart progress and ETA, download objects, rename files without re-uploading, and delete objects or buckets
-  **Create buckets** — create new buckets directly from the UI
-  **Bucket policy editor** — read, edit and remove a bucket's IAM policy in a JSON editor with syntax highlighting and inline validation
-  **CORS editor** — edit a bucket's CORS rules in the same editor, in the `aws s3api` JSON shape
-  **Audit history** — per-session activity log (uploads, downloads, deletes, renames) with user and action filters
-  **Light / Dark theme** — toggle stored in `localStorage`, light is the default
-  **Administration panel** — manage named S3 keys, decide who may use each one, and manage local accounts
-  **Per-user S3 sessions** — every signed-in user picks their own key; several users browse different storage at the same time
-  **Encrypted secrets** — stored S3 secret keys are encrypted at rest with AES-256-GCM
-  **Users & roles** — local accounts in an H2 or PostgreSQL database, with a seeded default administrator
-  **IAM management** — optional admin section for the storage backend's users, groups, memberships, access keys, managed policies, policy attachments and inline policies
-  **OIDC** — optional single-sign-on with role-based access control and multiple providers


## Upload flow

Files **≤ 5 MB** are uploaded via a simple multipart form POST through the backend.

Files **> 5 MB** use **server-proxied S3 multipart upload**:
1. Browser calls `POST /api/buckets/{b}/multipart/initiate` to get an `uploadId`
2. For each 5 MB chunk, the browser PUTs the raw bytes to `PUT /api/buckets/{b}/multipart/part` — the backend forwards the chunk directly to S3 using the AWS SDK and returns the `ETag`
3. Browser calls `POST /api/buckets/{b}/multipart/complete` to finish the upload

Routing parts through the backend avoids cross-origin (CORS) issues that would occur if the browser PUTted directly to the S3 endpoint.

Progress percentage and estimated time remaining are computed entirely in the browser using `XMLHttpRequest` upload events.

## Configuration

Runtime configuration lives in the administration panel; deployment configuration is provided via
environment variables.

### Database

The application ships with an H2 file database and needs no configuration. Point the standard Spring
datasource variables at PostgreSQL for production; no profile is needed.

| Variable | Description | Default | PostgreSQL configuration |
|---|---|---|---|
| `APP_DATA_DIR` | Directory holding the H2 database and the generated encryption key | `./data` (`/app/data` in the container) | Not used for the database; persist it only when `APP_ENCRYPTION_KEY` is generated rather than supplied |
| `SPRING_DATASOURCE_URL` | JDBC URL | `jdbc:h2:file:${APP_DATA_DIR}/s3webui` | `jdbc:postgresql://<host>:5432/s3webui` |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | JDBC driver | `org.h2.Driver` | `org.postgresql.Driver` |
| `SPRING_DATASOURCE_USERNAME` | Database user | `sa` | PostgreSQL role, for example `s3webui` |
| `SPRING_DATASOURCE_PASSWORD` | Database password | — | Password for that PostgreSQL role |
| `SPRING_JPA_DATABASE_PLATFORM` | Set to `org.hibernate.dialect.PostgreSQLDialect` for PostgreSQL | — | `org.hibernate.dialect.PostgreSQLDialect` |

The H2 file database tolerates a single writer, so keep `replicaCount: 1`. Running more than one
replica needs PostgreSQL **and** sticky sessions, because HTTP sessions are held in memory.

### Administrator and secret encryption

| Variable | Description | Default |
|---|---|---|
| `ADMIN_EMAIL` | E-mail of the administrator created on first start | `admin@s3webui.local` |
| `ADMIN_PASSWORD` | Its password. Change it after the first sign-in | `admin` |
| `APP_ENCRYPTION_KEY` | Base64 encoded 32 byte AES key protecting stored S3 secrets | generated into `${APP_DATA_DIR}/encryption.key` |

If you let the key be generated, **`${APP_DATA_DIR}` must be on persistent storage** — without that
file the stored S3 secret keys cannot be decrypted. The administrator is created only when the user
table is empty; changing `ADMIN_PASSWORD` later has no effect.

### Authentication

| Variable | Description | Default |
|---|---|---|
| `DISABLE_AUTHENTICATION` | Disable all application sign-in and make every visitor a virtual administrator | `false` |

When `DISABLE_AUTHENTICATION=true`, local sign-in, OIDC and local-user management are disabled.
Every request is handled as an administrator, including IAM management. Use this mode only behind a
trusted network boundary; no application-level access control remains. Full instructions for local
accounts, OIDC and disabled authentication are in [Authentication](docs/Authentication.md).

### S3 connection

| Variable | Description | Default |
|---|---|---|
| `S3_ACCESS_KEY` | S3 access key / username | `minioadmin` |
| `S3_SECRET_KEY` | S3 secret key / password | `minioadmin` |
| `S3_ENDPOINT_URL` | S3-compatible endpoint URL | `http://localhost:9000` |
| `S3_REGION` | AWS region (optional) | `us-east-1` |
| `S3_INSECURE_SKIP_TLS_VERIFY` | Skip TLS certificate verification for S3 endpoint | `false` |

These are optional. When all three of `S3_ACCESS_KEY`, `S3_SECRET_KEY` and `S3_ENDPOINT_URL` are
set, they appear in the key picker as a built-in key available to every signed-in user. It is shown
read-only in the administration panel; every other key is created there and stored encrypted.

### IAM (optional)

| Variable | Description | Default |
|---|---|---|
| `IAM_ENABLED` | Enable the IAM management section | `true` |

The IAM section manages identities on the *storage backend*, not the app's own local accounts. It
is admin-only, and every call is made with the S3 key selected for your session — so it can only
do what that key is allowed to do.

Support is detected at runtime and the panel disables itself, with the reason, on a backend that
cannot do it. There is no need to turn the flag off; it exists to remove the feature entirely.

| Backend | Support |
|---|---|
| AWS | Everything: users, groups, access keys, standalone policies, inline policies |
| Ceph RGW (Squid or later) | Users, groups, access keys, attach/detach and inline policies. Requires an **account root user's** key — a normal RGW user gets `AccessDenied`. Standalone policies are not implemented, so the Policies tab is disabled and only Ceph's six built-in managed policies can be attached; use an inline policy for finer grants |
| MinIO | Not supported — MinIO has its own admin API rather than the IAM API |
| RustFS 1.0.0+ | Users, groups, memberships, service-account access keys, named policy CRUD and policy attachments through the native [RustFS admin API](https://docs.rustfs.com/en/security-compliance/iam/policies). AWS-style inline policies are not supported; create and attach a named policy instead. |

#### Using IAM management

Open **Settings → IAM** as an administrator and select an S3 key that is authorised to call the
backend's identity-management API. The selected key is used for every IAM request, so changing the
active S3 key can change both the visible identities and the operations you are allowed to perform.
For RustFS, use the root credential or a credential with the corresponding `admin:*` actions; its
built-in `consoleAdmin` policy supplies those permissions. See the official
[RustFS IAM overview](https://docs.rustfs.com/en/security-compliance/iam) for its identity and policy
model. All IAM mutations are recorded in **History**.

Creating an IAM user also creates its first access key. Additional keys can be created from the
user's access-key dialog. Each generated key is saved in S3 Web UI as an encrypted S3 key for the
same endpoint, but initially has no application grants; grant it to users, roles or groups under
**Settings → S3 keys** before non-administrators can select it. The secret access key is shown only
once immediately after creation, so copy it before closing the dialog. Administrators can always
use stored keys, and deleting an IAM user cleans up its backend keys, group memberships and policy
attachments before removing the user.

On RustFS, the user name is also that user's protected primary access key. Additional keys created
from the dialog are RustFS service accounts: they inherit the parent user's current policies and can
be deleted independently. Deleting the IAM user removes its primary credential and derived service
accounts.

The IAM panel probes capabilities when it is opened. Users, groups, access keys, standalone
managed policies and inline policies are reported independently, so a backend can expose part of
the feature. AWS-managed policies are attach-only; AWS customer-managed policies can be edited or
deleted, and policy documents are validated as JSON before they are sent to the backend. On Ceph,
inline policies are the way to express fine-grained permissions because standalone policy CRUD is
not available. On RustFS, standalone named policies provide that fine-grained access because inline
user and group policies are unavailable.

### OIDC (optional)

| Variable | Description | Default |
|---|---|---|
| `OIDC_ENABLED` | Enable OIDC authentication | `false` |
| `OIDC_PROVIDER_NAME` | Display name for the legacy single-provider setup | `Single Sign-On` |
| `OIDC_CLIENT_ID` | OAuth2 client ID | — |
| `OIDC_CLIENT_SECRET` | OAuth2 client secret | — |
| `OIDC_ISSUER_URI` | OIDC issuer URI for the legacy single-provider setup | — |
| `OIDC_REQUIRED_ROLE` | Realm role required to access the app (optional) | — |
| `OIDC_CREATEUSERS` | Create a local record the first time an unknown SSO user signs in | `true` |
| `OIDC_INSECURE_SKIP_TLS_VERIFY` | Skip TLS certificate verification for the OIDC issuer | `false` |

When `OIDC_ENABLED=true`, the login page offers a button per configured provider alongside the local
sign-in form. The token must carry an e-mail address, which becomes the user's identity; a login
without one is rejected. With `OIDC_CREATEUSERS=false`, only users an administrator created up front
may sign in.

Administrator status always comes from the database, never from the token, so a realm role called
`admin` cannot promote anyone — promote users under **Settings → Users**. Realm roles, client roles
and the `groups` claim are still read from the token, because credential grants target them.

If `OIDC_REQUIRED_ROLE` is set, users without that realm role receive an **Access Denied** page.

#### Running behind a reverse proxy or ingress

`SERVER_FORWARD_HEADERS_STRATEGY` defaults to `framework`, so `X-Forwarded-*` headers from a
TLS-terminating proxy are honored automatically — the redirect URI sent to the identity provider uses
the public `https://` origin rather than the plain `http://` one the proxy actually connects to the
pod with. This is a no-op when there is no proxy in front, so it's safe to leave on for local testing.

The OIDC flow stores a short-lived pending authorization request in the session between the redirect
to the identity provider and the callback. If that request lands on a different replica, or the
session cookie doesn't round-trip on the callback, the sign-in fails with `authorization_request_not_found`
in the log; two things are worth checking if SSO login redirects you back to a generic error:

- Keep `replicaCount: 1` unless sessions are shared (see the database/persistence section above) —
  the callback has to land on the same instance that issued the redirect.
- Behind TLS, set `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=None` and `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`
  (both are standard Spring Boot properties, no code change needed) if the session cookie isn't making
  it back on the callback. Don't set `SECURE=true` without TLS in front — browsers refuse to send
  `Secure` cookies over plain HTTP, which would break sign-in entirely.

#### Multiple OIDC providers

To configure multiple providers, use indexed environment variables:

| Variable | Description |
|---|---|
| `OIDC_PROVIDERS_0_NAME` | Button label / display name |
| `OIDC_PROVIDERS_0_CLIENT_ID` | OAuth2 client ID |
| `OIDC_PROVIDERS_0_CLIENT_SECRET` | OAuth2 client secret |
| `OIDC_PROVIDERS_0_ISSUER_URI` | OIDC issuer URI |
| `OIDC_PROVIDERS_0_REGISTRATION_ID` | Optional explicit Spring Security registration id |
| `OIDC_PROVIDERS_0_USER_NAME_ATTRIBUTE` | Optional username claim, defaults to `preferred_username` |

Repeat the same pattern with `_1_`, `_2_`, and so on. Each configured provider is rendered as its own login button.

## Docker

Mount a volume on `/app/data`: it holds the H2 database and the generated encryption key, without
which stored S3 secrets cannot be decrypted.

```bash
docker run -d -p 8080:8080 \
  -v s3webui-data:/app/data \
  -e ADMIN_EMAIL=admin@example.com \
  -e ADMIN_PASSWORD=choose-something-better \
  ghcr.io/wenisch-tech/s3webui:latest
```

With PostgreSQL and a supplied encryption key, no volume is needed:

```bash
docker run -d -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/s3webui \
  -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
  -e SPRING_DATASOURCE_USERNAME=s3webui \
  -e SPRING_DATASOURCE_PASSWORD=secret \
  -e SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.PostgreSQLDialect \
  -e APP_ENCRYPTION_KEY="$(openssl rand -base64 32)" \
  ghcr.io/wenisch-tech/s3webui:latest
```

With OIDC:

```bash
docker run -d -p 8080:8080 \
  -e S3_ACCESS_KEY=your-access-key \
  -e S3_SECRET_KEY=your-secret-key \
  -e S3_ENDPOINT_URL=http://minio:9000 \
  -e OIDC_ENABLED=true \
  -e OIDC_PROVIDER_NAME="Company SSO" \
  -e OIDC_CLIENT_ID=s3webui \
  -e OIDC_CLIENT_SECRET=your-client-secret \
  -e OIDC_ISSUER_URI=http://oidc:8080/realms/myrealm \
  -e OIDC_REQUIRED_ROLE=s3-access \
  ghcr.io/wenisch-tech/s3webui:latest
```

## Helm chart

```bash
helm repo add wenisch-tech https://charts.wenisch.tech
helm repo update

helm install s3webui wenisch-tech/s3webui \
  --set persistence.enabled=true \
  --set secrets.ADMIN_PASSWORD=choose-something-better
```

`persistence.enabled=true` claims a volume for `/app/data`. Skip it only when you run PostgreSQL and
set `secrets.APP_ENCRYPTION_KEY` yourself.

To keep secrets out of your Helm values, pre-create the Kubernetes Secret and pass its name:

```bash
kubectl create secret generic s3webui-secrets \
  --from-literal=ADMIN_PASSWORD=choose-something-better \
  --from-literal=S3_ACCESS_KEY=your-access-key \
  --from-literal=S3_SECRET_KEY=your-secret-key
```

```bash
helm install s3webui wenisch-tech/s3webui \
  --set existingSecrets[0]=s3webui-secrets
```

`existingSecrets` can be used alone or together with `secrets`. If the same environment variable is
defined in both, the chart-managed value from `secrets` takes precedence.

### Example `values.yaml` with multiple OIDC providers

```yaml
persistence:
  enabled: true
  size: 1Gi

env:
  ADMIN_EMAIL: "admin@example.com"
  S3_ENDPOINT_URL: "http://minio.minio.svc.cluster.local:9000"
  S3_REGION: "us-east-1"
  IAM_ENABLED: "true"
  OIDC_ENABLED: "true"
  OIDC_PROVIDERS_0_NAME: "Internal SSO"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui"
  OIDC_PROVIDERS_0_ISSUER_URI: "http://keycloak.auth.svc.cluster.local:8080/realms/myrealm"
  OIDC_PROVIDERS_1_NAME: "Partner Login"
  OIDC_PROVIDERS_1_CLIENT_ID: "s3webui-partner"
  OIDC_PROVIDERS_1_ISSUER_URI: "https://partner-idp.example.com/realms/partner"
  OIDC_REQUIRED_ROLE: "s3-access"

secrets:
  ADMIN_PASSWORD: "choose-something-better"
  S3_ACCESS_KEY: "your-access-key"
  S3_SECRET_KEY: "your-secret-key"
  OIDC_PROVIDERS_0_CLIENT_SECRET: "your-client-secret"
  OIDC_PROVIDERS_1_CLIENT_SECRET: "your-other-client-secret"

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: s3webui.example.com
      paths:
        - path: /
          pathType: Prefix
```

### Example `values.yaml` with `existingSecrets`

```yaml
persistence:
  enabled: true
  size: 1Gi

existingSecrets:
  - s3webui-oidc-prod
  - s3webui-s3-credentials

env:
  ADMIN_EMAIL: "admin@example.com"
  S3_ENDPOINT_URL: "http://minio.minio.svc.cluster.local:9000"
  S3_REGION: "us-east-1"
  IAM_ENABLED: "true"
  OIDC_ENABLED: "true"
  OIDC_PROVIDERS_0_NAME: "Internal SSO"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui"
  OIDC_PROVIDERS_0_ISSUER_URI: "http://keycloak.auth.svc.cluster.local:8080/realms/myrealm"
  OIDC_REQUIRED_ROLE: "s3-access"

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: s3webui.example.com
      paths:
        - path: /
          pathType: Prefix
```

Create each Secret separately — one per OIDC provider, one for S3 credentials, etc. The keys inside
the Secrets must match the environment variable names the application expects.

## Running locally

**Prerequisites:** Java 17+, Maven 3.9+

### Building from source

```bash
mvn -B package -DskipTests
java -jar target/s3webui-*.jar
```

### Without OIDC

```bash
mvn spring-boot:run
```

Then open <http://localhost:8080> and sign in as `admin@s3webui.local` / `admin` (the password is
logged as a warning on first start). Add your storage under **Settings → S3 keys**, or pre-seed a
built-in key from the environment:

```bash
export S3_ACCESS_KEY=your-access-key
export S3_SECRET_KEY=your-secret-key
export S3_ENDPOINT_URL=http://your-s3-endpoint:9000
export S3_REGION=us-east-1

mvn spring-boot:run
```

### With a single OIDC provider

```bash
export S3_ACCESS_KEY=your-access-key
export S3_SECRET_KEY=your-secret-key
export S3_ENDPOINT_URL=http://your-s3-endpoint:9000

export OIDC_ENABLED=true
export OIDC_PROVIDER_NAME="Company SSO"
export OIDC_CLIENT_ID=s3webui
export OIDC_CLIENT_SECRET=your-client-secret
export OIDC_ISSUER_URI=http://localhost:8180/realms/myrealm
# Optional — require a specific realm role:
export OIDC_REQUIRED_ROLE=s3-access

mvn spring-boot:run
```

### With multiple OIDC providers

```bash
export S3_ACCESS_KEY=your-access-key
export S3_SECRET_KEY=your-secret-key
export S3_ENDPOINT_URL=http://your-s3-endpoint:9000

export OIDC_ENABLED=true
export OIDC_PROVIDERS_0_NAME="Internal SSO"
export OIDC_PROVIDERS_0_CLIENT_ID=s3webui
export OIDC_PROVIDERS_0_CLIENT_SECRET=internal-secret
export OIDC_PROVIDERS_0_ISSUER_URI=https://auth.example.com/realms/internal

export OIDC_PROVIDERS_1_NAME="Partner Login"
export OIDC_PROVIDERS_1_CLIENT_ID=s3webui-partner
export OIDC_PROVIDERS_1_CLIENT_SECRET=partner-secret
export OIDC_PROVIDERS_1_ISSUER_URI=https://auth.partner.example/realms/partner

mvn spring-boot:run
```

> **OIDC provider setup:** Create a client in your realm/provider with:
> - Client Protocol: `openid-connect`
> - Access Type: `confidential`
> - Valid Redirect URIs: `http://localhost:8080/*`
> - Set the issuer URI to `http://<provider-host>/realms/<realm-name>` or the provider's standard OIDC issuer URL

### Running under a subpath

S3 Web UI supports any servlet context path. Set the standard Spring Boot property
`SERVER_SERVLET_CONTEXT_PATH` to the public path prefix, for example `/s3webui` or `/s3/foo`.
Changing the value only changes the environment variable; no rebuild or application code change is
required. A reverse proxy must forward the complete public URI, including that prefix.

```bash
docker run -d -p 127.0.0.1:8080:8080 \
  -v s3webui-data:/app/data \
  -e SERVER_SERVLET_CONTEXT_PATH=/s3webui \
  -e SERVER_FORWARD_HEADERS_STRATEGY=framework \
  ghcr.io/wenisch-tech/s3webui:latest
```

The matching Nginx location keeps the prefix intact and forwards the public origin:

```nginx
location = /s3webui {
    return 308 /s3webui/;
}

location ^~ /s3webui/ {
    absolute_redirect off;

    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
}
```



## Contributing
Pull requests welcomed.

> **Please note :** CVE scanning via [Trivy](https://github.com/aquasecurity/trivy) is an essential part of the development process and runs automatically on every pull request. Fixing identified vulnerabilities is a mandatory step before merging.

## License

AGPL-3.0 — see [LICENSE](LICENSE) for details.

Copyright (C) 2026 Jean-Fabian Wenisch / wenisch.tech
