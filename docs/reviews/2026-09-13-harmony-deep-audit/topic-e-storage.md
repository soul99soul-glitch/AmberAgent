# 专题 E — 存储 / 同步 / 附件持久化 / 数据安全

- 审查对象：当前工作树，分支 harmony-preview，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。
- 范围：ChatRdbRepository / RdbRepository / AtomicJsonFile / ChatKvStore / PreferencesStorage；Memory / ManagedFile / Favorite / Workspace / Skill / Compact / Mcp / Prompt / SubAgent Store 读写协议；sync_snapshot / sync_webdav / SyncCryptoPort / SettingBackupPage。
- 说明：Novel 专项另有 owner；本报告只核共用 FileStore 契约（`FileStore.ets`、novel/repository.ts 对它的使用面）。
- 环境：`hdc list targets` 为空，无设备/模拟器；`SyncCryptoPort.ets` 存在已由主代理统一记录的编译阻断（createSymKeySync/initSync/CipherSpec/getRandomSync），本报告不重复计分，按其指示仅审运行逻辑。
- 验证分级：仅定位 / 已阅读 / 跨模块追踪 / 隔离验证（probe 见 `probes-e/sync_snapshot_probe.mts`，仅用合成 mock crypto，无网络、无真实凭据、无真实用户数据）。

## 覆盖清单（文件级）

| 文件 | 状态 | 说明 |
|---|---|---|
| `entry/.../platform_impl/ChatRdbRepository.ets` | 已完整阅读 | schema/迁移/事务/写队列/tombstone/降级 |
| `entry/.../platform_impl/RdbRepository.ets` | 已完整阅读 | deepread cache 单写队列/观察者 |
| `entry/.../platform_impl/AtomicJsonFile.ets` | 已完整阅读 | tmp+rename、quarantine、KeyedAsyncMutex |
| `entry/.../platform_impl/ChatKvStore.ets` / `PreferencesStorage.ets` | 已完整阅读 | KV 适配、flush、getAllStringEntries |
| `entry/.../platform_impl/MemoryStore.ets` | 已完整阅读 | memory/candidates/events/dream 读写 |
| `entry/.../platform_impl/ManagedFileStore.ets` | 已完整阅读 | managed_files JSON、DAO、删除清理编排 |
| `entry/.../platform_impl/FavoriteStore.ets` | 已完整阅读 | KV JSON 数组 + 写队列 |
| `entry/.../platform_impl/EntryCompactStore.ets` | 已完整阅读 | compacts/events KV + 会话级写队列 |
| `entry/.../platform_impl/WorkspaceStore.ets` / `SkillStore.ets` | 已完整阅读 | fileIo 同步端口、路径规范化、rawfile 内置资产 |
| `entry/.../platform_impl/McpSettingsStore.ets` / `McpFilesStore.ets` | 已完整阅读 | 内存权威 + 写穿；upload 落盘 + track |
| `entry/.../platform_impl/PromptConfigStore.ets` / `AgentRuntimePrefs.ets` | 已完整阅读 | agent_runtime RMW + sharedKvMutex |
| `entry/.../platform_impl/SubAgentStore.ets` / `NotificationNotifier` 相关 | 已完整阅读 | transcript append/tail、路径包含判定 |
| `entry/.../platform_impl/FileStore.ets` | 已完整阅读 | 相对路径校验、TRUNC 直写、withExclusiveLock |
| `entry/.../platform_impl/SyncCryptoPort.ets` | 已完整阅读（编译阻断另记） | AES-GCM/SHA256-ITERATED/CSPRNG |
| `entry/.../pages/SettingBackupPage.ets` | 已完整阅读 | 导出/上传/预览/恢复全流程 |
| `entry/.../pages/FavoritePage.ets` / `HistoryPage.ets` / `ChatPage.ets`（删除/保存相关段） / `ChatListPage.ets` | 已阅读相关段 | 真实用户入口 |
| `chat/.../sync_snapshot.ts` / `sync_webdav.ts` | 已完整阅读 + probe | 契约、校验、WebDAV 客户端 |
| `chat/.../conversation_export.ts` / `conversation.ts` / `persistence.ts` / `serialize.ts` / `assistant_serialize.ts` / `provider_settings_serialize.ts` / `kv_store.ts` / `managed_files.ts` / `pending_queue_store.ts` / `memory_import_export.ts` | 已完整阅读 | 序列化面 / 恢复语义 / 附件模型 |
| Android 基准：`SyncArchiveManager.kt`、`SyncRedactor.kt`、`ConversationRepository.kt`、`ManagedFileDAO.kt`、`FavoriteDAO.kt`、`ConversationContextRepository.kt` | 已阅读相关段 | 用于判定"独立缺陷 vs 双端共有" |

## 候选缺陷

---

### E-01 备份/恢复丢失消息分支与全部会话元数据（P1，置信度：高）

- 唯一 ID：E-01
- 严重度/置信度：P1 / 高（静态证明 + probe）
- 用户影响与前提：任何"导出 → 恢复"（换机迁移、误删找回）后：
  1. 每个 `MessageNode` 的**非选中分支全部丢失**（regenerate 产生的多个候选回答只剩当前选中那个）；
  2. `chatSuggestions`、`isPinned`、`autoApproveToolCalls` 全部被重置为 `[] / false / false`。
  用户会看到"已恢复 N 个会话"的成功提示，但内容已不可逆缩水。
