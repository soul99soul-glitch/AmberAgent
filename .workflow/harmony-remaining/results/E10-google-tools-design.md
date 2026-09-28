# E10 Google 内置工具：只读设计

2026-09-28。**尚未实现/验收**；仅准备正式 E10 packet，不修改产品、state/index，不运行测试、build 或设备。已读取 harmony-arkts skill，先查现仓与 iOS/Android，再检索本地官方文档；`devecocli docs search url_context` 仅命中 Harmony URL/Web API，没有 Gemini 协议材料，随后只读取 Google/OpenAI 官方当前文档。

## 已有链与准确缺口

| 链路 | 当前事实 / 最小触点 |
| --- | --- |
| 模型字段 | [provider_model.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/provider_model.ts:19) 已有 `BuiltInTools = search / url_context / image_generation`；ProviderModel 已携带 tools。无需新配置字段。 |
| 持久化 | [provider_settings_serialize.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/provider_settings_serialize.ts:47) 已接受三种 type，serialize 输出 `tools:[{type:…}]`。无需新存储键。 |
| 生效参数 | [context_assembly.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/context_assembly.ts:162) 的 `toChatModel` 拷贝 tools；AppContainer resolveChatRuntime 与 ChatPage 的 params 消费该映射。无需增加第二条工具链。 |
| 设置页 | [ChatModelParamsPage.ets](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/ChatModelParamsPage.ets:73) 只有 search/image 草稿、读取与保存，当前文案只写 OpenAI Responses。确认会重建 tools，因此导入的 url_context 会在点确定后丢失。应补 URL 草稿，并按**实际 providerOverwrite ?? 容器 provider**展示协议能力。 |
| Google 请求 | [google_request.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/google_request.ts:338) 仅生成 functionDeclarations；tools 字段已到达但 Google 内置映射仍登记未做。文件顶部“ChatModel 无对应字段”的旧注释与实际类型矛盾，应随实际实现修正。 |
| Google 传输 | [google_chat_api.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/google_chat_api.ts:72) 对流式与非流式共用同一 builder，走现有 generateContent/streamGenerateContent 与 HTTP/SSE。HTTP/SSE error 已显式传播；E10 无需改端点、auth 或重试。当前快照的 OAuth/service-account guard 是其它 phase 的边界，不由本准备任务处理。 |
| 响应来源 | [google_parse.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/google_parse.ts:107) 仅取 groundingChunks.web，丢 urlContextMetadata 与 searchEntryPoint；SSE 只有 content 存在才构造 delta，最后只有 metadata 的事件也会丢。 |
| 累积与保存 | [stream_accumulator.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/stream_accumulator.ts:153) 已按 annotations append + JSON distinct，不可变路径相同；[serialize.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/serialize.ts:94) 只认识 citation/interrupted。只需增加确定 annotation 分支，不能把未知类型落为 interrupted。 |
| UI 来源消费者 | [ChatMessageCommon.ets](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/ChatMessageCommon.ets:16) 展示现有 url_citation、点击直达浏览器；AgentMessageRow/ChatMessageActionFooter 已传 message.annotations。URL 状态与 Search Suggestions 应在这条消费者链旁局部接入。 |

## 跨端与官方边界

Android `GoogleProvider.kt:576–593` 映射 `search → googleSearch:{}`、`url_context → urlContext:{}`，但后写整个 tools 覆盖 functionDeclarations，其“目前不能兼容”注释不能当当前 Gemini 3 全面能力结论。Android 同样只消费 groundingChunks，未完成 URL retrieval/status 或 Search Suggestions；可借字段，不复制这些缺口。

iOS 实际 `IOSGeminiProvider.swift:406–426` 的 makeBody 只取 params.tools 的 functionDeclarations，未消费 params.model.tools；流式/非流式消息构造的 annotations 为空。iOS 当前实现同样不能充当 Google 内置工具完成证据。iOS KMP OpenAI / Android ResponseAPI 都明确 UrlContext 不支持。

