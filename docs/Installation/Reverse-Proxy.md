---
sidebar_label: Reverse proxy and sub-path
---

# Put S3 Web UI behind a reverse proxy

Use this page when you publish S3 Web UI through nginx, Traefik, an ingress controller or another proxy, with TLS, or under a path prefix such as `/s3webui`.

## Forwarded headers and TLS

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
