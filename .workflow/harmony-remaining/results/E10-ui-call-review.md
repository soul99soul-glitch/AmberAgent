# E10 独立 B：UI 与调用链只读 review

2026-09-28，基点 `caa5b9d50`。已读取 harmony-arkts skill、E10 frozen contract/design，等 B/C owner 源码 ready 后检查实际文件。仅新增本报告；没有修改产品源码、运行测试/types/lint/build、操作设备/截图或发起外部请求。域层 HTTP/metadata fixture 由独立 A 负责，本 review 不重复。

## 已核闭环

- `ChatModelParamsPage.ets:82/125/278` 的 search、url_context、image_generation 三个草稿布尔均从当前模型载入，并在确定时写回同一个 `model.tools`。不支持的协议仍保存已选值，页面提前给出真实说明；没有另建配置键或静默删除。
- `ChatModelParamsPage.ets:128/135/363` 按 `model.providerOverwrite ?? provider` 选择 Google/Responses/其它说明，与 `AppContainer.ets` 的 effectiveProvider/makeProviderChoice 一致。search 或 URL 开启时明确 Google 内置工具模式不注册本地/MCP 函数；仅 image 不声称映射为 Google 工具。custom Body 最后覆盖语义也明确可见。
- 配置链沿现有 `saveProviders/loadProviders → provider_settings_serialize.ts → toChatModel → resolveChatRuntime/buildProviderApi`。`context_assembly.ts:162` 拷贝 model.tools；运行时使用同一实际 choice 构建请求。没有凭容器品牌替代 override 协议。
- 根共享定义只增加两个 named annotation，Chat facade/index 与 DeepRead index 同源导出。`serialize.ts:94/215` 对新类型显式双向分支，未落为 generation_interrupted；`stream_accumulator.ts:153/350` 仍 append + JSON distinct。`persistence.ts:109/115` 通过真实消息 serializer/parse 写入和读回 RDB messages。
- Chat 的历史/再生成/active tail 与小说历史/active tail 沿 `AgentMessageRow → ChatMessageActionFooter → ChatMessageAnnotations` 消费实际 message.annotations；Common 精确分发 url_context 与 google_search_suggestions，没有另造引用或查询 chip。
- `ChatMessageCommon.ets:33/51` 保留 SUCCESS/ERROR/PAYWALL/UNSAFE 真实含义及未知状态原值，失败不标成成功；unsafe 不打开。可点击 URL 与 citation 行至少 44vp，长 URL/标题全文 BREAK_ALL，宽度沿现有消息 100%，仅 8vp 行内边距，不改变宿主 gutter/safe area。
- `GoogleSearchSuggestions.ets:22/47` 将原 HTML/CSS 插入 viewport/color-scheme 文档壳；附着 controller 后按仓内 loadData 编码先例载入，@Prop/@Watch 更新，离开后失效。FIT_CONTENT + SYNC_RENDER + NEVER over-scroll，不设固定 height/maxHeight。已读 owner 缓存的官方 `/tmp/e10-web-fit-content.md`、`e10-web-types.md`、`e10-web-dark.md`、loadData/intercept/new-window 文档，大小/导航 API 符合 API12 范围。
- Suggestions 只有 `onOverrideUrlLoading` 一个外链入口，主 frame 用户手势 HTTP(S) 调用一次 Common 的系统 browser path，并 true 阻止内嵌导航；没有 pageBegin/pageEnd 第二次打开、JS bridge 或脚本执行。multiWindowAccess=false。darkMode 跟随 AppStorage；不启用 force-dark、不重写 Google CSS。
- 模型页局部 V1 palette/watch、44vp toggle/输入/UA 操作、确认条 `16 + navBarHeight` 与原 Scroll 布局已核；实际换行、对齐、高度与亮暗效果仍须设备验收。

## 确认问题与修复复核

1. **P2 已源关闭：移除 Provider override 后确认会恢复旧值。** 基线 removeOverwrite 只落库/清展示，而 this.provider 仍保留旧 m.providerOverwrite，confirm 又从该快照重建。已通知 B/root；最终 `ChatModelParamsPage.ets:332` 成功保存后同步 no-override provider snapshot，并更新实际容器协议。再次确认不再使用旧 override；没有扩大保存链。
2. **P2 已源关闭：来源状态文字主题切换不响应。** 原 Common 的 ERROR/INK3 直接来自 module export let，且只监听 accentColor；active-tail 稳定 key 下可能保留旧颜色。最终一次对应源码复核：`ChatMessageCommon.ets:11/21/25` 已采用 `@State MessageAnnotationColors` 与 darkMode/themePackId 的 `@Watch`，整体替换 palette；状态、unsafe URL、citation 标号分别在 54/57/70 行绑定该状态。`GoogleSearchSuggestions.ets:9/20/60` 的错误文字同样由 dark/theme watcher 更新 `@State mutedColor`。两处均 V1，未改共享 tokens、ChatPage 或 HTML/CSS/导航行为；不用父级重建弥补局部颜色响应。

最终只读结论：两项确认 P2 已完成局部修复并源复核，无剩余确认 P1/P2，UI/调用链 source review 收口。最后复核只读取两个对应组件并更新本报告，没有新 checks，也没有扩展审查范围。主题问题的判断与关闭来自已知 V1 状态机制和真实调用链，没有冒称设备复现或截图验收。

## 外部 C gate

设备离线门继续 pending：真实 Google HTML/CSS 显示、实际 FIT_CONTENT 高度、长来源换行/触控、亮暗颜色、底部 safe area，以及 Google 建议链接（含 target blank）是否确实只打开一次正确外部 URL。源码只有一个导航入口，不等于已实测每个 Web 导航事件。没有用 owner lint 或域层 fixture 代替这些 UI/设备验收。
