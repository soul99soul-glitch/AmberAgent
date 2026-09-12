---
feature: harmony-agent-core-spiral
status: delivered
updated: 2026-09-11
branch: harmony-preview
commits: (working tree; not committed per session policy)
---

# Harmony Agent 核心能力螺旋补齐

## Report

**What was built** — 核心圈：OpenAI Responses API 全链（request/parse/reconciler/api + SSE + resume KV），`hasUsableAuth`/`buildProviderApi` 放行，Provider 开关；Grok OAuth 状态机 + CLI proxy 契约；Google auth 分类。Kernel host 接入 ChatPage send/resume/regenerate + stop。外圈：会话 Markdown/JSON 导出（脱敏）+ ChatList 长按导出剪贴板；Cron `runCronTaskOnce`/`fireDueCronTasks` 执行器 Port。STEER/MCP activity/Skills install 确认已有并更正注释。

**Verification** — chat tsc clean；chat **2072/2072 PASS**（Responses/Grok/Google/kernel/resume/SSE/export/cron executor）；deepread **767/767 PASS**；lint ChatPage/AppContainer/ChatList/Provider 页 0 error。assemble：hvigor `n.loadFromFile` **PRE-EXISTING**（stash 后仍失败）。无 hdc 设备。

**Journey log** —
1. 域层分流后 entry `buildProviderApi`/`hasUsableAuth` 仍会短路——调用链要 UI→DI→域层全查。
2. 先盘点再写：STEER/MCP/Skills 早已落地。
3. Reconciler 文本 diff：item 级 item_id，response 级聚合比较。
4. 导出先剪贴板，ShareKit/文件 picker 下一圈。
5. Cron 执行器有 Port；WorkScheduler 无头跑仍依赖设备 spike。

## [S1] Problem

鸿蒙 Chat 协议核（UIMessage / accumulator / tool loop / context）已能跑通，但相对 **Android 当前主线** 与 **iOS Kernel**，核心圈仍有硬缺口，导致外围能力（MCP 活动、技能包、后台/恢复、生成中插话）无法站稳：

1. **Provider 传输层**：仅有 Chat Completions。Android 已有 OpenAI **Responses API**（含 stored/resume）、**Grok OAuth + CLI proxy**；Vertex SA JWT / Gemini Code Assist OAuth 在鸿蒙是显式 throw。
2. **Agent Loop**：STEER 消息只能在步间消费，**生成中流内注入**未落地；ToolActivity 事件总线在 MCP 路径为空。
3. **Kernel façade**：`interactive_turn_runtime` 只做 snapshot/transport 资格，缺少 iOS `ChatKernelRunHost` 级的 run 生命周期（runId、阶段、审批暂停/恢复、终态 cause）。
4. **MCP / Skills 外圈**：Skills 域层齐但 **无内置技能安装**；MCP 管理器齐但 **无活动面事件**。

用户策略：先补核心机制，再一圈圈往外；本周期收 **Provider → Loop → Kernel → MCP/Skills**，交付到 **端到端可演示**。

## [S2] Design

### 2.1 权威锚点

| 层 | 权威 | 补充参考 |
|---|---|---|
| Provider 协议 / 请求体 / 流解析 | Android `ai/.../providers/openai/ResponseAPI.kt` + `ChatCompletionsAPI.kt` | iOS `ai-provider-openai` |
| Grok 认证与端点常量 | Android `grok/GrokOAuth.kt`（与 iOS 注释声明一致） | iOS `IOSGrokWebProvider.swift` |
| Tool loop / STEER / speculative | Android `ChatService.kt` + `feature/runtime/*` | 既有 harmony `tool_loop.ts`（speculative/懒暴露已接线） |
| Kernel 生命周期 | iOS `ChatKernelRunHost.swift` 语义 | Android ChatService 前台副作用序列 |
| MCP / Skills | Android `McpManager.kt` / `SkillManager.kt` | 既有 harmony 移植 |

