# S3WebUI Helm Chart

A Kubernetes Helm chart for deploying **S3WebUI** — a graphical web interface for S3-compatible object storage systems like MinIO, AWS S3, and others.

## Features

- **Multi-provider OIDC support** for single sign-on integration
- **S3 storage configuration** with support for any S3-compatible endpoint
- **Kubernetes-native deployment** with security context and resource controls
- **Health checks** with configurable liveness and readiness probes
- **Ingress support** for external access
- **Flexible authentication** with optional role-based access control

## Prerequisites

- Kubernetes 1.20+
- Helm 3.0+
- An S3-compatible object storage service (AWS S3, MinIO, etc.)
- (Optional) OIDC provider for single sign-on (Keycloak, Okta, etc.)

## Installation

### Add the Chart Repository

If the chart is hosted in a Helm registry:

```bash
helm repo add wenisch-tech https://charts.example.com
helm repo update
```

### Install the Chart

**Basic installation with S3 configuration:**

```bash
helm install s3webui wenisch-tech/s3webui \
  --set env.S3_ACCESS_KEY="your-access-key" \
  --set env.S3_SECRET_KEY="your-secret-key" \
  --set env.S3_ENDPOINT_URL="https://s3.example.com" \
  --set env.S3_REGION="us-east-1"
```

**Installation with OIDC single sign-on:**

```bash
helm install s3webui wenisch-tech/s3webui \
  --set env.S3_ACCESS_KEY="your-access-key" \
  --set env.S3_SECRET_KEY="your-secret-key" \
  --set env.S3_ENDPOINT_URL="https://s3.example.com" \
  --set env.OIDC_ENABLED="true" \
  --set env.OIDC_PROVIDER_NAME="Company SSO" \
  --set env.OIDC_CLIENT_ID="s3webui" \
  --set env.OIDC_ISSUER_URI="https://auth.example.com/realms/main" \
  --set secrets.OIDC_CLIENT_SECRET="your-oidc-client-secret"
```

**Installation with multiple OIDC providers:**

```bash
helm install s3webui wenisch-tech/s3webui \
  --set env.S3_ACCESS_KEY="your-access-key" \
  --set env.S3_SECRET_KEY="your-secret-key" \
  --set env.S3_ENDPOINT_URL="https://s3.example.com" \
  --set env.OIDC_ENABLED="true" \
  --set "env.OIDC_PROVIDERS_0_NAME=Internal SSO" \
  --set "env.OIDC_PROVIDERS_0_CLIENT_ID=s3webui" \
  --set "env.OIDC_PROVIDERS_0_ISSUER_URI=https://auth.example.com/realms/main" \
  --set "env.OIDC_PROVIDERS_1_NAME=Partner Login" \
  --set "env.OIDC_PROVIDERS_1_CLIENT_ID=s3webui-partner" \
  --set "env.OIDC_PROVIDERS_1_ISSUER_URI=https://partner.example.com/realms/partner" \
  --set "secrets.OIDC_PROVIDERS_0_CLIENT_SECRET=secret-for-internal" \
  --set "secrets.OIDC_PROVIDERS_1_CLIENT_SECRET=secret-for-partner"
```

### Using a Values File

Create a `values-prod.yaml` file:

```yaml
replicaCount: 2

image:
  tag: v0.5.2

service:
  type: LoadBalancer

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: s3.example.com
      paths:
        - path: /
  tls:
    - secretName: s3webui-tls
      hosts:
        - s3.example.com

resources:
  requests:
    cpu: 200m
    memory: 512Mi
  limits:
    cpu: 1000m
    memory: 1Gi

env:
  S3_ENDPOINT_URL: "https://s3.prod.example.com"
  S3_REGION: "us-west-2"
  S3_INSECURE_SKIP_TLS_VERIFY: "false"
  OIDC_ENABLED: "true"
  OIDC_PROVIDERS_0_NAME: "Corporate SSO"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui"
  OIDC_PROVIDERS_0_ISSUER_URI: "https://keycloak.example.com/realms/production"

secrets:
  S3_ACCESS_KEY: "prod-access-key"
  S3_SECRET_KEY: "prod-secret-key"
  OIDC_PROVIDERS_0_CLIENT_SECRET: "prod-oidc-secret"
```

