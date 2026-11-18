---
sidebar_label: Users and groups
---

# IAM users, access keys and groups

Create identities on the storage backend and decide who belongs to which group. Check the [supported backends](IAM-Management.md#supported-backends-and-capabilities) first: not every backend offers every operation.

## Users and access keys

Select **Users → New IAM user** and enter the backend username. Creating a user also creates its first access key.

The resulting credential has two distinct effects:

1. It exists on the storage backend and is governed by that backend's IAM policies.
2. S3 Web UI saves it as an encrypted S3 key for the same endpoint, initially with **no application grants**.

The secret access key is displayed only once. Copy it before closing the result dialog. To allow a non-administrator to use the new key in S3 Web UI, go to **Settings → S3 keys**, open the generated key, and add user, role, group, or everyone-signed-in grants.

Additional keys are created from a user's access-key dialog. Deleting a key revokes that backend credential; deleting a user cleans up its backend keys, group memberships, and policy attachments before removal.

On RustFS, the user name is also the protected primary access key. Additional keys in the UI are RustFS service accounts. They inherit the parent user's policies and can be deleted independently. RustFS currently requires root credentials to create a service-account key for a different IAM user.

## Groups and memberships

The **Groups** tab creates and deletes groups. Open a group to add or remove members, then attach a policy to grant permissions to everyone in it. This is backend IAM grouping and is unrelated to an OIDC `groups` claim used for granting access to S3 Web UI's stored keys.

Use backend groups when you want the storage provider to enforce shared data permissions. Use OIDC group grants when you want S3 Web UI to decide which preconfigured S3 connection a signed-in person may select. The two can be used together, but they are separate systems.