- 精确位置（1-based，当前工作树）：
  - 导出扁平化：`harmony/chat/src/main/ets/chat/conversation_export.ts:131-150`（`:138` 只取 `node.messages[selectIndex]`，`:139` `messages.push`，返回结构 `ConversationExportJson` 在 `:122-129` 不含 `selectIndex`/`chatSuggestions`/`isPinned`/`autoApproveToolCalls`）。
  - 负载 schema 只存打平数组：`harmony/chat/src/main/ets/chat/sync_snapshot.ts:67-73`（`SyncPayload.conversations: ReturnType<typeof exportConversationJson>[]`）。
  - 恢复侧重建：`harmony/entry/src/main/ets/pages/SettingBackupPage.ets:219-234`，尤其 `:221` `item.messages.map((m) => toMessageNode(m))`（`toMessageNode` 见 `conversation.ts:31`，固定 `selectIndex=0`）、`:228 chatSuggestions: []`、`:229 isPinned: false`、`:230 autoApproveToolCalls: false`。
- 调用链：设置页 → `pages/SettingBackupPage` → `doExport`（`:67-119`）或 `doUploadWebDav`（`:121-171`）→ `createSyncBackup`（`sync_snapshot.ts:236`）→ `buildSyncPayload`（`:181-190`）→ `exportConversationJson`（`conversation_export.ts:131`）。恢复：`doPickRestore`（`:173-197`）→ `doRestore`（`:199-259`）→ `restoreSyncBackup`（`sync_snapshot.ts:277-302`）→ `repo.save`。
- 根因：同步负载复用了"给人看的导出 JSON"（`exportConversationJson` 本就是 Markdown/JSON 导出用，语义是"每节点一条消息"），而非模型层的可逆快照。恢复代码是它的手写逆变换，两处都丢字段。
- 现有保护为何不成立：`restoreSyncBackup` 的 sha256 只校验密文完整性，不校验语义字段；UI 无"分支数/元数据"预览；错误提示只报会话条数。
- 复现/静态证明：`ConversationExportJson` 定义（`conversation_export.ts:122-129`）无分支字段；`toMessageNode` 恒 `selectIndex=0`。导出→恢复对 branch/元数据是信息销毁，属确定性静态证明，无需运行时。
- 实际验证：已读全部实现；probe 组件 [3] 确认 payload 只带打平 `messages` 数组（自造 payload 恢复后每会话只剩 `messages`，无 branch 概念）。未在设备端跑（无设备）。
- 最小修复：为同步单独定义可逆 DTO（`SyncConversation`：`id/assistantId/title/createAt/updateAt/chatSuggestions/isPinned/autoApproveToolCalls/+ nodes:[{id,selectIndex,messages:[UIMessage]}]`），`buildSyncPayload` 用它，`doRestore` 按 DTO 还原 `MessageNode`。若需兼容已产出的旧 v1 归档，用 `archiveVersion` 升级 + 旧格式按现逻辑降级并在预览里提示"旧格式不包含分支"。
- 回归测试：`sync_snapshot.test.ts` 增加 round-trip：构造含 2 个分支 + `isPinned=true` + `chatSuggestions` 的会话，断言恢复后 `node.messages.length===2`、`selectIndex` 一致、三个元数据字段一致。
- 历史关系：round3 未涉及；非"Android 同款"——Android 同步按 `SYNC_TABLES` 行级复制（`app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:428-470` restoreTables），保留 `select_index` 等列。属 Harmony 独立缺陷。

---

### E-02 恢复负载无 schema 校验：认证通过的格式异常快照可造成"部分恢复 + 无回滚"（P1，置信度：高）

- 唯一 ID：E-02
- 严重度/置信度：P1 / 高（probe 实证 + 静态）
- 用户影响与前提：限定为**合法加密、口令正确、GCM 认证通过**的 `.abin`，即密文未被篡改（普通密文截断/改写会在 AES-GCM 认证阶段就失败，不属本项），但解密后的 payload JSON 结构异常——例如异版本/其他分支产出、工具用正确密钥重新加密封装、或生成侧 bug 导致的畸形 payload。此时：
  - 若 `payload.conversations` 非数组（缺失/对象），`for...of` 抛 `TypeError`，UI 报"恢复失败"（无数据写入，可接受）；
  - 若数组里**靠前元素已成功 `repo.save`、靠后元素畸形**（如缺 `messages`），异常在循环中途抛出，UI 报"恢复失败"，但**前面已写入的会话不会回滚**——用户以为失败，实际是半恢复的混合态。
- 精确位置：
  - 无校验：`harmony/chat/src/main/ets/chat/sync_snapshot.ts:277-302`（仅校验 `archiveVersion`、sha256、密码；`:300` `JSON.parse(...) as SyncPayload` 是纯断言，无结构检查）。
  - 中途异常无回滚：`harmony/entry/src/main/ets/pages/SettingBackupPage.ets:219-234` 单循环逐条 `await repo.save(conv)`，无事务/暂存/回滚；`:254-258` catch 只改 `statusMsg`。
