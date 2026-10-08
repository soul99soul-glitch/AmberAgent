# Phase 2 Chat 独立复审

结论：**PASS，未发现需要追加修复的真实问题。** 本次审查者未参与 Phase 2 Chat 实施；仅新增本报告，不修改生产源、测试或其他 agent 的报告。以 `/tmp/amber-harmony-review-20261002-baseline/harmony` 为修改前基线，对照 `phase2-chat.md` 和当前工作树；逐一核查 OpenAI 两类 API、三种生成入口、context engine、新 retry helper 及新增回归测试。

## 契约复核

| 修复 | 独立复核结果与源码证据 |
| --- | --- |
| CHAT02 HTTP 状态被 JSON detail 丢失 | `openai_chat_api.ts:79`、`openai_responses_api.ts:95` 在解析 detail 后保留实际 status。三种调用路径 × 503/429/408/401/400 共 15 个测试通过。`generation_retry.ts:96` 分类优先级未改，`generation_retry.ts:137` 的 UNKNOWN 仍不可重试；已有 quota/context/safety 优先级保留，未将所有 HTTP 错误变成临时故障。 |
| CHAT03 再生等待期间 stop 不释放 | `generation_retry_wait.ts:4` 是从旧 chat_turn 原实现提取的 29 行 helper，两处调用分别见 `chat_turn.ts:350`、`regenerate.ts:278`。abort 立即使等待完成，移除 listener；再生随后清空失败尝试 accumulator，并在再次调用 provider 前退出。原注入 sleep 可以继续完成，但其迟到 resolve/reject 被 settled 门闩隔离，不再影响已停止等待或新一轮等待。没有改 timer API 或增加重试机制。 |
| CHAT04 附件展开后才应用最后预算 | `chat_turn.ts:219`、`regenerate.ts:159`、`tool_loop.ts:386` 都由同一局部闭包先 applyInputTransformers、再既有 fit。vision fallback 调该闭包的 forceImageToText 分支，重新物化后同样 fit。三个文档历史反例、三个 OCR 历史反例均验证 provider 入参预算、最新 user 留存及 persisted attachment 完整。canonical base/assembled source 不被替换成展开文本；历史筛选只发生在 provider 输入副本。 |
| 当前输入保留策略 | `context_compact.ts:1065` 原 fit 实现未改，保留 system 和最近一条非 system 消息；单条最新输入本身超过预算时仍保留整条，不新增无声截断。此次修复让历史物化后的估算参与已有筛选，不宣称任意超大最新附件都必然符合模型窗口。 |
| CHAT05 前台压缩取消及后台独立生命周期 | `context_engine.ts:426` 在请求前/正常完成后检测 abort，并以 AbortError 退出；`context_engine.ts:448` 将 signal 传 provider，已有 catch 在 `context_engine.ts:560` 直接重抛 AbortError。force 和 model-window fit 保持同一 deps；取消不进入质量重试、fallback 或 cancelled summary 的 insert。`context_engine.ts:690` 仅异步 precompact 清除 run signal，两种 launcher 路径都使用 backgroundDeps、保留 in-flight release。 |
| Entry 接线 | `ConversationRunDependencies.ets:230` 给 prepareContext 传 `ctx.signal`；压缩 provider wrapper 的 opts 直接透传。RcpHttpClient 的真实 abort 路径创建 AbortError，和 domain catch 契约一致。本报告仅只读核查接线；其实施/ArkTS 验收归 auth worker 与主 agent。 |

已二次检查最容易被误报的边界：底层旧 sleep 未被取消是沿用既有注入接口，真正要求的是等待立即释放并隔离晚到回调；UNKNOWN、quota 等分类未被放宽；最新超大输入完整保留是原有明确策略；后台 precompact 独立完成是既有设计。均未作为问题列表项。

## 独立验证

1. 下列 focused 命令实际为 **214 tests / 37 suites / 214 pass / 0 fail**，不是早期计划的 213。新 regression 文件是 25/25；追加的一个用例覆盖首轮 force summary 已完成、第二轮 fit 压缩与 stop 同时收到最后 buffered chunk：调用数停在 2，已完成首轮保留，取消摘要不落库。日志 `/tmp/phase2-chat-independent.log`。

```sh
cd harmony/chat
./node_modules/.bin/tsx --test \
  src/test/review_phase2_chat.test.ts src/test/openai_chat_api.test.ts \
  src/test/openai_responses_api.test.ts src/test/regenerate.test.ts \
  src/test/chat_turn.test.ts src/test/tool_loop.test.ts \
  src/test/context_engine.test.ts src/test/context_compact.test.ts \
  src/test/vision_fallback.test.ts src/test/generation_retry.test.ts \
  src/test/transformer_pipeline.test.ts src/test/document_transformer.test.ts
```

2. 独立 tmp probe `/tmp/phase2-chat-retry-independent.ts` 验证 abort listener 清理、旧 sleep reject 晚到不影响新等待、正常完成清理以及当前 sleep 失败清理，全部通过。运行命令：

```sh
harmony/chat/node_modules/.bin/tsx /tmp/phase2-chat-retry-independent.ts
```

3. scoped TypeScript 检查通过。配置 `/tmp/phase2-chat-review-tsconfig.json` extends 真实 chat tsconfig，include **全部 chat/main 源码**及上列 12 个测试文件（同时检查导入依赖），只排除其余测试文件。日志 `/tmp/phase2-chat-review-scoped-tsc.log` 无诊断。

```sh
harmony/chat/node_modules/.bin/tsc --noEmit -p /tmp/phase2-chat-review-tsconfig.json
```

4. **当前完整 chat `tsc --noEmit` 已通过，exit 0，无诊断。** 独立复审者在最终集成后重新确认一次，日志 `/tmp/phase2-chat-review-final-tsc.log` 为空；与主 agent 的 `/tmp/amber-review-final-chat-tsc.log` 及 tools 独立复审结果一致。首次复审时曾退出 2，留下三个本组范围外测试诊断：`council_tools.test.ts:15` 缺 mode，`search_cancellation.test.ts:135` options 的 apiKey 类型不匹配，以及 `search_cancellation.test.ts:149` union 含 undefined 不满足 JsonValue。该历史日志保留在 `/tmp/phase2-chat-independent-tsc.log`，三个问题随后由 tools worker 修正，均已消除；不构成当前未解决门。较早实施报告中的 subagent_tools 生产源诊断也已消除。

5. `git diff --check -- harmony/chat docs/reviews/2026-10-02-comprehensive/phase2-chat.md` 通过。

本次可批准 Phase 2 Chat domain 修复进入整合。ArkTS lint、HAR/native build、签名及设备/真实 provider 验收由主 agent 统一处理；此报告没有设备或云端验收结论。
