# 专题 C — Provider / HTTP 流 / MCP 协议审查

- 审查对象：分支 harmony-preview，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，当前工作树。
- 审查范围：`openai_{chat,responses}_{api,request,parse}`、`openai_responses_resume_store`、`openai_normalize`、`openai_request`、`claude_{chat_api,request,parse}`、`google_{chat_api,request,parse}`、`sse_assembler`、`openai_stream`、`RcpHttpClient.ets`、`OpenAiCompatibleAiClient.ets`、McpHttpPortImpl.ets、`mcp_{protocol,transports,manager,tools,config}`。
- 方法：关键函数与调用上下文完整阅读 + 跨模块追踪（entry 装配 / chat_turn / accumulator / Android 基准对照）+ 隔离合成 probe（fake `HttpClient`/`McpHttpPort`，无真实网络、无真实模型）。
- probe：`docs/reviews/2026-09-13-harmony-deep-audit/probes/probe-topic-c.ts`（仅本报告目录，未在 `harmony/` 业务树留文件）。
  运行：`cd harmony/chat && npx tsx --tsconfig tsconfig.json ../../docs/reviews/2026-09-13-harmony-deep-audit/probes/probe-topic-c.ts`（借 chat 的 tsconfig 解析 `@amber/deepread-domain` 路径别名）。
- 基线事实：`harmony/chat` 112 个 topic-C 相关用例全绿（`npx tsx --test src/test/{openai_chat_api,openai_responses_api,openai_responses_resume_store,mcp_protocol,mcp_transports,mcp_manager,mcp_tools}.test.ts`）。
- 工具限制：本机 `devecocli` 未安装（`harmony/scripts/devecocli.sh` 指向的 `/opt/homebrew/bin/devecocli` 不存在），平台 API 语义疑点走官方文档/源码核对，未能在设备上验证（见「盲区」）。

## 0. 覆盖状态表

| 文件 | 状态 | 说明 |
|---|---|---|
| `openai_chat_api.ts` (223) | 已完整阅读 + probe | 组装/分流/错误映射 |
| `openai_parse.ts` (279) | 已完整阅读 + probe | 事件解析/error 判定 |
| `openai_normalize.ts` (30) | 已完整阅读 | 多行 data 归一 |
| `openai_request.ts` (472) | 已完整阅读 + probe | 请求构建/mergeCustomBody |
| `openai_responses_api.ts` (235) | 已完整阅读 + probe | 流式/非流式/resume |
| `openai_responses_request.ts` (312) | 已完整阅读 + probe | capabilities/resume/input |
| `openai_responses_parse.ts` (600) | 已完整阅读 + 对照 Android Reconciler | 事件解析/调和 |
| `openai_responses_resume_store.ts` (39) | 已完整阅读 + probe | KV cursor |
| `claude_chat_api.ts` (153) | 已完整阅读 + probe | 请求头/多 key/错误 |
| `claude_request.ts` (294) | 已完整阅读 | system/thinking/tools/cache |
| `claude_parse.ts` (192) | 已完整阅读 | 事件解析/工具 index |
| `google_chat_api.ts` (193) | 已完整阅读 + probe | URL/auth/分流 |
| `google_request.ts` (358) | 已完整阅读 | contents/schema 裁剪 |
| `google_parse.ts` (259) | 已完整阅读 + probe | 解析/稳定 tool id |
| `sse_assembler.ts` (186) | 已完整阅读 + probe | 字节级 UTF-8/SSE 分组 |
| `openai_stream.ts` (138) | 已完整阅读 | 议会/小说文本流 |
| `RcpHttpClient.ets` (340) | 已完整阅读 | 平台 HTTP/fetchStream |
| `OpenAiCompatibleAiClient.ets` (425) | 已完整阅读 | 非流式 agent loop（仅 article_plan 消费） |
| `McpHttpPortImpl.ets` (492) | 已完整阅读 | MCP 平台端口 |
| `mcp_protocol.ts` (490) | 已完整阅读 | JSON-RPC 核 |
| `mcp_transports.ts` (639) | 已完整阅读 | 两 transport 全文 |
| `mcp_manager.ts` (687) | 已完整阅读 | 生命周期/重连/配置协调 |
| `mcp_tools.ts` (373) | 已完整阅读 | 工具桥接（授权策略属 D，未深审） |
| `mcp_config.ts` (143) | 已完整阅读 | 配置模型 |

---