- 调用链：`doRestore` → `restoreSyncBackup`（返回未校验 payload）→ `for (const item of result.payload.conversations)`。
- 根因：`SyncPayload` 是编译期类型，运行时来自不可信字节流（用户可选任意文件/任何来源的 .abin），没有 validator。
- 现有保护为何不成立：sha256 只证明"密文与 manifest 自洽"，在正确口令下 GCM 认证只证明"payload 是密钥持有者写入的原始明文的诚实副本"，不证明 payload 的 JSON 结构与语义；UI 预览只读 manifest。本项与"密文被篡改"是两个不同失效面。
- 复现/静态证明（隔离验证，已实跑）：`probes-e/sync_snapshot_probe.mts` 用合成 `SyncCryptoPort`（等价"合法加密 + 正确口令"，认证通过）自造归档喂入**真实** `restoreSyncBackup`：
  - `[1] missing conversations: restore RESOLVED (no validation)`
  - `[2] conversations=object: restore RESOLVED, Array.isArray=false`
  - `[3] duplicate+missing: restore RESOLVED; entries with messages===undefined=1`
  说明 domain 层完全不校验结构；随后 `doRestore` 的 `item.messages.map` 会在运行时抛错。真实 AES-GCM 只是把"截断/篡改密文"挡在认证外，挡不住"认证通过但结构畸形"。
- 最小修复：在 `restoreSyncBackup` 内加结构校验（`Array.isArray(payload.conversations)` + 每元素 `id/title/assistantId/createAt/updateAt` 必需、`messages` 必须为数组），或提供 `validateSyncPayload` 并在 `doRestore` 先整体校验再落库（全有或全无）。
- 回归测试：给 `restoreSyncBackup` 增加畸形 payload 用例，断言抛"同步备份结构非法"而非静默返回；`doRestore` 层用例断言畸形快照不产生任何 `repo.save`。
- 历史关系：新增；与 round3 "错误呈成功"主题同族但未覆盖同步面。

---

### E-03 恢复命中本地已删除会话 ID → `save` 抛 "Conversation was deleted"，恢复中途失败（P2，置信度：高）

- 唯一 ID：E-03
- 严重度/置信度：P2 / 高（逻辑静态证明）
- 用户影响与前提：同一进程内先删除某会话（`deletedIds` 打上墓碑，此后不再清除），再恢复一份包含该会话 ID 的备份（很自然的"误删后从备份找回"）。`repo.save` 抛错 → `doRestore` 整个循环中断 → 该 ID 及其后所有会话都不恢复，且提示"恢复失败"，而**用户要找回的正是这条**。
- 精确位置：
  - 墓碑设置：`harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets:481-512`（`:482` `this.deletedIds.add(id)`；仅在 delete 自身失败时 `:509` 清除）。
  - 写入拒绝：同文件 `:283-300`（`:285` 与 `:289` 两处 `if (this.deletedIds.has(conv.id)) throw new Error('Conversation was deleted: ...')`）。
  - 触发点：`SettingBackupPage.ets:233 await repo.save(conv)`。
- 调用链：`deleteDrawerConversation`/`commitPendingDelete` → `ChatRdbRepository.delete` → `deletedIds.add` ；之后 `doRestore` → `repo.save` → 命中墓碑抛错。
- 根因：墓碑的语义是"防止删除前的在途写迟到落库"，但被当成了"该 ID 永久不可再写"。恢复（重建）与迟到写未区分。
- 现有保护为何不成立：`deletedIds` 无 TTL/无容量上限/永不清理；`save` 是唯一入口且无"recreate/restore"旁路；`doRestore` 无逐条失败隔离与整体回滚。
- 复现/静态证明：路径确定，无分支依赖；`delete` 成功后墓碑必然残留（`catch` 只在失败时删除）。可用内存 repo + mock store 的合成测试固化（无需设备）。
- 最小修复：给 `save` 增加显式 `options?: { allowRecreate?: boolean }`，恢复路径传 `allowRecreate:true` 或先 `repository.clearTombstone(id)`；或让 `delete` 完成后延迟/限量清理墓碑（例如仅在存在在途 `mutationTails` 时保留）。
- 回归测试：删除 id → 清零在途 → `save` 同 id 应成功（restore 语义）；同时保留"删除后旧在途写被拒"的既有用例。
- 历史关系：`deletedIds` 本身是 round3 后新增的保护（针对"迟写复活"），本项是其过度副作用，属新增候选。

---

### E-04 RDB 初始化失败降级到内存，`chatStorageDegraded` 只写不读 → 重启静默丢失全部会话（P1，置信度：高）

