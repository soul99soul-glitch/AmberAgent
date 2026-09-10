# Phase 2 / W15：会话交换契约与 Android 接线

## 结论

Android 现在有完整的 `Conversation` 数据模型和 Room 写入路径，但没有一个与 iOS
`IOSSyncBackup.conversationsZip` 对齐的轻量会话交换入口。W15 增加了一个独立的
`ConversationExchangeCodec`，格式直接对齐 iOS 的 conversations ZIP：根目录放每个
`<conversation UUID>.json`，可选根目录 `thread-edges.json`，条目使用 UTF-8 和 ZIP
stored（无压缩）。它不是 `.amberbackup`，也不读写设置、密钥、附件实体或 dataset
保护状态。

Android 的设置 → 存储页新增「会话交换」区，可通过系统文件选择器导出全部会话或导入
一个交换文件。导入先解码并展示新增/同 ID 冲突及附件引用，用户确认后才在一个 Room
transaction 中覆盖同 ID 会话，保留 Android 独占的 `council_state`；其它会话不受影响。
写入经过 `SyncRestoreWriteGate`，会停止正在生成的会话并刷新其持久化快照。附件字节和
iOS 线程关系不会被静默写入 Android。

## 对照的生产实现

本次对照的是 iOS 当前源码，而不是仅凭界面猜测：

- `ios/core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/JsonConversationStorage.kt`
  的真实布局是 `index.json` 加每个 `{id}.json` 完整文档；`importConversations` 会先
  全量解码和重新编码，再写文档并重建 index（160–178 行）。
- `ios/iosApp/iosApp/IOSSyncBackup.swift` 的 `conversationsZip` 从目录收集根目录 JSON，
  排除 `index.json`、列表派生缓存和健康工具会话（133–177 行）；`conversationDocuments`
  按文件名排序读 UTF-8 JSON（199–215 行），`conversationThreadEdges` 对可选 sidecar
  解码（217–225 行）。
- `ios/iosApp/iosApp/IOSConversationThreadEdge.swift` 的 `validated` 校验 child/parent
  UUID、child 必须在同一批文档中、child 不得自指、child 不得重复，并沿合并后的父链
  检测循环（35–89 行）。parent 可以是同一批文档外仍保留在本机的父会话。
- iOS 的 `IOSStoredZipArchive` 只写 stored ZIP。Android 输出因此明确设置条目大小、
  CRC 和 `ZipEntry.STORED`，不会让 iOS 的自定义读取器遇到 DEFLATE。

## 字段与行为边界

| 内容 | W15 行为 | 说明 |
| --- | --- | --- |
| 文本消息、角色、时间、模型、usage、引用、翻译 | 保留 | 通过两端同名 `@Serializable` JSON 字段传递 |
| `messageNodes` 顺序、分支消息、`selectIndex` | 保留 | Room 的 message_node 会按原节点写回 |
| 工具调用及递归 `output` | 保留 | codec 递归检查并收集嵌套附件引用 |
| iOS `Tool.streamIndex` | Android 已补齐可选字段并参与合并/读取 | 旧 Android JSON 缺字段时默认为 null；既有 `stream_tool_index` metadata 仍兼容 |
| video/audio MIME | URL 保留；Android 缺少该字段时使用现有默认值 | iOS 当前 `Video`/`Audio` 不序列化 `mime`，Android 导出的该可选字段会被 iOS 的 `ignoreUnknownKeys` 忽略；MIME 不是跨端精确 round-trip 字段 |
| Android `generation_interrupted` annotation | Android 导出前拒绝 | iOS 当前 annotation sealed model 没有该 subtype；拒绝比导出后让 iOS 整批解码失败更准确 |
| iOS `Conversation.memoryMode` | 缺失或 `ENABLED` 才接受；`DISABLED`/`POLLUTED` 直接拒绝导入 | Android 当前 Conversation/Room 没有对应 owner 字段；静默改成 `ENABLED` 会改变隐私/召回语义，精确迁移需单独 DB 契约 |
| image/video/audio/document 的 `file://` | 只保留 URL 引用并统计 | ZIP 不携带附件字节；另一端必须仍能访问同一引用，或后续单独做附件迁移 |
| `data:` 内嵌图片/视频/音频/文档 | 导出前拒绝 | Android 的现有 repository 已拒绝 image base64；W15 不生成会在另一端静默丢失的数据 |
| 远程 `http(s)` 媒体 URL | 原样保留，不计入本地附件引用 | JSON 文档本身可跨端读取，下载策略仍由各端负责 |
| 线程关系 sidecar | codec 可解码、规范化 child/parent UUID、检查重复和循环；Android 导入入口对非空 sidecar 直接拒绝 | Android 当前 Room 线程图与 iOS `thread_edge` 契约不同；不把 sidecar 悄悄当成已应用 |
| `index.json` / `list-previews.json` / `list-icons.json` | 忽略 | 与 iOS `conversationDocuments` 的 metadata 排除规则一致 |
| 加密、设置、密钥、附件实体、dataset | 不包含、不触碰 | 完整恢复仍由 `SyncArchiveManager` 负责，W15 不与其重叠 |

