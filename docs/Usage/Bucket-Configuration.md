# Bucket policies and CORS

Open a bucket and select **Permissions** or **CORS** to configure that bucket on the storage backend. These controls require the selected S3 key to have the corresponding backend permissions.

## Bucket policy

**Permissions** edits the bucket's IAM-style policy. The dialog has two views, and you can switch between them at any time while editing without losing changes.

### Visual view

Each row is one principal. The picker offers:

- **IAM users** (admins only): users listed from the backend's IAM API.
- **Stored S3 keys** (admins only): S3 keys saved in S3 Web UI, shown as `key name (access key ID) → user`. A bucket policy cannot name an access key, so S3 Web UI looks up the IAM user that owns the key and grants that user. The grant therefore covers every key of that user. Keys that don't belong to a listable IAM user (for example the root key, or keys for another endpoint) are not offered.
- **Everyone**: public, anonymous access.
- **Enter a principal ARN**: any other principal, for example a user in another account.

Without IAM access (non-admins, or a backend whose IAM API isn't available) only **Everyone** and **Enter a principal ARN** are offered.

> **Groups can't be granted here.** S3 bucket policies don't accept IAM groups as a principal, and AWS rejects a group ARN in `Principal` as an invalid principal. To give a group access to a bucket, open **Settings → IAM → Groups**, choose the group's **Permissions**, and add the bucket under **Bucket access**. That grant is stored in the group's own policy and applies to every member, including users added later.

Use the chips to switch on what that principal may do:

| Chip | S3 actions | Resource |
| --- | --- | --- |
| List | `s3:ListBucket`, `s3:GetBucketLocation` | the bucket |
| Read | `s3:GetObject` | `bucket/*` |
| Write | `s3:PutObject`, `s3:AbortMultipartUpload`, `s3:ListMultipartUploadParts` | `bucket/*` |
| Delete | `s3:DeleteObject` | `bucket/*` |
| Full control | `s3:*` | the bucket and `bucket/*` |

New rows start from a preset (**Read only**, **Read & write**, or **Full control**). If Everyone can write or delete, the dialog shows a warning. If you remove every row and save, the bucket policy is deleted.

The visual view only rewrites statements it fully understands, meaning plain `Allow` statements built from the actions above on the whole bucket. It keeps every other statement exactly as it was and counts them as "advanced statements". This covers `Deny`, `Condition`, prefixes, other actions, and service principals.

### JSON view

The JSON view shows the full document. If the JSON is invalid, or a statement is missing `Effect`, `Action`, `Resource` or `Principal`, the visual view lists the errors and locks the chips, and **Save** is disabled. Select **Switch to editor** to jump to the first error. S3 Web UI does these checks before it sends the policy; the storage backend still validates policy semantics and permissions.

This example allows a single AWS principal to read objects in one bucket. Replace every placeholder and test against a non-production bucket first.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowReadOnly",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::123456789012:user/example-reader" },
      "Action": ["s3:GetObject"],
      "Resource": ["arn:aws:s3:::example-bucket/*"]
    }
  ]
}
```

### Example: application user with read/write access to one prefix

This policy lets the named backend IAM user list only the `incoming/` prefix and read, write, or delete objects within it. It is useful when an application must upload files without being able to browse or alter unrelated data.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListIncomingOnly",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::123456789012:user/import-service" },
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::example-bucket",
      "Condition": { "StringLike": { "s3:prefix": ["incoming/*"] } }
    },
    {
      "Sid": "ManageIncomingObjects",
      "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::123456789012:user/import-service" },
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::example-bucket/incoming/*"
    }
  ]
}
```

Provider support for IAM policy conditions varies across S3-compatible services. If the storage backend rejects a condition, consult its policy documentation rather than broadening access without review.

Use **Remove** to delete the bucket policy altogether. This can revoke access immediately; ensure another administrative identity retains access before saving or removing a restrictive policy.

## CORS rules

The CORS editor uses the same shape as `aws s3api get-bucket-cors` and `put-bucket-cors`: a root `CORSRules` array. CORS controls which web origins may call the **S3 endpoint** directly. It does not configure access to the S3 Web UI application.

```json
{
  "CORSRules": [
    {
      "ID": "web-client-uploads",
      "AllowedMethods": ["GET", "PUT"],
      "AllowedOrigins": ["https://app.example.com"],
      "AllowedHeaders": ["content-type", "x-amz-*"],
      "ExposeHeaders": ["ETag"],
      "MaxAgeSeconds": 3600
    }
  ]
}
```

### Example: read-only static site from one origin

This permits browser `GET` and `HEAD` requests from a single site and exposes the `ETag` response header. It does not make the bucket public; the browser request still needs whatever S3 authentication or public-read policy the bucket requires.

```json
{
  "CORSRules": [
    {
      "ID": "public-site-read",
      "AllowedMethods": ["GET", "HEAD"],
      "AllowedOrigins": ["https://www.example.com"],
      "AllowedHeaders": [],
      "ExposeHeaders": ["ETag"],
      "MaxAgeSeconds": 86400
    }
  ]
}
```

### Example: browser application with authenticated uploads

Use explicit origins and headers when a browser application uploads straight to S3 with an SDK or presigned URL. This is not necessary for S3 Web UI's own multipart upload path, which is proxied through the application.

```json
{
  "CORSRules": [
    {
      "ID": "application-uploads",
      "AllowedMethods": ["GET", "PUT", "POST", "DELETE"],
      "AllowedOrigins": ["https://app.example.com"],
      "AllowedHeaders": ["content-type", "x-amz-*"],
      "ExposeHeaders": ["ETag", "x-amz-version-id"],
      "MaxAgeSeconds": 3600
    }
  ]
}
```

At least one rule is required when saving. Use **Remove** to clear the complete CORS configuration. Avoid `"*"` in origins, methods, or headers unless the bucket's content and authentication model genuinely permit public browser access.

## Versioning and retention

The **Versioning** button (next to Permissions and CORS) enables or suspends object versioning for the bucket. Suspending keeps existing versions; it only stops creating new ones.

While versioning is enabled you can also **limit retained versions**: set how many noncurrent (older) versions to keep and after how many days older versions may be deleted. S3 has no direct "keep N versions" setting, so this is stored as a lifecycle rule with the ID `s3webui-version-retention` (`NoncurrentVersionExpiration` with `NewerNoncurrentVersions` and `NoncurrentDays`). Other lifecycle rules on the bucket are read and written back unchanged. Unchecking the limit, or suspending versioning, removes only that rule.

Some S3-compatible servers do not implement the versioning or lifecycle APIs; the dialog then reports that the provider does not support the operation.

## Troubleshooting

| Symptom | Likely cause or response |
|---|---|
| Editor reports invalid JSON | Check commas, quote all property names and strings, and keep the root object. |
| Save is denied by the backend | The active S3 key lacks `PutBucketPolicy`, `PutBucketCors`, `PutBucketVersioning`, `PutLifecycleConfiguration`, or provider-equivalent permission. Select an authorized key. |
| Browser still reports CORS errors | Verify the request reaches the S3 endpoint, the origin matches exactly, and the rule allows the HTTP method and request headers. S3 Web UI's proxied multipart upload does not need browser-to-S3 CORS. |
| A policy locks out expected users | Use a remaining administrative credential to restore access; policy changes are recorded in [History](Audit-History.md). |

See [Storage workflows](Storage-Workflows.md) for object operations and [IAM management](../Administration/IAM-Management.md) for backend identities and policies.
