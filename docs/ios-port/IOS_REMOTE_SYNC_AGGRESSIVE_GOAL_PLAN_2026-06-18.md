# iOS Remote Sync Aggressive Goal Plan 2026-06-18

本文档用于并行 Session C：AmberAgent iOS Remote Sync 大工程。

目标是在一个独立 session 内激进推进 iOS 同步与备份：从当前“本地 Settings 加密 .amberbackup 导出/导入”，推进到远端同步 provider 抽象、local folder mock provider、WebDAV provider、快照列表、上传、下载、恢复预览、冲突检测、同步状态持久化和 UI。默认不要求真实云账号；没有凭证时也必须通过 mock 和本地 provider 证明同步闭环。

## 推荐执行版（中文，可直接复制）

```text
/goal 激进推进 AmberAgent iOS Remote Sync 大工程：在独立 session 中尽可能完成 iOS 远端同步可用闭环，而不是停在本地 .amberbackup。基于当前 IOSSyncBackup、IOSSyncBackupTests、SyncBackupView 和 KMP SyncSettings 只读状态，继续实现 Swift 原生 remote sync provider 抽象、local folder provider、WebDAV provider、快照 manifest、snapshot list/upload/download/delete、restore preview、conflict detection、lastUploadAt/lastDownloadAt/remoteRevision/deviceLabel/lastError 状态持久化、SyncBackupView 远端 UI 和测试。优先保证 WebDAV 和 local mock provider 端到端可验证；S3 和 Google Drive 可以做 provider skeleton 和诚实未配置状态，不要因为缺真实账号阻塞本地可完成闭环。
验证：开始先运行 git status --short --branch、git log --oneline --decorate -12，并读取 SyncBackupView.swift、IOSSyncBackup.swift、IOSSyncBackupTests.swift、IOSSharedSettingsStore.swift 以及 Android 侧 BackupVM、GoogleDriveSyncRepository、WebDavSync、WebDavClient、S3Client、SyncArchiveManager、SyncSettings 作为对照；实现后新增或更新 Swift XCTest 覆盖 archive roundtrip、manifest preview、local folder upload/list/download/delete、WebDAV request construction 或 URLProtocol mock、conflict detection、wrong passphrase rejection、restore preview 不落盘、restore apply 写回 sharedSettings、sync status persistence；尽可能运行 xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build 和相关 iosAppTests；涉及 KMP shared mutation 时尽可能运行最小 Gradle 测试。若缺 iOS simulator runtime、Java、Xcode 组件或 test target 配置，记录精确错误和可复现命令，并继续用 swiftc -parse、纯 Swift provider tests 或静态检查推进可验证部分。
约束：遵守 AGENTS.md；先读实际代码再改；保护当前工作区已有改动，尤其不要回滚 MiniApp、Board、Search、Skill、plan 相关修改；不处理未跟踪的 iosApp/iosApp/CouncilChatRuntimeView.swift；不触碰真实密钥、真实云账号、生产数据、发布配置、证书或 google-services.json；不把 mock provider 伪装成真实云同步；不上传用户数据到任何真实远端，除非用户明确提供测试 WebDAV 配置并要求 live 验证；恢复操作必须先 preview，再由 UI 或测试显式 apply；不得静默覆盖本机 settings。
边界：允许修改 iosApp/iosApp 内 SyncBackupView、IOSSyncBackup、IOSSyncBackupDocument、IOSSharedSettingsStore 的最小 sync status 写回、以及新增 IOSRemoteSyncProvider、IOSLocalFolderSyncProvider、IOSWebDAVSyncProvider、IOSRemoteSyncStore、IOSSyncConflictResolver 等小模块和相关 iosAppTests；允许小幅更新 docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md 和本 plan 的 Sync 状态说明。禁止修改 MiniApp 文件、Board 文件、WebMount 文件、ChatViewModel、Android 业务逻辑、Gradle 配置、Xcode project 生成物、发布脚本、证书、google-services.json 和无关重构。
迭代策略：用激进单目标方式推进，不在 provider 抽象完成后结束。先审计 iOS 本地备份和 Android 对照，再按依赖顺序连续实现：remote snapshot models、sync status store、provider protocol、local folder provider、WebDAV provider 和 mock transport、snapshot list/upload/download/delete、restore preview 和 conflict detection、apply restore、SyncBackupView 远端 UI、测试和状态文案。每完成一个泳道运行最小验证；如果 WebDAV live 或网络栈连续 2 次被环境卡住，降级到 URLProtocol mock 和 local folder provider 后继续，不要整体停止。优先端到端本地可验证，其次 WebDAV 真实协议，再次 S3/Google skeleton，最后 UI polish。
完成条件：iOS 有统一 remote sync provider 协议和 snapshot 模型；local folder provider 能端到端上传、列出、下载、删除 .amberbackup 快照；WebDAV provider 至少能构造并 mock 验证 PROPFIND、PUT、GET、DELETE，若有测试凭证则可 live 验证；SyncBackupView 能展示远端状态、快照列表、上传、下载预览、恢复确认、冲突提示和错误；restore preview 能读取 manifest 和 dataset summary 且不落盘；apply restore 能明确写回 sharedSettings 并更新 lastDownloadAt/remoteRevision/deviceLabel/lastError；上传成功能更新 lastUploadAt/remoteRevision/deviceLabel；冲突检测能识别本地 remoteRevision 与远端最新 revision 不一致；测试或构建通过，或环境阻塞被精确记录且所有可静态验证部分已完成。
暂停条件：需要真实 Google Drive OAuth、真实 S3 密钥、真实 WebDAV 账号才能判断正确性、需要上传用户真实数据、需要修改 Xcode project 结构、需要删除用户未跟踪文件、需要安装系统组件且当前环境无法继续、需要产品决定自动同步策略或冲突覆盖规则、需要同步 Room tables 或文件树并可能覆盖本机数据、需要处理隐私合规或生产数据，或同一外部环境阻塞连续出现 3 次时暂停。
```

