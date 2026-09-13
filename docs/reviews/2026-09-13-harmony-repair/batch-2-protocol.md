# Batch 2 — 协议/取消收口（R05 / R06 / R18）

- 范围：chat provider 组装层 + deepread HttpClient Port + entry RcpHttpClient 实现。
- 未触碰：`ChatPage.ets` / `AppContainer.ets` / `chat|deepread` 的 `index.ts` / `tool_loop.ts`（另 owner）。
- 未跑：hvigor 构建、全仓 lint（主代理统一串行）；未触真实网络/模型。

## 修改文件（仅本 agent 获分配文件）

| 文件 | 修复 ID | 机制 |
|---|---|---|
| `harmony/chat/src/main/ets/chat/chat_kernel_host.ts` | R05 | 取消判据只认显式 `cancel()` / `signal.aborted`；包装 store 记录最后落库会话，取消收口返回该快照而非入参旧会话 |
| `harmony/deepread/src/main/ets/platform/http.ts` | R18 | `fetchStream` opts 新增可选 `shouldStop?: () => boolean` |
| `harmony/entry/src/main/ets/platform_impl/RcpHttpClient.ets` | R18 | 实现 `shouldStop`：`onChunk` 后检视，返回 true 即 `destroy()` + 立即 settle（resolve 当前 status），不再等服务端 dataEnd/心跳 |
| `harmony/chat/src/main/ets/chat/openai_chat_api.ts` | R06/R18 | 传 `shouldStop`；`[DONE]` 后 `state.done` 直返成功；2xx 零 SSE 事件 → 抛错误正文 |
| `harmony/chat/src/main/ets/chat/claude_chat_api.ts` | R06/R18 | 同上（`message_stop` 收口；2xx 零事件抛错） |
| `harmony/chat/src/main/ets/chat/google_chat_api.ts` | R06/R18 | 流内 `{"error":{...}}` 显式抛错并触发 `shouldStop`；2xx 零事件抛错（Google 无标准 `[DONE]`，成功仍靠 dataEnd） |
| `harmony/chat/src/main/ets/chat/openai_responses_api.ts` | R06/R18 | 传 `shouldStop`；`response.completed/incomplete`/`[DONE]` 收口；2xx 零事件抛错 |
| `harmony/chat/src/main/ets/chat/openai_request.ts` | R06 | merge 后强制回写 `stream`（对齐 Android `withForcedStream`），`customBody` 不能把流式请求改非流式 |
| `harmony/chat/src/main/ets/chat/claude_request.ts` | R06 | 同上强制回写 `stream`（Responses 本已强制，未改） |

## 修复详情

### R05 — 结构化取消判据 + 不回退旧会话
- 删除 `isAbortError = name==='AbortError' || message.indexOf('abort')`（过宽子串）。新判据 `cancelled || (abortSignal !== undefined && abortSignal.aborted)`：普通错误正文含 `abort`、或 `name='AbortError'` 但 signal 未 abort，都走 `failed` 并**原样抛出**（user 不被旧会话覆盖）。
- 引擎每次 `store.save` 被包装记录 `lastPersistedConversation`；真取消（Stop）时 catch 返回该快照，保证不早于已持久化的新 user。`result` 仍为 `null`，ChatPage 既有 `result===null` 分支无需改动。
- 真取消路径 ChatPage `stopped=signal.aborted=true`，对引擎最后快照做 stop 收口，语义更正确（保留 user + 部分 assistant）。

### R06 — 200 非 SSE / 零有效输出不得静默成功
- 四 Provider 统一：先抛 `state.error`；`state.done` 直返成功（提前收口时 status 可能为 0）；非 2xx 抛 `errorFromFailureBody`；**2xx 且零 SSE 事件** → 抛 `errorFromFailureBody(status, body)`，网关 JSON 错误正文得以透出（OpenAI 官方语义对齐 Android `isEventStream` 门）。
- 不误拒正常事件：usage-only/tool-only/空 delta 事件仍计入 `sawEvent`；只有整条流零事件才判失败。
- 请求形态：`openai_request` / `claude_request` merge 后强制 `stream`，`customBody.stream` 无法再制造「请求非流式、解析器按 SSE 读」的分叉。

