---
sidebar_label: Runtime options
---

# Runtime options: own credentials, key reveal and hidden buckets

These application-wide policies are stored in the database and change at runtime, without a restart. Only administrators see them.

Open **Settings → Options**. The settings are:

- **Let users connect with their own S3 credentials**: on by default. Turn it off to require every non-administrator connection to be an administrator-created and granted key. Existing own-credential sessions lose access on their next request.
- **Allow users to reveal keys**: off by default. Turn it on only if it is acceptable for a user assigned a stored S3 or IAM key to copy that access key and secret key for use outside S3 Web UI. Individual keys can opt in from their own edit panel.
- **Hide buckets that can't be browsed**: off by default. The bucket list comes from `ListBuckets` (`s3:ListAllMyBuckets`). On AWS this returns every bucket in the account, including buckets the key may not open, so users see buckets they then get a 403 for. With this option on, S3 Web UI checks each bucket with a one-key listing (`s3:ListBucket`, the permission browsing needs) and hides the buckets the selected key is denied. It applies to the bucket page, the bucket API and global search, for every user and key, administrators included.
  - **Caching:** results are cached per endpoint, key and bucket for 5 minutes. Policy changes made in S3 Web UI (bucket policies, IAM policies, attachments, group membership) clear the cache immediately; changes made elsewhere apply after at most 5 minutes.
  - **Only denials hide a bucket:** a 403 or `NoSuchBucket` hides it. Any other error or a timeout keeps the bucket visible, so a flaky backend never makes buckets disappear.
  - **Read without List:** a bucket the key may read objects from but not list (`s3:GetObject` without `s3:ListBucket`) is hidden too, because it can't be browsed.
  - **Not a replacement for `s3:ListAllMyBuckets`:** the option only hides buckets; the key still needs that permission to list buckets at all. Backends that already filter `ListBuckets` by policy (MinIO, some RustFS versions) are unaffected.
  - **IAM picker:** the IAM **Bucket access** picker always shows every bucket, so administrators can grant access to buckets their own key can't browse.
