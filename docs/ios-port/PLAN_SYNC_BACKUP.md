# Sync/Backup iOS 原生实现 — 实施计划

> 目标：让 iOS 能导出/导入加密备份归档（`.amberbackup` 格式），实现与 Android 跨平台备份兼容。

## 当前状态（HEAD `3e876389`）

### Sync 模块架构

```
core/sync/api (KMP commonMain) ← SyncSettings/SyncModels/SyncCipherInfo/S3Config 已 export
app/src/main/.../sync/ (Android-only, ~16 文件):
├── core/
│   ├── SyncArchiveManager.kt    — 创建/检查/恢复加密归档（SQLite + Zip + AES-GCM）
│   ├── SyncCrypto.kt             — AES-GCM-256 加密/解密
│   ├── SyncCryptoNative.kt       — JNI native 加密桥
│   └── SyncRedactor.kt           — 敏感数据脱敏
├── google/
│   ├── GoogleDriveSyncRepository.kt  — Google Drive 上传/下载（GMS Identity Auth）
│   └── GoogleDriveAppDataClient.kt   — Drive AppData API
├── local/
│   └── LocalBackupRepository.kt      — 本地文件导出/导入（SAF Uri）
├── webdav/
│   └── WebDavClient.kt + WebDavSync.kt  — WebDAV 同步
├── s3/
│   ├── S3Client.kt + AwsSignatureV4.kt  — S3 同步
└── importer/
    └── CherryStudioProviderImporter.kt  — 第三方导入
```

### 平台依赖分析

| 组件 | Android API | iOS 等价物 | 难度 |
|---|---|---|---|
| **SyncArchiveManager** | `SQLiteDatabase` + `Cursor` + `ZipOutputStream` | iOS SQLite + `Archive` / `ZIPFoundation` + `FileManager` | 大（SQLite 直接读 Room DB） |
| **SyncCrypto** | AES-GCM-256 (JNI native) | `CryptoKit` (`AES.GCM`) | 中（CryptoKit 原生支持 AES-GCM） |
| **GoogleDriveSyncRepository** | `com.google.android.gms.auth` (GMS Identity) | `ASWebAuthenticationSession` + Google REST API | 大（需实现 OAuth + Drive REST） |
| **LocalBackupRepository** | `android.net.Uri` (SAF) | `UIDocumentPickerViewController` | 中 |
| **WebDAV/S3** | OkHttp + 自定义签名 | URLSession + 同样的签名逻辑 | 中（签名算法纯 Kotlin） |
| **BackupVM** | `androidx.lifecycle.ViewModel` (7 个依赖) | Swift `@Observable` | 中 |

### 关键发现

1. **SyncArchiveManager 直接读 Android Room SQLite DB**（`SQLiteDatabase.rawQuery`）——这在 iOS 上**没有等价物**，因为 iOS 没有 Room DB。这意味着归档的内容（Settings + secrets + Room tables + 文件树）在 iOS 上需要完全不同的数据源。

2. **加密格式跨平台兼容**：AES-GCM-256 + PBKDF2 密钥派生。iOS `CryptoKit` 支持 AES-GCM，PBKDF2 可用 `CryptoKit` 或 `CommonCrypto`。

3. **Google Drive 用 GMS Identity SDK**（`com.google.android.gms`）——iOS 需用 Google Sign-In SDK 或 `ASWebAuthenticationSession` + REST API。

### 已在 commonMain + 已 export

```
SyncSettings        // ✅ core/sync/api commonMain，已 export（11 hits）
SyncModels          // ✅ SyncExportRequest/SyncRestoreRequest/SyncPreview/SyncManifest 等
SyncCipherInfo      // ✅ AES/GCM/NoPadding 配置
SyncKdfInfo         // ✅ PBKDF2 密钥派生参数
SyncMode            // ✅ google/webdav/s3/local
S3Config            // ✅
```

### iOS 当前状态

- SyncBackupView 已读 `sharedSettings.snapshot.syncSettings`（只读展示，Slice 43）
- **无导出、无导入、无加密、无云同步**

## 实施方案