iOS 导出器对包含 `health_summary_read` 工具调用的会话做隐私过滤；Android 当前没有
等价的本地健康会话导出规则，因此 W15 不声称两端的隐私过滤已经完全对称。若后续
需要 Android 生成可供 iOS 远端备份消费的同一批次，应先补同一过滤契约。

## 代码接线

- `app/src/main/java/app/amber/core/conversation/exchange/ConversationExchange.kt`
  包含 ZIP codec、sidecar 校验、附件引用统计、repository service 和 SAF 文件处理器。
- `app/src/main/java/app/amber/core/repository/ConversationRepository.kt` 增加完整会话
  批量读取和 upsert；upsert 复用现有 `conversationToConversationEntity`、message node
  stats 和 FTS 索引，不新增数据库表。
- `app/src/main/java/app/amber/core/di/RepositoryModule.kt` 提供 service/handler。
- `app/src/main/java/app/amber/feature/ui/pages/setting/SettingStoragePage.kt` 提供
  最小本地交换入口，位于存储页而不是完整备份页，避免把轻量会话交换误导成全量恢复。
- `ai/src/main/java/app/amber/ai/ui/Message.kt` 补齐 iOS 的可选 `streamIndex`，并让现有
  stream index 查询优先使用字段、再回退到旧 metadata。

## 校验与已知缺口

`app/src/test/java/app/amber/core/conversation/exchange/ConversationExchangeCodecTest.kt`
覆盖 stored ZIP、分支与嵌套工具结果、文件引用、sidecar canonicalization、循环、路径、
内嵌数据和非 `ENABLED` memory mode 拒绝。跨端交换的 Android 实际 encode/edit/export
脚本化在
`app/src/test/java/app/amber/core/conversation/exchange/ConversationExchangeInteropTest.kt`：
它从 `AMBER_CONVERSATION_W15_ROOT/ios-production-conversations.zip` 解码，修改标题，
再用 Android 生产 codec 导出
`android-edited-conversations.zip`，同时写出其中的 JSON 供 iOS 生产 storage 重读。
主线应在统一 Gradle 窗口运行：

```text
AMBER_CONVERSATION_W15_ROOT=/private/tmp \
  ./gradlew :app:testDebugUnitTest \
  --tests app.amber.core.conversation.exchange.ConversationExchangeInteropTest
```

`app/src/test/java/app/amber/core/conversation/exchange/ConversationExchangeServiceTest.kt`
补了实际 Room 边界：预览→同 ID 覆盖会保留 `council_state` 并替换 message nodes；预览后
出现新冲突会在 gate 内的重检处拒绝写入；带线程 sidecar 在 repository 写入前拒绝。
SAF 的导出、读取预览和确认导入三个入口均在 `Dispatchers.IO` 执行，避免设置页的
`rememberCoroutineScope` 主线程承担文件读写与 ZIP 编解码。导入的冲突重检和 upsert
现在位于同一个 `SyncRestoreWriteGate.withRestore` 临界区，避免预览后的 TOCTOU 覆盖。

已执行 iOS **真实生产 `Shared.framework`** 临时 Swift runner：
`/private/tmp/amber-ios-conversation-runner.swift`（源码）和
`/private/tmp/AmberCodecRunner.app/AmberCodecRunner`（二进制）直接实例化
`JsonConversationStorage`、`ConversationFile`、`Conversation`，调用生产
`saveConversation`/`importConversations`，并在 Simulator 中重读生成的 JSON。实际执行命令为：

```text
xcrun simctl launch --console-pty \
  959825AC-6070-43FC-B2BB-3571D4A8CAAF app.amber.codec.runner
```

其编译命令（只读使用 iOS 已有 debug framework）为：

