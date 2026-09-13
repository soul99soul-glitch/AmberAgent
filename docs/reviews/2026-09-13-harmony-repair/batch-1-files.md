# Batch 1A 实施结果(文件/记忆/收藏持久化 + 安全 GC + compact 清理)

日期: 2026-09-13  分支: harmony-preview  基线: bdce551dd

## 一、修改文件(均为本批次独占文件)

| 文件 | 修复 ID | 变更概要 |
|---|---|---|
| `harmony/entry/src/main/ets/platform_impl/AtomicJsonFile.ets` | R10/R11 支撑 | 新增读三态 `readGuardedJson`(ok/missing/io_error/blocked)、隔离标记 `.quarantined` 写入/存在性/显式清除;`quarantineIfStillInvalid` 顺序改为「先落标记再改名」 |
| `harmony/entry/src/main/ets/platform_impl/MemoryStore.ets` | R10 | 读路径降级为空(不写回,不丢数据);写路径统一走 `requireArrayForWrite`,缺失=首次/损坏=拒绝;dry-run 计划写入改严格;候选更新改严格;dream plan 5 个写方法改严格 |
| `harmony/entry/src/main/ets/platform_impl/ManagedFileStore.ets` | R10 + UI-F20 + R11 | 读/写共用 `requireHealthyShape`(缺失=空表,损坏/IO=抛错);新增健康检查 `checkManagedFilesHealth`、会话引用 `referencedUploadPaths`、只扫描 `scanManagedOrphans`、确认后执行 `deleteManagedOrphans`、`MANAGED_FILE_FOLDERS`;`cleanupDeletedConversation` 补 compact 清理并隔离收藏级联失败 |
| `harmony/entry/src/main/ets/platform_impl/FavoriteStore.ets` | R10 | `decode` 拆为 `decodeStatus`(ok/missing/blocked/io_error);写路径 `requireWritable` 在损坏/读失败时抛错;KV get 自身异常显式区分;新增 `getFavoritesStatus` |
| `harmony/entry/src/main/ets/platform_impl/EntryCompactStore.ets` | R11 | 新增删除标记 `compacts/<id>.deleted` + `deleteConversationCompactData` + 显式 `clearConversationDeletedMarker`;`getCompacts/insertCompact/recordEvent` 拒绝已删除会话;写后复核撤回竞态迟到写 |
| `harmony/entry/src/main/ets/pages/SettingFilesPage.ets` | UI-F20 | `reload` 记录单 folder 失败为 `registryError`;`cleanOrphans` 改为「健康检查 → 预览候选 → AlertDialog 确认 → 再核执行」;新增错误态 UI 与清理按钮禁用 |

未改动(他 owner):ChatPage.ets、AppContainer.ets、index.ts、ChatRdbRepository.ets、persistence/sync。

## 二、最小机制与关键决策

1. **错误读拒绝覆写(R10)**:所有 JSON 持久化的读基准分三态——`missing`(真首次,允许写空基准)、`blocked`(解析/结构失败,已隔离)、`io_error`。后两者写入一律抛错,不再以空快照覆盖。
2. **缺失 vs 损坏的持久区分**:损坏文件改名 `.bad` 后原路径会「消失」。若不加标记,下一次读会当首次初始化并覆盖。故 `quarantineIfStillInvalid` 先原子写 `.quarantined` 标记再改名;`readGuardedJson` 在文件缺失时检查该标记,命中即 `blocked`。清除标记仅经显式 `clearQuarantineMarker`,读路径绝不自动清除。
3. **读操作与写基准分离**:MemoryStore 的召回/列表是只读路径,损坏时降级为空(不写回,数据仍保留在 `.bad`,不会把读失败升级成发送链硬错误,`chat_turn.ts:212` 无 catch);只有写基准严格抛错。ManagedFileStore 的 `listByFolder` 会把错误暴露给 UI,这是 F20 判定错误态所必需(SettingFilesPage 捕获为 `registryError`)。FavoriteStore 的 `listFavorites` 兼容返回空数组,写路径严格。
4. **安全 GC(UI-F20)**:登记库必须 `status==='ok'` 才允许清理;候选 = 磁盘存在 ∧ 登记无 ∧ 全部会话引用无。扫描前后各取一次健康登记(执行时再核)。删除只能经 `deleteManagedOrphans`,内部再次核对,任何登记读取失败全量拒绝。UI 先展示前 5 条候选+总数再确认。没有引入完整 trash/回收站框架,也没有只加确认框。
5. **compact/events 清理 + 防复活(R11)**:`cleanupDeletedConversation` 经 `enqueueConversationFileMutation` 串行,新增 `deleteConversationCompactData`(删除标记 + 删两键)。`EntryCompactStore` 写路径先查标记,写后再复核一次,保证 delete 在「检查」与「写入」之间落地时撤回迟到写。删除标记仅在显式授权重建时清除。
6. **锁与 deadlock**:复用现有 `enqueueConversationFileMutation`、`EntryCompactStore` 同会话写队列、`MemoryStore`/`ManagedFileStore` 写队列、`FavoriteStore` 写尾。未新增锁;`deleteConversationCompactData` 不做 async 队列内自等待,避免与调用方持有的队列互锁。收藏级联失败被捕获(不中断删除编排),避免「收藏损坏导致整个删除失败」。

## 三、回归测试

新增独立探针(真实 .ets 源码 + 内存 mock FS/KV,无真实文件/设备/网络):