## C-01 · HTTP 200 + 非 SSE 响应体被静默当成功（无错误、无输出、不进重试）

- **级别/置信度**：P1 / 高（probe 实证）。
- **用户影响与前提**：网关/代理在鉴权失败、配额耗尽、模型不存在、路由错误时返回 **HTTP 200 + `application/json` 错误体**（OpenAI 兼容网关常见），或 200 + `text/plain`。此时 App 判定流正常结束，assistant 消息为空；用户看到「无回复」，且因为没有抛错，`generation_retry.classifyGenerationFailure`（`generation_retry.ts:96-138`）不会触发，`chat_turn.ts:356-389` 的重试逻辑也不触发。
- **准确行号**：
  - `harmony/chat/src/main/ets/chat/openai_chat_api.ts:124-151`：`fetchStream` 的 `onChunk` 只喂 `SseAssembler`；`148-150` 仅判 `status`，不看 content-type/body 形态。
  - `harmony/chat/src/main/ets/chat/claude_chat_api.ts:93-120`：同构。
  - `harmony/chat/src/main/ets/chat/google_chat_api.ts:136-160`：同构。
  - `harmony/deepread/src/main/ets/platform/sse_assembler.ts:131-162`：`parseSseBlock` 只认 `data:`/`event:`/`id:` 前缀，纯 JSON 正文（无 `data:`）→ `dataParts.length===0` → `return null`；`flush()`（`120-128`）对非空白 buffer 也只调 `parseSseBlock`，同样返回 `null`。
  - `harmony/deepread/src/main/ets/platform/http.ts:12-16,20-27`：`HttpResponse` 有 `headers` 但 API 层未用；`fetchStream` 契约无 content-type。
  - `harmony/entry/src/main/ets/platform_impl/RcpHttpClient.ets:128-284`：`headersReceive`（`252-254`）捕获了 `headers` 并在 resolve 时回传（`223`），但域层 API 未消费。
- **调用链**：`ChatPage` → `asChatStreamProvider` → `createOpenAIChatApi().streamText`（`openai_chat_api.ts:113`）→ `deps.http.fetchStream`（`RcpHttpClient.ets:128`）→ `onChunk`（`127-144`）→ `SseAssembler.feed/flush` →（非 SSE 无事件）→ `await` 返回 → `resp.status 200` → **无异常 resolve**。
- **根因**：域层把「流式端口成功返回」等同于「SSE 流已建立」；平台层也不校验 `Content-Type`。
- **现有保护为何不成立**：
  - `SseAssembler` 的 `[DONE]` 判定只在 `data:` 行上生效（`sse_assembler.ts:157-159`）。
  - `normalizeOpenAIStreamDataLines`（`openai_normalize.ts:17-30`）同样以 `data:` 行/`[DONE]` 为前提。
  - `errorFromFailureBody`（`openai_chat_api.ts:79-91`）只在 `status` 非 2xx 时才被调用（`148`），对 200 错误体完全不可达。
  - Android 侧不存在此缺口：okhttp-sse `RealEventSource.processResponse` 有 `if (!body.isEventStream()) listener.onFailure(…, IllegalStateException("Invalid content-type: …"))`（`okhttp-sse` 5.3.2 `RealEventSource.kt:61-68`；`isEventStream()` 要求 `text/event-stream`，同文件 `100-103`）。该 `onFailure` 会走 `ChatCompletionsAPI.kt:258-278` 的 body→`parseErrorDetail`，最终抛真实错误并触发重试分类。
- **复现/静态证明**：probe `P1`：
  - `P1.openai.200-non-sse: {"threw":null,"chunks":0}`
  - `P1.claude.200-non-sse: {"threw":null,"chunks":0}`
  - `P1.google.200-non-sse: {"threw":null,"chunks":0}`
  - 对照 `P1.control.sse-error-surfaced: {"threw":"model not found"}`（同错误体包在 `data:` 里就被正确抛出）。
- **实际验证结果**：三 provider 均静默成功；对照证明错误检测本身有效，缺的是形态/content-type 门。
- **最小修复**：
  1. `HttpClient.fetchStream` 契约增加 `contentType`（或 `headers`）透出；三 api 层在首个 chunk 前校验 `text/event-stream`（`alt=sse` / Claude / Responses 都应如此），不匹配且 `status` 2xx 时抛 `errorFromFailureBody(status, resp.body)`（需要平台层在非 2xx 前的 body 也可见——RcpHttpClient 对非 SSE 的 2xx 已把 body 收完，可复用）。
  2. 或更小改动：`SseAssembler` 增加 `sawDataLine` 标记；`onDataEnd/flush` 后若整条流零事件、零 `[DONE]`、status 2xx，抛「stream produced no SSE events: <body 前 N 字符>」。
