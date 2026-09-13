# 独立反证复核 — 专题 E（存储 / 同步 / 附件持久化 / 数据安全）

- 审查对象：当前工作树（dirty），分支 harmony-preview，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。
- 方法：亲读当前源代码 + 真实调用上下文；运行/增强隔离 probe（仅合成 crypto，无网络/无凭据/无设备/无真实用户数据）；对每项主动找上游校验、旁路、设计契约等反证。
- 证据分级：仅定位 / 已阅读 / 跨模块追踪 / 隔离验证（probe 见 `probes-e/review-e-probe.mts`，仅新增本文件与 review-e.md，未触碰业务代码）。
- 环境：无设备/模拟器（`hdc list targets` 为空，未重复执行）；平台 .ets 语义疑点已用源码与官方 API 先例判定，未把"无设备"当反证或当确认。
- 前置已读：`reviewer-contract.md`、`topic-e-storage.md`、根 `AGENTS.md`、`harmony-arkts` skill、round3 报告/fix-plan 相关段、Android 基准。

## 逐项结论速览

| ID | 原判 | 复核结论 | 置信度 | 关键反证检查结果 |
|---|---|---|---|---|
| E-01 | P1 高 | **确认（维持 P1）** | 高（probe 实证） | 无任何分支/元数据字段；Android 行级复制保留 select_index，非双端共有 |
| E-02 | P1 高 | **确认（维持 P1）** | 高（probe 实证） | 上游仅验 archiveVersion+密文 sha256；doRestore 无校验无回滚 |
| E-03 | P2 高 | **确认（P2，触发面比报告更宽）** | 高（静态确定） | 无 clearTombstone/allowRecreate 旁路；甚至删除不存在的 id 也留墓碑 |
| E-04 | P1 高 | **确认（维持 P1）** | 高（全仓 grep 零消费） | 无任何读取点，无 UI 告警；hilog 用户不可见 |
| E-05 | P2 高 | **确认（维持 P2）** | 高（静态确定） | 读 IO 异常 catch 返回空且不 quarantine，写侧随即空覆盖 |
| E-06 | P2 高 | **确认（维持 P2）** | 高（grep+跨页对比） | ChatPage 无 cleanup import/调用，其余三入口均调用 |
| E-07 | P2 高 | **确认（维持 P2）** | 高（静态确定） | decode catch→[]，addFavorite 以空基准覆盖 |
| E-08 | P3 高 | **假阳性（作为"真实 bug"）/ 登记项** | — | 无任何调用方（list/mkcol dead code），无用户可见影响 |
| E-09 | P3 中 | **确认（维持 P3，但更正历史依据）** | 高（残留确定） | 报告"双端共有"不成立：Android 有 FK CASCADE，Harmony KV 无级联 |

---

## 详细复核

### E-01 备份/恢复丢失消息分支与全部会话元数据 —— 确认（P1）

亲读当前实现（1-based，当前工作树）：

- 导出 DTO 无分支/元数据字段：`harmony/chat/src/main/ets/chat/conversation_export.ts:122-129` 仅 `id/title/assistantId/createAt/updateAt/messages`；`:131-150` 对每个 node 只取 `node.messages[selectIndex]`（`:138`）后 `push`（`:140`），替代分支被丢弃。
- 负载只存打平数组：`harmony/chat/src/main/ets/chat/sync_snapshot.ts:67-73`（`SyncPayload.conversations: ReturnType<typeof exportConversationJson>[]`）、`:181-190 buildSyncPayload`。
- 恢复侧手写逆变换：`harmony/entry/src/main/ets/pages/SettingBackupPage.ets:219-235`，`:221` 对每条消息 `toMessageNode(m)`（见 `conversation.ts:31`，恒 `selectIndex=0`），`:228-231` 硬置 `chatSuggestions: []`、`isPinned: false`、`autoApproveToolCalls: false`（`:228/:229/:230`）。

隔离验证（真实 domain 函数 + 合成 crypto，`probes-e/review-e-probe.mts`，实跑）：

```
[E01-a] exported per-conversation keys = ["assistantId","createAt","id","messages","title","updateAt"]
[E01-a] exported messages = 2 (original nodes=2, node-branch has 2 alternatives)
[E01-b] payload.conversations[0] keys = ["assistantId","createAt","id","messages","title","updateAt"]
[E01-c] after restore: nodes=2, branchNode.messages=2, restored node[1].selectIndex=0
[E01-c] metadata: chatSuggestions=undefined, isPinned=undefined, autoApproveToolCalls=undefined
[E01-c] => branches preserved=false, selectIndex preserved=false
```