### R18 — 协议终态立即收口底层流
- 最小域内协议：Port 新增可选回调 `shouldStop`，Provider 在 `done/error` 置位；`RcpHttpClient` 在 `onChunk` 返回后检视，true 时 `notifyDataEnd → settle`（`cleanup()` 解绑 signal 并 `httpRequest.destroy()`），复用既有 `settled/destroyed/cleanup`，无竞态残留。
- 错误收口：Provider 在 `error` 后返回 true → 同样立即关流；随后 `throw state.error` 保证错误可见。
- 未提供 `shouldStop` 的既有 `fetchStream` 调用方（`ModelCouncilRunner`、`OpenAiCompatibleAiClient`、各 fake HttpClient）行为不变，接口向后兼容。

## 接口变更（需主代理知晓 / 共享接线）

1. `HttpClient.fetchStream` opts 新增可选 `shouldStop?: () => boolean`（deepread Port）。纯新增、可选，无破坏。
2. `RcpHttpClient.fetchStream` 提前 settle 时 `status` 可能为 `0`（`requestInStream` 未回传）；Provider 依赖 `state.done/error` 先判定，不依赖该 status。其它直接消费 `resp.status` 的调用方（council/OpenAiCompatibleAiClient）不传 `shouldStop`，不受影响。
3. Provider 行为：2xx 零 SSE 事件从「静默成功」改为「抛错」。若某调用方依赖「空流也成功」，需复核（当前仅 chat_turn stream 路径经此）。
4. 无需改 `ChatPage/AppContainer/index/tool_loop`：`shouldStop` 在 Port 内部透传，`run()` 返回形状未变。

## 测试与结果（未 build）

新增 **5** 例（含 4 Provider R06/R18 覆盖），扩在现有文件内：

```
cd harmony/chat && npx tsc --noEmit -p tsconfig.json            # exit 0
npx tsx --test src/test/chat_kernel_host.test.ts \
  src/test/openai_chat_api.test.ts src/test/openai_responses_api.test.ts \
  src/test/key_roulette_wiring.test.ts src/test/openai_request.test.ts \
  src/test/claude_request.test.ts src/test/integration_chat_loop.test.ts
# tests 82 / pass 82 / fail 0

cd harmony/deepread && npx tsc --noEmit -p tsconfig.json && npx tsx --test "src/test/**/*.test.ts"
# tests 775 / pass 775 / fail 0

harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/RcpHttpClient.ets
# No defects found
```

新增用例：
- `chat_kernel_host.test.ts`: R05「含 abort 错误不误判取消 + 真 signal → cancelled 且返回含 user 的已落库会话」。
- `openai_chat_api.test.ts`: R06/R18「200 非 SSE 抛错、200 空流失败、`[DONE]` 后 shouldStop」。
- `openai_responses_api.test.ts`: R06/R18「200 非 SSE 抛错、`response.completed` 后 shouldStop」。
- `key_roulette_wiring.test.ts`: R06/R18「Claude/Google 200 非 SSE 抛错、`message_stop`/流内 error 收口」。
- `openai_request.test.ts`: R06「customBody stream 被强制回写调用模式」。

全量 chat 套件（`npx tsx --test "src/test/**/*.test.ts"`）：2118/2120 通过；2 例失败
（`tool_approval` skipStale、`agent_task_tools` delete_private_output）属其它 owner 改动，
单独运行均通过、与本批文件无交叉（已做配对验证），非本批引入。

## 未解决 / 边界（不声称设备已验证）

- `httpRequest.destroy()` 在流中即时中断语义、readTimeout 是整响应还是空闲口径，仍需设备实测（review-c C02 残余）；本修复不依赖 readTimeout 兜底，但真机行为未验证。
- Google 官方不定义 `[DONE]`：本次只补「流内 error 可见 + 立即收口」与「零事件失败」，其成功终止仍等 `dataEnd`，符合协议事实。
- R06 的零事件门依赖「headers/正文形态」判断，理论上存在合法但零 data 事件的流；当前按「零事件即失败」处理，未引入 content-type 白名单（RcpHttpClient 已回传 headers，如需可后续加）。
- 未做真机/模拟器装机验证；未运行 hvigor 构建或全仓 lint。
