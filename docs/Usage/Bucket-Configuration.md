# Bucket policies and CORS

Open a bucket and select **Policy** or **CORS** to configure that bucket on the storage backend. These controls require the selected S3 key to have the corresponding backend permissions.

## Bucket policy

The policy editor reads, writes, and removes the bucket's IAM-style JSON policy. S3 Web UI checks only that the document is valid JSON before it sends it; the storage backend validates policy syntax and permissions.

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

## Troubleshooting

| Symptom | Likely cause or response |
|---|---|
| Editor reports invalid JSON | Check commas, quote all property names and strings, and keep the root object. |
| Save is denied by the backend | The active S3 key lacks `PutBucketPolicy`, `PutBucketCors`, or provider-equivalent permission. Select an authorized key. |
| Browser still reports CORS errors | Verify the request reaches the S3 endpoint, the origin matches exactly, and the rule allows the HTTP method and request headers. S3 Web UI's proxied multipart upload does not need browser-to-S3 CORS. |
| A policy locks out expected users | Use a remaining administrative credential to restore access; policy changes are recorded in [History](Audit-History.md). |

See [Storage workflows](Storage-Workflows.md) for object operations and [IAM management](../Administration/IAM-Management.md) for backend identities and policies.