即：含 2 个替代分支、`selectIndex=1`、`isPinned=true`、`chatSuggestions` 非空的会话，经真实 `createSyncBackup`→`restoreSyncBackup`→真实 `toMessageNode` 逆变换后，只剩每 node 1 条消息、`selectIndex=0`、元数据全丢。属确定性信息销毁。

**反证检查**：全仓 grep 无第二套同步 DTO/还原路径；`restoreSyncBackup` 的 sha256（`sync_snapshot.ts:286-289`）只保证密文完整性，不恢复语义；UI 预览（`SettingBackupPage.ets:190-192`、`:298-302`）只显示版本/设备/时间/是否需密码，无分支数或元数据提示。Android 基准 `SyncArchiveManager.kt:761-800` 的 `SYNC_TABLES` 按行复制 `message_node`（列含 `select_index`，`MessageNodeEntity.kt:33 @ColumnInfo("select_index")`），故**非"Android 同款"**，报告结论成立。

**附带范围限制（确认）**：备份只含 conversations + settings blob（`SettingBackupPage.ets:81-96`），不含附件文件、memory、managed_files、compact、favorites；恢复不复制任何文件（页脚 `:360` 仅声明"云同步与跨端 KDF 互通待后续"，未声明数据完整性缺口）。这与 E-01 同源，不另开条目。

---

### E-02 恢复负载无 schema 校验 + 中途异常无回滚 —— 确认（P1）

- 上游只校验：`sync_snapshot.ts:215-234 inspectSyncArchive` 仅读 manifest、`archiveVersion` 数值/上界（`:225-227`）；`restoreSyncBackup`（`:277-302`）仅校验密文 sha256（`:286-289`）、密码存在性（`:290-292`）、解密非 null（`:298-299`），随后 `:300 JSON.parse(...) as SyncPayload` 纯编译期断言，无结构校验。
- 下游无回滚：`SettingBackupPage.ets:219-235` 单循环逐条 `await repo.save(conv)`；`:254-258` catch 只改 `statusMsg`，已写入的会话不回滚、不清理。

隔离验证（实跑）：

```
[E02-a] malformed restore RESOLVED (no runtime validation); entries with messages=undefined: 1
[E02-b] entry-layer loop: saved 1 conversation(s) BEFORE throwing "Cannot read properties of undefined (reading 'map')" -> partial restore, no rollback
[E02-c] duplicate-id payload keeps both entries; writeConversation uses ON_CONFLICT_REPLACE -> later dup overwrites earlier
```

- 数组元素畸形（`messages` 缺失）时，`item.messages.map`（`:221`）抛 TypeError，但前一条已 `repo.save` 成功 → **部分恢复 + 报"恢复失败"**。与报告一致。
- `conversations` 非数组时 `for...of` 在首轮前抛错，无写入，可接受。报告对两种分支的区分准确。
- 补充反证：`writeConversation` 用 `ON_CONFLICT_REPLACE` 且先 `delete message_node`（`ChatRdbRepository.ets:104-126`），故恢复对同 id 是**破坏性覆盖**（非合并）；重复 id 时后写覆盖先写（报告中提到，probe 复现 `ids=["dup","dup",...]`）。
- 上游无 `validateSyncPayload`、无事务包裹、无"全有或全无"暂存。确认维持 P1。

---

### E-03 删除墓碑阻断恢复 save —— 确认（P2，触发面更宽）

- 墓碑写入：`ChatRdbRepository.ets:481-512 delete`，`:482 this.deletedIds.add(id)` 在入队前**同步**执行；仅当删除自身抛错时 `:508-510` 才 `deletedIds.delete(id)`。
- 写入拒绝：`:283-300 save`，`:285` 与 `:289` 两处 `if (this.deletedIds.has(conv.id)) throw ...`。
- 触发点：`SettingBackupPage.ets:233 await repo.save(conv)`。