- **回归测试**：`streamText` 在 200 + `{"error":…}`（无 `data:`）时 `assert.rejects`；200 + 空 SSE（无任何事件）时也 reject。
- **历史关系**：round3 未列；round3 P6 只改了 MCP 的 `openSse` content-type 校验（见覆盖复核），未覆盖 provider SSE 端口。

## C-02 · 终止/错误事件后不取消底层 HTTP 流；无端口级 cancel（可挂死且不重试）

- **级别/置信度**：P1（潜在挂死）/ P2（结构缺口）/ 高（probe 实证）。
- **用户影响与前提**：`[DONE]`、`response.completed`、流内 `error` 帧到达后，API 层只置 `state.done/error` 并从回调 `return`，**不终止** `fetchStream`。若服务端/网关在终止帧后保持连接（不 dataEnd，例如错误帧后连接继续、或 keep-alive 代理），`fetchStream` 的 Promise 永不 resolve，`streamText` 永不返回 → 会话卡在「生成中」，Stop 是唯一出路；且错误帧场景下错误要等 `await` 后（`openai_chat_api.ts:147`）才抛，同样被挂死掩盖。
- **准确行号**：
  - `openai_chat_api.ts:128,134-144`：`state.error/done` 后 `return`（仅退出本次回调）。
  - `openai_chat_api.ts:147-150`：错误/状态判定在 `await` 之后，依赖流 settle。
  - `claude_chat_api.ts:97,104-113`：同构（`message_stop`/`error`）。
  - `google_chat_api.ts:140-152`：同构（parse 抛错置 `state.error`）。
  - `openai_responses_api.ts:140-143,161-181`：`response.completed/incomplete/[DONE]` 置 `state.done` 后 `return`。
  - `deepread/src/main/ets/platform/http.ts:20-27` 与 `RcpHttpClient.ets:37-41`：`fetchStream` opts 只有 `onChunk/onDataEnd/signal`，**没有任何 cancel/close**；`RcpHttpClient.ets:235-240` 的 `onAbort` 只由外部 `signal` 触发。
- **调用链**：`onChunk(evt)` → 解析出 `error`/`done` → 回调内 `return` → `RcpHttpClient` 的 `dataReceive` 监听未解绑 → 直到服务端 `dataEnd` 或外部 abort。
- **根因**：Android 的 okhttp `EventSource` 具备 `cancel()`，且 `ChatCompletionsAPI.kt:199-230` 在 `[DONE]`→`close()`、error payload→`eventSource.cancel()+close(error)`；`ClaudeProvider.kt:196-208` 在 `message_stop`→`close()`、error→`close(error)`。鸿蒙把 `HttpClient` 抽象成「回调式流」，但**丢掉了 cancel 能力**，注释（`openai_chat_api.ts:8-10`）自称「解析失败抛错即终止消费，底层流由 adapter 的 signal 收尾」，实际 signal 属于用户 Stop，不覆盖协议终止。
- **现有保护为何不成立**：`state.error/done` 只阻止后续 chunk 被消费（`128`），不阻止底层 socket 继续；`signal` 未在协议终止时被触发。
- **复现/静态证明**：probe `P4`（fake `fetchStream` 投递错误帧后返回永不 settle 的 Promise）：
  - `P4.openai.error-then-idle: {"started":true,"settledWithin60ms":false}`
  - `P4.openai.done-then-idle: {"started":true,"settledWithin60ms":false}`（`[DONE]` 之后同样不 settle）
- **实际验证结果**：60ms 内两个 promise 都未 settle，证明「终止事件不结束流」。
- **最小修复**：为 `fetchStream` 的 opts 增加 `cancel?: () => void`（RcpHttpClient 调 `httpRequest.destroy()` 并复用 `cleanup`）；三 api 层在 `done`/error 分支调用 `opts.cancel?.()`。或在 `RcpHttpClient` 内建「onChunk 返回 false 即 destroy」的约定。
- **回归测试**：fake `fetchStream` 在错误帧/`[DONE]` 后不 settle，断言 `streamText` 在该帧后于有限时间内 resolve/reject 且底层 cancel 被调用。
- **历史关系**：round3 未列（当时聚焦 MCP 侧取消）；与 round3「MCP signal 贯穿」同类但 provider 侧未覆盖。