Install using the values file:

```bash
helm install s3webui ./s3webui -f values-prod.yaml
```

## Configuration

### S3 Storage Parameters

| Parameter | Description | Default | Required |
|-----------|-------------|---------|----------|
| `env.S3_ACCESS_KEY` | AWS/S3 access key | `""` | Yes |
| `env.S3_SECRET_KEY` | AWS/S3 secret key | `""` | Yes |
| `env.S3_ENDPOINT_URL` | S3 endpoint URL | `""` | Yes |
| `env.S3_REGION` | AWS region | `us-east-1` | No |
| `env.S3_INSECURE_SKIP_TLS_VERIFY` | Skip TLS verification (not recommended for production) | `false` | No |

### OIDC / Authentication Parameters

| Parameter | Description | Default | Required |
|-----------|-------------|---------|----------|
| `env.OIDC_ENABLED` | Enable OIDC authentication | `false` | No |
| `env.OIDC_PROVIDER_NAME` | Display name for single-provider setup | `Single Sign-On` | No |
| `env.OIDC_CLIENT_ID` | OIDC client ID (deprecated, use OIDC_PROVIDERS_*) | `""` | No |
| `env.OIDC_ISSUER_URI` | OIDC issuer URI (deprecated, use OIDC_PROVIDERS_*) | `""` | No |
| `env.OIDC_REQUIRED_ROLE` | Restrict access to users with this role | `""` | No |
| `env.OIDC_INSECURE_SKIP_TLS_VERIFY` | Skip TLS verification for OIDC | `false` | No |

### Multi-Provider OIDC (Indexed Variables)

Configure multiple OIDC providers by using indexed environment variables:

```yaml
env:
  OIDC_ENABLED: "true"
  OIDC_PROVIDERS_0_NAME: "Internal SSO"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui"
  OIDC_PROVIDERS_0_ISSUER_URI: "https://auth.example.com/realms/main"
  OIDC_PROVIDERS_1_NAME: "Partner Login"
  OIDC_PROVIDERS_1_CLIENT_ID: "s3webui-partner"
  OIDC_PROVIDERS_1_ISSUER_URI: "https://partner.example.com/realms/partner"

secrets:
  OIDC_PROVIDERS_0_CLIENT_SECRET: "client-secret-internal"
  OIDC_PROVIDERS_1_CLIENT_SECRET: "client-secret-partner"
```

Each provider will display its own button on the login page. Increment the index (0, 1, 2, ...) for additional providers.

### Deployment Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `replicaCount` | Number of replicas | `1` |
| `image.registry` | Container image registry | `ghcr.io` |
| `image.repository` | Container image repository | `wenisch-tech/s3webui` |
| `image.tag` | Container image tag | `latest` |
| `image.pullPolicy` | Image pull policy | `IfNotPresent` |

### Service & Ingress Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `service.type` | Kubernetes service type | `ClusterIP` |
| `service.port` | Service port | `8080` |
| `service.targetPort` | Pod port | `8080` |
| `ingress.enabled` | Enable ingress | `false` |
| `ingress.className` | Ingress class name | `""` |
| `ingress.hosts[0].host` | Ingress hostname | `s3webui.example.com` |
| `ingress.tls` | TLS configuration | `[]` |

### Security Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `podSecurityContext.runAsUser` | Pod user ID | `65532` |
| `podSecurityContext.fsGroup` | Pod filesystem group | `65532` |
| `securityContext.allowPrivilegeEscalation` | Allow privilege escalation | `false` |
| `securityContext.readOnlyRootFilesystem` | Read-only root filesystem | `false` |

