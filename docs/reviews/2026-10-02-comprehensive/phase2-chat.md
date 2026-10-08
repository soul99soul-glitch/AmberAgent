# Phase 2 Chat 修复

所有权：chat domain OpenAI APIs、chat_turn/regenerate/tool_loop/context_engine 及相关 tests。不修改 Entry 文件；OAuth 压缩与 Entry signal wiring 由 auth worker 负责。

## RED（已完成，源码未改）

新增 `harmony/chat/src/test/review_phase2_chat.test.ts`，真实 domain 函数，mock 仅 HTTP/Provider/文件边界。

```sh
cd harmony/chat
./node_modules/.bin/tsx --test src/test/review_phase2_chat.test.ts
```

24 tests / 1 pass / 23 fail。日志：`/tmp/phase2-chat-red.log`。

- 15 个 HTTP分类反例：ChatCompletions stream、Responses stream/generate × 503/429/408/401/400。真实 status 在 JSON detail 解析后丢失。
- 1 个 regenerate 取消反例：sleep 挂起，abort 后 run 应立即 settle，且没有重试第二次或持久化失败内容。
- 3 个文档预算反例：normal/regenerate/tool loop 历史附件展开后应适用既有 4000 token fit，最新用户保留，持久源 document 不变。
- 3 个 OCR vision fallback 反例：同三入口重建输入后也 fit，来源 image 不变。
- 1 个 foreground force 压缩取消反例：Provider 收到 run signal，AbortError 不重试、不 insert。
- 1 个既有行为绿用例：后台 precompact 不继承已结束 run signal，仍能完成独立压缩。

## 最小实施方案

1. JSON error 包装保留 HTTP status/detail，继续由既有 classifier 决定重试，不把 UNKNOWN 改为自动重试。
2. 抽取普通 chat 已有 abort 可唤醒等待为小共享 helper，供两处真实重复使用；不新建 retry 框架。
3. 把既有 final fit 移至 input transform 之后，三入口及 vision fallback 同位置适用；保留最新输入/system 既有语义，不添加当前文件截断策略。
4. CompactEngineDeps 增加可选 abortSignal 并传 provider opts；precompact 显式以 undefined 覆盖，foreground force/fit 保持 run signal。auth worker 在 Entry deps 传 ctx.signal。

## GREEN / 实施完成

CHAT02：两种 OpenAI error parser 保留真实 status 与可读 detail；既有 classifier 正常识别 HTTP 503/429/408，401/400 不重试。没有扩张 UNKNOWN 重试策略。

CHAT03：新 `generation_retry_wait.ts` 提取 chat 原有 abort 可唤醒等待（29 行），普通生成与再生直接 import 共用。不需要 index 导出，没有另建 retry 框架。

CHAT04：normal/regenerate/tool loop 各自把原 final fit 移至 applyInputTransformers 后；vision fallback 调同一闭包重建并 fit。持久化源 document/image 保持完整，没有引入当前超大输入截断策略。

CHAT05：CompactEngineDeps 可选 abortSignal 传 Provider opts，并在请求前后识别取消，避免取消后质量重试或写入。precompact 以独立 backgroundDeps 显式去除 run signal；foreground force/fit 继承原 signal。Entry `ctx.signal` wiring 已由 auth worker 完成并核对 wrapper 的 opts 直接透传兼容。

新增一个 model-window fit 反例：初次 force 摘要完成后，第二次 fit 压缩与 stop 同时 resolve 最后缓冲 chunk，也应抛 AbortError、不进行第三次质量重试、不落取消摘要；初次完成摘要保留。

### 验证结果

- 新回归：25/25 通过（初始 RED 是 24 中 23 fail / 1 pass；追加 fit-stop 场景后全部 GREEN）。
- focused suites：214 tests / 37 suites / 214 pass / 0 fail。
- 真实历史附件物化后超窗反例已绿；三入口 latest user 保留，persisted source 完整。
- `git diff --check` 通过。
- 全仓 `tsc --noEmit` 本组零诊断；仓库当前其他文件仍有 subagent_tools 未导入 AbortSignalLike/三参数，以及 council/search 旧测试类型错误，已报告主 agent，不越界修改。
- 本组没有 UI/API 接入；ArkTS lint、HAR/native build 与阶段独立 subagent review 由主 agent 统一验收。未声称设备或真实云端验证。

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

日志：`/tmp/phase2-chat-focused.log`；原始 RED `/tmp/phase2-chat-red.log`；全仓 TypeScript 诊断 `/tmp/phase2-chat-tsc.log`。

