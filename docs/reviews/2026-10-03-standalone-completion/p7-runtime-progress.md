# P7 深读实时阶段接线

强制重新生成复用原稿保护：新文章完整生成并保存之前，仓库中的旧文章仍为 COMPLETE，因此缓存 phase 不能表示当前任务进度。本次只给现有活动作业增加阶段和说明，不持久化虚假进度、不新增 store、不轮询、不显示估算百分比。

`run_manager.ts` 的 `onProgress(stage,label)` 来自真实边界：prefetch 前 COLLECTING/收集来源；模型结构规划前 PLANNING/规划结构；每个 runStage 前 WRITING/当前分段名称；最终校验保存前 VERIFYING/校验并保存；完整新稿保存成功后才 COMPLETE/已完成。阶段失败和取消都不会从旧缓存推导新任务完成。

`scheduler.ts` 在既有 runningJobs 保存 stage/label，并通过 `getActiveRuns`/`observeActiveRuns` 投影。准入时 IDLE/准备运行，结束后按原逻辑移除。第四参数传给 factory 的 callback 检查当前 token 与取消信号；返回的 manager 也由同一归属检查包装，因此迟到 factory、取消后模型和前一轮回调不能更新新任务。保留外部 callback，DI 已透传同一 callback 时不重复通知。

`AppContainer.ets` 只新增授权的一行 `onProgress: options?.onProgress`。UI owner 消费现有活动作业字段，独立 reviewer 复查实际 force、取消和迟到归属。本任务未改其他 DI、小说生产代码、存储协议或分段生成协议，也未构建或操作设备。

新增实际 scheduler → RunManager 回归先 RED 后 GREEN：

1. 强制重生成的 collection/planning/four-section/final-save 阶段发布时旧缓存始终完整；阻塞最终写入时为 VERIFYING，没有提前 COMPLETE；保存成功才 COMPLETE，随后活动项移除。
2. factory callback 在取消后、下一轮当前 owner 活动期间、结束后均不能再发布旧进度。

组合 `scheduler_progress`、`scheduler_signal`、`scheduler_start_failure`、`deepread_structured_pipeline`、`deepread_template_library`：34/34。实际 Entry factory 5/5。DeepRead 与 Chat `tsc --noEmit` 通过；run_manager/scheduler/AppContainer scoped ArkTS lint 0 errors / 253 warnings。

日志：`/tmp/amber-p7-progress-{red,green,focused,entry,tsc,chat-tsc,lint}.log`。源码冻结。

P8 回归收口：阶段更新曾复用 `publishRunning`，让 `observeRunning` 在一次任务中额外发布七次 true。保留旧 `product_p1` 的精确 `[false,true,false]` 断言，先重现后仅抽出既有活动作业通知为 `publishActiveRuns`；准入/结束仍调用 `publishRunning`，阶段 callback 只通知活动作业订阅。`product_p1`、`scheduler_progress`、实际 `hotlist_board` 组合 24/24，DeepRead 类型检查通过，scheduler lint 0 errors。日志 `/tmp/amber-p8-progress-boolean-{red,green,tsc,lint}.log`。此修正没有改测试预期或引入新事件机制。