- 唯一 ID：E-04
- 严重度/置信度：P1 / 高（静态证明：全仓无消费点）
- 用户影响与前提（口径已按勘误收敛）：`getRdbStore`/schema 迁移失败（磁盘错误、旧库缺基础列等）时，`ChatRdbRepository` 回退到进程内 `memory` 仓库。用户可继续正常聊天、列表、删除，**无任何 UI 提示**。丢失范围以"持久化恢复之前进程退出"为前提：丢的是**降级期间新增/编辑的会话**；RDB 中原有旧数据不会被删除，一旦后续初始化成功会被 `initializeStore`（`:169-176`）迁回 RDB。注意 `ensureStore` 每次初始化完成后会清空 `initPromise`（`:193-195`），因此"RDB 恢复 + 迁回"每条会话至多发生一次（见勘误）。
- 精确位置：
  - 写标志：`harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets:178`（`'chatStorageDegraded' false`）、`:182`（`true`）；catch 在 `:180-185`。
  - 消费点：`grep -rn "chatStorageDegraded" harmony/entry/src/main/ets --include=*.ets` → **仅上述两处**，无任何读取。
- 调用链：`getChatRepository()`（`di/AppContainer.ets:836-843`）→ `createChatRdbRepository` → `ensureStore`/`initializeStore` → 失败置位。所有会话写入 `save`（`:283-300`）走 `store===null → this.memory.save`。
- 根因：降级状态只写进 AppStorage，未接到任何页面/横幅；且迁移仅对"降级期间新增/编辑"生效，"RDB 恢复前退出"即为净损失。`ProfilePage`/`ChatListPage` 均无读取。
- 现有保护为何不成立：`AppStorage.setOrCreate` 不是持久化告警；hilog 不面向用户；`save` 成功返回（内存），上层视为成功。
- 复现/静态证明：全仓 grep 证明零消费；`save` 的 `store === null` 分支写内存且正常 resolve。降级期间新增记录在先、`initializeStore` 迁回在后（`store = store` 发布晚于回灌循环，见 `:171-177`），无覆盖竞态。
- 最小修复：`export const isChatStorageDegraded = (): boolean => AppStorage.get<boolean>('chatStorageDegraded') === true;`，在 `ChatListPage`/`ChatPage` 顶部显示持久警示条（如"本地数据库不可用，新会话重启后会丢失"），并禁止"删除全部/清空"类破坏操作。参考 `BackupReminderCard.ets` 现有组件。
- 回归测试：注入一个 `getRdbStore` 失败的 mock，断言 UI 层收到 degraded=true（页面/适配器级）。
- 历史关系：round3 P0-1 关注 JSON 存储族；本项是 RDB 面同类"静默降级"，未覆盖。

---

### E-05 IO 异常仍被当"空库"：MemoryStore / ManagedFileStore 只隔离解析失败，不隔离读失败（P2，置信度：高）

- 唯一 ID：E-05
- 严重度/置信度：P2 / 高（静态证明）
- 用户影响与前提：`fileIo.access`/`readText` 抛瞬时 IO 错（文件被占用、句柄耗尽等）时，读取返回空数组/空表，且**不触发 quarantine、不进入错误态**；随后任意一次写操作（如召回路径 `touchMemories`、`trackUploadFile`）都会以空快照整体覆盖原文件 → 记忆/候选/事件/managed_files 全部永久丢失。这是 round3 P0-1 的残留分支：解析失败已隔离，读失败未隔离。
- 精确位置：
  - `harmony/entry/src/main/ets/platform_impl/MemoryStore.ets:39-63`：`:45-47` `catch { return []; }`（不隔离），只有 `:54-61` 的 `JSON.parse` 失败才走 `quarantineIfStillInvalid`。
  - `harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets:44-71`：`:51-53` `catch { return emptyShape(); }`（不隔离），`:62-69` 才隔离解析失败。
  - 覆盖写的写侧：`MemoryStore.ets:70-71` `writeAll`；`ManagedFileStore.ets:73-74` `writeShape`（原子写，但基准已是空）。
- 调用链：`touchMemories`/`addMemory`（`MemoryStore.ets:89-95`/`:101-107`）→ `readAll()` → 空 → `writeAll` 覆盖。
- 根因：把"不存在"（合法空库）与"读取失败"（可疑）混同；`quarantineIfStillInvalid` 只在 parse 异常后被调用。
- 现有保护为何不成立：原子写（tmp+rename）只防撕裂，不防空基准覆盖；`.bad` 隔离只在 parse 失败分支。
- 复现/静态证明（隔离验证，已实跑）：读侧 catch 直接返回空且不调用 quarantine，代码路径确定。`probes-e/probe-e-io.cjs`（转译**真实** `MemoryStore.ets` + `ManagedFileStore.ets` + `AtomicJsonFile.ets`，注入内存 mock fileIo；随后走**真实**写入路径）实测：
  - `[E-05] readText threw during addMemory -> addMemory resolved: true`；写盘后 `memory_records.json` 只剩 `["gamma"]`。
  - `[E-05b] readText threw during insert -> insert resolved: true`；写盘后 `managed_files.json` 只剩 `["upload/c.txt"]`（原 a/b 追踪行丢失）。
  即：一次瞬时 `readText` 失败 → 下一次写以空快照覆盖，旧记录永久丢失且**不抛错**。未读任何真实用户文件。
