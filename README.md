# S3 Web UI

A modern, clean graphical web interface for S3-compatible object storage, built with Spring Boot and Bootstrap 5.  
Dark theme by default — switch to light with the toggle in the top-right corner.

![CI](https://github.com/JFWenisch/s3webui/actions/workflows/ci.yml/badge.svg)

## Features

- 🗂 **Browse buckets** — list all buckets with creation date
- 📁 **Navigate folders** — browse objects with breadcrumb navigation
- ➕ **Create buckets** — create new buckets directly from the UI
- ⬆️ **Upload files** — client-side multipart upload with real-time progress bar and ETA
- ⬇️ **Download files** — download any object in a single click
- ✏️ **Rename objects** — rename files without re-uploading
- 🗑 **Delete** — delete individual objects or entire buckets
- 📦 **Folder support** — create virtual folders (prefix-based)
- 🌗 **Dark / Light theme** — toggle stored in `localStorage`, dark is the default
- 🔒 **OIDC / Keycloak** — optional single-sign-on with role-based access control

## Configuration

All settings are provided via environment variables:

### S3 connection

| Variable | Description | Default |
|---|---|---|
| `S3_ACCESS_KEY` | S3 access key / username | `minioadmin` |
| `S3_SECRET_KEY` | S3 secret key / password | `minioadmin` |
| `S3_ENDPOINT_URL` | S3-compatible endpoint URL | `http://localhost:9000` |
| `S3_REGION` | AWS region (optional) | `us-east-1` |

### OIDC / Keycloak (optional)

| Variable | Description | Default |
|---|---|---|
| `OIDC_ENABLED` | Enable OIDC authentication | `false` |
| `OIDC_CLIENT_ID` | OAuth2 client ID | — |
| `OIDC_CLIENT_SECRET` | OAuth2 client secret | — |
| `OIDC_ISSUER_URI` | Keycloak realm issuer URI (e.g. `http://keycloak:8080/realms/myrealm`) | — |
| `OIDC_REQUIRED_ROLE` | Keycloak realm role required to access the app (optional) | — |

When `OIDC_ENABLED=true`, unauthenticated users are redirected to the login page where they can sign in via Keycloak.  
If `OIDC_REQUIRED_ROLE` is set, users without that realm role receive an **Access Denied** page.

## Running locally

**Prerequisites:** Java 17+, Maven 3.9+

### Without OIDC

```bash
export S3_ACCESS_KEY=your-access-key
export S3_SECRET_KEY=your-secret-key
export S3_ENDPOINT_URL=http://your-s3-endpoint:9000
export S3_REGION=us-east-1

mvn spring-boot:run
```

Then open <http://localhost:8080> in your browser.

### With Keycloak OIDC

```bash
export S3_ACCESS_KEY=your-access-key
export S3_SECRET_KEY=your-secret-key
export S3_ENDPOINT_URL=http://your-s3-endpoint:9000

export OIDC_ENABLED=true
export OIDC_CLIENT_ID=s3webui
export OIDC_CLIENT_SECRET=your-client-secret
export OIDC_ISSUER_URI=http://localhost:8180/realms/myrealm
# Optional — require a specific Keycloak realm role:
export OIDC_REQUIRED_ROLE=s3-access

mvn spring-boot:run
```

> **Keycloak setup:** Create a client in your realm with:
> - Client Protocol: `openid-connect`
> - Access Type: `confidential`
> - Valid Redirect URIs: `http://localhost:8080/*`
> - Set `OIDC_ISSUER_URI` to `http://<keycloak-host>/realms/<realm-name>`

### Quick start with MinIO

```bash
# Start MinIO
docker run -d -p 9000:9000 -p 9001:9001 \
  -e MINIO_ROOT_USER=minioadmin \
  -e MINIO_ROOT_PASSWORD=minioadmin \
  minio/minio server /data --console-address ":9001"

# Start S3 Web UI
docker run -d -p 8080:8080 \
  -e S3_ACCESS_KEY=minioadmin \
  -e S3_SECRET_KEY=minioadmin \
  -e S3_ENDPOINT_URL=http://host.docker.internal:9000 \
  ghcr.io/jfwendisch/s3webui:latest
```

## Docker

```bash
docker run -d -p 8080:8080 \
  -e S3_ACCESS_KEY=your-access-key \
  -e S3_SECRET_KEY=your-secret-key \
  -e S3_ENDPOINT_URL=http://your-s3-endpoint:9000 \
  -e S3_REGION=us-east-1 \
  ghcr.io/jfwendisch/s3webui:latest
```

With Keycloak OIDC:

```bash
docker run -d -p 8080:8080 \
  -e S3_ACCESS_KEY=your-access-key \
  -e S3_SECRET_KEY=your-secret-key \
  -e S3_ENDPOINT_URL=http://minio:9000 \
  -e OIDC_ENABLED=true \
  -e OIDC_CLIENT_ID=s3webui \
  -e OIDC_CLIENT_SECRET=your-client-secret \
  -e OIDC_ISSUER_URI=http://keycloak:8080/realms/myrealm \
  -e OIDC_REQUIRED_ROLE=s3-access \
  ghcr.io/jfwendisch/s3webui:latest
```

## Helm chart

```bash
helm repo add jfwendisch https://jfwendisch.github.io/charts
helm repo update

helm install s3webui jfwendisch/s3webui \
  --set env.S3_ENDPOINT_URL=http://minio:9000 \
  --set secrets.S3_ACCESS_KEY=your-access-key \
  --set secrets.S3_SECRET_KEY=your-secret-key
```

### Example `values.yaml` with OIDC

```yaml
env:
  S3_ENDPOINT_URL: "http://minio.minio.svc.cluster.local:9000"
  S3_REGION: "us-east-1"
  OIDC_ENABLED: "true"
  OIDC_CLIENT_ID: "s3webui"
  OIDC_ISSUER_URI: "http://keycloak.auth.svc.cluster.local:8080/realms/myrealm"
  OIDC_REQUIRED_ROLE: "s3-access"

secrets:
  S3_ACCESS_KEY: "your-access-key"
  S3_SECRET_KEY: "your-secret-key"
  OIDC_CLIENT_SECRET: "your-client-secret"

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: s3webui.example.com
      paths:
        - path: /
          pathType: Prefix
```

## Building from source

```bash
mvn -B package -DskipTests
java -jar target/s3webui-*.jar
```

## Architecture

```
src/main/java/tech/wenisch/s3webui/
├── S3WebUiApplication.java          # Spring Boot entry point
├── config/
│   ├── S3Config.java                # S3Client / S3Presigner beans
│   ├── SecurityConfig.java          # Spring Security (permit-all or OIDC)
│   ├── GlobalModelAttributes.java   # Injects oidcEnabled into all templates
│   ├── FileIconHelper.java          # Maps file extension → Bootstrap icon
│   └── FileSizeFormatter.java       # Formats byte counts for display
├── controller/
│   ├── UiController.java            # Thymeleaf MVC (buckets, bucket, login, access-denied)
│   └── S3ApiController.java         # REST API (CRUD + multipart upload)
├── service/
│   └── S3Service.java               # All S3 SDK interactions
└── model/
    ├── BucketDto.java
    ├── S3ObjectDto.java
    └── CompleteMultipartRequest.java
```

## Upload flow

Files **≤ 5 MB** are uploaded via a simple multipart form POST through the backend.

Files **> 5 MB** use **client-side S3 multipart upload**:
1. Browser calls `POST /api/buckets/{b}/multipart/initiate` to get an `uploadId`
2. For each 5 MB chunk, it calls `GET /api/buckets/{b}/multipart/presign` for a presigned URL, then PUTs the chunk directly to S3
3. Browser calls `POST /api/buckets/{b}/multipart/complete` to finish the upload

Progress percentage and estimated time remaining are computed entirely in the browser using `XMLHttpRequest` upload events.

## License

GPL-3.0 — see [LICENSE](LICENSE) for details.