现有 Harmony OpenAI Responses [openai_responses_request.ts](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/openai_responses_request.ts:292) 仅映射 web_search 和 image_generation，跳过 url_context；ChatCompletions 也没有该映射。官方 Responses 工具名仍是 `web_search`，本 phase 不将 Google URL context 改名注入 OpenAI。[OpenAI tools](https://developers.openai.com/api/docs/guides/tools)

Google 当前通用 URL/Search 指南的首页示例已迁到 Interactions；仓内使用 **GenerateContent**，应引用它的官方专页与 REST reference：`tools[].googleSearch:{}` / `tools[].urlContext:{}`；retrieval 结果位于 candidate 的 `urlContextMetadata.urlMetadata[]`，含 `retrievedUrl` 和 SUCCESS/ERROR/PAYWALL/UNSAFE/UNSPECIFIED 状态。[GenerateContent Tool / UrlContextMetadata](https://ai.google.dev/api/generate-content#Tool), [GenerateContent URL context](https://ai.google.dev/gemini-api/docs/generate-content/url-context)

Google Search 除来源链接外返回 `groundingMetadata.searchEntryPoint.renderedContent`，它是需要展示的 Search Suggestions HTML/CSS；只画来源列表不能关闭 Google search 的 UI 消费链。[GenerateContent Search response](https://ai.google.dev/gemini-api/docs/generate-content/google-search), [Search Suggestions display](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/grounding/grounding-with-google-search#use-google-search-suggestions)

**混用有真实协议成本**：当前 GenerateContent 专页支持 Gemini 3 preview 混用，但要求 `toolConfig.includeServerSideToolInvocations=true`，后续轮必须原样保留 toolCall/toolResponse、所有 thoughtSignature、functionCall id，并在 functionResponse 返回同 id。仓内 parse 未认识 server parts，会抛 unknown part；现 builder 没有完整 round-trip。这不是追加两个 JSON 字段便可声称完成的能力。URL 页同时保留“function calling unsupported”的旧限制，与专门 Gemini 3 页有文档矛盾；明确按模型/模式区分，不全局猜测。[GenerateContent tool combinations](https://ai.google.dev/gemini-api/docs/generate-content/tool-combination)

## 建议的最小正式范围

本 phase 的最小可用方案是 **Google 内置工具模式**：search 与 url_context 可一起启用；启用时该请求声明这两个服务商工具，设置页提前说明该模式不向模型注册本地/MCP functionDeclarations。无内置工具时保留现有 functionDeclarations 行为。此模式参照 Android 已有产品语义，是显式选择；不把 Gemini 3 的更高能力错误描述为平台完全不支持，也不在请求失败后降级、重试或改用本地 scrape。

若正式 E10 packet 要求 Gemini 3 内置/本地混用，应把完整 server-part/id/signature 回传纳入该 packet；在上述 round-trip 做完前不启用组合 flag、不宣称混用。这样 root 可以固定真实范围，不能把后续缺口隐入“映射已通”的验收结论。本报告没有修改该行为或另创功能登记。

字段映射不依赖 `abilities.includes('tool')`：该开关目前用于用户 function 声明，服务商 built-ins 由 model.tools 控制。两种内置有实际条目时才写 tools 数组；image_generation 不产生 Google 工具声明（Google 图片输出已有 outputModalities → responseModalities 路径），不要因只含此项而写空 tools 覆盖正常声明。customBody 仍保持既有最后覆盖语义。

设置页按协议展示：Google 显示“联网搜索(Google Search)”与“网址上下文(URL context)”；OpenAI Responses 显示现有 search/image；OpenAI ChatCompletions/Claude 提示当前该协议没有此内置消费者。已持久化但当前协议不支持的选择保留，只展示说明，不能保存一次便悄悄删掉。服务商工具是否实际执行由模型决定；不按名字子串承诺所有 Gemini/代理端点支持，HTTP 400/权限/额度错误保持原始可见。

响应/来源最小模型建议在**唯一 DeepRead UIMessageAnnotation 定义**增加两个明确分支，Chat facade 继续 re-export：

```ts
{ type: 'url_context'; url: string; status: string }
{ type: 'google_search_suggestions'; html: string }
```

`status` 保存真实服务商值；SUCCESS 展示可点击的已读取网址，ERROR/PAYWALL/UNSAFE 展示对应读取失败/付费限制/被阻止，不能把失败 URL 标成成功引用。UNSPECIFIED/未识别值展示未确认状态，不捏造成功、不导致整条有正文的回答失败。既有 grounding web citation 不改。搜索建议 html 原样保存/展示该返回片段，不伪造来自 queries 的第三方 chip。

Google parser 在两种响应共用来源 helper：content + metadata 都在时正常解析；SSE 有来源 metadata 而 content 缺失时仍发 `makeUIMessage('assistant', [], {annotations})`，沿已有 accumulator 合并，不附加假正文。非流式也从同 candidate 取来源。仅失败 URL 是局部工具结果；Google promptFeedback/HTTP/SSE error 仍走现有异常。普通无来源回复不制造 citation/status。

GoogleSearchSuggestions 作为独立小 Web 组件接入 ChatMessageAnnotations，宽度与该消息内容一致，HTML/CSS 不重绘成自定义风格；controller attach 后使用已有 `loadData` 路径，点击直接打开原目标。仓内 DeepReadArticlePage / MiniAppRunnerPage 已有 loadData 先例，本地官方 `arkts-apis-webview-webviewcontroller` / `faqs-arkweb-125` 说明先绑定 controller；正式实现时由 UI owner 读具体 API12 签名。用 V1 状态，暗/亮主题截图只检查实际高度、边距、截断与单次直达，不附加 JS bridge 或通用 HTML 引擎。

## 窄 owner 与一个必要成功标准

- **Google domain owner**：google_request.ts / google_parse.ts / 必要 google_chat_api 调用参数，仅新增实际字段映射、模式约束与共用来源提取；一个必要 fixture。不得改认证、provider 路由或其它 phase。
- **Root shared owner**：DeepRead annotation 两分支及 Chat serialize 双向分支；检查所有 switch/serializer 的实际消费者，只改真实窄复制点。无需增加 ProviderModel 字段、存储键或改整个 accumulator。
- **UI owner**：ChatModelParamsPage URL 草稿/保存/有效 provider 能力说明，ChatMessageCommon 两来源分支与独立 GoogleSearchSuggestions.ets；不重排整页、修改 ChatPage 主循环或重做模型 picker。
- **独立 reviewer**：正式实现后核同一条调用链、metadata-only tail、持久化和 UI 实际尺寸，不重复已绿全 suite。

**唯一必要验证成功标准**：同一组小 fixture 将真实序列化并重载的 Google model.tools=`[search,url_context]` 经 toChatModel 和 createGoogleChatApi 发出正确 GenerateContent body（模式准确、无空 tools），fake HTTP 只供两个实际 SSE event：正文先到、最后仅 grounding/urlContext metadata（一个成功、一个真实失败、重复同来源与真实 Search Suggestions）。真实 parser → accumulator → serialize/parse round-trip 后，正文不重复、全部来源/失败状态/HTML 保留且 citations 不重复。正式 UI 验收沿这份响应检查三个设置项保存回读、来源单次点击、建议宽高与暗/亮主题；设备/UI 是否执行单独报告，不由本只读设计宣称。HTTP/SSE 错误沿既有检查保留，只有新修改引入疑问时补一个必要断点，不扩参数矩阵或全套重跑。

本报告仅为 E10 准备，当前 E7 的 source/state/index 与验证进程均未触碰。