### Resource Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `resources.requests.cpu` | CPU request | `100m` |
| `resources.requests.memory` | Memory request | `256Mi` |
| `resources.limits.cpu` | CPU limit | `500m` |
| `resources.limits.memory` | Memory limit | `512Mi` |

### Health Check Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `livenessProbe.enabled` | Enable liveness probe | `true` |
| `livenessProbe.initialDelaySeconds` | Initial delay | `30` |
| `livenessProbe.periodSeconds` | Probe period | `10` |
| `livenessProbe.failureThreshold` | Failure threshold | `3` |
| `readinessProbe.enabled` | Enable readiness probe | `true` |
| `readinessProbe.initialDelaySeconds` | Initial delay | `10` |
| `readinessProbe.periodSeconds` | Probe period | `5` |
| `readinessProbe.failureThreshold` | Failure threshold | `3` |

## Examples

### Example 1: MinIO with OIDC

```bash
helm install s3webui ./s3webui \
  --set env.S3_ENDPOINT_URL="https://minio.example.com:9000" \
  --set env.S3_REGION="us-east-1" \
  --set env.S3_INSECURE_SKIP_TLS_VERIFY="true" \
  --set env.OIDC_ENABLED="true" \
  --set env.OIDC_PROVIDER_NAME="Keycloak" \
  --set env.OIDC_CLIENT_ID="s3webui" \
  --set env.OIDC_ISSUER_URI="https://keycloak.example.com/realms/main" \
  --set secrets.S3_ACCESS_KEY="minioadmin" \
  --set secrets.S3_SECRET_KEY="minioadmin" \
  --set secrets.OIDC_CLIENT_SECRET="your-keycloak-secret"
```

### Example 2: AWS S3 with Ingress

```yaml
# values.yaml
image:
  tag: v0.5.2

ingress:
  enabled: true
  className: nginx
  hosts:
    - host: s3webui.example.com
      paths:
        - path: /
          pathType: Prefix
  tls:
    - secretName: s3webui-tls-cert
      hosts:
        - s3webui.example.com

env:
  S3_ENDPOINT_URL: "https://s3.amazonaws.com"
  S3_REGION: "eu-west-1"
  OIDC_ENABLED: "false"

secrets:
  S3_ACCESS_KEY: "AKIAIOSFODNN7EXAMPLE"
  S3_SECRET_KEY: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
```

### Example 3: Multiple OIDC Providers

```yaml
# values.yaml
env:
  S3_ENDPOINT_URL: "https://s3.example.com"
  S3_REGION: "us-east-1"
  OIDC_ENABLED: "true"
  # First provider
  OIDC_PROVIDERS_0_NAME: "Corporate SSO"
  OIDC_PROVIDERS_0_CLIENT_ID: "s3webui-corp"
  OIDC_PROVIDERS_0_ISSUER_URI: "https://corporate-auth.example.com/realms/main"
  # Second provider
  OIDC_PROVIDERS_1_NAME: "Partner A"
  OIDC_PROVIDERS_1_CLIENT_ID: "s3webui-partner-a"
  OIDC_PROVIDERS_1_ISSUER_URI: "https://partner-a-auth.example.com/realms/s3webui"
  # Third provider
  OIDC_PROVIDERS_2_NAME: "Partner B"
  OIDC_PROVIDERS_2_CLIENT_ID: "s3webui-partner-b"
  OIDC_PROVIDERS_2_ISSUER_URI: "https://partner-b-auth.example.com"

secrets:
  S3_ACCESS_KEY: "access-key"
  S3_SECRET_KEY: "secret-key"
  OIDC_PROVIDERS_0_CLIENT_SECRET: "corporate-client-secret"
  OIDC_PROVIDERS_1_CLIENT_SECRET: "partner-a-client-secret"
  OIDC_PROVIDERS_2_CLIENT_SECRET: "partner-b-client-secret"
```

