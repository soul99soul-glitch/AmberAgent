# 批 1B — 备份恢复（R02 / R03 / R04 / R09 / UI-F25）

独占范围：`harmony/chat/src/main/ets/chat/sync_snapshot.ts`、`harmony/chat/src/main/ets/chat/persistence.ts`、`harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets`、`harmony/entry/src/main/ets/pages/SettingBackupPage.ets`，及直接相关已有测试。未改 `index.ts` / `ChatPage.ets` / `AppContainer.ets` / `ManagedFileStore.ets` / `ConversationRunGuard.ets`。

## 修改文件

- `harmony/chat/src/main/ets/chat/sync_snapshot.ts` — 完整 nodes DTO、恢复校验、真实序列化器、v1 只读兼容。
- `harmony/chat/src/main/ets/chat/persistence.ts` — `ConversationRepository` 新增 `restoreConversations`；内存实现批量 staging。
- `harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets` — 拆无事务写入体、单事务批量恢复、删除/恢复代际、R04 降级拒绝写。
- `harmony/entry/src/main/ets/pages/SettingBackupPage.ets` — 空 URI 导出取消、活跃 run 预检 + 恢复占用、批量恢复、settings 第二阶段回滚、精确反馈。
- 测试：`harmony/chat/src/test/sync_snapshot.test.ts`、`harmony/chat/src/test/persistence.test.ts`。

## 修复 ID 与最小机制

### R02 完整备份可逆（信息不再缩水）
- 单一完整 DTO `SyncConversationDto { id, assistantId, title, createAt, updateAt, chatSuggestions, isPinned, autoApproveToolCalls, nodes: SyncMessageNodeDto[] }`，`SyncMessageNodeDto { id, selectIndex, messages: JsonObject[] }`。
- `toSyncConversationDto`：node 的**全部替代分支**与 `selectIndex` 原样保留；消息经真实 `serializeUIMessage`。导出与恢复共用同一 DTO。
- `conversationFromSyncDto`：经真实 `parseUIMessage` 还原（不 `as` 强转）。
- 打包即校验：非法 `selectIndex`/空消息/缺 id/非法时间在 `buildSyncPayload` 阶段抛错，不产出不可恢复归档。
- `SYNC_ARCHIVE_VERSION` 1→2（payload 版本同步 1→2）。**人类可读导出 `exportConversationJson/Markdown` 未改**。

### R03 整体校验 + 全有或全无
- `validateSyncPayload(raw)`：真实反序列化器逐字段校验——结构、会话/节点/消息 ID 唯一（node id 含**跨 conv 的 message_node PK 唯一性**）、`selectIndex` 范围、ISO 时间、消息、`settings`（assistants/providers 经真实 parser，未知枚举即拒）。解密后 `restoreSyncBackup` 调用它，**不再 `JSON.parse(...) as SyncPayload`**。
- 校验在写库前完成：任何非法都在 `restoreConversations` 之前以非持久化错误抛出，前一条不会半写。
- RDB `restoreConversations(convs)`：**单 `beginTransaction/commit`**，失败 `rollBack` 全部不落。写入复用 `writeConversationBody`（无事务），**不嵌套 transaction**；单会话 `save` 仍走自己的事务体。
- 内存实现同样先整体 staging 再落库。
- **P2-1 复核修正**：`parseUIMessage` 对缺 `id` 会 `newId()`、缺 `createdAt` 会用 `now()`，故在 `validateNodeDto` / `validateLegacyConversation` **parse 前**先强验原始 `id` 非空、`createdAt` 为 ISO；节点内消息 `id` 用 Set 去重（消息 id 非 DB PK，不跨会话唯一）。否则损坏归档被静默补值掩盖、恢复不忠实。

### R04 降级不再静默写内存
- `initializeStore` 失败仍 `AppStorage('chatStorageDegraded', true)` + 内存回灌保留；降级期**读**仍可回退内存。
- `save` / `delete` / `restoreConversations` 在 `store === null` 时**抛非持久化错误** `数据库暂不可用，已取消写入以免数据丢失，请稍后重试`，不再写降级内存装成功。下次操作重试初始化，成功时内存回灌 + 清标志。
- **P2-2 复核修正**：`delete` 原先 `old = await getById(id)` 在前，降级时内存通常为空 → `old===null` 提前 return，绕过拒绝写并静默删成功（墓碑却已置，重启复现）。现改为**先 `ensureStore()` 判 null 抛错，再判存在性**。抛错时 catch 因代际仍最新撤墓碑，不留假墓碑。