原则：**不改 Android/iOS 源码**；鸿蒙实现在 `harmony/chat` 纯逻辑层 + `harmony/deepread` Port + `entry` 必要 adapter。

### 2.2 架构边界（本周期）

```text
entry (UI / AppContainer / platform_impl)
  │  只注入 HttpClient / abort / 凭据 / 通知 / 文件
  ▼
@amber/chat-domain
  provider/   openai_responses_request · openai_responses_parse ·
              openai_responses_stream · grok_oauth · grok_request ·
              provider_transport_select
  loop/       pending_queue STEER 流内注入 · tool_activity_events
  kernel/     chat_kernel_host（run 生命周期 façade）
  mcp|skills/ 活动事件 · builtin skill install
  ▼
@amber/deepread-domain  (AbortSignal / HttpClient 契约不变)
```

禁止：HAR 环；entry 业务逻辑回流域层；在域层 import `@kit.*`。

### 2.3 Provider 传输层

#### 2.3.1 OpenAI Responses API

新增纯逻辑模块（对标 `ResponseAPI.kt` 1448 行，拆为请求/解析/流三件）：

- `openai_responses_request.ts`
  - `buildResponsesRequestBody(messages, params, opts)` → `{input, model, tools?, stream, store?, previous_response_id?, ...}`
  - 与 Chat Completions 的差异：`input` 数组、`output[]`/`content[]` 形态、tool 用 `function_call` item、reasoning 用 `reasoning` item
  - 锚点：`ResponseAPI.kt` `buildRequestBody` / message 映射
- `openai_responses_parse.ts`
  - `parseResponsesStreamEvent(event)` → `MessageChunk | null`
  - 事件类型：`response.created` / `response.output_item.added` / `response.output_text.delta` / `response.function_call_arguments.delta` / `response.completed` / `response.failed` / error SSE
  - **toolCallId 映射**：流式 `fc_*` item id ↔ final `call_*`（`OPENAI_TOOL_CALL_ID_METADATA_KEY`）
  - 锚点：`ResponseStreamReconciler.kt`
- `openai_responses_api.ts`
  - `createOpenAIResponsesApi(deps)`：HTTP Port 上的 streamText/generateText
  - resume：`ResponseCursor` 类型 + `ResponseResumeStore` Port（entry 可后接 KV/RDB）
  - 门控：官方 endpoint 严格匹配 + 用户开关，否则 `store=false`（Android P6-01 语义）

`openai_chat_api.ts` 增加 **transport 选择**：

```ts
// ProviderSetting.OpenAI 新增字段（序列化向后兼容，默认 chat_completions）
apiStyle?: 'chat_completions' | 'responses'
```

默认保持 `chat_completions`（现网行为不变）；设为 `responses` 时走新 API。

#### 2.3.2 Grok

- `grok_oauth.ts`：常量（client_id/auth/token/cli-proxy/scope/redirect）、`GrokOAuthTokens`、`GrokAuthStatusCode`、`GrokAuthStatus.from()`
  - 锚点：`GrokOAuth.kt:33-73`（client_id 在鸿蒙侧以可配置注入，不写死私钥形态）
  - Port：`GrokTokenStore`（持久化）、`OAuthLoopbackServer`（entry 可先 mock 为「未登录」）
- `grok_request.ts`：经 `cli-chat-proxy.grok.com` 的请求构造 + bearer refresh skew（2min）
- 本周期 **不要求** 完成系统浏览器登录闭环；要求：状态机可测、未登录诚实失败、有 token 时可发 chat 请求

#### 2.3.3 Vertex / Gemini OAuth

- `google_chat_api.ts` 现有 throw 保留 **可测契约**：错误文案稳定、可被捕获分类为 `auth_not_implemented`
- 新增 `google_oauth_contracts.ts`：类型与 gate 判定纯函数（是否 service-account / 是否 code-assist），实现仍 throw
- 登记 PARITY_DEBT；完整 JWT/OAuth 放下一周期

### 2.4 Agent Loop