## Upgrading the Chart

To upgrade an existing release:

```bash
helm upgrade s3webui ./s3webui -f values-prod.yaml
```

To see what changes will be applied:

```bash
helm diff upgrade s3webui ./s3webui -f values-prod.yaml
```

## Uninstalling the Chart

```bash
helm uninstall s3webui
```

This command removes all Kubernetes components associated with the chart and deletes the release.

## Security Considerations

1. **Store Secrets Securely**: Use Kubernetes Secrets or external secret management (e.g., HashiCorp Vault, AWS Secrets Manager) for sensitive values like `S3_SECRET_KEY` and `OIDC_CLIENT_SECRET`.

   ```bash
   # Use --set to inject secrets from environment variables
   helm install s3webui ./s3webui \
     --set secrets.S3_ACCESS_KEY="$S3_ACCESS_KEY" \
     --set secrets.S3_SECRET_KEY="$S3_SECRET_KEY"
   ```

2. **TLS for OIDC**: Always use HTTPS for OIDC issuer URIs in production. Set `OIDC_INSECURE_SKIP_TLS_VERIFY=false` (default).

3. **Network Policies**: Consider implementing Kubernetes Network Policies to restrict traffic.

4. **Pod Security**: The chart runs with a non-root user (UID 65532) and drops all Linux capabilities by default.

5. **TLS for S3**: Use HTTPS endpoints when connecting to S3. Only set `S3_INSECURE_SKIP_TLS_VERIFY=true` for development/testing with self-signed certificates.

6. **RBAC Access**: Use Kubernetes RBAC to limit who can view/edit the S3WebUI deployment and secrets.

## Troubleshooting

### Pod fails to start

Check pod logs:

```bash
kubectl logs -n default deployment/s3webui
```

Common issues:
- Missing S3 credentials: Verify `env.S3_ACCESS_KEY` and `env.S3_SECRET_KEY` are configured
- Invalid S3 endpoint: Ensure `env.S3_ENDPOINT_URL` is reachable and correct
- OIDC misconfiguration: Verify `OIDC_ISSUER_URI`, `OIDC_CLIENT_ID`, and `OIDC_CLIENT_SECRET` are correct

### Application logs show permission errors

Check that the pod has correct AWS/S3 permissions:

```bash
# Test S3 connectivity from within the pod
kubectl exec -it deployment/s3webui -- bash
aws s3 ls --endpoint-url https://s3.example.com
```

### OIDC login not working

1. Verify OIDC is enabled: `env.OIDC_ENABLED: "true"`
2. Check issuer URI accessibility from the pod
3. Verify client ID and secret match your OIDC provider configuration
4. Check application logs for OIDC configuration errors

### Health checks failing

If readiness/liveness probes fail:

```bash
# Check actuator health endpoint
kubectl port-forward deployment/s3webui 8080:8080
curl http://localhost:8080/actuator/health
```

Adjust `initialDelaySeconds` and `periodSeconds` if the application is slow to start.

## Development

### Building the Chart

Validate the chart syntax:

```bash
helm lint ./s3webui
```

Render templates locally without deploying:

```bash
helm template s3webui ./s3webui -f values-prod.yaml
```

### Testing the Chart

Use Helm test hooks to validate deployments:

```bash
helm test s3webui
```

## Storage

This Helm chart does not include persistent storage by default. S3WebUI is a **stateless application** — it does not store user session data or application state that needs to persist across pod restarts.

- Session data is managed by your OIDC provider
- S3 object metadata is stored in your S3 backend
- No persistent volume is required

## Support and Contributing

For issues, feature requests, or contributions, please visit the [S3WebUI GitHub repository](https://github.com/wenisch-tech/s3webui).

## License

This Helm chart is distributed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. See the [LICENSE](../../LICENSE) file for details.

---

**Chart Version**: 0.5.2  
**App Version**: 0.5.2  
**Maintainer**: Jean-Fabian Wenisch
