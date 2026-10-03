# P5 润色领域闭环

2026-10-03；E05/D12/D13。实现沿用现有 durable polish job、项目锁、CAS、owner claim、章节版本、ledger 和原子 receipt，没有新建存储或事务机。未在此子任务执行构建或设备安装。

## 对照实际 iOS

`NovelSessionViewModel.startBatchPolish/runBatchPolish/retryFailedBatchPolish` 按选定章节串行生成和采用，分别记录成功、剧情漂移跳过、失败与未处理。普通失败继续后续章；重试失败和未处理章，保留成功/漂移跳过结果。`NovelInjectionPlanner` 注入的是项目 `polishPreference`；`NovelBatchPolishSheet` 将项目空偏好显示为未设置，没有全局偏好回退。

## 最小领域实现

- 新任务在 repo start 锁内冻结 `polishPreferenceAtStart`。writer 和 reviewer 请求都携带该字段；恢复/后台继续/定向重试沿同一 job 使用原值。旧 `loadPolishPreference` 参数为既有调用源码兼容保留，runner 不再调用可变的全局读取器。
- `creation.previewPolishSelected(projectId, chapterIds, options)` 返回 CAS、实际目标和完整冻结上下文；`startPolishSelected(..., expectedCas?)` 从同一快照选章，并在模型验证等待后把原 CAS 传入 repo。`startSelectedPolish(ordinals)` 与旧范围入口保留。
- `polish_context.ts` 是 preview/start 共用的纯冻结 helper。实际使用 P3 `classifyNovelMaterials`，保存入选文本中的 aliases/tags，smart 依据所选正文、计划与剧情；off 被排除，但本分支覆盖优先保护。always/override 事实超过现有容量时明确拒绝开始，不静默裁断。可选人物开关控制相关的可选人物资料，强制事实仍入。
- `outcomes` 每个目标一项：`success / driftSkipped / failed / unprocessed`。review 阻塞以漂移跳过记录；网络、模型参数/JSON/候选错误和未落盘的章节采用失败以失败记录。持久结果推进仍检查当前 CAS 和 claim；失去分支、CAS 或 owner 不得借逐章失败继续覆盖。
- `polish_outcomes.ts` 提供独立的结果投影和目标选择。成功仍保留原 commit receipt；结果推进/失败只更新现 jobs 私有文件。成功采用仍在现有原子快照中同时保存正文、旧版本、receipt、job cursor 和结果。
- `retryPolish(projectId, jobId, chapterIds?)` 对同一任务的失败/未处理目标明确重试。未传 IDs 选择所有失败/未处理；显式集合不得含成功或漂移跳过。原始 targets 不变，`retryChapterIds` 限定此次队列，成功章不会再执行。再次失败也保留可重试结果。任务结束后若另一 durable job 已启动，拒绝抢占重试。
- 后台 `maxChapters` 计入成功、失败及跳过的已处理章，避免一个后台时间片越过多章失败。取消仍保留尚未处理结果，恢复现有 suspended stage；实际重试从明确选择的新候选开始。
- v1 durable 字段为兼容旧备份的可选扩展。旧 progress 按原 cursor 规则验证；新 outcomes 的成功项与 progress receipt 按章节 ID 对应，不假定所有前序章成功。校验不允许 cursor 指向已处理目标。

## 实际验证

`novel_polish_completion.test.ts` 九个真实 repo/runner 场景：

1. 不连续选择 1/3/4：第一章 provider 失败、第三章漂移跳过、第四章成功；后续同 durable job 仅重试第一章，成功/跳过章不重复，旧版本只增加实际成功次数。
2. 项目偏好冻结 → 暂停 → native backup → restore → resume → writer/reviewer 实际请求；全局读取回调调用次数为 0。
3. 预览与 selected-ID 开始使用相同上下文；aliases/tags 进入 smart 判定，off override 实际保护，无关 smart 人物排除。
4. 旧预览 CAS 和超容量必需事实均拒绝，未创建任务。
5. 首章失败消耗一个后台章数配额；取消留下未处理章；只选择其中一章重试，其他结果保留。
6. 外部更新真实正文后恢复旧 durable job，claim/start/retry 仍被 CAS 拒绝，没有模型请求或正文覆盖。
7. 独立 reviewer 发现旧 native job 的显式重试选择曾被 legacy 分支忽略；现在仅显式选择路径使用已有结果投影升级，实际只跑作者选择的第三章，未做全量迁移。
8. 达两次重写上限后将当前章记为失败，继续下一选章并成功采用。
9. 独立 reviewer 第二个反例为旧 native 成功 receipt + 冷启动漂移审核：现 repo 沿同一结果投影对旧/新任务一致记录漂移跳过，继续下一章，保留旧成功 receipt 与真实版本。

已有 domain/runner/repository/creation 润色组与上述测试共 54/54；standalone defaults 中项目偏好调用测试 3/3。deepread/chat tsc 通过；7 个变更生产文件 ArkTS lint 0 error（4644 warning）；diff check 通过。

最终全小说领域测试 497/497 通过，覆盖同期普通 run 变化。独立 reviewer 的五组聚焦测试 54/54 与四个原反例 4/4，共 58/58 通过，结论为 passed；旧成功 receipt、新 outcomes、非连续重试跨四次 native 恢复均保留，成功章没有重复采用。两个精准兼容修复涉及的 polish.ts/repository.ts 再次 lint 0 error。root 保存构建与设备验收结果。

日志：`/tmp/amber-p5-polish-tests.log`、`/tmp/amber-p5-polish-defaults.log`、`/tmp/amber-p5-polish-tsc.log`、`/tmp/amber-p5-polish-chat-tsc.log`、`/tmp/amber-p5-polish-lint.log`、`/tmp/amber-p5-polish-reviewfix-lint.log`、`/tmp/amber-p5-polish-all-novel.log`、`/tmp/amber-p5-polish-review-probes.log`。
