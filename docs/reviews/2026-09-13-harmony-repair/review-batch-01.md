# 独立复核 — batch 0/1（只读）

范围：`implementation-contract.md`、`batch-0-crypto.md`、`batch-1-backup.md`，以及当前工作树相关源文件 diff。
基线 `bdce551dd`（harmony-preview）。复核方式：读源码 + SDK d.ts 对照 + 官方文档核对 + 现有 targeted 测试/探针实跑。
本文件只读产出，未改任何代码。

结论：两个 batch 的方向与绝大部分机制成立；发现 2 个应修 P2、2 个需在设备上确认的 P1 候选。按契约，主代理统一修正，我做只读反证。

---

## 一、已复核通过（不再展开）

- **GCM 返回/校验契约**（`SyncCryptoPort.ets`）：`encrypt` 返回 `ciphertext||tag(16B)`，`decrypt` 截末尾 16B 作 `GcmParamsSpec.authTag` 传入 `initSync`，`updateSync` 出明文、`doFinalSync(null)` 校验 tag，认证失败 → `null`；`initSync`（第 66 行）在 try 之外，密钥/IV 参数错误确实向上抛而非被吞成 `null`。与 SDK `initSync(opMode,key,params)` / `doFinalSync(data|null)`（@since 12）及官方 GCM 文档一致。
- **空文本**：`decrypt` 的 `ciphertext.length < 16` 早退（第 55 行）；空明文加密输出 16B（纯 tag），解密 `tagStart=0` → 空明文。边界自洽，未发现越界。
- **KDF 未改**（SHA256-ITERATED，与 manifest 标注一致）；`randomBytes` 改真 CSPRNG；`sha256Hex` 不变。
- **R03 全有或全无**：`restoreConversations` 单 `beginTransaction/commit`，失败 `rollBack`；`validateSyncPayload`/`buildSyncPayload` 在写库前抛非持久化错误。真实序列化器（`parseUIMessage`/`parseAssistantList`/`parseProviderSettingList`）确实会拒绝未知 role/part、未知枚举。
- **R09 代际**：`save` 入队时捕获 gen，执行时 `isDeletedAt`；`delete` 自增+墓碑、catch 仅在代际最新时撤墓碑；`restoreConversations` 自增+清墓碑+等 `mutationTails` 后再进事务。排序与「恢复是权威、删除是权威」的语义自洽。`restoreConversations` 复用 `enqueueConversationFileMutation`（未嵌套 RDB 事务），与 save/delete 跨仓储 FIFO，无锁环。
- **UI-F25** 空 URI 取消：不再私有落盘、不报成功。
- **R11 compact 删除标记**的写后复核正确：`deleteConversationCompactData` 不走 `enqueueWrite`，`insertCompact`/`recordEvent` 在写后重查标记并撤回，覆盖「检查↔写入之间落删除」的竞态；`clearConversationDeletedMarker` 现已被 `SettingBackupPage` 在恢复成功后调用（第 289-296 行），跨 batch 的「恢复后 compact 复活标记未清」已接线。
- **SettingFilesPage 清理**：先健康登记 → 只读扫描候选 → 确认后 `deleteManagedOrphans` 二次核对。`scan/delete` 自身不持 `enqueueWrite`/`enqueueConversationFileMutation`，await 只落在 repository 读与 fileIo，未发现锁次序死锁。
- targeted 实跑：`sync_snapshot.test.ts` + `persistence.test.ts` → 23 pass/0 fail；`probe-batch1-r10-r11.cjs`、`probe-batch1-f20.cjs` → exit 0。
- 静态探针确认：`validateSyncPayload` 对「消息缺 id/createdAt」确实宽容（见 P2-1 证据）。

---

## 二、应修 P2

### P2-1 `validateSyncPayload` 对消息 id/createdAt 过度宽容，恢复非忠实/不唯一

文件：`harmony/chat/src/main/ets/chat/sync_snapshot.ts` `validateNodeDto`（334-354）与 `validateLegacyConversation`（306-332）。

证据（实跑 `/tmp/rev-probe.ts`，只读）：
```
ACCEPTED missing id/createdAt: {"id":"ca7ced79-...","role":"user",...,"createdAt":"2020-01-01T00:00:00.000Z"}
ACCEPTED extra unknown part field
V1 ACCEPTED missing id, node.id= 322e775a-...
```
`parseUIMessage`（`serialize.ts:226`）对缺 `id` 走 `strOr(obj['id'], newId())`、缺 `createdAt` 走 `now()`。因此：
- 归档消息缺 id → 校验期被替换成**新随机 id**，恢复结果与归档不一致（R02「信息不缩水」在消息 id 这一维被破坏）；
- v1 legacy 路径连 `requireIsoTime` 都没有（320 行只 push 序列化结果），非 ISO 的 `createdAt`/缺 `createdAt` 静默通过；
- 节点内消息 id 未做唯一性检查（`requireUniqueNodeIds` 只看 node id），重复消息 id 可溜过。