## Goal Draft English Compatible

```text
/goal Aggressively advance AmberAgent iOS Remote Sync in an independent session: complete as much of the iOS remote sync loop as possible instead of stopping at local .amberbackup export/import. Starting from IOSSyncBackup, IOSSyncBackupTests, SyncBackupView, and read-only KMP SyncSettings state, implement Swift-native remote sync provider abstraction, local folder provider, WebDAV provider, snapshot manifest, snapshot list/upload/download/delete, restore preview, conflict detection, lastUploadAt/lastDownloadAt/remoteRevision/deviceLabel/lastError status persistence, SyncBackupView remote UI, and tests. Prioritize end-to-end verifiability through WebDAV and local mock providers; S3 and Google Drive may be provider skeletons with honest unconfigured states, and missing real accounts must not block local verifiable progress.
Verification: first run git status --short --branch and git log --oneline --decorate -12, then inspect SyncBackupView.swift, IOSSyncBackup.swift, IOSSyncBackupTests.swift, IOSSharedSettingsStore.swift, plus Android BackupVM, GoogleDriveSyncRepository, WebDavSync, WebDavClient, S3Client, SyncArchiveManager, and SyncSettings for parity guidance; add or update Swift XCTest coverage for archive roundtrip, manifest preview, local folder upload/list/download/delete, WebDAV request construction or URLProtocol mock, conflict detection, wrong passphrase rejection, restore preview without applying, restore apply writing sharedSettings, and sync status persistence; when possible run xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build and related iosAppTests; if KMP shared mutation is touched, run the smallest relevant Gradle tests. If simulator runtimes, Java, Xcode components, or test target setup block verification, record exact errors and reproducible commands, and continue with swiftc -parse, pure Swift provider tests, or static checks for verifiable parts.
Constraints: follow AGENTS.md; inspect real code before editing; protect existing worktree changes, especially MiniApp, Board, Search, Skill, and plan edits; do not touch untracked iosApp/iosApp/CouncilChatRuntimeView.swift; do not touch real secrets, real cloud accounts, production data, release config, certificates, or google-services.json; do not present mock providers as real cloud sync; do not upload user data to any real remote unless the user explicitly provides test WebDAV config and asks for live verification; restore must preview first, then apply only through explicit UI or test action; never silently overwrite local settings.
Boundaries: edits are allowed in iosApp/iosApp SyncBackupView, IOSSyncBackup, IOSSyncBackupDocument, minimal IOSSharedSettingsStore sync status write-back, new small modules such as IOSRemoteSyncProvider, IOSLocalFolderSyncProvider, IOSWebDAVSyncProvider, IOSRemoteSyncStore, IOSSyncConflictResolver, and related iosAppTests; small updates to docs/ios-port/PLAN_IOS_MARKER_CLEANUP_AND_WIRING.md and this plan are allowed. Do not modify MiniApp files, Board files, WebMount files, ChatViewModel, Android business logic, Gradle config, generated Xcode project files, release scripts, certificates, google-services.json, or unrelated refactors.
Iteration policy: work as one aggressive goal and do not stop after provider abstraction. Audit iOS local backup and Android parity first, then implement continuously in dependency order: remote snapshot models, sync status store, provider protocol, local folder provider, WebDAV provider with mock transport, snapshot list/upload/download/delete, restore preview and conflict detection, apply restore, SyncBackupView remote UI, tests and status copy. Run the smallest relevant verification after each lane. If WebDAV live or network stack repeats the same environment failure twice, downgrade to URLProtocol mock and local folder provider and continue instead of stopping everything. Prioritize end-to-end local verifiability first, real WebDAV protocol second, S3/Google skeleton third, UI polish last.
Stop when: iOS has a unified remote sync provider protocol and snapshot model; local folder provider can upload, list, download, and delete .amberbackup snapshots end to end; WebDAV provider can at least mock-verify PROPFIND, PUT, GET, and DELETE, and can live-verify when test credentials exist; SyncBackupView shows remote status, snapshot list, upload, download preview, restore confirmation, conflicts, and errors; restore preview reads manifest and dataset summary without applying; apply restore explicitly writes sharedSettings and updates lastDownloadAt/remoteRevision/deviceLabel/lastError; successful upload updates lastUploadAt/remoteRevision/deviceLabel; conflict detection flags mismatch between local remoteRevision and latest remote revision; tests or builds pass, or environment blockers are recorded exactly and all statically verifiable parts are complete.
Pause if: real Google Drive OAuth, real S3 keys, or real WebDAV account is required to determine correctness; real user data must be uploaded; Xcode project restructuring is required; user-untracked files must be deleted; system components must be installed and the environment cannot continue; product decisions are required for auto-sync policy or conflict overwrite rules; syncing Room tables or file trees may overwrite local data; privacy compliance or production data is involved; or the same external blocker repeats three times.
```