- 最小修复：读失败与"文件不存在"区分开——`access` 失败按"不存在"返回空；`readText` 抛错时**不返回空**，向上抛或返回错误态，让写路径拒绝 overwrite（与 round3 P0-1 "解析失败进入错误态、拒绝后续写" 一致口径）。至少在 read catch 中同样调用 `quarantineIfStillInvalid` 或设置内存错误标志，写侧读到该标志时抛错。
- 回归测试：mock fs 让 `readText` 抛错，随后调用 `addMemory`，断言不覆盖且抛错/上报。
- 历史关系：round3 P0-1 已修"解析失败 + 原子写"，本项是同一 P0 的未闭合分支（读 IO 错）。

---

### E-06 ChatPage 抽屉删除会话不执行 `cleanupDeletedConversation` → 收藏/待发队列/upload 附件泄漏（P2，置信度：高）

- 唯一 ID：E-06
- 严重度/置信度：P2 / 高（跨页对比 + grep 证明）
- 用户影响与前提：从 **ChatPage 抽屉**删除会话（`ChatPage.ets:2727` `deleteDrawerConversation`）：只调 `repo.delete`，不清理。后果：
  - 该会话的收藏仍留在 `FavoriteStore`（`FavoritePage` 仍显示已删会话的收藏，`conversationId` 悬空）；
  - `pending-queue/<id>` 及其 `.events` 审计键残留；
  - 引用到的 `upload/` 附件若不再被其他会话引用则成为孤儿文件（`managed_files` 行 + 磁盘文件都留）。
  对比：ChatListPage（`:248-254`、`:267-268`）与 ChatAssistants/AssistantList 删除助手时会话级联都调用了 `cleanupDeletedConversation`。
- 精确位置：`harmony/entry/src/main/ets/pages/ChatPage.ets:2727-2752`（`:2741` `getChatRepository().delete(item.id).then(...)`，无 cleanup）；`grep -n cleanupDeletedConversation ChatPage.ets` 无结果。
- 调用链：`deleteDrawerConversation` → `repo.delete` → （缺失）`cleanupDeletedConversation`（定义 `platform_impl/ManagedFileStore.ets:208-227`）。
- 根因：删除编排分散在多个页面，ChatPage 路径漏接。
- 现有保护为何不成立：`ChatRdbRepository.delete` 自身只删 RDB 行，不做文件/收藏级联（设计如此，级联在 entry 层编排）；无统一删除服务。
- 复现/静态证明：grep 证明 ChatPage 无 cleanup import/调用；其余三个入口均有。
- 最小修复：把"删除会话"收口到单一入口（如 `deleteConversationWithCleanup(repo, kv, id)`），ChatPage 抽屉改调它；或在 `repo.delete` 后统一由上层钩子触发。
- 回归测试：删除含收藏 + upload 附件的会话后断言 `listFavorites()` 不含该会话、KV 待发键被删、文件被删。
- 历史关系：round3 未列；属功能一致性回归。

---

### E-07 收藏存储损坏 → 静默清空，并在下次写入时整体覆盖（P2，置信度：高）

- 唯一 ID：E-07
- 严重度/置信度：P2 / 高（静态证明）
- 用户影响与前提：`favorites_json` 键内容**解析级损坏**（外部改写、旧版本 schema、非法 JSON）时，`decode` 的 `JSON.parse` catch 吞异常返回 `[]`；下一次 `addFavorite` 以 `[]+新条目` 覆盖 → 现有全部收藏静默消失。无错误提示、无隔离。（注意：若 `kv.get()` 本身抛错——KV 层 IO 失败——则 **不会**被吞，异常会向上抛到 UI；本项限定为"get 成功但值不可解析"。）
- 精确位置：`harmony/entry/src/main/ets/platform_impl/FavoriteStore.ets:27-45`（`:42-44` `catch { return []; }`）；覆盖点 `:66-72` `addFavorite`。
- 调用链：`ChatPage.favoriteText`（`ChatPage.ets:2572-2583`）→ `addFavorite` → `decode` 空 → `put` 覆盖。
- 根因：与 E-05 同族——损坏/异常被折叠为"空"，无隔离或错误态。收藏是无原子文件保护的 KV blob。
- 现有保护为何不成立：`favoriteWriteTail` 只解决并发 RMW 互斥（round3 #56 已修），不解决损坏基准覆盖。
- 复现/静态证明（隔离验证，已实跑）：catch → `[]` 直接进入 `list.push` 后 `put`，路径确定。`probes-e/probe-e-io.cjs`（转译**真实** `FavoriteStore.ets`，注入内存 mock KV）实测：预置 2 条合法收藏 → 将键改写为非法 JSON → 调真实 `addFavorite` → 键内只剩 1 条（`["new-1"]`），2 条旧收藏静默消失。未读任何真实用户数据。
- 最小修复：`decode` 失败时区分 `raw===null`（合法空）与 parse 失败（可疑）——后者抛出或把原值转存 `<key>.bad` 并拒绝写；或改用与 MemoryStore 一致的 JSON 文件 + quarantine 形态。
- 回归测试：写入损坏 raw 后调用 `addFavorite`，断言抛错/原值保留，而不是变为 1 条。
- 历史关系：round3 #56 修了并发覆盖；损坏覆盖为新发现残留。

---

### E-08 WebDAV `list` 用 GET 代替 PROPFIND（P3 死代码 → 移交主报告）

