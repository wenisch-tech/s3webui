# Install S3 Web UI

S3 Web UI runs as a single container. Choose how you want to run it, then sign in and connect your storage.

| Guide | Use it when you want to… |
|---|---|
| [Run with Docker](Docker.md) | start S3 Web UI on one machine with `docker run` or Docker Compose |
| [Run on Kubernetes with Helm](Helm.md) | deploy to a cluster with persistence, Secrets, ingress and health probes |
| [First sign-in and connecting storage](First-Steps.md) | sign in for the first time, connect an S3 endpoint and upload a first file |
| [Configure the database, encryption key and environment variables](Configuration.md) | use PostgreSQL, keep the encryption key safe or look up a setting |
| [Put S3 Web UI behind a reverse proxy](Reverse-Proxy.md) | add TLS, run behind nginx or an ingress, or publish under a path prefix |

After installation, continue with [Use S3 Web UI](../Usage/README.md) for storage operations or [Administer S3 Web UI](../Administration/README.md) to configure access.