## C-03 · 显式 `"error": null` 被当作流式错误抛出

- **级别/置信度**：P2 / 高（probe 实证）。
- **用户影响与前提**：部分 OpenAI 兼容网关/包装层在正常 chunk 里固定带 `"error": null` 字段。此时整个流被以 message=`"null"` 的错误中断，用户丢失本次回复并触发重试（分类 UNKNOWN → 不可重试 → 直接报错）。
- **准确行号**：
  - `openai_parse.ts:239-243`：`const errorValue = it['error']; if (errorValue !== undefined) { throw new OpenAiStreamError(base.message, errorValue); }` —— `JSON.parse` 的 `null` 不是 `undefined`，命中。
  - `openai_responses_api.ts:153-155`：`if (obj['error'] !== undefined) throw parseOpenAiErrorDetail(...)`；`parseOpenAiErrorDetail(null)` → `new Error('null')`（`openai_parse.ts:201`）。
- **根因**：Kotlin 基准用 `it["error"] != null`（同时排除缺失与 JsonNull），Harmony 用 `!== undefined` 只排除缺失。
- **现有保护为何不成立**：`parseOpenAiErrorDetail` 对 `null` 明确返回 `Error('null')`（`openai_parse.ts:201`），反而把空值转成错误。
- **复现/静态证明**：probe `P7`：
  - `P7.openai.error-null: {"threw":"null","chunks":0}`
  - `P7.responses.error-null: {"threw":"null","chunks":0}`
- **实际验证结果**：注入含正常 `content` 且 `"error":null` 的 chunk，流被中断且不产出任何 chunk。
- **最小修复**：两处改为 `if (errorValue !== undefined && errorValue !== null)`（Responses 同）。保留 `null` 时不抛。
- **回归测试**：`"error":null` chunk 正常产出；`"error":{...}` 仍抛。
- **历史关系**：round3 未列；Android (`ChatCompletionsAPI.kt:213`、`ResponseAPI.kt:186`) 无此问题。

## C-04 · `customBody` 覆写 `stream` 的连接层分叉（openai chat 与 Android 不一致）

- **级别/置信度**：P3 / 高（probe 实证）。
- **用户影响与前提**：用户/模型自定义 body 里若含 `stream`（或误配），行为因 provider 而异：OpenAI chat 路径会被改为非流式，服务端回整段 JSON，与 C-01 叠加后表现为**静默空回复**；Responses 路径强制 `stream` 不受影响；Claude 路径可被改为非流式（与 Android 同款）。前提是用户配置了该 customBody 项。
- **准确行号**：
  - `openai_request.ts:396`（`req['stream']=stream`）→ `465`（`mergeCustomBody`）→ `466-471` 只删 temperature，**无 withForcedStream**。
  - `openai_responses_request.ts:303-311`：`mergeCustomBody` 后 `out = { ...out, stream }` → **强制**。
  - `claude_request.ts:234`（`req['stream']=stream`）→ `293`（`mergeCustomBody` 最后）→ **可被覆盖**。
- **根因**：Android `ChatCompletionsAPI.kt:472-476,487-488` 在 `mergeCustomBody` 之后有 `.withForcedStream(stream)`，Harmony chat 路径漏移植；Android `ClaudeProvider.kt:377` 无 forced（与 Harmony 一致）；Android `ResponseAPI.kt:405-407` 有 `.withForcedStream`（与 Harmony 一致）。
- **现有保护为何不成立**：`provider_settings`/`RuntimeConfigRefresh` 不校验 customBody key。
- **复现/静态证明**：probe `P2`（customBody `{key:'stream',value:false}` + 调用 `stream=true`）：
  - `P2.openai.chat.stream: false`（Harmony 被覆盖；Android 会强制 true）
  - `P2.claude.stream: false`（与 Android 同）
  - `P2.openai.responses.stream: true`（强制）
  - 叠加 `P2b.claude.customBodyStreamFalse`：`{"sentStream":false,"threw":null,"chunks":0}` —— Claude `streamText` 发出 `stream=false`，服务端回单条 JSON，被 C-01 静默吞。