## 并行 Session 建议

推荐分支：

```text
codex/ios-sync-remote
```

推荐模型：

```text
GPT-5.5 medium 起步，遇到冲突/恢复策略或 WebDAV/S3 签名问题时升到 xhigh
```

原因：C 的大部分工作是清晰工程实现和测试；真正需要高推理的是恢复覆盖、冲突策略和远端协议边界。

## 当前 iOS 起点

当前已具备：

- `IOSSyncBackup.export` 能把 KMP Settings 编成 Android 形状的 `.amberbackup`。
- `IOSSyncBackup.import` 能解密并恢复 Settings。
- archive 内有 `manifest.json` 和 `payload.enc`。
- payload 内有 `settings.json` 和 `payload_manifest.json`。
- PBKDF2WithHmacSHA256、AES/GCM/NoPadding、payloadSha256 已实现。
- `SyncBackupView` 有本地 document exporter/importer。
- `IOSSyncBackupTests` 覆盖 roundtrip、空口令 fallback、错误口令拒绝。

当前缺口：

- remote provider abstraction 已推进到 Swift 协议与统一 snapshot model。
- 本机文件夹 provider 已实现 upload/list/download/delete；用于无账号端到端验证。
- WebDAV provider 已实现 PROPFIND/PUT/GET/DELETE 与 mockable transport；真实 live 仍需用户提供测试配置。
- Google Drive/S3 仍是诚实未配置 skeleton；没有 OAuth / S3 签名 live。
- restore preview UI 已推进：本地导入和远端下载都先 preview，再显式 apply。
- conflict detection 已实现最小策略：本机 remoteRevision 与远端最新 revision 不一致时暂停上传并提示。
- lastUploadAt/lastDownloadAt/remoteRevision/deviceLabel/lastError 已在 iOS `IOSRemoteSyncStatus` 镜像持久化；未伪装成 KMP shared mutation。
- 没有 Room tables、file roots、secrets 的 iOS 归档范围。
- 没有 auto sync scheduler。

## 一口气完成清单

### Snapshot Model

新增 Swift Codable 模型：

- snapshot id
- fileName
- provider
- createdAt
- modifiedAt
- sizeBytes
- remoteRevision
- deviceId
- deviceLabel
- appVersionName
- mode
- encrypted
- passphraseProtected
- payloadSha256

revision 建议：

