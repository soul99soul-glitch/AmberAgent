# Phase 2 · T1–T4 实施与验证

2026-10-02。承接 `audit-tools.md` 四组已复核问题。变更留在当前工作区，无提交/推送。保留此前 dirty/untracked 工作；`AppContainer.ets` 和 `tool_loop.ts` 未修改。

## 实施

| 问题 | 实际改动 | 保持的边界 |
| --- | --- | --- |
| T1 搜索取消丢失 | search_web/scrape_web → orchestrator/aggregator executor → configured/free service 全程传独立 SearchRequestContext；取消抛 AbortError，不记普通来源失败；runFreeEngine 取消路径跳过 breaker 写入 | 没有改全局 SDK context、查询策略、并发数或增加 fallback/retry |
| T2 技能目录旧快照 | ConversationRunTools 的 catalog 每次重建当前 assistant ID 的技能 tools，现读 enabledSkills 和 SkillManager.listSkills；真实 tool_search 目录同步更新 use_skill | rt.assistant、provider、model、参数和其余 primitives 仍是该 run 原快照；不切换当前 UI 助手 |
| T3 report retry 误报完成 | 第一轮和补交报告的最后一轮都检查最终 pending 工具，共用原 approval_required 结果结构 | 保留第一轮可见内容，不增加报告重试次数或改变 fallback 文本 |
| T4 父等待不可取消 | 子代理/议会工具把 signal 传到 manager.wait；wait 取消及时拒绝并移除监听，议会等待清理 deadline timer | 取消的是父等待；不会调用子代理/议会的 cancel，不修改独立子任务状态 |

`free_web_engines.ts` 的额外局部变更已向主 agent 说明并获准：已有 dirty per-call signal 代码保留，仅增加已取消 signal / AbortError 路径，避免取消污染共享熔断。

## Red → Green

先新增并执行以下真实行为测试；第一轮每项准确失败：

- `search_cancellation.test.ts`：从 actual createSearchTools 入口检查 HTTP signal，旧实现实际为 undefined。
- `subagent_runner.test.ts`：首轮文本、report retry pending session_read，旧结果 completed。
- `subagent_tools.test.ts` / `council_tools.test.ts`：父信号 abort 后等待没有拒绝，旧结果继续等待至超时。
- `skill_catalog_refresh_entry.test.cjs`：transpile 执行 actual entry ConversationRunTools + RunToolCatalog、真实 SkillManager/技能管理/discovery，旧代码 enable 后 refreshTools 仍缺 use_skill。

红命令：

```sh
cd harmony/chat
./node_modules/.bin/tsx --test --test-name-pattern='chat search and scrape|report retry stops|stopping the parent' src/test/search_cancellation.test.ts src/test/subagent_runner.test.ts src/test/subagent_tools.test.ts src/test/council_tools.test.ts
./node_modules/.bin/tsx --test src/test/skill_catalog_refresh_entry.test.cjs
```

修复后扩大测试，覆盖取消不记来源失败、已取消请求不发 HTTP、并发请求互不影响、取消不写 breaker 而真实403仍写 breaker、技能 enable/import/disable 闭环和懒 discovery 可发现 use_skill、provider/model 身份保留、等待超时/成功/已取消入口及 listener 释放。

Green 命令（完整 focused suites）：

```sh
cd harmony/chat
./node_modules/.bin/tsx --test src/test/search_cancellation.test.ts src/test/search_tools.test.ts src/test/search_orchestrator.test.ts src/test/search_aggregator.test.ts src/test/search_free_web_engines.test.ts src/test/subagent_runner.test.ts src/test/subagent_tools.test.ts src/test/subagent_manager.test.ts src/test/subagent_tool_scope.test.ts src/test/subagent_report_tool.test.ts src/test/council_tools.test.ts src/test/skills.test.ts src/test/tool_exposure.test.ts src/test/skill_catalog_refresh_entry.test.cjs
```

结果：**186 tests / 186 pass / 0 fail**，约 1.5 秒。日志 `/tmp/phase2-tools-green.log`。

既有 subagent_tools 两个超时测试故意保持 fake child running，留下生产 runWithTimeout 的60秒定时器，使完整 red suite 退出被延迟。只在这两个测试断言后追加 manager.cancel 清理测试任务，未更改生产超时；现在整套 focused 正常退出。

## 编译与静态验证

- chat `./node_modules/.bin/tsc --noEmit`：退出0；日志 `/tmp/phase2-tools-tsc.log`。没有 any 断言或忽略类型错误。
- 定向 ArkTS lint（10个生产文件）：退出0，**0 errors / 1597 warnings**；日志 `/tmp/phase2-tools-lint.log`。包含模块解析相关 no-unsafe 警告，未据此声称零警告。
- 定向 `git diff --check`：退出0。

ArkTS lint 命令（仓库根）：

```sh
harmony/scripts/lint-arkts.sh harmony/chat/src/main/ets/search/search_tools.ts harmony/chat/src/main/ets/search/search_orchestrator.ts harmony/chat/src/main/ets/search/search_aggregator.ts harmony/chat/src/main/ets/search/free_web_engines.ts harmony/chat/src/main/ets/chat/subagent_runner.ts harmony/chat/src/main/ets/chat/subagent_tools.ts harmony/chat/src/main/ets/chat/subagent_manager.ts harmony/chat/src/main/ets/chat/council_tools.ts harmony/entry/src/main/ets/platform_impl/ConversationRunTools.ets harmony/deepread/src/main/ets/council/manager.ts
```

深度阅读 council manager 的生产编辑严格局限 wait 方法，与负责 runRound/runSeat/synthesize 的 worker 分界；没有覆盖对方编辑。完整 Harmony 构建、签名与设备验证由主 agent 统一执行，本报告不声称已完成这些门。后续独立 subagent review 应重点检查 signal 生命周期、独立子任务保留、技能刷新仅按 owner ID、report retry pending 不误报成功。
