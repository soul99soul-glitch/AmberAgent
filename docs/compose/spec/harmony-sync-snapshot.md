---
feature: harmony-sync-snapshot
status: delivered
updated: 2026-09-11
branch: harmony-preview
commits: (working tree; not committed)
---

# 同步加密快照（域 + entry + WebDAV）

## Report

**What was built** — 域：`sync_snapshot` 加密容器 + `sync_webdav` PUT/GET/list/Basic Auth。entry：`SyncCryptoPort`、`SettingBackupPage` 本地导出/恢复、**WebDAV 上传**、**恢复时可选覆盖 settings**（助手+提供商）。

**Verification** — chat **2084/2084**（+5 webdav）；deepread **775/775**；lint SettingBackupPage **0 defects**。

**Journey log** —
1. WebDAV 经现有 HttpClient Port，不新开网络栈。
2. settings 覆盖默认关闭，防误抹 API Key。
3. KDF 与 Android 不互通——本端闭环。

## Tasks
- [x] T1 域 snapshot
- [x] T2 entry crypto + 本地备份 UI
- [x] T3 WebDAV 上传 + settings 恢复