Sync 是 5 项中**依赖最复杂的**（SQLite + Zip + AES-GCM + Google OAuth + Drive API + WebDAV + S3）。建议分阶段实施。

### 阶段 1：Settings 导出/导入（JSON + AES-GCM 加密）

**目标**：让 iOS 能导出 Settings 为加密 JSON 文件，能导入恢复。

**步骤 1**：在 core/sync/api commonMain 定义备份接口

```kotlin
interface SyncBackupInterface {
    suspend fun exportSettings(passphrase: String?): ByteArray  // 加密 JSON
    suspend fun importSettings(data: ByteArray, passphrase: String?): SyncPreview
}
```

**步骤 2**：iOS 实现（iosApp Swift）

```swift
// IOSSyncBackup.swift
struct IOSSyncBackup {
    /// 导出 Settings 为加密 JSON
    /// 用 CryptoKit AES.GCM + PBKDF2 密钥派生（与 Android 兼容格式）
    static func export(settings: Settings, passphrase: String?) throws -> Data {
        // 1. JSON 序列化 Settings（用 Shared 的 JsonInstant）
        // 2. PBKDF2 密钥派生（CryptoKit HKDF 或 CommonCrypto PBKDF2）
        // 3. AES-GCM 加密（CryptoKit AES.GCM.seal）
        // 4. 返回加密数据
    }

    /// 导入加密 JSON 恢复 Settings
    static func `import`(data: Data, passphrase: String?) throws -> Settings {
        // 1. AES-GCM 解密
        // 2. JSON 反序列化
        // 3. 返回 Settings
    }
}
```

**步骤 3**：SyncBackupView 加导出/导入按钮

```swift
// SyncBackupView
Button("导出备份") {
    let data = try IOSSyncBackup.export(settings: sharedSettings.snapshot, passphrase: nil)
    // 用 UIDocumentPicker / shareLink 保存文件
}
Button("导入备份") {
    // 用 UIDocumentPicker 选文件 → IOSSyncBackup.import → 更新 Settings
}
```

### 阶段 2：iCloud Drive 同步（替代 Google Drive）

**目标**：用 iCloud Drive 替代 Google Drive（iOS 原生，无需 Google Auth）。

**步骤**：用 `URLSession` + iCloud Drive API（或 CloudKit），或直接用 `UIDocumentPickerViewController` 让用户选择 iCloud 位置。

### 阶段 3：WebDAV/S3（可选）

**目标**：WebDAV/S3 同步协议是纯 HTTP（平台无关）。

**步骤**：用 `URLSession` 实现 WebDAV/S3 客户端（签名算法纯 Kotlin，可从 Android 移植）。

## 验证

- `git diff --check`
- `xcodebuild` BUILD SUCCEEDED
- `ios_build_and_run` → SyncBackupView → 导出 → 得到加密文件 → 导入 → 恢复
- 加密格式与 Android 兼容性验证（如果需要跨平台）
- subagent review

## 风险

- **SQLite 依赖**：SyncArchiveManager 直接读 Android Room DB——iOS 没有 Room，归档内容完全不同
- **加密兼容性**：PBKDF2 参数（salt/iterations）需与 Android 一致才能跨平台解密
- **Google Drive**：GMS Identity SDK 是 Android 专属——iOS 需用 Google Sign-In SDK 或 ASWebAuthenticationSession
- **文件树归档**：Android 归档包含 app filesDir 文件树——iOS 文件结构不同
- **建议**：阶段 1 只做 Settings JSON 导出/导入（不含 Room DB / 文件树），这是最可行的跨平台备份

## 涉及文件

- 新建：`core/sync/api/src/commonMain/.../SyncBackupInterface.kt`
- 新建：`iosApp/iosApp/IOSSyncBackup.swift`（CryptoKit AES-GCM + JSON 导出/导入）
- 改：`iosApp/iosApp/SyncBackupView.swift`（导出/导入按钮）
- 不动：app/src/main/.../sync/（Android 实现保持不变）
- 阶段 2 可能新建：`iosApp/iosApp/IOSCloudSync.swift`（iCloud Drive）