- 唯一 ID：E-08
- 严重度/置信度：P3 / 高（静态证明）
- **处置：已按协调员指示作为"死代码"合并进主报告，本文件仅留证据链接，不重复计分。**
- 用户影响与前提：`sync_webdav.list` 用 `GET` + `Depth: 1` 请求集合，真实 WebDAV 服务器对目录 GET 返回 HTML 或 405，正则 `href` 解析基本拿不到条目 → 返回 `[]`。当前仓库无任何调用方（`grep createWebDavClient` 仅 `SettingBackupPage.doUploadWebDav`，只调 `put`），所以**无用户可见影响**，属未接线死代码，不是漏洞。
- 精确位置：`harmony/chat/src/main/ets/chat/sync_webdav.ts:121-146`（`:127` `method:'GET'`，注释自认 405 风险）；无 `list()` 调用方。
- 根因：`HttpRequest.method` 端口未枚举 PROPFIND（`deepread/.../platform/http.ts:5-10`，`RcpHttpClient.mapMethod` 只支持 GET/POST/PUT/DELETE，PATCH 显式抛错）。
- 最小修复：恢复云快照前，扩展 HttpClient 端口支持 PROPFIND（或接受"按已知文件名 GET"的降级路径），并加 e2e mock 测试。
- 历史关系：新增登记（round3 未提 WebDAV list）。
- 备注：`mkcol` 用 PUT 且把 405/409/301 视为成功（`:147-159`），在 `doUploadWebDav` 未被调用，且注释诚实；不单独计分。

---

### E-09 会话删除未级联清理 compact KV（P3 → 移交主报告，与 cleanup 合并）

- 唯一 ID：E-09
- 严重度/置信度：P3 / 中
- **处置：已按协调员指示与 cleanup 主题合并进主报告处理，本文件仅留证据，不重复计分。**
- 用户影响与前提：删除会话后 `compacts/<conversationId>` 与 `.events` 键永久残留（存储泄漏）。摘要内容可能含会话原文，属残留数据；id 为 UUID，被新会话复用的概率极低，故不构成实际串号。
- 精确位置：`harmony/entry/src/main/ets/platform_impl/EntryCompactStore.ets:53-59` 提供 `deleteByConversation`，但 `cleanupDeletedConversation`（`ManagedFileStore.ets:208-227`）未调用它；`grep deleteByConversation` 的 entry 调用方只有 `context_engine.invalidateCompacts`（消息编辑/分支切换时，非删除会话）。
- 根因：会话删除的级联清单（收藏/附件/待发队列）未包含 compact。
- 现有保护为何不成立：Android 同样不在删除会话时清 compact（`ConversationRepository.kt:425-432` cleanup 只做 favorite/files），属双端共有；但 Harmony 以 KV blob 承载，残留更直接。
- 最小修复：在 `cleanupDeletedConversation` 追加 `getCompactStore().deleteByConversation(conv.id)`（注意 EntryCompactStore 的会话级写队列，避免与在途 precompact 竞态复活——`deleteByConversation` 已入同队列，可安全调用）。
- 回归测试：删除会话后断言 compact/event 键不存在。
- 历史关系：round3 #20 关注 insertCompact 并发；未覆盖删除级联。

---

## 已复核为"非缺陷 / 可接受"的项（排除项）