### R09 授权恢复 vs 迟到旧写
- 新增 `deletedGen: Map<id, number>`。`save` 在**入队时**捕获 gen；执行时 `isDeletedAt(id, gen)`（墓碑存在**或** gen 不符）即拒。故删除后清墓碑也不会放行此前排队的旧 save。
- `delete`：gen 自增 + 立墓碑；catch 仅在代际仍最新时撤墓碑（避免误撤更新的删除/恢复）。
- `restoreConversations` 即**显式授权恢复**：自增 gen + 清墓碑 → 等涉及会话在途 mutation → 进入事务前再授权一次（覆盖等待期并发删除）。
- 恢复成功后 `SettingBackupPage` 对每个 id 调 `EntryCompactStore.clearConversationDeletedMarker(kv, id)`（**仅清 compact 删除标记**，绝不触碰 memory/managedfiles 的损坏隔离保护——恢复不携带这些 store 的数据）。该步失败**不泛报「恢复失败」**（会话已落库），而是明确 `已恢复 N 个会话，但压缩状态恢复失败：…`。

### UI-F25 空 URI 导出
- `docPicker.save()` 返回 `[]` 或空串 = 用户取消 → `statusMsg='已取消导出（未选择保存位置）'`，`toast('已取消导出')`，**不私有落盘、不报成功**（删除原 fallback 到 `filesDir` 的分支）。真实 cancel 返回 `[]` 还是 reject 仍待设备，此处两路都无害。

### 恢复活跃 run 检查（协调接口）
- `doRestore`：解密 → `hasActiveConversationRuns(ids)` 预检，命中则置明确失败文案并返回（不占用）→ `beginConversationRestore(ids)` 同步占用（覆盖预检后新 run 竞态，`beginRun` 期间被拒）→ `restoreConversations` → settings 第二阶段 → `finally release()`。
- 主代理确认 release 语义由 guard/ChatPage/cron 调用方保证（整轮 promise settle + `checkpointTail` 完成后释放）。

### settings 明确第二阶段
- `settingsFromSyncPayload` 在写会话前解析（预校验失败整体不写）。
- `applyRestoreSettings`：覆盖前取原值 → 覆盖失败**回滚原值**（`null` 原值则 `delete`）→ 返回准确反馈。**回滚本身失败时文案为「回滚未完成」而非「已回滚原设置」**（rollback 失败明细一并给出）。会话与 settings **不假装跨 store 原子**，成功文案区分三种终态：仅会话成功 / settings 失败 / 压缩状态恢复失败。

## 测试命令与结果

```
cd harmony/chat && ./node_modules/.bin/tsc --noEmit            # exit 0
cd harmony/chat && ./node_modules/.bin/tsx --test "src/test/**/*.test.ts"
  → tests 2104 / pass 2104 / fail 0（含新增 6 例：R02 可逆+导出校验、R03 4 例含 v1 legacy、批量恢复）
cd repo && harmony/scripts/lint-arkts.sh <两个改动 .ets>
  → "No defects found in your code"（codelinter 中途打印内部 "Some error occurred during linting" 告警，
     但最终判定 0 defect；ArkTS 严格模式真错误以主代理 hvigor 为准）
```

新增回归（7 例，均为根因复现而非源码字符串断言）：
1. R02 完整 DTO 双分支 + `selectIndex=1` + 元数据经真实 serializer 深等价往返；
2. R02 非法 `selectIndex` 打包即抛；
3. R03 拒绝缺 conversations / 非数组 / 缺 messages / `selectIndex` 越界；
4. R03 拒绝重复会话 ID 与**跨 conv 重复 node ID**；
5. R03 非法时间 / 畸形消息 / settings 未知枚举在校验期拒绝；
6. R03 v1 legacy 只读还原 + 内存 `restoreConversations` 批量保留分支/元数据；
7. P2-1 parse 前强验：缺/空消息 `id`、非 ISO `createdAt`（v2 与 v1）、节点内重复消息 id 均拒。

未跑 hvigor / 全仓 lint（按契约由主代理统一串行）。

## 共享接线需求 / 未解决点

1. **barrel（主代理已接）**：`harmony/chat/src/main/ets/index.ts` 需导出 `conversationFromSyncDto`、`settingsFromSyncPayload`（值）与 `SyncConversationDto`、`SyncRestoreSettings`（类型）。可选补 `validateSyncPayload`/`toSyncConversationDto`/`SyncMessageNodeDto`/`SYNC_PAYLOAD_VERSION`/`SYNC_LEGACY_PAYLOAD_VERSION`。`restoreConversations` 在已导出的 `ConversationRepository` 接口上，无需新增导出。
2. **ConversationRunGuard（主代理已接）**：`SettingBackupPage` import `hasActiveConversationRuns`/`beginConversationRestore`；guard 文件未改、签名无需变。另 import `EntryCompactStore.clearConversationDeletedMarker`（既有导出，仅清 compact 删除标记）。
3. **待设备**：UI-F25 真实 cancel 的 SDK 返回形态；RDB 事务内并发读；真实文件/DB 恢复。
4. **范围外（登记）**：备份不含附件文件树 / memory / managed_files / compact / favorites（E-01 附带范围，本轮未扩）；跨端 KDF 互通仍待后续。
5. `restoreConversations` 仍在文件在途队列（`enqueueConversationFileMutation`）内执行以保持与 save/delete 的跨仓储串行语义；未嵌套 RDB transaction。
