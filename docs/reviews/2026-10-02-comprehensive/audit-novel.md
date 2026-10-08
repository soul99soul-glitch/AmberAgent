# Novel audit — 2026-10-02

范围：HEAD 5ea51f7a 加当前未提交实现；novel 领域、NovelCreation/Repository、entry 小说业务调用。已读 harmony-arkts、2026-09-30 功能完成计划与 current-parity，未将已记录的可选 iOS 功能或平台边界当缺陷。

二次复核后的真实问题：

1. **P1 — 项目模型设置后切分支使项目无法再读取。** `repository.ts:2047` switch 把当前项目 modelPolicy 覆盖到目标分支，但未追加 head；`repository.ts:802,959` 读取将 modelPolicy 与目标旧 head 比较。生产路径：创建分支 → 在 fork 项目设置切换固定模型 → 切 main，switch 返回成功，随后 open 报 `branch state 与 head 不一致`。这是数据一致性缺陷，不是设计差异；三职责模型属项目设置，切分支应继续保留。独立 memory FileStore 实测复现。
2. **P1 — 代笔重写阶段不能暂停、取消，provider 失败不能正确落为 failed。** `ghostwrite.ts:371-375` 只在 active rewriting 判断旧 candidate.attempt = rewriteCount - 1；暂停/失败/取消后仍保留此候选却以 rewriteCount 校验。`repository.ts:1090` 在保存前严格校验，三个动作皆抛 `candidate 与当前代笔进度不匹配`，旧作业停留 rewriting 阶段。生产 reader/workspace 作业控制提供暂停和取消；provider 失败 catch 同样调用 failGhostwriteJob。域测试分别实测 pause/fail/cancel，无存储损坏（保存前拒绝），但控制失败、后台可能卡住。
3. **P1 — 普通正文编辑推进 CAS 后，代笔永久无法取消。** Reader `NovelChapterReaderPage.ets:340-342,444` 的编辑入口只检查页面 busy，不检查 durable job；`creation.ts:saveChapter → commitMutation → repository.ts:1526` 接受编辑；随后所有 job control 经 `repository.ts:1470/1477/1108` 对 workspace CAS 校验，取消也被拒。实测运行 job → creation.saveChapter 旧章 → cancelGhostwrite 抛 `工作区已变化，拒绝覆盖`。真实内容写入拒旧 CAS 是正确设计；取消/失效收口也必须有路，不能把 stale 作业永久锁住。fork/switch 的活动锁在 `repository.ts:1121` 把该作业视为未结束。
4. **P2 — 暂停后立即继续可落到没有 driver 的 writing。** `creation.ts:1435,1492` driving Set 在旧 promise finally 前保留 key；resume 调用 launch 被 Set 拒绝，旧取消回调 30ms 后返回后才清 key，未再次启动。真实 Chat cancel 是异步 abort，UI 暂停请求返回后已可点击继续。独立挂起模型实测：pause → resume → 100ms，requests=1（期望2），durable writing+claim:null。ghostwrite/polish 同一生命周期结构；需保证旧回调不能写新状态，也不能吞掉新 owner 的启动。

红测试：`harmony/deepread/node_modules/.bin/tsx --test /tmp/novel-audit-red.test.ts`，当前 4 tests / 0 pass / 4 fail。关键输出分别是上述 candidate mismatch、requests 1 !== 2、workspace CAS changed、branch state/head mismatch。只读复现脚本 `/tmp/novel-audit-repro.ts` 另行分别检验 rewrite pause/fail/cancel。

明确排除：

- ghostwrite 新章没有显式 ordinal 并非编号缺陷：`novelChapterOrdinal` 从 `chapter-008-ghostwrite-*` 正确解析，导入旧章 ordinal7 后生成新章的显示及文件路径均为8；实测通过。
- 失败停点重试、普通运行无远端 response cursor、公开交换不含私有运行历史、iOS 可选差距：在既有计划中明确，是产品策略/平台边界。
- 验证仅纯领域和源码生产调用；未以这些结果宣称真实云模型或系统后台验收通过。

Phase1 回归追加确认第五项 **P1 — 连续编辑的单步 undo 回退到过旧稿**：installSnapshot 保留旧 undo 进入 passthrough，写入新 undo 后又被旧 bytes 覆盖。新增原稿→第二稿→第三稿→undo 红测得到空项目；makeUndo 排除当前分支旧 undo 回写后一章第二稿。修复与验证见 phase1-novel.md。

追加确认第六项 **P1 — 代笔重用废弃章号，恢复旧章可损坏台账**：startGhostwriteJob 的 max 循环跳过 discarded，与既有 nextNovelChapterOrdinal 的全部保留章占位规则冲突。唯一废弃第1章后启动代笔仍从1写，完成后旧章恢复且同标题会生成重复文件路径/changedPaths，下一次 load 报 `工作区台账损坏`。完整 start/claim/write/review/commit/restore/open 红测复现；启动点改复用现存 nextNovelChapterOrdinal 后通过。
