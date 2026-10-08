# Chat 只读审查与二次复核

审查基线：HEAD `5ea51f7a`，当前工作区完整内容（含 untracked），已读取 harmony-arkts skill。只拥有本报告文件，未改源码。范围覆盖 chat turn/regenerate/context/provider/request/transformer/recap，以及 ConversationRunService/Guard/Dependencies、Recap/Compaction support、OpenAiCompatibleAiClient 和 RcpHttpClient。以下行号来自 2026-10-02 审查时工作区；并行修复可能移动行号。

## 确认的问题

### CHAT-01 [P1] 压缩 Provider 绕过 OAuth runtime，使用旧 token 且缺 Codex account header

- 自动路径：`harmony/entry/src/main/ets/platform_impl/ConversationRunDependencies.ets:185-197`。默认 auto task reference 在 `prepareConversationSeed` 中 fallback 到 `runtime.resolvedChoice`；`resolveChatRuntime` 在 AppContainer.ets:1802-1816 返回的 `resolvedChoice` 是刷新前原 choice，`prepareProviderRuntime` 只更新自己的深拷贝。
- 手动路径：`harmony/entry/src/main/ets/platform_impl/ContextCompactionSupport.ets:51-52`。两条路径直接 `buildProviderApi(choice.provider, headers)`，都不走 `prepareProviderRuntime`，也不传第三参数 accountId。
- 可达触发：主聊天 Codex token 刚好临期，正常发送先刷新成功，自动压缩使用旧 choice；或单独指定另一个临期的 OAuth 压缩模型。Codex 压缩无论 token 是否临期均缺 `ChatGPT-Account-Id`。
- 影响：聊天可以发送，但达到上下文阈值后压缩鉴权失败；auto_force 让整个发送失败。手动压缩同样受影响。
- 二次复核：CodexOAuthClient.ets:288-311 确实把新 token 同步写回 KV；但不能更新本次 run 已持有的旧 choice。若固定压缩模型恰好重新读取同 provider，可能读到新 token，因此不声称每个压缩请求一定用旧 token。缺 accountId 和另一个 OAuth model 不刷新是独立确定事实。
- 最小复现/失败测试：沿已有 `provider_runtime.test.ts` fixture，provider.apiKey=`stale-token`，refresh 返回 `fresh-codex/account-1`；比较 `prepareProviderRuntime` 构建 API 收到 fresh token/accountId 与压缩 factory 收到原 choice stale token/空 accountId。使用 `authMode=grok_oauth` 复核另一种 OAuth。
- by design 对照：provider_runtime.ts 明确将 refresh 与 auth snapshot 作为共享请求入口；压缩注释声称与自动/聊天相同解析，不是有意让辅助任务绕过认证。
- 最小修复：压缩模型复用现有 OAuth runtime 解析；同一个已解析模型可复用其 API；保留现有压缩参数边界，不引入新缓存/重试。

### CHAT-02 [P2] OpenAI HTTP 错误解析丢失 status，已启用的重试不会执行

- `harmony/chat/src/main/ets/chat/openai_chat_api.ts:79-90` 和 `openai_responses_api.ts:94-108` 返回 `parseOpenAiErrorDetail()` 的纯 message，丢失 status 以及 error.type/code。
- 可达触发：HTTP 503 返回 `{"error":{"message":"Please try again later","type":"server_error"}}`；429/408 错误正文也可不含对应数字或 classifier 关键字。
- 影响：`classifyGenerationFailure` 将已知可重试的 HTTP 失败分类为 UNKNOWN，`runChatTurn` 立即失败；与非流式 ChatCompletions 保留 `503` 的行为不一致。
- 实测：调用真实 `createOpenAIChatApi` + mock HttpClient 返回上述 503，分类输出 `{category:'UNKNOWN',retryable:false}`。
- 最小失败测试：同 fixture 分别覆盖 ChatCompletions stream、Responses stream/generate；断言 classifier 返回 SERVER/retryable true，401/400 不变。
- by design 对照：generation_retry.ts 明确包含 408/429/500/502/503/504 的重试规则，JSON 不应剥掉这些已知状态信息。
- 最小修复：错误中保留真实 HTTP status 和可读 detail；复用既有 classifier，不扩张到所有 UNKNOWN 重试。

### CHAT-03 [P2] 无工具 regenerate 的重试等待不能立即取消

- `harmony/chat/src/main/ets/chat/regenerate.ts:277-283` 直接 `await sleep(decision.delayMs)`，只在延时完成后读 aborted；`chat_turn.ts:278-310` 已有 abort 可唤醒的等待。
- 可达触发：不带工具的再生（tool_loop.ts:624-625 返回 runRegenerateAt），网络错误进入重试等待时点停止。指数等待可达约 16 秒（含 jitter）。
- 影响：UI sending 与 per-conversation admission 仍被占用，用户不能立即重发；不会发起下一次请求，但停止时机错误。
- 实测：sleep 使用可控未完成 Promise，开始睡眠后 abort，等待 25ms，run 仍未 settle；人工释放 sleep 后才 settle。
- 最小失败测试：注入挂起 sleep、AbortController；abort 后 Promise 应及时结束，provider 只调用一次、不写失败尝试输出。
- by design 对照：同函数注释要求停止生成正常 resolve，普通 chat 同条件已立即唤醒；不是完整等待再收口的设计。
- 最小修复：复用普通 chat 已有等待语义或小共享 helper，不重做 retry 框架。

### CHAT-04 [P1] 最终 fit 在附件/OCR/template 展开前执行，实际请求仍可超窗

