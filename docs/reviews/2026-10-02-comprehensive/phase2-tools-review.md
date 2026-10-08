# Phase 2 tools 独立复审

日期：2026-10-02。审查者未参与实现；本轮只写此报告，不修改生产代码或测试。已读取 harmony-arkts skill，并对照 `/tmp/amber-harmony-review-20261002-baseline/`、`audit-tools.md`、`phase2-tools.md` 检查 T1–T4。Council manager 只审查 wait；runRound/runSeat/synthesize 由另一独立报告负责。

结论：**T1–T4 修复通过本轮独立复审，没有发现需追加修复的实质问题。**

## 逐项二次复核

| 项目 | 本轮实际复核 | 结论 |
| --- | --- | --- |
| T1 搜索取消 | search_web/scrape_web 把该次调用 signal 传至 orchestrator → configured/free service → HTTP；aggregator 同样传 context。取消和原生 AbortError 在归一为来源失败之前重新抛出，无法进入 WebView fallback 结果生成。free-engine 已取消入口不发请求；取消失败不更新 breaker，真实403仍更新。 | 通过。没有修改共享 SDK signal、搜索策略、并发数或新增 retry。 |
| T2 技能管理刷新 | catalog 每次按 `rt.assistant.id` 找持久化助手，现读 enabledSkills/SkillManager.listSkills，再重建技能 primitives 与完整 registry；RunToolCatalog 仍对新增技能工具执行现有 profile 过滤。真实 entry 测试执行 enable → refresh → discovery/use、import → list/use、disable → 拒绝；同表先放另一个助手，仍正确读取 owner。检查 getChatAssistants/mutateAssistant 的实际KV读取路径。 | 通过。provider/model/temperature 和其余 primitives 保持原运行快照，没有把 UI 当前助手引入运行。 |
| T3 补交报告审批 | 首轮与 report retry 后都检查最终 pending tool；末轮检查先于 resultOrFallback。liveParts 包含真实 pending，liveText 仍为首轮可见答案。复跑原 manager 的 approval_required → INTERRUPTED/保留cancel用例。 | 通过。不增加报告重试；审批不会误记 completed。 |
| T4 父等待停止 | 两种工具都传父signal至 manager.wait。subagent wait 在每次原有200ms sleep中竞争 abort Promise，并在 finally 移除listener；council wait 在 finally 清理deadline timer与listener。两者均未调用独立子任务cancel或改其状态。 | 通过。父方立即拒绝，独立子任务保持 running。 |

Subagent wait 继续复用原 `deps.sleep(200)` 端口。abort 后最后一次已开始的睡眠可以自行结束，最迟200ms；不会继续轮询或阻碍父方Promise收口。这不是原先60秒等待泄漏，也不需要为取消一次短睡眠扩展整套sleep端口。Council 的长deadline timer已经显式清理。

## 独立验证

复跑 `phase2-tools.md` 完整focused命令：**186 tests / 186 pass / 0 fail / 0 skip**，约1.86秒。日志：`/tmp/phase2-tools-independent-review.log`。

独立新增临时探针（无仓库测试或源码编辑）：`/tmp/amber-phase2-tools-independent-probes.mts`，日志 `/tmp/phase2-tools-independent-probes.log`。实际调用生产入口，替换边界HTTP/runner：

1. 同一 createSearchTools 产生两个并发 search_web；确认分别使用各自signal；取消第一条后第二条HTTP未取消，且第二条实际返回可解析搜索结果。
2. fallback 已开启且不传父signal，让原生 executor 抛 AbortError；两种实际aggregator均拒绝，没有归一成empty/fallback结果。
3. 实际 council manager 长wait，记录123456ms deadline timer；abort后listener归零、deadline timer明确clear，子议会仍running；再次传已取消signal立即拒绝。最后释放子runner并等待完成，探针没有留下长定时器。

上述探针 **3/3 PASS**（第二项分别覆盖两种aggregator）。独立执行 chat `./node_modules/.bin/tsc --noEmit`：退出0，日志 `/tmp/phase2-tools-independent-tsc.log`。

## 验收边界

这份报告确认生产接线、可控边界行为和域层回归。没有把 HTTP mock/VM entry 测试视为真实provider或设备接受；Harmony整体构建、签名和设备验证仍由主agent在集成后完成。没有发现需引入额外retry、fallback或通用取消框架的实际故障。