- `probes/probe-batch1-r10-r11.cjs`(7 组 R10 + 5 组 R11):瞬时读失败拒绝且旧记录保留、损坏后写拒绝且标记粘滞(不再当首次)、真首次仍可写、Managed 读损坏抛错、Favorite 损坏不覆写、compact 删除+标记、迟到写不复活、在途写与删除竞态被撤回、收藏损坏不阻断 compact 清理。
- `probes/probe-batch1-f20.cjs`(T1–T5):损坏/缺失/瞬时读失败 → 扫描 error 且零候选零删除;健康库候选只含真孤儿(登记内有与「未登记但被会话引用」均保留);无孤儿时零候选。

运行结果:

```
node docs/reviews/2026-09-13-harmony-repair/probes/probe-batch1-r10-r11.cjs   # exit 0
node docs/reviews/2026-09-13-harmony-repair/probes/probe-batch1-f20.cjs       # exit 0
```

日志:同目录 `probe-batch1-r10-r11.log`、`probe-batch1-f20.log`。

静态检查(逐文件 `harmony/scripts/lint-arkts.sh`):6 个改动文件均 `No defects found in your code.`。未运行 hvigor/全仓 lint(按契约由主代理统一构建)。

## 四、共享接线需求(需主代理)

1. **R11 删除标记的清除入口**:`manifest/AppContainer` 无需改;但 R09 备份恢复(他 owner)若要让旧会话 compact 恢复可用,应在显式恢复路径调用 `clearConversationDeletedMarker(kv, id)` 与 `clearQuarantineMarker(path)`。本批次只提供显式 API,不自动清除,避免在途摘要复活。
2. **SettingFilesPage → chat repo**:页面新增 `getChatRepository()` 依赖(文件末尾 import 自 `../di/AppContainer.ets`),用于会话引用核验。该 import 属既有公共单例,不构成循环;若主代理希望页面不直接依赖 AppContainer,可改为注入,但需要容器配合。
3. **共享 UI 错误提示需求**:
   - 记忆页(MemoryPage)写失败已有 toast(「记忆保存失败」);读失败不会抛错,无需新提示。
   - 收藏页(FavoritePage)写失败已有 toast(「收藏失败:…」);损坏时错误消息为「收藏数据损坏,已阻止写入以保留原值(需导出/恢复后处理)」,目前只会显示在删除/新增的 catch 里,建议后续在页头展示一次性横幅提示。
   - 设置-文件管理页已就地展示「登记库不可用」错误态 + 禁用清理;无需全局提示组件。
4. **需要 repo 接口协调**:`scanManagedOrphans`/`referencedUploadPaths` 依赖 `ConversationRepository.list()/getById()`(既有),未新增接口,无反向 import 新循环。

## 五、未解决点 / 交给后续批次

- 隔离标记的生命周期管理(用户在设置页「丢弃损坏数据/重新初始化」的显式入口)未接线,仅提供 API;`MemoryPage`/`SettingFilesPage` 未加该按钮。
- `deleteManagedOrphans` 使用 `fileIo.unlinkSync`(检查后同微任务内同步删除,消除 await 窗口)。
- 未做设备/模拟器实测(本批次契约要求主代理统一验证),不声称 UI 已通过截图验证。

## 六、GC 精准加固(第二轮,仅 ManagedFileStore + 其 probe)

针对「重新读快照后逐 await unlink,中间可 save/register 新引用」的窗口,补到正确而非只避死锁:

1. **引用快照 + 删除临界段入既有锁**:`deleteManagedOrphans` 把「refresh 登记 → `referencedUploadPaths` 会话引用快照 → 逐条删除」整段置于 `enqueueConversationFileMutation` 内。已先核锁依赖:
   - `ChatRdbRepository.save/delete/restoreConversations` 均在 `enqueueConversationFileMutation` 内执行(R09/R03 同锁),故临界段内无新会话引用落库;
   - `repository.list()/getById()` 只 `ensureStore`,**不取该锁**,临界段内调用不会自嵌套死锁。
2. **登记写不入同锁 → 逐条复核 + 同步删**:`ManagedFileStore` 的登记写(`insert/update/delete*`、`trackUploadFile` 调用链)走独立 `enqueueWrite` 队列,不受 `enqueueConversationFileMutation` 约束。因此每条 `unlink` 前用 `loadManagedRegistryForCleanup()` 重读最新登记,命中即跳过;检查后**不再 await**,直接 `fileIo.unlinkSync`,同一微任务内无其他写者插入。
3. **降级拒绝**:`referencedUploadPaths` 在 `repository.list()` **前后各查一次** `AppStorage('chatStorageDegraded')`(首次查询时 RDB init 可能失败并在此期间置 true),任一为 true 即抛 `DEGRADED_REF_ERROR`;`scanManagedOrphans` 转为 error 结果、`deleteManagedOrphans` 抛错,均拒绝删除。已核该标记仅由 `ChatRdbRepository` 的 init 失败路径写入(`ChatRdbRepository.ets:195/199`)。
4. **planned 路径最小校验**:新增 `isSafeOrphanPath`,仅允许 `MANAGED_FILE_FOLDERS` 内的**单层**文件名,拒绝 `../`、`..`、多级路径、反斜杠与空名。`deleteManagedOrphans` 对 planned 先过滤,非法项一律不删(外部入口防御,不依赖 UI 供值)。

新增 probe 情景(原 `probe-batch1-f20.cjs` 追加 T6/T7,无新框架):
- T6:`isSafeOrphanPath` 正反例 + 恶意 planned `../outside.png` 不删任何文件。
- T7:`chatStorageDegraded=true` → 扫描返回 error、删除抛错、文件保留。