| 项 | 位置 | 结论 |
|---|---|---|
| 备份中 `providers.apiKey` 等明文进入 settings blob | `SettingBackupPage.ets:81-96`、`provider_settings_serialize.ts:159-184` | **不计为泄露漏洞**：`createSyncBackup`（`sync_snapshot.ts:236-270`）恒 `encrypted:true`，payload 经 AES-GCM 加密后才落盘/上传；`passphraseProtected:false` 时用空口令派生密钥属"无密码备份"的既定语义（manifest 如实标注），文件可解密是产品意图。probe [5] 确认序列化器确实带 apiKey，但该串只存在于加密 payload 内。 |
| 附件在恢复后指向不存在的 `file://` | 备份 payload 不含任何文件 | **计为 E-01 的同源范围限制**而非独立漏洞：本页页脚已声明"当前仅本地导出/恢复；云同步与跨端 KDF 互通待后续"，且恢复不复制文件（Android 有 `SYNC_FILE_ROOTS` 文件树，Harmony 未移植）。用户可见"恢复成功"但图片/文档缺失，已在 E-01/E-02 的"语义不完整"中合并陈述；不另开 pending。 |
| 沙箱明文存储（MemoryStore/ManagedFileStore/Preferences 明文 JSON） | 各 Store | 按任务约定，**不自动视为泄露漏洞**：应用私有 `filesDir`/preferences 非共享目录，无其他应用读取路径。 |
| `AtomicJsonFile.writeTextFileAtomic` tmp+rename | `AtomicJsonFile.ets:11-34` | 已正确实现原子替换与 tmp 清理；无 fsync，掉电窗口存在但无设备可证，记为盲区不立候选。 |
| `quarantineIfStillInvalid` 二次确认 | `AtomicJsonFile.ets:38-54` | 二次读校验 + 改名为 `.bad`，设计正确；rename 失败时仍返回空（注释已声明），属残余风险归入 E-05。 |
| RDB schema 列 diff 迁移 | `ChatRdbRepository.ets:66-84,129-147,157-167` | 两阶段（建表→补列→建索引）正确；`auto_approve_tools` 等后加列有 ALTER 默认值；缺 PK/基础列的病态旧表会 init 失败并回退内存（已在 E-04 计入）。round3 #3 "无 migration" 已修复，当前确认。 |
| RDB 写事务 + 回滚 | `ChatRdbRepository.ets:104-126`（save）、`:491-506`（delete） | `beginTransaction/commit` + 失败 `rollBack` 正确；`enqueueConversationFileMutation` 全局串行使跨会话写不并发。 |
| 每会话删除墓碑防迟写 | `ChatRdbRepository.ets:92-102,481-512` | 保护成立；副作用缺陷见 E-03。 |
| Compact 会话级写队列 | `EntryCompactStore.ets:16-33,46-59` | insert/delete/recordEvent 同队列串行，round3 #20 的"invalidate 与在途 precompact 竞态"已修，确认。 |
| Favorite 写串行 | `FavoriteStore.ets:58-64` | round3 #56 并发覆盖已修；损坏覆盖见 E-07。 |
| `agent_runtime` 跨文件共享 `sharedKvMutex` | `AtomicJsonFile.ets:57-74`；`AgentRuntimePrefs.ets`；`PromptConfigStore.ets:379-394` | 两地共用同一 `sharedKvMutex` 实例，round3 #71 针对该键的实例已修，确认。 |
| `McpSettingsStore` hydration 竞态 | `McpSettingsStore.ets:41-67,92-111` | `pendingUpdates` 回放 + `persistQueue` 串行，避免 hydrate 覆盖并发编辑，正确。 |
| `pending_queue_store` / `memory_import_export` 去重 | `pending_queue_store.ts:39-47`；`memory_import_export.ts:163-186` | 会话级写队列 + 导入去重索引用 upsert 返回值推进，round3 相关项已修。 |
| `FileStore.validateRelativePath` | `FileStore.ets:24-41` | 拒绝绝对路径与 `.`/`..` 段，防 `..` 逃逸，正确（novel 等共用）。 |
| `SubAgentStore.isPathInside` / transcript | `SubAgentStore.ets:109-155` | 段级规范化后 containment，防 `root/../x`；readTail 字节边界处理正确。 |
| SyncCryptoPort 编译阻断 | `SyncCryptoPort.ets:21,28,32,90` | 已由主代理统一记录（createSymKeySync/initSync 3 参/CipherSpec/getRandomSync），本报告不重复计 P1，不在此列候选。 |

## 待验证 / 盲区

1. **relationalStore 事务内并发的读**：`getById/list/search` 不经写队列，可能在 `writeConversation` 事务打开时并发查询同一 store。HarmonyOS relationalStore 对同 store 上并发查询+未提交事务的行为未经设备验证，无法静态定论（无设备、无 RDB 运行时）。
2. **`AtomicJsonFile` 无 fsync**：`write→close→rename` 在掉电/系统 kill 下的持久性窗口。
3. **`fileIo.renameSync` 与 picker 返回的 `content://`/`file://` URI**：`SettingBackupPage` 直接 `fileIo.openSync(uri)`；DocumentViewPicker 返回 URI 是否可直接被 fileIo 打开（是否需要 `fileIo.open(uri)` 或授权）未在设备验证。
4. **SyncCryptoPort 的实际密码学正确性**（GCM IV/tag、iterated SHA-256 强度、`createRandom` 熵）：受编译阻断影响，运行正确性未验证；离线文档未展开。
5. **`PreferencesStorage` 大值/blob**：settings/assistants/providers 体量增长后 preferences 的容量与 flush 性能；`chatStorageDegraded` 之外无配额监控。
6. **RDB 旧库病态 schema**（缺 PK/基础列）：仅能确认会回退内存（E-04），无法在无设备下构造真实旧库验证 ALTER 行为。
7. **同步并发**：`doRestore` 与 ChatPage 在途生成对同一会话 ID 的 `save` 竞争——两者经 `mutationTails` 串行，但"最后写者赢"的语义（恢复覆盖新消息 vs 相反）依赖时序，未做运行时验证；无回滚（E-02）使其更敏感。
8. **`assistant_serialize` 的 `customBodies`/`localTools` 等强校验**：`parseAssistantList` 对未知枚举抛错；若备份 settings 含未识别值，`saveAssistants` 路径可能整包失败（覆盖设置时的部分失败）。未构造畸形 settings 端到端验证。

## 验证方法与证据

- 隔离 probe（真实 domain 代码 + 合成 crypto，无网络/无凭据）：`docs/reviews/2026-09-13-harmony-deep-audit/probes-e/sync_snapshot_probe.mts`，运行方式 `cd harmony/chat && ./node_modules/.bin/tsx <probe>`。输出：
  - `[1] missing conversations: restore RESOLVED (no validation)`
  - `[2] conversations=object: restore RESOLVED, Array.isArray=false`
  - `[3] duplicate+missing: restore RESOLVED; entries with messages===undefined=1`
  - `[4] createSyncBackup default mode=STANDARD`
  - `[5] serializeProviderSettingList contains apiKey field: true; contains literal secret value: true`