- **实际验证结果**：定制请求体确实绕过流式开关。
- **最小修复**：`buildChatCompletionRequest` 末尾加 `out = { ...out, stream }`（与 Responses/Android 对齐）；Claude 保持现状或同步加 forced（属 Android 同款，建议双端同步修）。
- **回归测试**：customBody 含 `stream` 时最终 body 的 `stream` 恒等于入参。
- **历史关系**：round3 未列。

## C-05 · OpenAI Responses resume 在真实装配中是死代码，且请求体从不携带 resume 指针

- **级别/置信度**：P2（已声明功能未接线 / 写盘残留）/ 高（静态 + probe）。
- **用户影响与前提**：`ProviderSettingOpenAI.useResponseApi=true` 且官方 `api.openai.com` 时，`buildProviderApi` 注入 `resumeStore` 并把 `enableResponsesResume=true`，但 **从不传 `runId`**（`AppContainer.ets:1269-1295` 无 `runId` 字段）。于是 `resolveResume` 恒返回 `null`（`openai_responses_api.ts:56-64`），`store=false` 永远发送、不 load/save。用户以为开启了 stored response 续跑，实际无用。
- **准确行号**：
  - `entry/src/main/ets/di/AppContainer.ets:1269-1295`（缺 `runId`）。
  - `openai_responses_api.ts:56-64`（`deps.runId === undefined` → null）、`118-123`（load 条件）、`169-175`（save 条件）。
  - `openai_responses_request.ts:231-312`：即使传入 `resume`，body 只写 `store=true`（`246-250`），**从不写 `previous_response_id`**；`resumeFrom.responseId/sequence` 未被使用。
  - `openai_responses_resume_store.ts:11-39`：KV cursor 实现完整，仅无调用者传 runId。
- **根因**：`runId` 是 Port 契约里的可选参数，entry 未提供；且 request 构建未实现 resume 指针序列化（注释 `openai_responses_api.ts:3-5` 自认「stored 重连 GET /responses/{id} 完整实现放后续」）。
- **现有保护为何不成立**：三个条件（`enableResponsesResume` && `resumeStore` && `runId`）中 `resumeStore` 恒存在，看起来「已启用」，掩盖了 `runId` 缺失。
- **复现/静态证明**：probe `P3`/`P3c`：
  - `P3.responses.resume-body: {"store":true,"hasPreviousResponseId":false,"previousResponseId":null}`（传 resume 时也不发指针）
  - `P3c.responses.no-runId (real wiring): {"loads":0,"saves":0,"sentStore":false}`（真实装配形态）
- **实际验证结果**：真实装配形态下 resume 完全不发生；手工传 runId 时才会 load(1)/save(2)，但请求体仍无 `previous_response_id`，即 resumeFrom 未被消费。
- **最小修复**：
  1. 明确取舍：要么在 `buildProviderApi` 传入稳定 `runId`（如 conversationId+turn），并在 `buildResponsesRequestBody` 写 `previous_response_id`；要么把 `resumeStore`/`enableResponsesResume` 的接线一并移除，避免「看似开启」。
  2. 若保留，save 需带 catch（见 C-06）。
- **回归测试**：wiring 级测试断言注入 `runId` 时 body 含 `previous_response_id` 且 store 生命周期正确；未注入时不得注入 `resumeStore`。
- **历史关系**：round3 未列；`docs/compose/spec/harmony-agent-core-spiral.md:85-86` 只要求「Port 可后接 KV」，未要求本期实现 previous_response_id，但 entry 已按「已启用」接线，形成语义落差。

## C-06 · resume save 为 fire-and-forget，失败产生 unhandledRejection（当前不可达）

- **级别/置信度**：P3（latent）/ 高（probe 实证；因 C-05 当前不可达）。
- **准确行号**：`openai_responses_api.ts:174`：`void deps.resumeStore.save(...)` —— `void` 只忽略返回值，不 catch rejection。
- **复现/静态证明**：probe `P6.resume-save-rejects: {"threw":null,"unhandledRejections":["Error: kv write failed","Error: kv write failed"]}`。
- **影响**：一旦 C-05 接通 runId，KV 写失败会在全局产生 unhandledRejection（ArkTS 下可能触发运行时告警/崩溃策略）。
- **最小修复**：`void deps.resumeStore.save(...).catch(() => {})`（或按领域错误策略记日志）。
- **回归测试**：save 抛错时 `streamText` 不受影响且无 unhandledRejection。

## C-07 · Google 流遇 `data: [DONE]` 抛 JSON 解析错（与 Android 同款）