**反证检查（重点）**：全仓 grep `clearTombstone|allowRecreate|tombstone` 无任何旁路；`save` 是会话写入唯一入口；`deletedIds` 无 TTL/无上限/无清理（`:93`）。`getChatRepository()` 是模块级单例（`di/AppContainer.ets:836-842`），跨页共享，故 SettingBackupPage 的恢复会命中同一实例的墓碑。恢复路径**没有**绕过墓碑的写入方式。

**比报告更宽**：即使 `delete(id)` 的 `old === null`（id 本来不存在）提前返回，`:482` 的墓碑仍残留；删除失败才清除。因此"误删找回"场景确定失败，且提示为"恢复失败"。这是设计契约缺陷（墓碑语义应为"防在途迟写"，被实现成"该 id 永久不可写"）。确认 P2。

---

### E-04 RDB 初始化失败静默降级内存 —— 确认（P1）

- 写标志：`ChatRdbRepository.ets:178 AppStorage.setOrCreate('chatStorageDegraded', false)`；`:182` 置 `true`；catch 在 `:180-185`。
- 降级落点：`save` 中 `store === null` 时写内存（`:292-297`），正常 resolve；`getById/list/...` 同样回退内存（`:303/:399/:413` 等）。
- 消费点：`grep -rn "chatStorageDegraded" harmony --include=*.ets --include=*.ts | grep -v /build/` → **仅上述两处写，零读取**。`ChatListPage`/`ChatPage`/`ProfilePage` 均未接。
- 文件头注释 `:5-7` 自称"失败策略（非静默）"，实际仅 `hilog.warn`（`:183`），对用户不可见。

**反证检查**：是否有通用告警条/全局横幅消费该键？全仓 grep 无；`BackupReminderCard` 等未引用。是否有其他持久化告警通道？无。故重启后进程内会话全部消失且此前无任何警示，确认 P1。

---

### E-05 IO 读异常被当空库 —— 确认（P2，round3 P0-1 未闭合分支）

- MemoryStore：`MemoryStore.ets:39-63 readJsonArray`，`:42-47` 的 `access/readText` 异常 catch 直接 `return []`（`:45-47`），不隔离、不进错误态；只有 `:48-61` 的 JSON 解析失败才 `quarantineIfStillInvalid`。写侧 `:70-71 writeAll`、调用链 `:89-95 touchMemories`/`:101-107 addMemory`。
- ManagedFileStore：`:44-71 readShape`，`:51-53` catch 返回 `emptyShape()`；`:62-69` 才隔离解析失败。写侧 `:73-74 writeShape`，调用链 `:96-118 dao.insert` 等。
- `quarantineIfStillInvalid`（`AtomicJsonFile.ets:38-54`）只在 parse 失败分支被调用；原子写（`:11-34`）只防撕裂、不防空基准覆盖。

**反证检查**：读路径是否被写队列统一包裹以规避？无——`readAll/readShape` 不在 `enqueueWrite` 之外做错误态标记；写侧读到空值无从识别"空库 vs 读失败"（缺 `access`→"不存在" 与 `readText` 抛错→"可疑"的区分）。路径确定。确认 P2。

---

### E-06 ChatPage 抽屉删除不执行 cleanup —— 确认（P2）

- `ChatPage.ets:2727-2752 deleteDrawerConversation`，`:2741 getChatRepository().delete(item.id).then(...)`，无 cleanup。
- `grep -n "cleanupDeletedConversation" ChatPage.ets` 无结果；`grep` 亦无 `ManagedFileStore`/`clearPendingQueueStorage`/`removeFavoritesOfConversation` 的 import（ChatPage import 段 `:96-107` 无）。
- 对照三个入口均有：`ChatListPage.ets:250`、`:268`（撤销窗提交 `commitPendingDelete` 与删除全部）；`AssistantListPage.ets:132`；`ChatAssistantsPage.ets:332`。
- 缺 cleanup 的后果（`ManagedFileStore.ets:208-227` 编排）：收藏残留（`removeFavoritesOfConversation` `FavoriteStore.ets:83-89` 不被调用）、`pending-queue/<id>` 与 `.events` 残留（`clearPendingQueueStorage` `pending_queue_store.ts:116-121`）、无他人引用时 `upload/` 附件成孤儿。
- ChatPage 删除是**立即删除**，无 ChatListPage 的撤销窗，因此无"延迟 cleanup"补偿。

