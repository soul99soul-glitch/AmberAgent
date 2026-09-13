# 独立反证复核 — 专题 C（Provider / HTTP 流 / MCP 协议）

- 复核对象：分支 `harmony-preview`，HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`，当前工作树（不是仅 diff）。
- 复核依据：`docs/reviews/2026-09-13-harmony-deep-audit/reviewer-contract.md`；owner 报告 `topic-c-protocol.md`（240 行，已落盘）；owner 探针 `probes/probe-topic-c.ts`（本会话已复跑）。
- 本会话独立探针：`probes/probe-review-c.ts`（真实 chat-domain / MCP transport 逻辑 + 内存 fake，无网络、无模型）。运行：
  `cd harmony/chat && npx tsx --tsconfig tsconfig.json ../../docs/reviews/2026-09-13-harmony-deep-audit/probes/probe-review-c.ts`
- 业务代码绝对只读；本会话只在报告目录新增 `probe-review-c.ts` 与 `review-c.md`，未改动 `harmony/` 任何文件。
- 编号：本文沿用主代理口径 C01–C08；括号内标注 owner 报告 `C-0x` 对应。MCP 排除项 10/11 单列。

## 0. 裁决总表

| 项 | owner 定级 | 本次裁决 | 一句话结论 |
|---|---|---|---|
| C01（C-01）200 非 SSE 静默成功 | P1 高 | **确认** | 真跨端回归；且 `chat_turn`/UI **无空输出兜底**，端到端实证静默丢回复。 |
| C02（C-02）终止事件不取消底层流 | P1（挂死）/P2 | **降级为 P2（条件性）** | 结构缺口成立，但 `readTimeout=120s` + 外部 abort 下界，非无限挂死；仅“终止帧后持续心跳”才可能长时间不 settle。 |
| C03（C-03）`error:null` 误抛 | P2 高 | **降级为 P3 / 待验证（并纠正事实）** | Harmony 确实抛，但 **Android 同款**（Kotlin `!= null` 不排除 JsonNull）；无确证协议发 `error:null`。 |
| C04（C-04）customBody 覆写 stream | P3 高 | **确认（并入 C01 根因）** | OpenAI Chat 相对 Android 为回归（Claude 同款、Responses 已强制）；用户可见的空回复根因即 C01。 |
| C05（C-05）Responses resume 未接线 | P2 | **降级为已登记裁剪（非新 bug）** | `PARITY_DEBT.md:30` 明确登记“完整断线重连后续”；当前装配恒不可达。 |
| C06（C-06）resume save 未 catch | P3 latent | **确认（不可达 latent）** | 探针可复现 unhandledRejection，但受 C05 门控当前不可达。 |
| C07（C-07）Google `data:[DONE]` | P3 parity | **假阳性/降级** | Google 协议不定义 `[DONE]`；仅 OpenAI 风格兼容网关才会触发，属未承诺能力。 |
| C08（C-08）Responses done 依赖 `event:` | P3 parity 风险 | **降级 / 待验证** | 官方端点带 `event:`；缺 `event:` 的网关仍能靠 payload `type` 解析正文，只是不提前收口。 |
| owner C-09 `mapMethod` 未知方法降级 GET | P3 | **降级 / 待验证** | 当前仅 POST/DELETE 调用，不可达；非本期缺陷。 |
| MCP 排除 10 start() 在 try 外 | 假阳性 | **降级 / 接受残余（有界核查）** | 当前两 transport 无确认资源残留；残余=将来无 onClose 的 start 失败会留 `client.transport`。 |
| MCP 排除 11 Streamable SSE EOF 不重连 | parity | **降级 / 接受残余（非纯同款排除）** | 实证不自恢复、不重连；影响限服务器主动通知丢失，POST 工具面不受影响。 |

---

## 1. C01 — 200 非 SSE 静默成功；**无空输出兜底**（确认，P1）

### 亲读源码（当前工作树）
- `harmony/chat/src/main/ets/chat/openai_chat_api.ts:124-151`：`onChunk` 只喂 `SseAssembler`；`147` 先抛 `state.error`，`148-150` 仅判 `resp.status`，**不看 Content-Type / 正文形态**。claude `claude_chat_api.ts:93-120`、google `google_chat_api.ts:136-160` 同构。
- `harmony/deepread/src/main/ets/platform/sse_assembler.ts:131-162`：`parseSseBlock` 只认 `data:`/`event:`/`id:`；纯 JSON 正文 → `dataParts.length===0` → `null`。`flush()`（`120-128`）同样返回空。
- `harmony/entry/src/main/ets/platform_impl/RcpHttpClient.ets:252-254` 捕获 headers、`223` 回传，但 `http.ts:12-16` 的域层 API 未消费 content-type。

### 反证排查：空输出兜底是否存在
结论：**不存在**。逐层证明：
- `chat_turn.ts:396` 只看 `finished.length === 0` 提前 return（空即等于 seed），不会把“0 chunk”判为错误，也不进入 `generation_retry`。
- `regenerate.ts:278`（`if (!sawChunk) return ...`）确有“无 chunk 不追加”的守卫 —— 但**只保护 regenerate 路径**，普通发送路径无对应守卫。
- UI 层：`finishGenerationCommon`（`ChatPage.ets:1166-1190`）只做收口/保存，无错误置位；空 assistant 时 `resolveActionFooterMode`（`ChatMessageActionFooter.ets:14-21`）对 `lastMessage && !loading` 返回 `visible`，即显示空气泡+操作条，**没有任何“空回复”报错**。

### 独立实证（probe-review-c.ts）
```
C01.stream-empty.streamOutput=true: {"threw":null,"nodes":1,"assistantNodes":0}      # 只落 user 节点，无任何错误
C01.nonstream-empty.streamOutput=false: {"threw":null,"nodes":2}
C01b.nonstream-empty.parts: {"nodes":2,"assistantParts":[0],"finishedAt":["...Z"]}   # 空 assistant 节点(0 part)竟正常入列
C01.e2e.real-api.200-non-sse: {"threw":null,"nodes":1,"assistantNodes":0}            # 真实 createOpenAIChatApi + 200 JSON 错误体
```
最后一行用**真实 API 组装层** + fake `fetchStream`（200、`content-type: application/json`、body 为 `{"error":{...}}`）驱动 `runChatTurn`：`threw=null`、仅 user 节点。即“网关 200 返回 JSON 错误 → 用户看到无回复、无报错、无重试”端到端成立。

### Android 反证（真跨端回归）
- `ai/src/main/java/app/amber/ai/util/SSE.kt:48-52`：`if (!body.isEventStream()) onFailure(..., IllegalStateException("Invalid content-type: ..."))`；`isEventStream()`（`:87-89`）要求 `text/event-stream`。
- 仓库依赖 `okhttp-sse` 5.3.2 亦有同门：反编译 `okhttp3/sse/internal/RealEventSource.class` 含字符串 `Invalid content-type:` 与 `event-stream`（`javap`/字节串已核对）。provider 均经 `EventSources.createFactory(...).newEventSource(...)`（`ChatCompletionsAPI.kt:285`、`ClaudeProvider.kt:280`），故 Android 在 200 非 SSE 时走 `onFailure` → 抛真实错误并进入重试分类。
- 结论：C01 是 **Harmony 缺失 content-type 门**的回归，非“Android 同款”。

**裁决：确认，P1。** 建议修复=平台层透出 content-type，或 API 层在“零事件 + 2xx + 非空正文”时抛 `errorFromFailureBody`。回归测试：200+`{"error":...}`（无 `data:`）必须 reject。

---

## 2. C02 — 终止/错误事件后不取消底层流：**降级为 P2 条件性**（确认结构缺口）

### 亲读源码
- `openai_chat_api.ts:128,134-144`：`state.error/done` 后仅 `return` 退出回调，不终止 `fetchStream`；`147-150` 的抛错要等 `await` 之后。
- `claude_chat_api.ts:97,104-113`、`google_chat_api.ts:140-152`、`openai_responses_api.ts:140-143,161-181` 同构。
- `deepread/.../platform/http.ts:20-27` 与 `RcpHttpClient.ets:37-41`：`fetchStream` opts 只有 `onChunk/onDataEnd/signal`，**无端口级 cancel**；`onAbort`（`RcpHttpClient.ets:235-240`）只由外部 signal 触发。
- Android 具备 `EventSource.cancel()`：`ChatCompletionsAPI.kt:248,253,289`、`ResponseAPI.kt:193,199`、`ClaudeProvider.kt:183,187,285`、`GoogleProvider.kt:411,461`。

### 反证排查：超时是否界定（关键降级依据）
- **`RcpHttpClient.ets:142`（stream）与 `:59`（fetch）均 `readTimeout: 120000`。** 若终止帧后连接**不再有字节**，`requestInStream(...).catch`（`:278-282`）会 fire → `fail()` → `streamText` reject，**非无限挂死**。
- 另有用户 Stop：`ChatPage.ets:666,1117` `abortController?.abort()` → `EntryAbortController` → `RcpHttpClient onAbort` 立即 reject（`AbortError`）。用户始终有出路。
- 唯一残余条件：**终止帧后服务端持续发送心跳/字节**（如 `: heartbeat` 注释行或 keep-alive padding）。每当有字节到达，readTimeout 计时会被重置，而 API 层因 `state.done/error` 提前 return 不再消费 → 理论上可长时间不 settle。这需真实网关行为佐证。
- 官方 SDK 文档（`@ohos.net.http.d.ts:275-289`）仅述 “Read timeout period. The default value is 60,000, in ms.”，**未证明是“整响应超时”还是“chunk 间空闲超时”**——这正是残余不确定点。

### 独立实证
`probe-topic-c.ts` P4（本会话复跑）：`error-then-idle` / `done-then-idle` 均 `settledWithin60ms:false` —— 证明“终止事件不结束 fake 流”这一结构事实为真。但 fake 无 readTimeout/abort 语义，**不能据此断言真实环境无限挂死**。

**裁决：确认结构缺口，但降级为 P2（条件性），撤回 P1“可挂死”。** 需设备实测 readTimeout 是整响应还是空闲口径（owner 盲区 1/2 同结论）。修复方向（端口 cancel / onChunk 返回 false→destroy）仍成立。

---

## 3. C03 — `error:null` 误抛：**降级为 P3/待验证，并纠正 owner 的错误前提**（重要）

### 亲读源码（Harmony）
- `openai_parse.ts:239-243`：`const errorValue = it['error']; if (errorValue !== undefined) throw new OpenAiStreamError(...)` —— `JSON.parse('null')` 为 `null`（≠`undefined`），命中。
- `openai_responses_api.ts:153-155` 同型；`parseOpenAiErrorDetail(null)` → `new Error('null')`（`openai_parse.ts:201`）。

### 独立实证
```
C03.parse.error-null: {"threw":"null","chunks":0}       # 含正常 content 的 chunk 被 null 中断
C03.parse.error-absent: {"threw":null}                  # 键缺失不抛
C03.parse.error-object: {"threw":"rate limit"}          # 真错误对象仍抛
```
即 Harmony 的 null 行为**确认**：显式 `error:null` 会抛。

### 纠正 owner 的事实错误（按 coordinator 要求，不复制错误假设）
owner 报告 `topic-c-protocol.md:101,109` 称“Android 用 `it["error"] != null`（同时排除缺失与 JsonNull）… Android 无此问题”。**此前提错误：**
- Kotlin `JsonObject.get("error")` 返回 `JsonElement?`（Kotlin null 仅代表键缺失）。显式 `null` 解析为 `JsonNull`，是**非 Kotlin-null 的引用**，`!= null` 为 **true**。
- 依据：**本仓库自己的移植注释** `harmony/chat/src/main/ets/chat/openai_parse.ts:189` 明写“Kotlin this[it] != null 对 JsonNull 也为 true → 按 key 存在性判断”；Android 实测 `ChatCompletionsAPI.kt:213`、`ResponseAPI.kt:186` 正是 `if (it["error"] != null) throw ...parseErrorDetail()`。
- 因此**Android 同样会对 `error:null` 抛异常**。C03 是**跨端 parity**，不是 Harmony 回归。

### 真实协议判断
- OpenAI Chat Completions / Responses 官方流式 chunk **不定义** `"error": null` 字段；真错误通道是对象型 `error` 或 `response.failed`。
- 仅部分 OpenAI 兼容网关会在正常 chunk 固定带 `"error": null`（常见但本仓库无样本证据）。

**裁决：降级 P3 / 待验证。** null 抛错行为真实，但（a）Android 同款、（b）无确证协议发 `error:null`。可作低危健壮性硬化（建议改 `!== undefined && !== null`），**不作为本轮 P2 缺陷主张**。需真实网关样本升级。

---

## 4. C04 — `customBody` 覆写 `stream`：确认（请求形态）+ 与 C01 同根

### 亲读源码
- 来源：`context_assembly.ts:151` `customBodies: [...assistant.customBodies, ...model.customBodies]` → `AppContainer.ets:1328-1338` 写入 `params.customBody` → `ChatPage.ets:1906/3126-3133` 传给 provider。即**用户/模型原始自定义配置**（Android 迁移项），非 UI 流式开关。
- OpenAI Chat：`openai_request.ts:396` 写 `req['stream']`，`:465` 才 `mergeCustomBody`，其后 `:466-470` 仅删 temperature，**无 forced 回写** → 可被覆盖。
- Claude：`claude_request.ts:234` 写 stream，`:293` `mergeCustomBody` 为最后一步 → 可覆盖。
- Responses：`openai_responses_request.ts:303-310` merge 后 `out = { ...out, stream }` **强制回写** → 安全。

### Android 对照（逐字核对，非推断）
- OpenAI Chat 回归：`ChatCompletionsAPI.kt:473-474` `.mergeCustomBody(...).withForcedStream(stream)`（`withForcedStream` 定义 `:485-488` 强制 `put("stream", ...)`）→ Android 不受覆盖。
- Claude **同款**：`ClaudeProvider.kt:308` `put("stream", stream)`，`:377` `.mergeCustomBody(params.customBody)` 且**无 forced** → Android Claude 同样可被覆盖。
- Responses **同款**：`ResponseAPI.kt:405-407` `.mergeCustomBody(...).withForcedStream(stream)` 强制。

### 独立实证
```
C04.stream-after-customBody: {"openaiChat":false,"claude":false,"responses":true}
C04.reverse.customBody-true.caller-false.openaiChat: {"stream":true}   # 反向也可覆盖
```
`customBody {stream:false}` + 调用 `stream=true` → OpenAI Chat/Claude 请求体变 `false`，Responses 仍 `true`。

### 与 C01 是否同根 —— 是
- C04 本身只是**请求形态分叉**（OpenAI Chat 相对 Android 回归，Claude 为双端同款，Responses 安全）。
- 其**用户可见的“静默空回复”完全由 C01 承担**：服务端按 `stream:false` 回单条非 SSE JSON，被 C01 的缺失 content-type 门静默吞掉（owner `P2b`：`{"sentStream":false,"threw":null,"chunks":0}`）。
- 故应**并入 C01 一并修**：根因是“域层把端口成功等同 SSE 建立”；C04 只是可放大该根因的一条前置条件。

**裁决：确认（P3 请求形态）；与 C01 同根合并处理。** OpenAI Chat 侧是相对 Android 的回归，值得修；Claude 与 Android 同款，建议双端同步而非单方“修 bug”。

---

## 5. C05 — Responses resume 未接线：**已登记裁剪，不得报新 bug**（降级）

### 亲读证据
- `AppContainer.ets:1234` `buildProviderApi` 注入 `resumeStore` + `enableResponsesResume=true`，但**全仓无 `runId` 传入**。本会话穷举：`grep -rn "runId" entry/src/main/ets chat/src/main/ets` 命中的 runId 全属 subagent/council/novel 等**无关域**，`buildProviderApi` 的 6 处调用点（`AppContainer.ets:628,1350`、`MemoryDreamOps.ets:83`、`ChatPage.ets:1874,1923,1976,2435`）均只传 `(provider, headers)`。
- `openai_responses_api.ts:62` `if (deps.resumeStore === undefined || deps.runId === undefined) return null` → 真实装配恒 `null`；`:118-123` 不 load、`:169-175` 不 save。
- `openai_responses_request.ts` 即使传入 resume，body 只写 `store=true`，**从不写 `previous_response_id`**。
- **登记锚点**：`PARITY_DEBT.md:30` 明写“resume store Port 可注入（**完整断线重连 GET /responses/{id} 仍后续**）”。功能裁剪已显式登记。

### 独立实证（复核 owner）
`probe-topic-c.ts` P3c（本会话复跑）：`P3c.responses.no-runId (real wiring): {"loads":0,"saves":0,"sentStore":false}` —— 真实装配形态下 resume 完全不发生，store 恒 false。

**裁决：确认属于已知裁剪（PARITY_DEBT），当前不可达，非新 bug。** owner 报告已自行更正，本次复核支持该降级。建议仅补注释说明“runId 未注入 = 隐式安全”，接线前须先补 `previous_response_id` 序列化 + `GET /responses/{id}` 恢复 + catch + clear。

---

## 6. C06 — resume save fire-and-forget（C-06）：确认 latent（不可达）

- `openai_responses_api.ts:174` `void deps.resumeStore.save(...)`：`void` 不 catch rejection。
- 独立实证（owner P6，复跑一致）：`P6.resume-save-rejects: {"threw":null,"unhandledRejections":["Error: kv write failed","Error: kv write failed"]}`。
- 可达性：受 C05 门控（需注入 `runId`），**当前不可达**。

**裁决：确认 latent，P3；与 C05 同批处理（接线前必修）。** 不单列用户可见缺陷。

## 7. C07 — Google `data:[DONE]` 抛错（C-07）：假阳性/降级（未承诺能力）

- `google_chat_api.ts:144-148` 不检查 `evt.done`，把 `'[DONE]'` 传入 `parseGoogleStreamEventData` → `JSON.parse` 抛错（owner `P5`，复跑 `{"threw":"Unexpected token 'D', ..."}`）。
- 协议事实：Gemini 官方 `:streamGenerateContent?alt=sse` **不定义 `[DONE]` 终止帧**。Android `GoogleProvider.kt:363-371` 同款。此能力从未承诺，机械计缺陷不当。
- 可达性：仅当用户把 Google 端点指向一个追加 OpenAI 风格 `data: [DONE]` 的兼容网关。

**裁决：假阳性/降级（P3 兼容候选）。** 可作跨 provider 一致性硬化（`evt.done` 提前结束），但非本轮缺陷。

## 8. C08 — Responses done 依赖 SSE `event:` 字段（C-08）：降级/待验证

- `openai_responses_api.ts:162,177-180` 用 `event.event === 'response.completed'` 收口；`parseResponsesStreamEvent` 用 payload `type` 分派（`openai_responses_parse.ts:196`）。
- 若网关省略 `event:` 行：正文仍按 payload `type` 正确解析（内容不丢），只是不提前 `state.done`，需等 `dataEnd`。`dataEnd` 到达后 `RcpHttpClient` 仍会 settle 并 resolve → 不丢回复。
- Android `ResponseAPI.kt:204` 同样用 okhttp 的 `type`（即事件名）判断，行为等价。

**裁决：降级/待验证（P3 parity 风险），非 Harmony 独有缺陷。** 无需修复；若产品要覆盖“省略 event 行”的网关，可改为同时接受 payload `type`。

## 9. owner C-09 `mapMethod` 未知方法降级 GET：降级/待验证

- `McpHttpPortImpl.ets:56-66` `default: return http.RequestMethod.GET`。
- 可达性：`request()` 当前仅被 POST（SSE send/tools-call）与 DELETE（terminate）调用，错误路径**不可达**；且 `RcpHttpClient.mapMethod`（`:286-297`）对未知方法抛错。属契约硬化项。

**裁决：降级/待验证，非本期缺陷。**

---

## 10. MCP 补查（不因“Android/SDK 同款”直接排除，独立核查）

### 排除项 10 — `McpClient.connect`：`transport.start()` 在 try 之外
owner 结论“未改，SDK 同款，反证已找但被设计接受”。本次**独立核查资源是否真残留**：
- 源码：`mcp_protocol.ts:168` `await transport.start();` 在 `try {`（`:170`）之前。若 `start()` reject，catch（`:197-206`）与其 `await this.close()` **不会执行**。

独立实证：
```
EXCL10.client.connect.start-rejects: {"threw":"boom-start","started":1,"transportCloseCalled":0,"clientHasTransport":true}
EXCL10.sse-transport.start-fails-selfclean: {"startThrew":"openSse down","closeCb":2,"errCb":0,"secondStartThrew":"openSse down","openCalls":2}
EXCL10b.real-sse.connect-openSse-fail: {"threw":"openSse down","clientHasTransport":false}
```
- 对**抽象 transport**：start reject 时 `client.transport` 残留、`close()` 不被调用（第一行 `clientHasTransport:true`）。
- 对**真实 `McpSseClientTransport`**：其自身 `start()`（`mcp_transports.ts:233-258`）在失败时执行 `closeResources()`（`:349-362`，cancel session + **`closeBlock()`**），`closeBlock` 经 `client.onClose → doClose`（`mcp_protocol.ts:165,474-478`）把 `transport` 置 null → 第三行 `clientHasTransport:false`，**无实际泄漏**。第二次 start 依 SDK 语义再失败（可接受）。
- 对 `McpStreamableHttpTransport`：`start()`（`:408-414`）只置标志、**从不 reject**，无此路径。

残留风险（未证伪）：将来若新增“start() reject 且不触发 onClose”的 transport，则 `client.transport` 会残留、后续 `hasTransport=true` 使 `sync` 跳过 connect。当前两 transport 下**未确证资源残留**。

**裁决：降级/接受残余（有界核查）。** 不宣称“无 bug”；登记为“未来 transport 需自清理或在 connect 中把 start 纳入 try”的设计约束。P3。

### 排除项 11 — Streamable SSE EOF 不重连（round3 #40）
owner 结论“Android 同款 parity”。本次**独立核查 EOF 后 client 是否自恢复/重连及影响**：
- 源码：`mcp_transports.ts:556-583` `collectSse` 在 `session.next()===null` 时 `break`，**不调 `errorBlock`/`closeBlock`**；`startSseSession`（`:524-553`）仅在 POST 返回 **202** 时被触发（`:447-453`）。
- 独立实证：
```
EXCL11.legacy-sse.eof:          {"closeCb":1,"errCb":0}                       # Legacy SSE EOF → 触发 onClose → manager 可重连
EXCL11.streamable-sse.eof:      {"openCalls":1,"closeCb":0,"errCb":0,"subsequentSendThrew":null}
```
  Streamable SSE 会话被服务端关闭后：**无 onClose、无 onError、也不自动重连**；transport 仍可继续 POST（`subsequentSendThrew:null`）。
- manager 侧：`mcp_manager.ts:432-449` 只在 `onClose`/`onError` 且状态为 `connected` 时 `scheduleReconnect`；因 Streamable EOF 不回调，**不会触发重连**。
- 影响评估：Streamable HTTP 的请求/响应走 POST（`send`），故 `tools/list`/`tools/call` **不受影响**；丢失的是 **GET SSE 通道上的服务器主动消息**（如 notifications/progress、list-changed）。`lastEventId` 有记录但无自动 resumption 重连。属功能降级而非工具链中断。
- Android 同样在 `collectSse` 正常结束时不 `_onClose`（`app/.../StreamableHttpClientTransport.kt:280-315`），属 parity；但**本次不以“同款”作为排除理由**，而是按“影响有限 + 需重连时机”判定。

**裁决：降级/接受残余（非纯同款排除）。** 记录为已知残余：Streamable 服务器通知通道断后不自恢复；若要求 server-initiated 通知可靠，需在 EOF 时按 resumption 重连或触发重连。P3，不做“无 bug”声明；因无设备/真实 MCP 服务器，标为**待验证**（真实 EOF 时序、是否 202 会重新拉起 SSE）。

---

## 11. 探针输出（本次独立，逐字）

```
C01.stream-empty.streamOutput=true: {"threw":null,"nodes":1,"assistantNodes":0}
C01.nonstream-empty.streamOutput=false: {"threw":null,"nodes":2}
C01.e2e.real-api.200-non-sse: {"threw":null,"nodes":1,"assistantNodes":0}
C01b.nonstream-empty.parts: {"nodes":2,"assistantParts":[0],"finishedAt":["2026-09-13T07:23:14.529Z"]}
C03.parse.error-null: {"threw":"null","chunks":0}
C03.parse.error-absent: {"threw":null}
C03.parse.error-object: {"threw":"rate limit"}
C04.stream-after-customBody: {"openaiChat":false,"claude":false,"responses":true}
C04.reverse.customBody-true.caller-false.openaiChat: {"stream":true}
EXCL10.client.connect.start-rejects: {"threw":"boom-start","started":1,"transportCloseCalled":0,"clientHasTransport":true}
EXCL10.sse-transport.start-fails-selfclean: {"startThrew":"openSse down","closeCb":2,"errCb":0,"secondStartThrew":"openSse down","openCalls":2}
EXCL10b.real-sse.connect-openSse-fail: {"threw":"openSse down","clientHasTransport":false}
EXCL11.legacy-sse.eof: {"closeCb":1,"errCb":0}
EXCL11.streamable-sse.eof: {"openCalls":1,"closeCb":0,"errCb":0,"subsequentSendThrew":null}
```

---

## 12. 未决项 / 盲区（不宣称无 bug）

1. **C02 readTimeout 计时口径**（整响应 vs 空闲）：需 API12/24 设备慢速 SSE 实测；仅此决定“120s 是否必然兜底”。本机 `devecocli` 不可用（`Cannot find module '/opt/homebrew/bin/devecocli'`），无设备。
2. **C02 心跳残留**：终止帧后持续 keep-alive 字节是否会让 readTimeout 永不触发，需真实网关样本。
3. **C03 升级条件**：需一个真实会发 `"error": null` 的 OpenAI 兼容网关样本，方可从 P3 升级。
4. **C01 修复的 content-type 依赖**：需确认 `headersReceive` 必先于 `dataReceive`（HTTP 语义应成立，未设备证）；部分合规 provider 可能不带 content-type，修复需“白名单 + 正文形态兜底”。
5. **MCP Streamable EOF**：真实服务器关闭 GET SSE 的时序、是否由后续 202 重新拉起、`lastEventId` resumption 是否可用，均需真实 MCP 服务器验证。
6. **MCP exclusion 10**：当前无泄漏，但“start reject 不触发 onClose”的未来 transport 会残留 `client.transport`；属设计约束登记。
7. **`httpRequest.destroy()` 流中即时中断语义**：C02 修复依赖此，未设备验证。

---

## 13. 与 owner 报告的差异摘要

| 项 | owner | 本次独立复核 | 差异性质 |
|---|---|---|---|
| C01 | P1 | P1 确认 | 一致；补充“无 UI/chat_turn 兜底”的端到端实证 |
| C02 | P1 可挂死 | **降级 P2 条件性** | 加入 readTimeout/abort 反证，撤回“无限挂死” |
| C03 | P2，称 Android 无此问题 | **降级 P3/待验证；纠正 Android 同款** | owner 的 Kotlin `!=null` 前提错误（连仓库注释都写明 JsonNull 命中） |
| C04 | P3，OpenAI 回归/Claude 同款 | 确认，**并入 C01 根因** | 一致，明确同根 |
| C05 | P2“已声明功能未接线” | **降级为已登记裁剪** | `PARITY_DEBT.md:30` 显式登记，非新 bug |
| C06 | P3 latent | 确认 latent | 一致 |
| C07 | P3 parity | 假阳性/降级 | 未承诺能力 |
| C08 | P3 parity 风险 | 降级/待验证 | 一致偏弱 |
| MCP 10/11 | 假阳性/parity | 降级/接受残余，独立核查 | 不以同款排除，列残余 |

## 14. 结论摘要（供主代理汇总）

- **唯一“确认 + 高优先”项：C01**（200 非 SSE 静默成功，且 chat_turn/UI 无空输出兜底；相对 Android 的真回归）。**建议 P1 修复**：流形态/content-type 门 + 零事件守卫双保险。
- **C02**：结构缺口确认但降级 **P2 条件性**（readTimeout 120s + 用户 Stop 下界，非无限挂死，需实测口径）。
- **C04**：确认请求形态分叉，**并入 C01 同根修复**（OpenAI Chat 相对 Android 回归；Claude 双端同款）。
- **C03**：真实 null 抛错，但 **Android 同款 parity**，降级 P3/待验证（owner 的 Android 前提需纠正）。
- **C05/C06**：**已登记裁剪 / 不可达 latent**，不得计为新 bug。
- **C07/C08/C-09**：parity 或不可达，降级/待验证。
- **MCP 10/11**：单列**接受残余**，不宣称无 bug：10 当前无泄漏但有未来 transport 设计约束；11 Streamable 通知通道 EOF 不自恢复（工具 POST 面不受影响，属待验证残余）。
