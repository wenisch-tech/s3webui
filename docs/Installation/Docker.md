---
sidebar_label: Docker
---

# Run with Docker

This page starts S3 Web UI as a single container and shows how to keep its data and run it with Docker Compose. PostgreSQL is covered in the [configuration reference](Configuration.md#persistence-and-database). For Kubernetes, see [Run on Kubernetes with Helm](Helm.md).

## Start the container

You need an S3-compatible endpoint, such as AWS S3, Ceph, MinIO or RustFS. The data volume is deliberate: it preserves users, stored S3 keys and the generated encryption key when the container is replaced.

```bash
docker run -d --name s3webui -p 8080:8080 \
  -v s3webui-data:/app/data \
  -e S3_ACCESS_KEY=your-access-key \
  -e S3_SECRET_KEY=your-secret-key \
  -e S3_ENDPOINT_URL=https://s3.example.com \
  ghcr.io/wenisch-tech/s3webui:latest
```

Open <http://localhost:8080> and continue with [First sign-in](First-Steps.md). The three `S3_*` values are optional: they create a built-in S3 key for every signed-in user. Without them you add keys in the application.

Pin a version tag such as `ghcr.io/wenisch-tech/s3webui:1.9.1` instead of `latest` for reproducible deployments. Images are signed with cosign.

## Try it with a local RustFS

If you have no storage to connect, run RustFS next to the application for a quick local test:

```bash
docker run -d --name rustfs -p 9000:9000 -p 9001:9001 \
  -v rustfs-data:/data \
  -e RUSTFS_ACCESS_KEY=S3WEBUI \
  -e RUSTFS_SECRET_KEY=s3webui-secret \
  -e RUSTFS_ADDRESS=:9000 \
  -e RUSTFS_CONSOLE_ADDRESS=:9001 \
  rustfs/rustfs:latest /data

docker run -d --name s3webui -p 8080:8080 \
  --add-host=host.docker.internal:host-gateway \
  -v s3webui-data:/app/data \
  -e S3_ACCESS_KEY=S3WEBUI \
  -e S3_SECRET_KEY=s3webui-secret \
  -e S3_ENDPOINT_URL=http://host.docker.internal:9000 \
  ghcr.io/wenisch-tech/s3webui:latest
```

This is for local development only. Use unique credentials and TLS for a real deployment.

## Docker Compose

```yaml
services:
  s3webui:
    image: ghcr.io/wenisch-tech/s3webui:latest
    ports:
      - "8080:8080"
    volumes:
      - s3webui-data:/app/data
    environment:
      ADMIN_PASSWORD: replace-with-a-strong-password
      S3_ACCESS_KEY: your-access-key
      S3_SECRET_KEY: your-secret-key
      S3_ENDPOINT_URL: https://s3.example.com
    restart: unless-stopped

volumes:
  s3webui-data:
```

`ADMIN_PASSWORD` only applies while the user table is empty, on the first start.

## Upgrade

Pull the new image and recreate the container. The data volume keeps your configuration:

```bash
docker pull ghcr.io/wenisch-tech/s3webui:latest
docker rm -f s3webui
# then run the same docker run command again
```

With Compose, run `docker compose pull && docker compose up -d`.

## Next steps

- [Configuration reference](Configuration.md): environment variables, PostgreSQL and the encryption key.
- [Reverse proxy and sub-path](Reverse-Proxy.md): TLS and publishing under a path.
- [Sign-in and OIDC](../Administration/Authentication.md): single sign-on.