**反证检查**：`repo.delete` 自身是否做级联？`ChatRdbRepository.delete`（`:481-512`）只删 RDB 的 message_node/conversation 行，注释与设计均把级联留在 entry 层。无统一删除服务。确认 P2。

---

### E-07 收藏损坏静默清空并覆盖 —— 确认（P2）

- `FavoriteStore.ets:27-45 decode`，`:29-41` 解析，`:42-44 catch { return []; }`。
- 覆盖点：`:66-72 addFavorite`（`:68 get` → `:69 decode` → `:70 push` → `:71 put`）；同型 `removeFavorite :74-80`、`removeFavoritesOfConversation :83-89`。
- 调用入口：`ChatPage.ets:136 import addFavorite`、`:2573 addFavorite(...)`。

**反证检查**：round3 #56 的 `favoriteWriteTail`（`:58-64`）只解决并发 RMW 互斥，不解损坏基准；raw 为 `null`（合法空）与 parse 失败（可疑）在 `decode` 中同收敛为 `[]`，无 `.bad` 隔离、无错误态。确认 P2。

---

### E-08 WebDAV list 用 GET 代替 PROPFIND —— 假阳性（作为"真实 bug"）/ 登记项

- `sync_webdav.ts:121-146 list` 用 `method:'GET'` + `Depth:1`（`:127`），对目录 GET 真实服务器多返回 HTML/405；`mkcol`（`:147-159`）用 PUT 并把 405/409/301 视为成功。
- 但：`grep -rn "createWebDavClient" harmony`（排除 build）仅命中 `SettingBackupPage.ets:161`，且该处只 `client.put(name, bytes)`（`:163`），**无 `list()`/`mkcol()` 调用方**；`list` 仅被 `sync_webdav.test.ts` 测试引用。
- 结论：当前无用户可见影响，属"同步下行未完成"的登记项。按本次任务约定 **dead code 不作真实 bug**，判为假阳性（作为缺陷计分）/ 待接线登记。报告本身也标注"暂无用户可见影响"，与其 P3 措辞一致，但不应计入本轮缺陷数。

---

### E-09 删除会话未级联清理 compact/审计键 —— 确认（P3，但需更正历史依据）

- `EntryCompactStore.ets:53-59 deleteByConversation` 仅删 `compacts/<id>`（`:57`），**不删** `.events` 键（`:14` 定义）。
- `cleanupDeletedConversation`（`ManagedFileStore.ets:208-227`）只做附件/待发队列/收藏，未调用 `deleteByConversation`。
- `deleteByConversation` 的 entry 调用方只有 `context_engine.ts:364-371 invalidateCompacts`（消息编辑/分叉失效），非删除会话。
- 故删除会话后 `compacts/<id>` 与 `compacts/<id>.events` 永久残留（KV blob，摘要可能含会话原文）。

**反证检查（关键更正）**：报告称"Android 同样不在删除会话时清 compact，属双端共有"——**不成立**。Android `ConversationCompactEntity.kt:9-20` 与 `ConversationContextEventEntity.kt:9-22` 均对 `ConversationEntity` 声明 `ForeignKey(onDelete = CASCADE)`；Room 默认启用 foreign key 约束，删 conversation 行会级联清除 compact/context_event。旁证：`SyncArchiveManager.kt:437` restore 前显式 `PRAGMA foreign_keys=OFF` 以防级联误删。因此 E-09 是 **Harmony 特有的级联缺失**（KV blob 无 FK 语义），比报告定位更实体。严重度仍为 P3（id 为 UUID，复用概率极低，主要是存储/残留数据）。

---

## 跨模块 sync 与活跃生成契约