真实备份由 `serializeUIMessage` 生成，恒带 id/createdAt，故正常链路不受影响；这是防御/保真缺口，不是线上必现。修法（在 `validateNodeDto` 每条消息与 `validateLegacyConversation` 每项，`parseUIMessage` 之前）：
```ts
const mid: unknown = obj['id'];
if (typeof mid !== 'string' || mid.length === 0) {
  throw new Error(`备份消息缺少 id(node ${id} message[${i}])`);
}
requireIsoTime(obj['createdAt'], `node ${id} message[${i}].createdAt`);
```
并在 `validateNodeDto` 内对 `mid` 做本节点 Set 去重即可（消息 id 非 DB PK，无需跨会话唯一）。

### P2-2 降级库下 `delete` 被 `old === null` 提前 return，绕过 R04 的「拒绝写」并静默成功

文件：`harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets` `delete`（540-575）。

链路：`delete` 先 `const old = await this.getById(id)`（547）；降级（`store===null`）时 `getById` 回退 `this.memory`，而 `initializeStore` 失败分支**不重置** memory、通常为空 → `old===null` → 第 548 行 `return;`，永远走不到第 549-553 的 `DEGRADED_WRITE_MSG` 抛出。副作用：墓碑与 gen 已在内存置位（542-544），但磁盘什么都没删；UI 视为删除成功，重启后会话复现。这正是 R04 想消灭的「静默写内存装成功」。

修法：先拿 store 再判断存在性——
```ts
async delete(id: string): Promise<void> {
  const gen: number = (this.deletedGen.get(id) ?? 0) + 1;
  this.deletedGen.set(id, gen);
  this.deletedIds.add(id);
  return this.enqueueMutation(id, async (): Promise<void> =>
    enqueueConversationFileMutation(async (): Promise<void> => {
      const store = await this.ensureStore();
      if (store === null) throw new Error(DEGRADED_WRITE_MSG);   // 先于 old===null
      const old: Conversation | null = await this.getById(id);
      if (old === null) return;
      /* 事务删除体不变 */
    }).catch(...));
}
```
（`save`/`restoreConversations` 的降级抛错路径正确，无需改。）

### P2-3 孤立文件 GC 的「会话引用」核验未 gate 数据库健康态，降级时可能漏引用

文件：`harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets` `scanManagedOrphans`（328）、`deleteManagedOrphans`（367）；调用方 `SettingFilesPage.ets:180/221`。

`referencedUploadPaths`（299）经 `repository.list()/getById()` 取引用。RDB 降级时这些读回退**空内存快照**（`ChatRdbRepository` 各读方法 `store===null → this.memory.*`），`referenced` 变空。registry 与 RDB 相互独立，registry 仍可能 `status==='ok'` → 于是「磁盘存在 ∧ 登记无 ∧ 引用漏检」的文件被判为孤儿。登记表通常覆盖这些文件，但「未登记却被会话引用」正是该扫描存在的意义；二者叠加（registry 健康 + RDB 降级 + 未登记引用文件）即误删。这是 Coordinator 点名的「getById 读失败降级 memory 导致 GC 漏引用」。

修法（最小）：`scanManagedOrphans`/`deleteManagedOrphans` 在取引用前加健康门，例如
```ts
if (AppStorage.get<boolean>('chatStorageDegraded') === true) {
  return { error: '会话库不可用，已禁用孤立文件清理', candidates: [] };
}
```
（`deleteManagedOrphans` 则 `throw new Error('会话库不可用，已取消清理')`）。若嫌 AppStorage 耦合，可给 repository 暴露只读 health。

---

## 三、需设备确认的 P1 候选（当前静态无法证伪，成本极低的对冲）

### P1-a 加密 `initSync` 传空 authTag（官方示例为 16B 占位）

文件：`harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets:40-46`。

`GcmParamsSpec.authTag` 在加密时传 `emptyBlob()`（0B）；`decryptGcm` 传真实 16B tag（正确）。
官方 GCM 文档加密示例先建 **16 字节全 0** 占位 tag（`arr = [0×16]; // 16 bytes`）再 `initSync`，并注明「GCM 模式下算法库目前仅支持 16 字节 authTag」。d.ts 未声明 encrypt init 是否强制 tag 长度。

