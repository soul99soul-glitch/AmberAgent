# E10 Google 来源与 Search Suggestions UI

2026-09-28。E9 源检查点 `caa5b9d50` 后实施；已读 harmony-arkts skill、E10 frozen contract 和设计。ONLY 修改 `components/ChatMessageCommon.ets`，新增 `components/GoogleSearchSuggestions.ets`；没有修改认证、详情页、ChatPage、annotation 定义、index 或 serializer，保留并行 owner 的编辑。

- 原消息链 `AgentMessageRow → ChatMessageActionFooter → ChatMessageAnnotations` 直接消费唯一共享 annotation。Common 新增 `url_context` 与 `google_search_suggestions` 两个精确分支；原 citation 仍直接打开实际 URL，没有重新造来源或查询 chips。
- URL 读取状态按实际 `URL_RETRIEVAL_STATUS_SUCCESS/ERROR/PAYWALL/UNSAFE`（及同名简写）显示“已读取/读取失败/付费访问限制/因安全原因未读取”；其它值显示未确认与原值，不猜成功。被服务商标为 unsafe 的 URL 保留可读来源但不打开；失败/付费/未确认来源仍可按原 URL 手动浏览，文案不变成成功。
- 引用标题、长 URL 全文换行，原单行截断已移除；原 citation 行和新 URL 行至少 44vp，宽度沿消息内容 100%，使用现有 accent/token、8vp 行内边距。不改变消息外层 gutter 或 safe area。
- 新组件 64 行，props 为 `html:string` 与 `onOpenUrl:(url:string)=>void`。返回 HTML/CSS 原样插入 viewport/color-scheme 文档壳，未改提供方片段/CSS、未强制重着色；controller attached 后沿现有 DeepRead 的 URL 编码与 `loadData(...,' ',' ')` 加载路径。V1 `@Prop/@Watch` 处理更新，离开失效。没有 JS 执行、JS bridge、伪造建议文本或通用浏览器框架。
- Web 使用实际自适应 `layoutMode(WebLayoutMode.FIT_CONTENT)` + `RenderMode.SYNC_RENDER`，不设置固定 height/maxHeight 或测量轮询，宽度 100%。AppStorage darkMode 驱动 WebDarkMode On/Off，Google 原 CSS 的 dark media 规则保持；forceDarkAccess=false。
- 仅一个 `onOverrideUrlLoading` 导航拥有外链处理：HTTP(S) 主 frame 用户手势调用一次 Common 的原系统浏览器路径，返回 true 阻止内嵌导航；不在 pageBegin/pageEnd/第二拦截回调再次打开。multiWindowAccess=false，无新 Web 窗口。真实设备点击/target blank 是否触发预期回调仍属 root 外部 UI 门，未用源码冒称实测。

## 官方 API12 依据

全部先经仓内 devecocli 本地官方 docs search/read，随后核同机官方 SDK `component/web.d.ts` 与 `api/@ohos.web.webview.d.ts` 的 since/signature：

- `开发指南/ArkWeb_方舟Web/Web渲染和布局/Web组件大小自适应页面内容布局/web-fit-content`：自适应网页真实高度，与 ArkUI 滚动整体布局；推荐同步渲染与关闭 over-scroll，FIT_CONTENT 不用 height 改高。缓存 `/tmp/e10-web-fit-content.md`。
- `API参考/ArkWeb_方舟Web/ArkTS_组件/Web/Enums/arkts-basic-components-web-e`：FIT_CONTENT/OverScrollMode API11，RenderMode API12，WebDarkMode API9。缓存 `/tmp/e10-web-enums.md`。
- `API参考/ArkWeb_方舟Web/ArkTS_组件/Web/Types/arkts-basic-components-web-t`：OnOverrideUrlLoadingCallback API12，true 阻止加载。缓存 `/tmp/e10-web-types.md`。SDK request 的 getRequestUrl/isMainFrame/isRequestGesture 均 API8 起；onControllerAttached API10、WebviewController.loadData API9。
- `FAQ/Web框架/Web开发_ArkWeb/Web组件的请求拦截接口使用场景总结/faqs-arkweb-195`：用户导航拦截/iframe/POST 的真实范围，不宣称覆盖未有的导航事件。缓存 `/tmp/e10-web-intercepts.md`。
- `FAQ/Web框架/Web开发_ArkWeb/如何解决webview_loaddata白屏问题/faqs-arkweb-57`：CSS # 与空格 base/history 参数规则；现仓 DeepReadArticlePage 的相同编码先例已核。缓存 `/tmp/e10-web-loadData-faq.md`。
- `FAQ/Web框架/Web开发_ArkWeb/Web页面如何适配深色模式/faqs-arkweb-112`：darkMode 使用页面自己的 media CSS，force dark 会改色，本组件不启用强制。缓存 `/tmp/e10-web-dark.md`。
- `开发指南/ArkWeb_方舟Web/设置基本属性和事件/在新窗口中打开页面/web-open-in-new-window`：多窗口与 ControllerHandler 生命周期；本组件不创建新窗口。缓存 `/tmp/e10-web-new-window.md`。

## 必要验证

一次 2 owned 文件 lint `/tmp/e10-source-ui-lint.log` 退出 0，0 error / 1 warn（现成 Common 组件的“优先 Builder”性能建议；独立 Web 生命周期组件是合同要求，不为该建议重新合并组件）。`git diff --check` 通过；共享命名 DTO 与 Web 参数/返回值按真实 domain/官方 SDK 核对。两文件均 ArkUI DSL，不拿普通 TS parser 的抽样结果冒称完整类型编译；root 统一实际 CompileArkTS 与独立 review 收口。未新建测试、运行测试/应用构建/签名、操作设备或发起网络/Google 账户请求。亮暗样式、实际网页高度与单次链接点击的设备 C 门保持待验。

独立 B review confirmed 的实际主题 P2 已精准修复：流式尾消息 key 不随 dark 变化重建，静态 imported INK3/ERROR 不主动触发子 V1 组件渲染。Common 增加本地 `@State` 色板快照与 darkMode/themePackId `@Watch`，状态/unsafe URL/引用编号均消费快照；Suggestions 的原 WebDarkMode 继续直接响应 dark，同时 loadError 提示的 muted 字色改用同监听的本地 `@State` 字符串。主题变化不重新加载或修改 HTML/CSS，无 shared token/ChatPage 修改。受影响两个文件仅一次 lint `/tmp/e10-source-ui-themefix-lint.log` 退出 0，官方输出 `No defects found in your code.`；root 收口最终真实 compiler，不新建测试或操作设备。