1. **无后台/自动同步**：全仓（排除 build）除 `SettingBackupPage` 外无 `createSyncBackup/restoreSyncBackup/inspectSyncArchive` 调用；无 autoSync/backgroundSync 编排。同步面=手动导出/上传/恢复，restore 仅此一页。
2. **恢复不感知活跃生成**：`SettingBackupPage` 无 `isGenerationActive`/`sending` 之类判断（grep 无），`doRestore`（`:199-259`）直接 `repo.save`。ChatPage 的生成中尾写走 `mutationTails`（`ChatRdbRepository.ets:92-102`）+ `checkpointTail`（`ChatPage.ets:1164-1171`），restore 的 `save` 走同一 per-id 队列 → **串行但"最后写者赢"，无版本校验/无合并**。由于 E-01 恢复的是打平且可能陈旧的快照，若 restore 写落在生成尾写之后，会用旧快照覆盖较新的会话（并丢分支）。
3. **可达性**：ChatPage 抽屉 `openDrawerSettings`（`:2773-2775`）→ SettingPage（`SettingPage.ets:111` push SettingBackupPage）在生成中未被禁用（仅删除当前会话被 `:2729` 拦）。Harmony 路由 pushUrl 使 ChatPage 留在栈内、其流式保存仍在进行。
- 判定：静态上"无守卫、无合并"确定存在；"恢复与生成的先后时序"依赖运行时，属**待验证**（见下），未用无设备糊弄高影响断言。

---

## 待验证 / 盲区

1. 恢复与活跃生成的**时序竞争**（上文 2/3）：需要设备/运行时构造，确认"最后写者赢"的实际覆盖方向与频率。
2. `relationalStore` 事务内并发读（report 待验证 #1）：无 RDB 运行时。
3. `AtomicJsonFile` 无 fsync 的上电/掉电持久性窗口（report 待验证 #2）。
4. `fileIo.openSync(picker URI)` 对 `content://` 的可用性（report 待验证 #3）。
5. `SyncCryptoPort` 密码学正确性（createSymKeySync/CipherSpec/getRandomSync 等确由主代理另记编译问题；本次按其指示只审运行逻辑，未在设备验证）。
6. 畸形 `settings`（assistant/provider 未知枚举）在 `saveAssistants/saveProviders` 的整包失败行为（report 待验证 #8），未构造端到端。

---

## 验证方法与证据

- 隔离 probe（新增，仅 report 目录）：`docs/reviews/2026-09-13-harmony-deep-audit/probes-e/review-e-probe.mts`。使用真实 `conversation.ts`/`conversation_export.ts`/`sync_snapshot.ts` 与真实 `toMessageNode`；唯一 mock 为 `SyncCryptoPort` 合成实现，无网络/无凭据/无真实用户数据。运行：`cd harmony/chat && ./node_modules/.bin/tsx <probe>`。
- 既有 probe 复跑（未改动）：`probes-e/sync_snapshot_probe.mts` 输出与报告一致（[1] 缺 conversations 仍 RESOLVED；[2] object 仍 RESOLVED；[3] 重复 id+缺 messages RESOLVED；[4] 默认 STANDARD；[5] 序列化含 apiKey 字段与字面值）。
- 既有域测试实跑：`cd harmony/chat && ./node_modules/.bin/tsx --test src/test/sync_snapshot.test.ts` → 6 pass / 0 fail（happy-path、错密码、篡改、emoji；**未覆盖**分支/元数据/畸形结构/重复 id）。
- Android 基准亲读：`SyncArchiveManager.kt:437,440-465,761-800`（行级表复制 + PRAGMA foreign_keys=OFF）、`MessageNodeEntity.kt:33`（select_index 列）、`ConversationCompactEntity.kt:9-20`、`ConversationContextEventEntity.kt:9-22`（FK CASCADE，用于更正 E-09 历史依据）、`ConversationRepository.kt:425-432`（cleanup 只做 favorite/files/images，但 compact 由 FK 级联兜底）。

## 主要结论

- **E-01 / E-02 / E-04** 三项 P1 全部独立确认：分别是"备份语义不完整"、"恢复无校验无回滚"、"降级静默丢库"，都是"用户以为成功、实际不可逆损失"。
- **E-03** 确认且触发面比报告更宽（删除不存在 id 也留墓碑；无任何 allowRecreate/clearTombstone 旁路）。
- **E-05 / E-06 / E-07** 确认，均为 round3 修复的未闭合分支（读 IO 错、删除编排漏接、损坏 KV 覆盖）。
- **E-08 判为假阳性（作为真实 bug）**：`list`/`mkcol` 为 dead code，无调用方，遵任务约定不计真实缺陷。
- **E-09 确认（P3）但更正历史依据**：Android 由 FK CASCADE 兜底，非"双端共有"；Harmony KV 缺级联为独立残留。
- 未发现可构造的明文泄露：备份恒 `encrypted:true`、payload 经 AES-GCM；`apiKey` 只在加密 payload 内，不作为独立泄露计分（与报告一致）。