```text
CLANG_MODULE_CACHE_PATH=/private/tmp/amber-module-cache \
SWIFT_MODULECACHE_PATH=/private/tmp/amber-swift-module-cache \
xcrun --sdk iphonesimulator swiftc \
  -sdk /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator26.5.sdk \
  -target arm64-apple-ios-simulator \
  -F /Users/mi/Downloads/AI/AmberAgent/ios/shared/build/bin/iosSimulatorArm64/debugFramework \
  -framework Shared /private/tmp/amber-ios-conversation-runner.swift \
  -o /private/tmp/AmberCodecRunner.app/AmberCodecRunner \
  -Xlinker -rpath -Xlinker @executable_path/Frameworks
```

执行输出包含 `saved-by-ios-production-codec` 和 `reread-by-ios-production-codec`，两段
文档均为同一 UUID、同一消息内容，且 `memoryMode` 为 `enabled`。输出已保存为
`/private/tmp/ios-production-conversation.json`，再按 iOS stored ZIP 规则用 Python
标准库封装为 `/private/tmp/ios-production-conversations.zip`，作为上面 Android 测试的
输入。该临时 runner 真实覆盖的是 iOS 生产 `JsonConversationStorage` 的文档生成与导入；
`IOSSyncBackup.conversationsZip` 位于 iOS app target、依赖 UIKit/应用层类型，未被这个
隔离 Shared.framework runner 直接调用，因此这里不把 host 侧 ZIP 封装冒充成该 Swift
方法的执行证据。

完成 Android 测试后，可用
`/private/tmp/AmberCodecImportRunner.app/AmberCodecImportRunner` 将 Android 输出的
JSON 交给 iOS 生产 storage 重读。该 runner 的源码是
`/private/tmp/amber-ios-import-runner.swift`，编译目标为
`arm64-apple-ios16.0-simulator`，启动时把
`/private/tmp/android-edited-conversation.json` 做 base64 作为唯一参数：

```text
CLANG_MODULE_CACHE_PATH=/private/tmp/amber-module-cache \
SWIFT_MODULECACHE_PATH=/private/tmp/amber-swift-module-cache \
xcrun --sdk iphonesimulator swiftc \
  -sdk /Applications/Xcode.app/Contents/Developer/Platforms/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator26.5.sdk \
  -target arm64-apple-ios16.0-simulator \
  -F /Users/mi/Downloads/AI/AmberAgent/ios/shared/build/bin/iosSimulatorArm64/debugFramework \
  -framework Shared /private/tmp/amber-ios-import-runner.swift \
  -o /private/tmp/AmberCodecImportRunner.app/AmberCodecImportRunner \
  -Xlinker -rpath -Xlinker @executable_path/Frameworks
```

```text
IOS_JSON_B64=$(base64 < /private/tmp/android-edited-conversation.json | tr -d '\\n')
xcrun simctl launch --console-pty \
  959825AC-6070-43FC-B2BB-3571D4A8CAAF \
  app.amber.codec.import.runner "$IOS_JSON_B64"
```

实际 Android `ConversationExchangeInteropTest` 已通过（1 test / 0 failures / 0 skipped），随后用该测试写出的真实 JSON 重新执行 iOS importer。stdout 包含 `android-export-imported-by-ios-production-codec`、标题 `android codec edited` 和原消息内容，完成生产文档 codec 的双向验证。证据保存于 `parity-artifacts/phase2-conversation-ios-reimport.log`；对应双端 ZIP fixture 保存于同目录 `phase2-ios-conversations.zip`、`phase2-android-conversations.zip`。临时 Swift runner 源码已保存于 `scripts/conversation-exchange/IOSConversationExportRunner.swift` 和 `IOSConversationImportRunner.swift`，便于重建复验。ZIP 外壳仍为 host 标准库封装，不冒充 iOS app target 的 ZIP 方法实测。

### 后续需要独立决策的两项

1. 若要求 memoryMode（尤其 POLLUTED）跨端精确迁移，需要 Android ConversationEntity
   增列和 owner/CAS 规则；这会进入会话数据迁移阶段，不应在交换 codec 中偷偷塞旁路字段。
2. 若要求线程关系在 Android 导入后可导航，需要 Android 先拥有与 iOS `thread_edge`
   对齐的持久化 owner；当前入口对带 sidecar 的交换文件明确拒绝，待契约具备后再开放，
   不在其它阶段复用不兼容的线程图表。
