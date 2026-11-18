---
sidebar_label: Policies and bucket access
---

# IAM policies and bucket access

Give backend users and groups permissions, either with the visual bucket-access editor or with policy documents.

The **Policies** tab lists standalone managed policies when the backend supports them. Create or edit a customer/named policy with an IAM-style JSON document, then attach it to a user or group from that identity's **Permissions** dialog.

Policy behavior varies by provider:

- AWS provider-managed policies can be attached but not edited. AWS customer-managed policies can be edited and deleted.
- Ceph exposes its built-in managed policies for attachment but does not implement standalone policy CRUD. Use inline policies for fine-grained rules.
- RustFS uses named policies; create and attach those rather than inline policies.

Where supported, an inline policy lives directly on one user or group. It is useful for a narrowly scoped rule, such as granting access to one bucket. The JSON editor validates JSON before it sends it to the backend; it cannot guarantee that the backend will accept the policy semantics.

## Bucket access (visual editor)

A user's or group's **Permissions** dialog opens with **Bucket access**. Pick a bucket, then switch on **List**, **Read**, **Write**, **Delete** or **Full control** (the chips map to the same S3 actions as the [bucket policy editor](../Usage/Bucket-Configuration.md#visual-view)). Then select **Save access**. You can switch to the **JSON** view at any time; the same error handling applies.

S3 Web UI stores these grants in a single document per identity:

- **AWS and Ceph** (inline policies supported): an inline policy named `s3webui-bucket-access`.
- **RustFS** (named policies only): a policy named `s3webui-user-<name>` or `s3webui-group-<name>`. It is created and attached on the first save, and detached and deleted when you remove every bucket.

The document also appears under **Advanced: policies & JSON**, marked with a *Bucket access* badge. Rules the visual editor cannot express (conditions, prefixes, denies) are kept as-is. The policy and inline-policy editors in this section also offer the Visual / JSON toggle.

## Example: attach a managed or named policy to a user

1. Open **Users** and select the permissions action for the target backend user.
2. Under **Attach a policy**, select an available policy and choose **Attach**.
3. Confirm the policy appears under **Attached policies**. Use the detach action to remove it later.

On AWS, create a customer-managed policy from the **Policies** tab before attaching it. On RustFS, create a named policy and attach it. On Ceph, select a built-in managed policy when available or use the inline-policy option below.

## Example: inline policy for one user's upload prefix

Where inline policies are supported, open the user's **Permissions** dialog, select **Add inline policy**, enter a policy name such as `uploads-to-incoming`, and save a document like this:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ListIncomingOnly",
      "Effect": "Allow",
      "Action": "s3:ListBucket",
      "Resource": "arn:aws:s3:::example-bucket",
      "Condition": { "StringLike": { "s3:prefix": ["incoming/*"] } }
    },
    {
      "Sid": "WriteIncomingObjects",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::example-bucket/incoming/*"
    }
  ]
}
```

An inline identity policy deliberately has no `Principal`: the user receiving the attachment is the principal. This differs from a [bucket policy](../Usage/Bucket-Configuration.md#bucket-policy), which names the external principal in its document. Inline policies are unavailable on RustFS; create and attach a named policy there instead.
