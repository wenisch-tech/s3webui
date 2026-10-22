# Helm chart reference

The chart is located at [`charts/s3webui`](../../charts/s3webui). Use this guide with [Deployment](Deployment.md) for application configuration and persistence rationale.

## Durable single-replica installation

```bash
helm install s3webui ./charts/s3webui \
  --set persistence.enabled=true \
  --set secrets.ADMIN_PASSWORD='replace-with-a-strong-password'
```

The default H2 database and generated encryption key live in `/app/data`. Enable persistence unless PostgreSQL is configured and `APP_ENCRYPTION_KEY` is supplied through a secret.

## Values that affect availability

| Value | Default | Meaning |
|---|---:|---|
| `replicaCount` | `1` | Keep one replica with H2. More replicas require PostgreSQL and sticky/shared sessions. |
| `strategy.type` | `Recreate` | Prevents two Pods from opening the H2 file during an upgrade. |
| `persistence.enabled` | `false` | Creates/mounts a volume at `/app/data`. |
| `persistence.size` | `1Gi` | Requested persistent-volume size. |
| `persistence.existingClaim` | empty | Reuses an existing claim instead of creating one. |
| `resources` | 100m/256Mi request | CPU and memory requests/limits for the Pod. |

The chart provides a startup probe, liveness probe, and readiness probe. Startup allows H2, Flyway, and Hibernate initialization time; readiness removes an unhealthy Pod from service without necessarily restarting it; liveness detects a stuck process.

## Secrets and configuration

Put non-sensitive application values under `env`. Put passwords, S3 secrets, encryption keys, and OIDC client secrets under `secrets`, or provide existing Kubernetes Secrets under `existingSecrets`.

```yaml
persistence:
  enabled: true

existingSecrets:
  - s3webui-production-secrets

env:
  ADMIN_EMAIL: admin@example.com
  S3_ENDPOINT_URL: https://s3.example.com
  OIDC_ENABLED: "true"
  OIDC_PROVIDERS_0_NAME: Company SSO
  OIDC_PROVIDERS_0_CLIENT_ID: s3webui
  OIDC_PROVIDERS_0_ISSUER_URI: https://login.example.com
```

The keys in an existing Secret must exactly match environment-variable names, such as `ADMIN_PASSWORD`, `S3_SECRET_KEY`, `APP_ENCRYPTION_KEY`, or `OIDC_PROVIDERS_0_CLIENT_SECRET`. Multiple `existingSecrets` are imported in list order. If a key is also set in chart-managed `secrets`, the chart-managed value wins.

## Ingress and TLS

Enable ingress and set the public hostname. TLS termination is recommended, especially for OIDC.

```yaml
ingress:
  enabled: true
  className: nginx
  hosts:
    - host: s3webui.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: s3webui-tls
      hosts:
        - s3webui.example.com
```

`SERVER_FORWARD_HEADERS_STRATEGY=framework` is enabled by default so S3 Web UI honors forwarded host and scheme headers. For an OIDC session-cookie callback issue behind TLS, see [Authentication](../Administration/Authentication.md#proxy-and-callback-configuration).

## Pod placement and security

The chart runs as a non-root user, drops Linux capabilities, and lets you set `nodeSelector`, `tolerations`, and `affinity`. Its root filesystem is writable by default because H2 and the generated encryption key need application data storage; mounting the configured data path is the durable approach.

For HA deployment design and PostgreSQL configuration, see [Deployment](Deployment.md#persistence-and-database).
