# Phase1 Novel 独立复审

结论：通过。指定4个源码文件与新增回归测试没有发现需要阻止下一阶段的问题。审查对照 `/tmp/amber-harmony-review-20261002-baseline`，并核对 `audit-novel.md`、`phase1-novel.md`；只读代码，仅写本报告。

## 原始缺陷与新增缺陷的二次确认

| 问题 | 修复边界与复核 | 结果 |
|---|---|---|
| NOV01 项目模型切分支后不可读取 | `repository.ts:797-811` 只移除 project-level modelPolicy 的 branch head 对比。章节记录、资料、会话、资料建议、设置提案、版本、章节剧情、讨论归档、branchSettings、revision 均继续比较；正文仍独立校验 tree digest。switch 原有保留项目模型设置行为保留，没有插入 metadata commit | 通过 |
| NOV02 rewriting 不能 pause/fail/cancel | Ghostwrite 使用 `resumeStage ?? stage` 计算活跃/暂停/失败的 candidate attempt；cancelled 只豁免“当前执行进度的 attempt 相等”条件。候选合法 attempt 范围、摘要、plan、ordinal 和 review 绑定继续校验，终态候选保留供既有查看使用。Polish 对取消应用同一边界 | 通过 |
| NOV03 作者改稿后 CAS stale 不能取消/failed 收口 | loadBound 的 requireCas=false 仅在 fail/cancel 两条收口路径使用，job 类型、project/branch 绑定继续校验。Ghostwrite fail 与提供 claim 的 cancel 保留 token/epoch/lease 校验；null claim 只允许无有效 owner，终态重复取消拒绝。Polish 原有用户主动 cancel 不要求 claim，此授权模型没有改变。真实内容 checkpoint/commit、claim/resume/retry 仍走默认 CAS 校验 | 通过 |
| NOV04 pause 后立即 resume 无 driver | 原 Set 换成 runner Promise Map。resume/retry await 旧 driver；旧 promise finally 清 key 后才恢复任务及启动新 driver。没有 timer retry、新调度器或额外 provider fallback；旧 callback 不能落新 epoch 状态的回归同时覆盖 ghostwrite/polish | 通过 |
| NOV05 连续编辑 undo 恢复过早快照 | `repository.ts:1296` makeUndo 时从 preservedPrivate 排除正要重写的当前分支 undoPath，避免旧 passthrough 后写覆盖刚生成的新 undo。clearUndo 行为保持，其他分支 undo/private 状态仍保留，仍为单层撤销 | 通过 |

## 独立红绿验证

将正式新增10个测试复制到 `/tmp/amber-phase1-novel-review-baseline.test.ts`，仅把 source import 改指基线快照，不改测试条件或断言，不改基线源码。对基线运行得到0/10通过；失败包含 candidate progress mismatch、无法启动第二个 model request、workspace CAS 拒绝收口、branch state/head mismatch，以及连续改稿 undo 恢复到空章节（旧更早快照）。

其中 Reader CAS 测试在基线先命中“错误 claim 应报 lease”的预期不符，因为基线在检查 claim 前就拒绝 CAS；不能把这个错误文字顺序本身当新产品缺陷。实际取消无法收口已由原 audit 独立复现。当前测试验证了真实写入拒绝旧 CAS 和有效/null/错误 claim 三种控制边界。

当前源码运行正式10个回归加代表性 branch/job 测试：

```bash
# cwd: harmony/deepread
npx tsx --test src/test/novel_audit_regressions.test.ts \
  src/test/novel_ghostwrite_repository.test.ts \
  src/test/novel_polish_repository.test.ts \
  src/test/novel_workspace_history_repository.test.ts \
  src/test/novel_creation_ghostwrite.test.ts
```

结果45/45通过，0 fail、0 skipped、0 todo，退出码0。代表测试仍证明：old epoch/expired lease 拒绝、CAS drift 写入拒绝、candidate/review错绑拒绝、提交原子与receipt replay、branch隔离、磁盘漂移拒绝、单步undo追加commit、活动作业互斥。

## 精准性与验证界限

没有新增历史格式、metadata commit、model fallback、定时重试或大范围重构。取消终态豁免执行进度相等是必要边界，不豁免候选自身身份/内容校验。Promise Map 复用已有 runner 生命周期；await 的等待时长仍由现有 runner/模型取消机制决定，没有新增延迟重试。

本次验证属于纯领域、受控模型与内存 FileStore。ArkTS 构建和真实设备/云模型/后台验收由主 agent 集成阶段执行，不从纯领域结果外推这些门槛。


## 追加 NOV06 废弃章序号复审

结论：通过，真实缺陷修复，不是永久保留所有历史序号的新增设计。

原 startGhostwriteJob 跳过 discarded 章节计算 maxOrdinal，与作者保存/新建收录已使用的 nextNovelChapterOrdinal(project.chapters) 不一致。discarded 仍是可恢复的项目章节；新代笔若复用它的 ordinal，恢复同标题旧章会使两章公有路径相同，后续 open 报“工作区台账损坏”。这条路径通过 start → claim → writing → candidate → review → commit → 恢复废弃章 → open 实际复现，未依赖人工篡改。

源码仅增加既有 helper import，删除跳过 discarded 的局部 max 循环，并传入 helper 返回的 startChapterOrdinal；models.ts helper没有改变。它包含当前仍存在的 discarded 章节、兼容旧 id/ordinal 解析，删除最高章后允许按既有规则复用；没有引入永久 reservation ledger、UI筛选变化或排序变化。

独立红测：更新 /tmp 基线测试副本后执行 `npx tsx --test --test-name-pattern='代笔保留废弃' /tmp/amber-phase1-novel-review-baseline.test.ts`，1/1 FAIL，实际错误为“工作区台账损坏”。当前重新运行上面相同代表测试集合，新增回归总数11，全部集合46/46 PASS、0 fail/skip/todo。新增用例同时验证同标题旧/新章内容保留、最终 ordinal 为[1,2]、冷读成功。

此追加没有发现新的阻塞问题，不要求扩展防御分支或额外兜底。