- `harmony/chat/src/main/ets/chat/chat_turn.ts:217-223`、`regenerate.ts:152-157`、`tool_loop.ts:385-390` 都先 fit assembled，再 applyInputTransformers。
- `document_transformer.ts:154-168` 随后把文档展开成最多 512Ki 字符文本。prepareContext 对未展开 document 只估算 fileName+80，无法预先消除膨胀。
- 可达触发：历史 user 消息附 200000 字符文本文件，助手已回复，后续用户继续问；model budget=4000。document transformer 在每轮重新物化历史文件，fit 看的是很小的 document 元数据。
- 影响：发送超过真实模型上下文的请求，模型报 context length；已有历史裁剪无法工作，附件会让后续对话持续失败。
- 实测：真实 runChatTurn + createDocumentTransformer + capture provider，`finalTokenBudget=4000`，provider 收到的 `estimateTokens(messages)=50076`。
- 最小失败测试：历史附件 + 普通后续用户；断言实际 provider 输入 <=4000，最新用户保留，持久化源 document 仍完整。补单跑/再生/tool loop 与 vision fallback 重建路径。
- by design 对照：fit 保留最新消息和 system 的设计可接受。本项明确针对可裁剪的历史附件，不要求无声截断用户当前附件，也不要求对所有超大 system 加兜底。
- 最小修复：让既有发送预算在最终文本物化后起作用；调整顺序/位置，避免另加多层 fallback。单条当前输入本身过大是另一类产品策略，此报告不扩大到该范围。

### CHAT-05 [P2] foreground 自动强制压缩不传取消信号，停止只能等压缩请求结束

- `harmony/chat/src/main/ets/chat/context_engine.ts:410-439` CompactEngineDeps 没有 abortSignal，streamCompactSummary 调 provider 不带 opts。
- `harmony/entry/src/main/ets/platform_impl/ConversationRunDependencies.ets:222-235` prepareContext 位于 active run 内，但未向压缩请求传 `ctx.signal`。
- 可达触发：上下文达到 force threshold（或 auto_fit_model_window），正在 await 压缩模型时用户点停止。
- 影响：底层 HTTP 压缩请求继续运行，run 不能释放直到正常结束/120s timeout，期间不能发新消息；可能在用户已停止后继续发第二次质量重试并落压缩结果。
- 实测：调用真实 compactConversation + 挂起 provider，provider 得到的 opts 恒 `undefined`；触发 host AbortController 不会触达该请求。
- 最小失败测试：prepareContext force 路径 + provider 监听 opts.signal，触发 abort 后应抛 AbortError、不启动质量重试、不 insertCompact；通过真实 entry wiring 复核 signal 来源。
- by design 对照：异步 precompact 允许跨越 generation 生命周期是已有 app-scope 设计，不在此项内。这里仅 foreground auto_force/auto_fit，context_engine 已显式把 AbortError 重新抛出，说明取消应可传播。
- 最小修复：只向 foreground 压缩传所属 run signal，沿已有 HTTP AbortSignalLike 链传播；不要给脱离 run 的 precompact 强行绑定过期 token。

## 运行证据与复现命令

本轮临时探针（未写仓库源码）：`/tmp/audit-chat-probes.ts`。

```sh
cd harmony/chat
./node_modules/.bin/tsx --tsconfig tsconfig.json /tmp/audit-chat-probes.ts
```

输出：

```text
compact opts: undefined
race compact count: 1 edited source retained: true old summary injected: false
503 classification: { category: 'UNKNOWN', retryable: false, reason: 'failure is not known to be retryable' }
regenerate settled after abort in retry wait: false
doc budget/request estimate: 4000 50076
```

探针调用仓库真实 domain 函数，mock 仅限 Provider/HTTP/文件读取边界；没有声称已测试真实云端、SDK设备、安装或 UI。

## 已排除 / by design / 证据不足

- **precompact 在 edit invalidate 后迟到插入旧摘要**：最初怀疑重新覆盖编辑后的内容；实际 editMessage 追加新 message id，validCompletedCompacts 要求所有 sourceMessageIds 仍处于当前分支。实测 edited source retained=true、old summary injected=false。旧记录可能入库，但不造成所假定内容污染，不报告为功能 bug。
- **异步 precompact 在生成结束后继续运行**：context_engine 的 app-scope 分支与 ConversationRunGuard 独立 compaction 计数是刻意设计；只查 foreground 压缩的取消缺失。
- **regenerate 目标后的节点仍在会话中**：源码/tests 明確“仅生成上下文截断，后续节点保留”，不改变此分支产品语义。
- **regenerate 没有在 domain 层应用 visual transforms**：entry updateVisual 会应用，不能凭 domain 函数直接断言 UI 回归。
- **Responses resume 永远没有 runId**：buildProviderApi 当前没有传 runId，但 responses_api 顶部明确把 stored reconnect 实现放后续；作为已登记能力裁剪，不当成本轮真实回归。
- **DeepSeek 最终隐藏 tools 时不保留历史 reasoning_content**：查 [DeepSeek 官方 thinking mode 文档](https://api-docs.deepseek.com/guides/thinking_mode/)，当前规则明确对带 tools 参数的请求要求全量回传；现有 preserve 限定 carrying tools，tests 也钉住该范围，不报告。
- **legacy OpenAiCompatibleAiClient 对 pending 高风险工具继续循环**：当前 DeepRead 正常运行入口复用 createChatDeepReadAiClient；legacy client 仍在 container 默认配置，但未证明该候选可从真实用户路径触发，不计数。
- **RcpHttpClient 未知 HTTP status 时根据 SSE MIME/前缀提前消费**：为了兼容 Harmony requestInStream 在 dataEnd 后才给 status 的流式行为，有明确当前设备注释与探测限制；没有证据把这整体视为 bug。
- **recap 截取最近 32 条、每条 1500 字符、只挂当前有效引用**：明确有限摘要输入与派生缓存策略，未找到违反真实产品承诺的确定路径。