- **E-05/E-07 隔离 probe（真实 `.ets`，注入 mock 文件 IO/KV）**：`docs/reviews/2026-09-13-harmony-deep-audit/probes-e/probe-e-io.cjs`，运行 `node <probe>`，日志 `docs/reviews/2026-09-13-harmony-deep-audit/probe-e-io.log`。做法与主 probe-f 一致：用 `typescript.transpileModule` 加载**当前源码**（MemoryStore.ets / ManagedFileStore.ets / AtomicJsonFile.ets / FavoriteStore.ets），仅替换 `@kit.CoreFileKit`、`@kit.PerformanceAnalysisKit`、`@amber/chat-domain`、`../di/AppContainer.ets` 等依赖为内存 mock（其中 `managed_files.ts` 的真实实现也按源码加载，仅 DAO 依赖被替换）；不复制实现、不读真实用户文件、无网络/设备/凭据。关键输出：
  - `[E-05] readText threw during addMemory -> addMemory resolved: true`；写盘后文件仅 `["gamma"]`（原 alpha/beta 丢失）。
  - `[E-05b] readText threw during insert -> insert resolved: true`；`managed_files.json` 仅 `["upload/c.txt"]`（原 a/b 追踪行丢失）。
  - `[E-07] stored favorites after corrupt-value + addFavorite: 1 ids: ["new-1"]`（原 2 条收藏丢失）。
- 既有域测试实跑（无外部调用）：`harmony/chat` `tsx --test src/test/sync_snapshot.test.ts` → 6 pass / 0 fail（覆盖 happy-path round-trip、错密码、篡改校验；**未覆盖**畸形 payload / 去重 / 分支元数据）。
- Android 基准核对：`SyncArchiveManager.kt:428-470`（行级表复制，保留 select_index）、`SyncRedactor.kt`（STANDARD 模式对敏感字段打码 `__MASKED_BY_AMBERAGENT_SYNC__`）、`ConversationRepository.kt:425-432`（cleanup 只做 favorite/files）。用于证明 E-01 非双端共有，并说明 Harmony 未移植 redactor（但因始终加密，不作为独立泄露计分）。

## 勘误（Erratum）

1. **E-04 丢失范围精确化（原表述过于宽泛）**：初稿写"重启后所有会话消失"，不准确。准确机制为：
   - `initializeStore` 在成功后会把内存 `memory` 仓库的行**回灌**进 RDB（`ChatRdbRepository.ets:171-176`：`fallbackRows = await this.memory.list()` → 逐条 `writeConversation`），随后 `this.memory = createMemoryConversationRepository()` 重置、`this.store = store` 发布（`:176-178`）。
   - 因此真正的净损失前提是：**持久化尚未恢复前进程退出**，丢的是**降级期间新增/编辑的会话**。
   - RDB 中**原有旧数据不会被删除**：该实现全程没有 `DELETE FROM conversation`（除按 id 的 `delete`），`save` 是 upsert，迁移是 add-only 回灌。
   - `ensureStore` 在每次初始化完成后清空 `initPromise`（`:193-195`），故"恢复 + 回灌"每条会话至多发生一次，`save` 可能在重试之间把同一条会话 upsert 两次（幂等 REPLACE，无重复行）。综上 E-04 的 P1 定级与"UI 无降级提示"结论不变，但丢数据口径应表述为"降级期间增量在 RDB 恢复前退出即净丢失"，而非"整库清空"。

2. **E-02 失效面限定**：补充说明该缺陷限定于**合法加密 + 正确口令 + GCM 认证通过**的格式异常快照；普通密文截断/改写会在 AES-GCM 认证阶段直接失败，不由此路径绕过。E-02 的修复价值在于"结构校验 + 全有或全无落库"，与密文完整性是两个独立面。

3. **E-08/E-09 处置**：E-08 为未接线死代码、E-09 与 cleanup 主题重合，按协调员指示合并进主报告处理，本文档保留证据链、不重复计分。

4. **E-07 边界**：仅 `JSON.parse` 解析级失败被吞；`kv.get()` 自身抛错（KV 层 IO 失败）不会被吞，会向上抛到 UI。E-05 则是**文件 IO 读取**失败被吞（`readText`/`access` catch）。两者是不同层的"读失败静默空库"。

## 主要结论

- 最值得修的是 **E-01（备份恢复丢分支/元数据）** 与 **E-04（RDB 降级静默丢库）**：都是"用户以为成功、实际不可逆丢数据"，且都有明确最小修复。
- **E-02/E-03** 使同步恢复在异常/冲突下呈"部分写入 + 报失败"，缺整体校验与回滚，属同一主题的加固面。
- **E-05/E-07** 是 round3 P0-1 "损坏静默清空" 在 IO 异常与收藏 KV 上的未闭合分支。
- **E-06** 是删除编排漏接（ChatPage 抽屉路径）。
- 未发现可构造的明文泄露漏洞；备份内容恒加密，无攻击路径 pending。