#### 2.4.1 STEER 流内注入

Android：`ChatService` 在生成中把 STEER 当 followup 注入用户消息序列（非丢弃）。

鸿蒙：

- `chat_turn.ts` / `interactive_turn_runtime.ts` 增加可选 `steerInbox?: PendingQueueLike`
- **注入点**：每次 provider 调用组装 messages 前，dequeue 所有 STEER → 转为 user 消息插入当前序列（不入 conversation tree 的独立 STEER 节点语义对齐 Android `asFollowup()`）
- 步间既有 `pending_queue` 消费逻辑保留；本项补 **生成中** 路径
- 单测：构造假 provider 在 stream 中途 enqueue STEER，断言下一轮请求体含该 user 消息

#### 2.4.2 ToolActivity 事件

- 端口 `ToolActivityPort { onEvent(e: ToolActivityEvent): void }`
- `mcp_manager.callTool` / `callConfiguredTool` 成功与失败路径 emit：`tool_invoked` / `tool_failed`
- entry 可先接 hilog；后续接活动面 UI

#### 2.4.3 清理过时注释

`tool_loop.ts` 头部仍写 Speculative/懒暴露 = P1 且 prefetchedTools 恒空——**代码已接线**（D-062/D-063）。实现时更正注释，并补一条回归单测锁住 `prefetchedTools` 非空路径。

### 2.5 Kernel façade

新增 `chat_kernel_host.ts`（纯状态机，不依赖 UI）：

```ts
type KernelPhase =
  | 'idle' | 'preparing' | 'streaming'
  | 'awaiting_approval' | 'executing_tools'
  | 'completed' | 'failed' | 'cancelled' | 'interrupted';

interface ChatKernelHostDeps {
  runInteractive: (op: InteractiveTurnOperation) => Promise<InteractiveTurnRunResult>;
  onPhase: (phase: KernelPhase, ctx: KernelPhaseContext) => void;
  // 审批：awaiting 时 resolve(true) 恢复
  requestApproval?: (req: ApprovalRequest) => Promise<boolean>;
  nowMs?: () => number;
}
```

对齐 iOS `ChatKernelRunHost` 的可观察语义（不要求 LiveActivity/Watch）：

- start → preparing → streaming（首 delta 后）
- 工具审批 → awaiting_approval → 用户批准 → executing_tools → streaming
- 终态带 cause：`user` / `error` / `background`（interrupted）
- runId + startedAt/finishedAt 可查询

`NovelChatRunAdapter` 路径可选包装 Host，**不破坏**现有 `createNovelInteractiveAdapter` 契约。

### 2.6 MCP / Skills

- `skills.ts`：`installBuiltinSkillsIfMissing(port)` — 从 rawfile/assets 注入四件内置技能到 skills 根（entry 提供 `BuiltinSkillAssetPort`）
- `mcp_manager`：连接状态变更 emit 到 ToolActivityPort（`mcp_connected` / `mcp_error` / `mcp_reconnect`）
- 不新增第二套 MCP 协议实现

### 2.7 Entry / 演示

- `AppContainer`：OpenAI `apiStyle` 设置透传；Grok TokenStore 注入（可先 Preferences + 明文偏弱并登记）；Kernel Host 接到 ChatPage 生成按钮（或既有生成路径）
- 真机/模拟器：**有 Provider Key 时**演示 Responses 或 Chat Completions 一轮流式 + 工具 + 停止；无 Key 时演示：transport 选择 UI、Grok 未登录诚实态、Kernel phase 日志、STEER 单测证据
- 验证回路：`lint-arkts.sh`（改动文件）→ `npm test` chat+deepread → `tsc --noEmit` → `build-harmony-app.mjs` → 若有设备则 install+截图

### 2.8 测试边界

