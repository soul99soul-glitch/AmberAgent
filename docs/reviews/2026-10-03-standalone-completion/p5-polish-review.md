# P5 润色领域独立审查

2026-10-03。范围 E05/D12/D13：项目润色偏好、任意选章、逐章结果、同任务失败集重试、真实 CAS/claim/receipt、旧新 native 任务恢复与资料冻结。生产代码只读；本报告和 `/tmp/p5-polish-review-probes.mts` 由独立 reviewer 维护。UI 尺寸、系统调度及设备 provider 验收由另外的 P5 reviewer/root 负责。

## 当前结论

**本领域审查通过。** 两个独立复现的问题 R1/R2 均由实现 owner 精准修复，原反例与最终聚焦测试 **58/58 通过**。未发现剩余的润色领域或持久调用链阻断问题；本结论不覆盖另外负责的设备/UI gate。

## 实际调用链与事实

- iOS 当前 `NovelSessionViewModel.startBatchPolish/runBatchPolish/retryFailedBatchPolish` 串行处理作者所选 ID，普通章级失败继续下一章，报告态只重试失败与未处理的章节；项目偏好是实际来源。Harmony 新任务沿 `NovelPolishPage.preview/start/retry → creation.previewPolishSelected/startPolishSelected/retryPolish → repository.startPolishJob/retryPolishJob → createPolishRunner` 执行，选章预览冻结 CAS，启动在 model validation await 后仍由 repo 比对同一 CAS。
- `polish_context.ts` 是预览与持久启动共用 helper，目标按实际 ordinal 排序，保留非连续章节选择；项目偏好在 repo 锁内冻结到 job。writer/reviewer 的实际 JSON 输入均带 `polishPreferenceAtStart`，runner 不调用可变全局读取器。既有 DI 参数保留兼容，不影响真实来源。
- 实际 request 冻结原章文本/digest、分类资料内容及 sourcePath；使用 `read_only` profile。alias/tag 与受保护的本分支 off override 能进入冻结文本；人物开关关闭时仍保留必须保护的事实。必需事实超出现有容量时明确拒绝创建 job，未静默截断。
- 新任务章级 provider/JSON/审核/提交失败沿现 `recordPolishChapterResult` 推进；blocking review 分类为 `driftSkipped`，重写上限分类为 `failed`。CAS 或 owner 改变仍是任务级停止条件，不通过逐章失败绕开。
- 成功采用仍通过现 `installSnapshot` 单次原子安装正文、旧 chapterVersion、ledger、receipt、job cursor/outcomes 及下一 CAS。重试保留同一 job/原冻结 source，已有成功章 receipt 不再采用；进度以目标 ID 对应 success outcome，允许非连续成功与失败重试后的 progress 顺序。
- native 恢复沿现 jobs store、inspect/install 与 receipt 绑定校验，无第二任务 store。独立混合探针已证明旧任务成功 c1 → 新 runner 失败 c2 → native 冷恢复成功 c3 → 再次 native 冷恢复只重试 c2 → 第四次 native 冷恢复，progress 恰为 c1/c3/c2，旧 c1 正文只润色一次，章节版本总计 3。

## 发现与复验

### R1：旧 native failed 任务忽略显式重试 ID（已关闭）

有效旧 v1 job 缺少新增 optional outcomes 字段，失败 cursor 指向 c1；作者显式选 c3 重试时，`retryPolishJob` 原来只恢复旧 cursor，静默重跑 c1。真实 native inspect/install 后独立探针复现。

实现现将「已含 outcomes 或显式传入 chapterIds」统一交给现 `retryPolishOutcomes`，旧数据由 `projectPolishOutcomes` 投影，不新增迁移存储。无显式 ID 的旧恢复路径保留。原反例已独立通过；永久实际 runner 回归也已落到 `novel_polish_completion.test.ts`。

### R2：旧 native 恢复后的漂移仍停止整批（已关闭）

真实步骤：任务先成功采用 c1，并 waiting_system 停在 c2；剔除新增 optional 字段得到有效旧 v1 jobs；native backup/inspect/install 后 resume；c2 的真实 reviewer 返回 blocking hard_continuity。

`repository.checkpointPolishReview` 原判断 `assessed.stage === 'failed' && loaded.job.outcomes !== undefined`，导致旧任务保留整批 failed，runner 停止 c3，漂移也被投影为 failed。同样的旧任务 provider failure 已能通过现结果投影继续，与此链不一致。独立探针要求最终 `[success, driftSkipped, success]`，实际在 failed 停止。

实现已移除 outcomes 存在条件：审核失败无论旧新 optional 字段，均调用现 `recordPolishChapterResult`，由 `projectPolishOutcomes` 保留原成功 receipt 并生成结果，无额外存储或重试层。独立原反例现得到 `[success, driftSkipped, success]`，progress 只含 c1/c3；永久旧 native 冷恢复场景也已加入 `novel_polish_completion.test.ts`。

## 验证记录

最终独立运行五个聚焦 polish suites **54/54**，加四个独立 probes **4/4**，合计 **58/58 通过**。日志 `/tmp/p5-polish-review-tests.log`；独立 probes `/tmp/p5-polish-review-probes.mts`。覆盖非连续选章、逐章 failure/drift/rewrite exhaustion、取消后未处理选择、失败集合重试、预览 CAS 过期拒绝、旧新结果跨 native 冷恢复、原成功 receipt 不重复采用、请求 frozen preference/alias/tag/off override，以及已有的 claim/lease/receipt/原子提交回归。

本报告的 domain/mock model 执行实际 creation/runner/repository/native storage 链；不能替代新包设备布局、WorkScheduler 系统触发或外部 provider 的验收。
