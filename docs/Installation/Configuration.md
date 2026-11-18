---
sidebar_label: Configuration reference
---

# Configure the database, encryption key and environment variables

This page covers the configuration choices that matter in production, whichever way you run S3 Web UI ([Docker](Docker.md) or [Helm](Helm.md)). Environment variables configure how the application is deployed; the **Settings** panel configures shared S3 keys and runtime options afterwards.

## Persistence and database

The default H2 file database lives in `APP_DATA_DIR` (`/app/data` in the container). That directory also holds a generated `encryption.key` unless `APP_ENCRYPTION_KEY` is supplied. Persist it, or set a stable externally managed encryption key and use PostgreSQL.

| Deployment shape | Requirements |
|---|---|
| Single replica, H2 | Persist `/app/data`; keep `replicaCount: 1` |
| Multiple replicas | PostgreSQL, shared/sticky HTTP sessions, and a shared `APP_ENCRYPTION_KEY` |
| Ephemeral app disk | PostgreSQL plus a supplied `APP_ENCRYPTION_KEY` |

H2 tolerates a single writer. PostgreSQL alone does not make browser sessions shared, so an OIDC callback must still return to the same instance unless sessions are shared or the load balancer is sticky.

To supply the encryption key, generate a base64-encoded 32-byte value once and retain it securely:

```bash
openssl rand -base64 32
```

Example with PostgreSQL on Docker:

```bash
docker run -d -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/s3webui \
  -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver \
  -e SPRING_DATASOURCE_USERNAME=s3webui \
  -e SPRING_DATASOURCE_PASSWORD='replace-me' \
  -e SPRING_JPA_DATABASE_PLATFORM=org.hibernate.dialect.PostgreSQLDialect \
  -e APP_ENCRYPTION_KEY='base64-32-byte-key' \
  ghcr.io/wenisch-tech/s3webui:latest
```

## Core environment variables

| Variable | Purpose | Default |
|---|---|---|
| `APP_DATA_DIR` | H2 database and generated encryption key directory | `./data` |
| `ADMIN_EMAIL`, `ADMIN_PASSWORD` | First administrator only; not a later password reset | `admin@s3webui.local`, `admin` |
| `APP_ENCRYPTION_KEY` | Base64 32-byte key for stored S3 secrets | generated in `APP_DATA_DIR` |
| `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_ENDPOINT_URL` | Optional built-in S3 key; set all three | unset |
| `S3_REGION` | Region for the built-in key | `us-east-1` |
| `S3_INSECURE_SKIP_TLS_VERIFY` | Disables S3 certificate verification | `false` |
| `IAM_ENABLED` | Shows storage-backend IAM management | `true` |
| `DISABLE_AUTHENTICATION` | Makes all visitors administrators | `false` |

See [Choose a sign-in method](../Administration/Authentication.md) for the sign-in modes, and [Set up OIDC single sign-on](../Administration/OIDC-Setup.md) for the OIDC variables.

## Health endpoints

The application listens on port 8080 and exposes health endpoints at `/actuator/health/liveness` and `/actuator/health/readiness`. Use them for container or Kubernetes health checks, and keep them on internal routing; health details are not exposed.