风险：若设备端在 `initSync(ENCRYPT_MODE)` 校验 tag 长度，则**所有带密码导出**直接失败，且 `encrypt` 包装成 `AES-GCM encrypt failed`。batch-0 的测试用 mock crypto port（`sync_snapshot.test.ts:13`），真机 GCM 往返未跑，无法暴露。

建议（一行、无副作用）：加密侧也用 16B 占位，解密侧不变——
```ts
authTag: blobOf(new Uint8Array(GCM_TAG_LEN)),   // 替换 encryptGcm 里的 emptyBlob()
```
若设备实测空 tag 可过，此改动亦无害。请主代理在模拟器/真机跑一次「带密码导出→再导入」正向+错密码负向后再判定。

### P1-b 编辑/append/手动改名等非 run 路径未纳入 ConversationRunGuard（restore 冲突面）

文件：`harmony/entry/src/main/ets/pages/ChatPage.ets`：`runSendEdit`（2314，`save` 后 `regenerate`）、`sendWithoutAnswer` append（3713-3736）、`startEditTitle` 改名（2021）、`applyToolVerdict`（3400）、pin 切换（4203）。

`reserveConversationRun` 只在三条生成路径（`runRegenerate:1687`、`resumeToolLoop:3415`、`runSendParts:3887`）与 `releaseConversationRunAfterSaves` 中。恢复预检/占用只看 `beginConversationRun` 的活动计数，因此「编辑已落库、`regenerate` 尚未 `reserve`」的窗口内可被恢复插入，随后 regenerate 的终写覆盖恢复结果。`sendEdit` 是先 `save(updated)` 再 `regenerate`，窗口真实存在（网络/await 点）。

另：`maybeAutoTitle`(1900)/`maybeSuggestions`(1957) 在生成结束后**非阻塞**发起，其 `getChatRepository().save(merged)` 不经 `checkpointTail`，故 `releaseConversationRunAfterSaves`（3294）等 `checkpointTail` 并不覆盖这两个写；它们靠「读回 latest 并比对 title/tail」自保（1940-1943 / 1992-1993），恢复后通常因 tail 不同而早退，残留窗口很小但非零。

判定：这是**主代理独占文件**的编排问题，按契约我只给接口建议、不改：
- 最小方案：在 `runSendEdit` 一开始即 `reserveConversationRun`，或复用「先 append 占位、再生成」的既有 `sending` 占位（`sendParts:3869`）语义；
- 或将 `maybeAutoTitle`/`maybeSuggestions` 的最终 `save` 也挂到 `checkpointTail`，使 release 等待包含它们。
其余 `startEditTitle`/pin/applyToolVerdict 属「用户显式即时操作」，与恢复真正并发窗口极小，登记为残留即可，不必强行套 guard。

---

## 四、跨 owner 交界记录（ManagedFileStore，另一 owner 仍改动中，只记录不等）

- 「整库物理缺失当可写首次」：`loadShape`（65）在文件缺失且无 `.quarantined` 标记时返回可写空基准。可接受——`managed_files.json` 不在 `MANAGED_FILE_FOLDERS` 内，不会被本批 GC 删；且删除候选须同时「登记无 ∧ 引用无」，引用扫描是真正兜底。残留：引用扫描仅认 `file://` 前缀（`referencedUploadPaths` + `filePartUri`），非 `file://` 形态的附件 uri 不被保护；当前上传写入即 `file://`，暂不构成问题。
- 「quarantine 后写入永久报错、无显式用户恢复」：确认无任何调用方使用 `AtomicJsonFile.ets:49 clearQuarantineMarker`（全仓仅定义+探针）。`managed_files.json`/Memory/Favorite 一旦损坏，写路径永久拒绝，用户只能清应用数据。与本批「只提供 API 未接线」一致，登记为待接（设置-文件管理页需加「丢弃损坏数据/重新初始化」显式入口）。恢复不携带这些 store，故 `SettingBackupPage` 只清 compact 标记、不清 managed/memory 隔离标记，判断正确。
- 「scan/delete 锁次序 deadlock」：复核为**无锁环**——两函数不获取 `enqueueWrite`/`enqueueConversationFileMutation`，await 只落在 repository 读与 fileIo。无需改。
- P2-3 的健康门需落在这两个函数内或 `SettingFilesPage`，属 ManagedFileStore/页面 owner 范围，请转交。

## 五、未做/边界

- 未跑 hvigor/全仓 lint（按契约主代理统一）；`lint-arkts.sh` 对未改文件亦未重跑。
- 真机 GCM/PKCE、RDB 单事务并发、docPicker cancel 真实返回形态均未验证。
- SettingBackupPage 同时被 batch-1-backup 与 batch-1-files 触及（compact 标记 import/调用），文件归属有重叠，请以主代理最终接线为准。