- **级别/置信度**：P3（parity）/ 高。
- **准确行号**：`google_parse.ts:221-227`（`parseGoogleStreamEventData` 直接 `JSON.parse(data)`）；`google_chat_api.ts:144-148`（parse 抛错置 `state.error` 并上抛）。
- **说明**：Gemini 官方 `:streamGenerateContent?alt=sse` 不发 `[DONE]`。Android `GoogleProvider.kt:363-371` 同样 `json.parseToJsonElement(data)` 抛错→`close(e)`，行为一致。`SseAssembler` 会把 `data: [DONE]` 归一为 `done` 事件，但 `google_chat_api.ts:145-147` 未检查 `evt.done`，仍把 `'[DONE]'` 传入解析器。
- **建议**：`google_chat_api.ts` 在 `evt.done` 时提前结束（与 openai/claude 的 `state.done` 一致），使跨 provider 行为统一；非阻塞。
- **历史关系**：round3 未列。

## C-08 · Responses 的 done 判定依赖 SSE `event:` 字段而非 payload `type`

- **级别/置信度**：P3（parity 风险）/ 中。
- **准确行号**：`openai_responses_api.ts:162`（`parsed === null` 时用 `event.event`）、`177-180`（正常路径用 `event.event === 'response.completed'`）。
- **说明**：`parseResponsesStreamEvent` 以 `jsonObject['type']` 分派（`openai_responses_parse.ts:196`），而结束判定却查 SSE 事件名。若网关省略 `event:` 行（仅 `data:` 带 `type`），流不会因 completed 提前收口，只能等 dataEnd；官方端点会带 `event:`。Android `ResponseAPI.kt:204` 同样按 `type ==`（okhttp 的 `type` 参数即事件名），行为等价。不作为 Harmony 独有 bug。

## C-09 · McpHttpPort `mapMethod` 未知方法静默降级 GET

- **级别/置信度**：P3 / 高（静态）。
- **准确行号**：`McpHttpPortImpl.ets:56-66`：`default: return http.RequestMethod.GET`。
- **说明**：`request()` 当前只被 POST（SSE send / tools-call）与 DELETE（terminate）调用，故当前不可达错误路径；但契约上 `McpHttpPort.request` 接受任意 method，未来若加 PATCH 会静默变成 GET。建议 default 抛错，与 `RcpHttpClient.mapMethod`（`RcpHttpClient.ets:286-297`）一致。
- **历史关系**：round3 未列。

---

## 排除项 / 假阳性（含反证）

