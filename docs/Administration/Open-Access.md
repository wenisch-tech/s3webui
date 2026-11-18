---
sidebar_label: No sign-in
---

# Run without sign-in (disabled authentication)

Use this only on a network you already trust. In this mode S3 Web UI has no users at all.

Set `DISABLE_AUTHENTICATION=true` to bypass local and OIDC sign-in completely. S3 Web UI assigns
every request the virtual administrator identity `authentication-disabled@s3webui.local`. This keeps
the existing administrator-only operations available: S3-key management, audit history and IAM all
continue to work without changes to their permissions.

In this mode, no local administrator is seeded, the **Settings → Users** tab and its create/edit
controls are hidden, and the `/api/admin/users` endpoints return `403 Forbidden`. OIDC is not
configured even if `OIDC_ENABLED=true`, and the login route redirects to the bucket browser. The
application still uses browser sessions to remember the selected S3 key.

There is no application-level identity or access control in this mode. Anyone able to reach the
application can perform every administrator action and use every stored S3 key. Restrict access with
an ingress, reverse proxy, VPN, firewall or equivalent network control before enabling it.

### Configuration examples

For a local process or container:

```bash
export DISABLE_AUTHENTICATION=true
```

For Helm:

```yaml
env:
  DISABLE_AUTHENTICATION: "true"
```

The setting is read at startup, so restart the application after changing it. Its default is `false`.
