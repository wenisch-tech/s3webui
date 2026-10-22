# Deployment and configuration

This guide covers the durable configuration choices that matter in production. Environment variables configure deployment behavior; the Settings panel configures shared S3 keys and runtime options.

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

See [Authentication](../Administration/Authentication.md) for the OIDC variables and security consequences.

## Docker health endpoint

The application listens on port 8080 and exposes health endpoints at `/actuator/health/liveness` and `/actuator/health/readiness`. Put these behind internal routing or your container orchestration checks; health details are not exposed.

## Helm

The chart is in [`charts/s3webui`](../../charts/s3webui). A minimal durable release is:

```bash
helm install s3webui ./charts/s3webui \
  --set persistence.enabled=true \
  --set secrets.ADMIN_PASSWORD='choose-a-strong-password'
```

Put sensitive values in `secrets`, or preferably create Kubernetes Secrets separately and list them under `existingSecrets`. Their keys must be environment-variable names such as `S3_SECRET_KEY` or `OIDC_CLIENT_SECRET`. Values under the chart's `secrets` take precedence if both supply the same key.

The chart's `Recreate` strategy prevents two pods opening the H2 file during upgrades. Change that only after moving to PostgreSQL and providing session affinity or another session-sharing strategy.

For ingress, Secret precedence, probes, Pod security context, resources, persistence values, and placement controls, see the [Helm chart reference](Helm-Chart.md).

## Reverse proxies and context paths

`SERVER_FORWARD_HEADERS_STRATEGY=framework` is enabled by default. It makes the application honor `X-Forwarded-*` headers so OIDC redirects use the public HTTPS address.

To publish under a prefix, set `SERVER_SERVLET_CONTEXT_PATH`, for example `/s3webui`. Your proxy must forward the complete prefix rather than stripping it:

```nginx
location = /s3webui { return 308 /s3webui/; }

location ^~ /s3webui/ {
    absolute_redirect off;
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $host;
    proxy_set_header X-Forwarded-Port $server_port;
}
```

For an OIDC callback failing with `authorization_request_not_found`, first ensure the callback lands on the same instance. When TLS is terminated in front of the app, setting `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE=None` and `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` can allow the pending OIDC session to return. Do not set `Secure=true` when serving plain HTTP: browsers will not send the cookie.