1. **SSE 多字节 UTF-8 跨 chunk** —— 反证成立，无 bug。`SseAssembler.safeDecodeEnd`（`sse_assembler.ts:47-62`）+ 字节级 `pending`（`83-94`）正确保留不完整序列；probe `CTRL.utf8-split: {"ev1":0,"ev2":1,"data":"{\"t\":\"你好\"}"}`。`McpHttpPortImpl` 另有 `StatefulUtf8Decoder`（`93-124`）同构。
2. **reasoning / tool 增量丢失** —— 反证成立。`openai_parse.ts:79-89`（reasoning part）、`tool_merge.ts:66-109`（按 stream index/id 归位、replace 语义）、`openai_responses_parse.ts:488-571`（Reconciler 的 text/tool 去重与 `openai_call_id` 匹配）逐条对照 Android `ResponseStreamReconciler.kt` 一致；probe `CTRL.reasoning-delta` 证明 delta 透传。Responses 流式 tool 用 item id、final 用 call_id，metadata `openai_call_id` 在 trackTool 中读取（`openai_responses_parse.ts:502-505`），与 Android 一致。
3. **usage-only 尾块丢弃（round3 #1）** —— 已修复并复核：`stream_accumulator.ts:269-277`（choice 判空前 mergeUsage）、`321-331`（appendChunkToMessage）、`435-440`（handleMessageChunk）三处齐全。
4. **图片 data URI 只认 PNG（round3 #2）** —— 已修复：`openai_parse.ts:123-132` 对非 PNG data URI 原样保留；`stream_accumulator.ts:203-206,393-395` 仅对裸 base64 补 PNG 前缀。
5. **HTTP 非 2xx 错误体解析** —— 无 bug。`errorFromFailureBody`（openai/claude/responses）与 Google 的 `errorFromFailureBody` 均先尝试 `parseErrorDetail` 再回退「status+raw」，与 Android `onFailure` 一致；测试已覆盖（`openai_chat_api.test.ts:137-156`）。
6. **MCP round3 #35 endpoint 前 EOF 挂起** —— 已修复：`mcp_transports.ts:349-362` `closeResources` 在 endpoint 未决时 `endpointReject(new Error('SSE connection closed before endpoint event'))`，`start()` 的 `await` 被解除。
7. **MCP round3 #36 protocolVersion 未回写** —— 已修复：`mcp_protocol.ts:193-195` → `mcp_transports.ts:424-426`（Streamable 后续请求带 `mcp-protocol-version`）。
8. **MCP round3 #38 Accept / Content-Type** —— 已修复：`mcp_transports.ts:249`（Legacy SSE 补 `Accept: text/event-stream`）；`McpHttpPortImpl.ets:364-370` 对 openSse 2xx 非 `text/event-stream` 抛 `McpSseOpenError(status,contentType)`，供 `startSseSession` 的 405/json 静默分支（`mcp_transports.ts:538-546`）使用。
9. **MCP round3 #41 add/remove/reconnect await 期间误删** —— 已修复：`mcp_manager.ts:388-398`（generation token + `isCurrentClient`），`addClient`（`400-427`）、`sync`（`453-519`）、`removeClient`（`533-557`）、`reconnectClient`（`620-650`）四处 await 后均做身份校验。
10. **MCP round3 #37 start() 在 try 外** —— 未改，属 round3 假阳性清单「SDK 同款」；`mcp_protocol.ts:168` `await transport.start()` 在 try 之外（`170` 起才是 try），失败不 close，与 SDK 0.8.4 `Client.connect` 同构。**反证已找但被设计接受**，非本轮新 bug。
11. **MCP Streamable SSE EOF 不触发重连（round3 #40）** —— 仍未处理：`mcp_transports.ts:556-583` `collectSse` 在 `event===null` 时 break，不调 `errorBlock/closeBlock`；Android `StreamableHttpClientTransport.kt:335-350` 的 `collectSse` 同样正常结束不 `_onClose`，属 round3 假阳性「同款」。记录为 **parity，非 Harmony 独有**。
12. **KeyRoulette 多 key 轮换并发** —— 无 bug。`search_service.ts:424-486` 的 `next()` 全程同步（`store.read/write` 同步），JS 单线程下不会交错；Android 的 `LruFileLock` 是 Kotlin 多线程需要，Harmony 不需要。多 key 接线正确：`AppContainer.ets:1249,1260,1267-1268`、`OcrSupport.ets:148,156,173` 每请求注入。
13. **McpClient.connect 失败时 close() 二次抛错覆盖根因** —— 不成立：`start()` 失败在 try 外（同 #10），进入 catch 的是 initialize 阶段失败，此时 transport 已 `initialized=true`，`close()` 正常；已排除。
14. **JSON-RPC 未知 id 响应被静默丢弃** —— 设计一致：`mcp_protocol.ts:428-436` 走 `protocolErrorHook`（默认 noop），对应 SDK 默认仅日志；非 bug。
15. **Responses `input` 中 tool 的 `function_call_output` call_id 对齐** —— 复核一致：`openai_responses_request.ts:192-207` 用 `tool.toolCallId`，与解析侧 `openai_responses_parse.ts:133-137`（final 用 call_id）在 reconcile 后统一（`reconcileTools` 回填 `state.id`）。无发现。
16. **`mergeCustomBody` 深合并语义** —— 与 Android `Request.kt:48-90` 逐字一致（同键皆对象则递归，否则替换；空 key 跳过）。无发现。

---

## 待验证项 / 覆盖盲区

