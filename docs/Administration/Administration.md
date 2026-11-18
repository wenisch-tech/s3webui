---
slug: /Administration/overview/
sidebar_label: Settings panel
---

# Manage users, S3 keys and options in Settings

Only application administrators can open **Settings**. The panel has four tabs. In disabled-authentication mode, the Users tab is intentionally absent.

| Tab | What it is for | Guide |
|---|---|---|
| S3 keys | store storage connections and decide who may use them | [Share storage connections with S3 keys](S3-Key-Management.md) |
| Users | create, edit and disable local accounts | [Manage local user accounts](Local-Users.md) |
| Options | application-wide policies such as own credentials and key reveal | [Runtime options](Runtime-Options.md) |
| IAM | manage users, groups and policies on the storage backend | [Manage storage users, groups and policies (IAM)](IAM-Management.md) |

## Operational checklist

- Use a dedicated, least-privilege S3 key for each intended workload.
- Audit every broad `Everyone signed in` grant.
- Back up the database and the encryption key together.
- Keep the selected IAM key separate from routine data-access keys where practical.
- Review History after credential, policy, and destructive object changes.