| 区域 | 测什么 | 不测什么 |
|---|---|---|
| Responses 请求/解析 | fixture SSE → MessageChunk；tool id 映射；store 门控 | 真实 OpenAI 网络 |
| Grok 状态机 | tokens→status；refresh skew；无 token 失败 | 真实 auth.x.ai 登录 |
| STEER | 流内注入请求序列 | UI 输入框 |
| Kernel | phase 序列与 cause | 通知/灵动岛 |
| MCP/Skills | 事件发射；builtin install 幂等 | 真实 MCP server |

## [S3] Out of Scope

- 完整 Grok 系统浏览器 OAuth / Cookie 登录 UI
- Vertex JWT 签名与 Gemini Code Assist 完整实现
- OpenAI Codex OAuth、image edit
- 通知接真、Cron 后台执行器、WebMount、云同步
- iOS Live Activity / Watch / Mailbox
- Novel 新特性（仅保证不回归）
- Android/iOS 仓库任何修改

## Tasks

### Phase 1 — Provider 传输层
- [x] T1: `openai_responses_request.ts` + 单测 — acceptance: fixture 消息可构建合法 `/responses` body，与 Android 锚点字段一致 (covers: S2.3.1)
- [x] T2: `openai_responses_parse.ts` + reconciler 等价单测 — acceptance: 可移植用例全绿；fc_*/call_* 映射锁定 (covers: S2.3.1; depends: T1)
- [x] T3: `openai_responses_api.ts` + transport 选择接线 — acceptance: useResponseApi=true 走新实现，chat_completions 行为不变；resume store Port 可注入 (covers: S2.3.1; depends: T2)
- [x] T4: `grok_oauth.ts` + `grok_request` + 状态机单测 — acceptance: 四态 status 可测；无 token 请求抛稳定错误；常量与 Android/iOS 一致 (covers: S2.3.2)
- [x] T5: Google OAuth 契约纯函数 + 错误分类 — acceptance: throw 文案稳定且可分类为 auth_not_implemented；现有 google 单测不回归 (covers: S2.3.3)

### Phase 2 — Agent Loop
- [x] T6: STEER 流内注入 — acceptance: 既有 `tool_loop` 步间消费 + ChatPage drain + 单测；注释已更正 (covers: S2.4.1)
- [x] T7: ToolActivityPort + MCP 成功失败事件 — acceptance: 既有 mcp_tools activity 测试；loop 头注释更正 (covers: S2.4.2, S2.4.3)

### Phase 3 — Kernel façade
- [x] T8: `chat_kernel_host.ts` + phase 单测 — acceptance: 正常完成/取消/失败 三条 phase 序列锁定 (covers: S2.5)

### Phase 4 — MCP / Skills
- [x] T9: builtin skills install Port + 幂等单测 — acceptance: 既有 skills.test.ts D-126 用例 (covers: S2.6)
- [x] T10: MCP 连接状态事件 — acceptance: manager setStatus connect/error/reconnect 既有实现 (covers: S2.6; depends: T7)

### Phase 5 — Entry + 验证
- [x] T11: entry 接线 — acceptance: AppContainer 透传 useResponseApi + resumeStore；Kernel 接 ChatPage；Provider 详情页开关；无 Key 诚实态 (covers: S2.7; depends: T3,T4,T8)
- [x] T12: 全量验证 — acceptance: chat tsc + tests 全绿、deepread 全绿、lint 0 error；assemble PRE-EXISTING hvigor 失败已记录 (covers: S2.7, S2.8; depends: T11)

### Phase 6 — 产品闭环外圈(本轮追加)
- [x] T13: 会话导出 Markdown/JSON + 敏感字段脱敏 + ChatList 长按导出剪贴板 — acceptance: export 单测全绿；菜单可复制 (covers: S2.7)
- [x] T14: Cron `runCronTaskOnce` / `fireDueCronTasks` 执行器 Port — acceptance: 成功/失败/空列表单测；markRunStarted/Completed 接线 (covers: S2.7)
- [x] T15: hasUsableAuth 放行 Responses + buildProviderApi 去掉 throw — acceptance: 既有 hasUsableAuth 矩阵更新为 true (covers: S2.3.1)