1. **`@ohos.net.http` `requestInStream` 对长连 SSE 的 readTimeout**：`RcpHttpClient.ets:141` 与 `McpHttpPortImpl.ets:349,390` 都设 `readTimeout: 120000`。SSE 若 120s 无字节是否被底层掐断，需设备/官方文档确认；MCP SSE 与 provider 流共用该值。**无设备，未验证**（`hdc list targets` 为空；devecocli 本机缺失）。
2. **`httpRequest.destroy()` 在 `headersReceive` 之后、流进行中是否立即中断 socket**：C-02 的修复依赖此语义，未在设备验证。
3. **平台 `HttpDataType.ARRAY_BUFFER` 下 `dataReceive` 的分片边界**：`SseAssembler`/`StatefulUtf8Decoder` 在 Node 合成测试通过；真实 NetStack 是否会出现空 chunk、超大 chunk 未验。
4. **RcpHttpClient `copyHeaders` 仅在 `fetch` 用 `resp.header`；`fetchStream` 的 `headers` 只在 `headersReceive` 捕获**（`252-254`）→ C-01 修复若走 content-type 需确认 `headersReceive` 一定先于 `dataReceive`（HTTP 语义应成立，未设备证）。
5. **OpenAiCompatibleAiClient（DeepRead）`stream:false` 写死**（`OpenAiCompatibleAiClient.ets:278-280`）与真机流式挂死注释：当前只被 `article_plan.ts:251` 消费（非流式），`stream` 参数虽在 `GenerateTextParams` 里但被忽略。若后续接 `collectRun`，`callStream`（`297-321`）的 `[DONE]` 不终止（`parseOpenAiStreamEvent` 的 `done` 未用于取消，同 C-02 模式）。属扩展域/接线盲区（D/扩展专题），此处仅登记。
6. **MCP 工具授权/审批**（`mcp_tools.ts:213-214,242,266,351` 的 `needsApproval/allowsAutoApproval`）：归 D 专题，C 未深审策略正确性，仅确认桥接映射存在且 `tool.inputSchema` 以函数形式返回（延迟求值）。
7. **`hasUsableAuth` 对 coding-plan/OAuth 的封锁**：`provider_settings.ts:202-214` 只认 `api_key`，使 `mimo_coding_plan`（`openai_chat_api.ts:212-223` 已实现 api-key 头）等模式在 UI/装配层不可用；`provider_settings.ts:183-192` 的固定 baseUrl 表存在但被 `hasUsableAuth` 挡在 `buildProviderApi` 之外（`AppContainer.ets:1238-1240`）。属已知 P1 登记（`google_oauth_contracts.ts` 显式 throw 同族），未计为 C 新 bug。
8. **真实服务兼容性/协议扩展**：未调用真实模型；对「OpenAI Responses/Anthropic/Google 线上报文形态」的推断均已用官方事件名/Android 基准交叉核对，未把猜测当 bug（C-08 明确降级为 parity 风险）。

---

## 历史结论重核（round3 / Android 基准）

| 历史项 | 现状 | 结论 |
|---|---|---|
| round3 #1 usage-only chunk | `stream_accumulator.ts:269-277,321-331,435-440` | 已修，复核实证 |
| round3 #2 data URI 前缀 | `openai_parse.ts:123-132`、`stream_accumulator.ts:203-206` | 已修 |
| round3 #35 endpoint 前 EOF 挂起 | `mcp_transports.ts:349-362` | 已修 |
| round3 #36 protocolVersion 回写 | `mcp_protocol.ts:193-195` | 已修 |
| round3 #37 start() try 外 | `mcp_protocol.ts:168` | 未改，SDK 同款（假阳性清单），非新 bug |
| round3 #38 Accept / openSse Content-Type | `mcp_transports.ts:249`、`McpHttpPortImpl.ets:364-370` | 已修 |
| round3 #40 Streamable SSE EOF 不重连 | `mcp_transports.ts:556-583` | 未改，Android 同款 parity |
| round3 #41 manager await 误删 | `mcp_manager.ts:388-398` 等 | 已修 |
| round3 #55 搜索不随 Stop 取消 | 非 C 范围（搜索专题） | 转交 |
| Android parity「openai chat withForcedStream」 | `openai_request.ts:465-471` 缺失 | **新发现 = C-04** |
| Android parity「error != null」 | `openai_parse.ts:239`、`openai_responses_api.ts:153` | **新发现 = C-03** |
| Android okhttp-sse content-type 门 | Harmony 无 | **新发现 = C-01** |
| Android EventSource.cancel | Harmony 无 port cancel | **新发现 = C-02** |

## 结论摘要（供主代理）

- 新确认候选：C-01（P1，200+非 SSE 静默成功）、C-02（P1/P2，终止事件不取消底层流，可挂死）、C-03（P2，`error:null` 误判）、C-04（P3，customBody 覆写 stream 分叉）、C-05（P2，Responses resume 死代码 + 从不发 previous_response_id）、C-06（P3 latent，resume save unhandledRejection）、C-07/C-08（P3 parity）、C-09（P3 静态）。
- 均以「真实域逻辑 + 合成 fake 端口」probe 实证或逐行静态证明；未调用真实模型/网络。
- C-01 与 C-02 是 C 专题最值得优先修的两项（可组合成「静默空回复 / 生成卡死」用户可见故障）。