- 上传时用 manifest `createdAt`、payloadSha256、deviceId 拼出稳定 revision。
- 如果远端支持 ETag，则存 ETag；local folder provider 可用 SHA-256。

### Provider Protocol

统一协议建议包含：

- testConnection
- listSnapshots
- uploadSnapshot
- downloadSnapshot
- deleteSnapshot
- providerStatus

必须有 progress/error model，但第一版可简单。

### Local Folder Provider

用途：

- 无账号端到端验证。
- 用临时目录或 Documents/sync-remote-local。
- 支持 upload/list/download/delete。
- 用文件修改时间和 sha256 做 remoteRevision。

这是 C 的最稳验收锚点。

### WebDAV Provider

目标：

- 支持 baseURL、path、username、password。
- 支持 PROPFIND list。
- 支持 MKCOL 或 ensure collection。
- 支持 PUT upload。
- 支持 GET download。
- 支持 DELETE。
- 用 URLSession 或 mockable HTTP transport。
- tests 用 URLProtocol mock 或 fake transport，不要求真实账号。

注意：

- 密码不要写入仓库。
- 如果要持久化测试配置，只允许本地 UserDefaults 或 Keychain 占位，不提交真实凭证。

### S3 和 Google Drive

本轮激进但不强求真实账号。

可做：

- provider skeleton。
- settings model。
- UI 显示未配置。
- 错误状态清晰。

不强做：

- Google OAuth。
- S3 Signature V4 完整 live 验证。
- 真实上传。

### Restore Preview 和 Apply

必须先 preview：

- 解包外层 manifest。
- 显示 app version、device、createdAt、mode、dataset summary、size。
- 不写 sharedSettings。

Apply：

- 只有用户点击恢复或测试显式调用时才 `sharedSettings.restoreSnapshot`。
- 更新 sync status。
- 错误口令、sha mismatch、unsupported version 必须可见。

### Conflict Detection

最小策略：

- 如果本地 lastRemoteRevision 非空，且远端最新 revision 不等于本地记录，上传前提示 conflict。
- 提供 skip、download preview、force upload 三种状态模型。
- 本轮 UI 可先不给 force upload 按钮，但内部模型要有。

### Sync Status Store

写回字段：

- lastUploadAt
- lastDownloadAt
- lastBackupVersionName
- lastBackupDeviceLabel
- lastRemoteRevision
- lastError

如果 KMP `SyncSettings` copy 不方便从 Swift 写，允许做 Swift 侧 `IOSRemoteSyncStore` mirror，但文案必须诚实说明。优先使用 KMP mutation helper。

## 明确不做

本 session 不做：

- MiniApp。
- Board。
- WebMount。
- ChatViewModel。
- 真实 Google Drive OAuth。
- 真实 S3 live upload，除非用户提供测试凭证。
- 自动后台同步。
- Room tables 恢复覆盖。
- 文件树恢复覆盖。
- secrets 同步。
- 生产数据上传。

## 验证命令

起点：

```bash
git status --short --branch
git log --oneline --decorate -12
```

定位：

```bash
rg -n "IOSSyncBackup|SyncBackupView|SyncSettings|WebDav|S3|GoogleDrive|remoteRevision|lastUpload|lastDownload|RestoreScope|BackupVM" iosApp app shared
```

优先构建：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" build
```

优先测试：

```bash
xcodebuild -project iosApp/AmberAgent.xcodeproj -scheme iosApp -destination "generic/platform=iOS Simulator" test
```

回退检查：

```bash
xcrun swiftc -parse iosApp/iosApp/IOSRemoteSyncProvider.swift
xcrun swiftc -parse iosApp/iosApp/IOSLocalFolderSyncProvider.swift
xcrun swiftc -parse iosApp/iosApp/IOSWebDAVSyncProvider.swift
```

如涉及 shared mutations：

```bash
./gradlew test --tests "*Sync*"
```

## 完成报告要求

最终报告必须列出：

- 哪些 provider 已真实可用。
- 哪些 provider 是 skeleton 或 mock。
- 是否能 local folder 端到端 upload/list/download/delete。
- WebDAV 是否 mock 验证，是否 live 验证。
- restore preview 和 apply 的行为。
- conflict detection 策略。
- sync status 写回位置。
- 跑过哪些测试或构建。
- 哪些验证因环境阻塞无法运行。
- 剩余缺口是否属于真实账号、生产数据、覆盖策略或后续自动同步。
