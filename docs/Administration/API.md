# Internal API surface

S3 Web UI's browser interface uses JSON endpoints under `/api`. They require the same authenticated browser session and CSRF protections as the UI. They are an implementation surface for the bundled UI, not a versioned public integration API; endpoint shapes may change between releases.

Use an S3 SDK or the storage provider's API for programmatic object-storage integrations. Use this page to understand browser/network behavior or to troubleshoot the bundled UI.

## Endpoint groups

| Prefix | Purpose |
|---|---|
| `/api/s3/session` | List/select the session's S3 key, submit own credentials, and conditionally reveal an assigned key. |
| `/api/buckets` | Create/list/delete buckets; list, search, upload, download, rename, copy, move, delete objects; bucket statistics, policies, CORS, and multipart upload lifecycle. |
| `/api/search` | Global search over the selected key's accessible buckets. |
| `/api/history` | Audit entries visible to the signed-in identity. |
| `/api/admin` | Administrator-only stored credentials, local users, and runtime options. |
| `/api/admin/iam` | Administrator-only storage-backend IAM capability, identity, key, group, membership, and policy operations. |

## Browser requirements

State-changing requests carry the CSRF token provided to the browser session. A direct client must also authenticate, retain the session cookie, and send the required CSRF token. Responses use normal HTTP status codes; API errors are returned as JSON by the application's exception handling.

The active S3 key is stored in the server-side HTTP session. Selecting a different key invalidates the global-search catalogue for that session. Configuration and IAM access are authorized again on the server; a client cannot gain access merely by sending another key identifier.

## Multipart uploads

For files over 5 MB, the UI initiates an upload, sends 5 MB parts to the application, completes the multipart upload, and can abort it if required. Parts are intentionally proxied through S3 Web UI; they are not browser-to-S3 presigned uploads. See [Storage workflows](../Usage/Storage-Workflows.md#upload-files).

## Operational guidance

- Do not expose `/api` as an unauthenticated public service.
- Do not build a durable external integration against undocumented endpoint payloads.
- Prefer the S3 API for object reads/writes and the identity provider/storage backend APIs for external administration.
- Use the UI's audit history and application logs to diagnose failed requests.
