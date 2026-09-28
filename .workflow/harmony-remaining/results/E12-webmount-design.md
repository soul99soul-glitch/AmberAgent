# E12 WebMount 余量：源码核实与最小实施合同

2026-09-28，owner `/root/e3_native_mosh`。本次是 **只读准备**：未修改产品、阶段状态或导出 index，未运行测试、构建或设备验证。以下是按当前源码冻结的实施边界；正式实施由 root 在 E12 授权。E11 尚未实施的接口是明确依赖，不能提前记作已接线。

## 结论与范围校正

五项均可提供真实端内路径：站点配置的 OAuth、按站点 DOM 提取、完整 HAR 文件消费及安全请求重放、有实际子步骤执行的网页目标循环、DOM tap。账号/站点应用未配置、页面宿主不在、没有 HAR 来源或决策服务不可用时，给出具体未执行原因。Moli/Playwright/Steel 远程桌面仍属范围③，不新增或伪造远程服务。

**“完整 HAR”必须区别于采集覆盖率。** 当前 Android `wm_fetch_replay` 用的是 document-start fetch/XHR 的 observed GET/HEAD template，并非完整浏览器 HAR；iOS 明确仍不支持 replay/OAuth/site_adapter。E12 的最小真实路径是：导入用户提供的完整 HAR 1.2，保留其结构和来源，按 opaque entry ID 重放其中允许的请求；同时补真实 page fetch/XHR 观察。仅页内 fetch/XHR 覆盖不能标成全浏览器 HAR 采集。若 root 最终要求“内置完整浏览器 HAR 采集”而非“完整 HAR 消费”，仍需额外实证 collector；下述方案不能替其验收。

## 当前调用链证据

| 当前事实 | 源码位置及影响 |
|---|---|
| 端内浏览器是真实 ArkWeb，单 controller；tab 只是逻辑 URL 状态 | `harmony/entry/src/main/ets/platform_impl/WebMountSession.ets:59,143,514,551`。没有后台稳定 WebView 或远程桌面宿主。 |
| page tools 执行前读 global OR assistant、bot/hand、当前站点；逐主框架导航再查 gate | `WebMountTools.ets:100,123`；`WebMountSession.ets:226,533`；`WebMountSites.ets:136,151`。新增工具沿这些现有边界。 |
| `wm_click` 强制人工；`wm_eval` 任意表达式强制人工 | `WebMountTools.ets:244,320`。tap 不能成为关闭 eval 后的绕行口。 |
| `evalJson` 立即 stringify 同步值 | `WebMountSession.ets:473`。返回 Promise 会被序列化，不会自动等待页面 fetch。 |
| `network_inspect` 只有 ≤50 条导航字符串 | `WebMountSession.ets:125`；`WebMountTools.ets:754`。没有 request headers/body、响应、XHR 或 fetch template registry。 |
| 已有飞书 PKCE、系统浏览器、手动粘贴回调、token 兑换与过期刷新 | `webmount_oauth.ts:12,23,32,67,89`；`WebMountOAuthSupport.ets:21,115`；`SettingExperimentalWebMountPage.ets:97,120,135,181`；`feishu_docs_client.ts:33`。现存流程允许裸 code，完整 URL 中 state 才核对；没有通用应用配置或自动回调 listener。 |
| Harmony 站点专用 API 工具目前只有飞书文档族 | `ConversationRunTools.ets:199`；`FeishuDocsTools.ets:59`。注册九个种子站点不代表九套 API adapter 已移植。 |
| 真正运行入口已从 Page 转入 E6 Service 构造目录 | `ConversationRunTools.ets:191,253,272`。root 接 E12 的位置是该目录/adapter，而不能只改 ChatPage 旧构造分支。插件中心另有 `AppContainer.ets:1220` 当前 oracle。 |
| 当前页面 owner 与 actual inFlight 拒绝跨会话/重入 | `ConversationBrowserGuard.ets:13,37,53`；`PluginWebMountGate.ets:23,37,45`。离页后旧工具链不可复活；scope 的晚回调不得操作 B 页面。 |
| 子步骤权限、captured target、save-before-effect 有现成接口 | `recipes/ports.ts:24`；`recipe_loop.ts:51`；`tool_loop.ts:140,290,515`。现只识别 recipe/plugin 两类 adapter/checkpoint，不能假称已支持 goal。 |
| 插件 WebMount 能力是显式白名单 | `plugins/broker.ts:6`，目前无 tap/site_adapter/goal/OAuth/replay。新增只读 adapter 与 tap 可以按既有声明更新；OAuth、HAR 原始数据和嵌套 goal 不默认变成插件 hostcall。 |

路径无目录前缀的 `*.ets` 在 `harmony/entry/src/main/ets/platform_impl/`；domain 文件在 `harmony/chat/src/main/ets/chat/`。上表是当前观察，不是实现验收。

## 官方能力核实

已读 `.agents/skills/harmony-arkts/SKILL.md`；沿 wrapper 搜索并阅读离线官方文档：

- `faqs-arkweb-59`：`javaScriptOnDocumentStart` 在文档初始化时注入，跳转后的新文档会执行。真实 SDK `ets/component/web.d.ts:10493–10511` 声明 since 11，符合本仓 API12。
- `web-app-page-data-channel`：`createWebMessagePorts`/`postMessage` 可建立页面与应用数据通道，销毁前关闭。真实 SDK `@ohos.web.webview.d.ts:4543–4585` 声明基础接口 since 9。不是此批必需的新桥；优先用下述局部异步结果槽。
- `web-scheme-handler`、`faqs-arkweb-83`：普通 `onInterceptRequest` 不提供 POST data，也不能取得全部请求头。SchemeHandler 在 API12 可主动接管请求并提供响应；它不是“不干预请求即可旁观完整原始响应”的 HAR API。用宿主 HTTP 重新发一次请求会改变 browser/Cookie/CORS/ServiceWorker 路径，不能作为全浏览器原请求采集证明。
- 当前 SDK `@ohos.web.webview.d.ts` 没有 WebviewController 的触摸注入入口；已查 `injectTouchEvent` 离线命中的是 WindowManager C/C++，不能据此假设普通应用 API12 获得系统触摸注入能力。本批 DOM synthetic tap 的能力声明据实际事件与结果验证，不承诺 native trusted gesture。

通用 OAuth 按外部浏览器 + Authorization Code/PKCE S256 处理；不把 WebMount 隐藏 WebView 当登录浏览器。[RFC 8252](https://www.rfc-editor.org/rfc/rfc8252.html) 给出 native app 外部 user-agent/PKCE 约束。HAR 按 [HAR 1.2 原始规范](https://www.softwareishard.com/blog/har-12-spec/) 校验结构与有意义的计时字段，无法测量的可选时段不编造数值。

## 1. 通用 OAuth：站点配置 → 真实授权 → API 消费

最小站点应用配置包含 `siteId`、authorization/token endpoints、clientId、可选 clientSecret、redirectUri、scope、token request encoding（form/json）与 client authentication（none/body/basic）、允许消费 Bearer 的 API origins。用户在已有 WebMount 设置页填入配置；不是工具调用临时传入任意 endpoint 或 secret。配置绑定实际注册站点，允许的 API origin 与站点/显式配置相交，不能靠 OAuth 自动扩大 Stations。

公开配置按站点 ID 存 KV；secret/token 持久化若新增，复用 E2 已验证的 Asset 内随机 AES key + 私有文件密文方式，在局部 OAuth credential port 实现。`ChatKvStore` 是普通 Preferences，现有飞书 token KV 不能被报告成安全仓。不要为此重构 SSH credential module 或所有 provider token。飞书既有键先兼容读取，用户迁移后才改消费者；不在此次准备阶段写迁移。

冻结流程：

1. `wm_oauth_connect(site_id)` 按当前站点配置启动，沿真实工具审批后用系统浏览器打开 authorization URL，返回 `status: needs_user_action`、站点/作用域与设置入口。不返回 verifier、secret、token。没有配置则 `oauth_not_configured`，零授权/兑换请求。
2. 设置页与工具共享同一小 service 的 pending flow，PKCE/state 由现存 crypto port 生成，仅内存保留；重启明确重新发起。generic 完成入口只接受完整回调 URL，标准 URL 解码、redirect binding 与本次 state 必须一致；这不是把现有飞书裸 code 的例外推广到通用流。
3. 用户在系统浏览器完成登录后粘贴完整回调 URL，由设置页兑换。此路径不声称已有 loopback server、App Link 自动接收或远端 callback relay。上游不允许其应用配置的 redirect/PKCE 时返回真实 OAuth 错误，不另设替代认证。
4. `wm_oauth_refresh(site_id)` 使用已保存 refresh token 和固定配置 endpoint；失效/撤销返回 `oauth_reconnect_required`。token 轮换成功才替换旧 token；每次请求使用 `fetchWebMountRequest` 的许可/取消检查。
5. 必须有实际 token 消费者：`wm_signed_fetch` 可选 `oauth_site_id`，只由宿主添加该站点 Bearer 到预先配置 API origin。该模式使用 E5 已有 RCP `autoRedirect=false` 先例的局部请求适配，每个 Location 在发送前核 configured origin；不能只核起始 URL 后让旧 HTTP client 自动跨域转发 Bearer。请求外沿仍复用 `fetchWebMountRequest` 的许可/取消门。OAuth token 不进入模型输出或页面脚本。飞书现有 API client 保留真实消费者并共享配置/refresh 的确定映射。

设置显示“待回调/已连接/过期/失败”，退出删除本绑定 token。应用 endpoints/clientId/redirectUri 改动使旧 pending flow 与 token binding 失效，避免用旧批准/旧 token 配新地址。Generic OAuth 不需要新增后台 OAuth 恢复队列；不把等待用户当作登录成功。

## 2. site_adapter：固定站点 profile 的真实 DOM 数据

`wm_site_adapter(site_id?, limit?)` 只读当前已授权、ready 页面。站点 ID 缺省由当前 URL 匹配；显式 site ID 与当前 URL 不符直接拒绝。返回 `site_id`、当前 URL、`fields`/records、匹配数量和 `truncated`，不是给 generic `wm_extract` 换名称。

使用 Android `app/src/main/assets/webmount/profiles/*.json` 中九个 Harmony 种子站点的 `interactive_selectors`，移植 **有限的只读提取规则**：HN story/comment/user，Reddit post/comment，GitHub README/code/issue/comment，Bilibili title/uploader，X timeline/tweet/author，微博 feed/text/author，掘金 article/title/author，知乎 answer/question/author，飞书 title/block/outline。字段只提取 DOM 可见文本和公开链接，不读输入 value、cookie/storage、HTML 或 arbitrary page function。现成 profile 的 signing/permissions/script 字段不因此获得执行权。

profile origins 与现行 Stations 授权同时匹配；原登录子域 seed 不授权其他主站。例如注册 `passport.bilibili.com` 不自动授权 `www.bilibili.com`，需要用户已有站点配置允许实际页面。自建无 profile 返回 `site_adapter_unavailable`；已知站点但当前页面未匹配字段返回 `site_page_unmatched`，附现有 generic `wm_extract` 使用提示，不伪造 API 数据/登录态。选择器来自现存 profile，真实站点 DOM 是否仍匹配属于后续实测层。

这不包含另移植七套 Android REST client、批量写站点、Bili WBI、知乎签名或 profile_synthesize；飞书专用 REST 工具仍可单独使用。

## 3. HAR 与 fetch_replay：真实来源、opaque ID、实际网络

新增一个局部 browser network ledger：文档初始化时包装真实 fetch/XHR，记录 method、绝对 URL、状态、响应类型/结构、文档 ID 与 monotonically increasing seq，≤200 项。包装保持原调用语义，不消费原 Request/Response stream。缺/受限/二进制 body 标注不可完整采集；Model 只见 URL query 去值、摘要与 opaque request_template_id。现导航日志保留为 navigation 字段，不与 network records 混同。

完整 HAR 入口是 WebMount 设置页导入用户选择的 HAR 1.2 文件，验证 log/version/entries/request/response/timings 后保留来源 archive ID；不借工具任意读取私有文件。导入与 page ledger 使用相同内部 replay template 形状。原始 credentials 留宿主私有数据，不回显 Model；导入有明确大小/entries 上限（采用现有工具数据预算，不建无限 archive 仓）。没有实际导入来源或实际观察模板时，`wm_fetch_replay` 返回 `request_template_unavailable`，不能从模型自行给的 URL/body 注册“观察结果”。

冻结 `wm_network_inspect` 增量输出 `capture_coverage`（`page_fetch_xhr` 或 `imported_har`）、source/archive ID、templates 与 replayable/reason；保留现有 navigation 信息。`wm_fetch_replay(session_id=main, request_template_id, max_chars?)`：

- template 必须来自本 session/来源记录，doc/origin 变化后重新核现页。完整 HAR 可含 POST 等条目，但 v1 replay 与 Android 真实边界一致：**GET/HEAD、当前同源、非 mutation-like endpoint**；其余明确 `replay_not_supported`，不调用网络。不能把“导入完整结构”描述成“任意方法重放”。
- 每次强制人工批准；URL/method 从固定 template 取，调用参数不能替换 endpoint/header/body。Cookie 由页面 `fetch(credentials: include)` 带入，HAR 内过期 Authorization/Cookie 不灌回页面，也不额外扩大 origin。
- 真实 `fetch` 必须在该 ArkWeb 页面执行并等待实际响应。给 Session 增加局部 `evalAsyncJson`：脚本把结果写入按 request ID 的页面临时槽，应用沿已有轮询形式等结果。deadline/AbortSignal/document ID/原 owner 在每轮与结束时检查；取消用该页面 fetch AbortController，detach 后不操作 B 的槽或 reset B。完成删除本槽，不新增通用 browser RPC 框架。
- 返回实际 status/content type/预算内正文/truncated、source ID；敏感响应头与凭据不出工具。重定向跨 origin 不继续 credentialed replay（页面 fetch 使用 `redirect: error`），网络失败给实际失败而非成功空对象。

**内置完整浏览器 HAR 采集仍未证明。** Document-start wrapper 不覆盖 ServiceWorker、跨域 frame、资源下载和内核全部响应头。API12 SchemeHandler 的主动代理会改变现有网络路径，不在最小批次中加入全流量代理。无真实全量 collector 时展示该覆盖范围；完整 HAR 来源缺失可明确 unavailable。这是能力证据边界，不是给范围③增设服务。

## 4. run_goal：E11 决策 + 当前目录子工具 + 原子 checkpoint

使用 iOS 实际入口 `ChatToolRuntime.swift:5774,5807` / `IOSJevWebMountLoop.swift:36` 作为行为参考：宿主编排，观察与执行走实际工具链；Jev DONE 不能证明完成。复用 E11 的 `web_actions` 决策端口、OFF/SHADOW/ACTIVE 与取消/预算，不新增另一 provider/model 配置服务。当前 `JevSupport.ets` 只有四种 wired purpose、`jevEvaluate` 没有传 AbortSignal，E11 必须先补实消费者与取消，缺失时 E12 返回 handback，不自动降级为主模型反复规划。

`wm_run_goal(session_id, goal, completion_text, allowed_actions?, draft_value?, max_action_decisions?, max_seconds?, max_no_progress?)` 只允许观察/滚动/导航链接/选择/草稿文本/只读搜索，排除一般提交、发布、删除、支付、登录、验证码、任意 eval。allowed_actions 只能收窄。初版更小有界预算：最多 12 动作决策、60 秒、3 次无进展；不用照搬 iOS 的缓存重放/100 步/600 秒大系统。

Domain 新增独立 goal adapter 和 `webmount_goal_v1`，通过现有 ordered batch 的实际 permission/dispatcher/save port 推进，不把动态 goal 假装 installed Recipe。root 在 tool_loop/recipe_loop 加一个明确 optional slot/支持判断，不新增通用 adapter registry。checkpoint 保存固定 goal/边界、current observation、已有 steps、pendingStep 与 phase；每个副作用 child 的 captured metadata + started 必须先落父 checkpoint，再 dispatch。ASK 时父停住，approval 卡显示实际 child 工具/参数；批准只推进这一步，不能外层 auto/允许名称信任一口气授权所有步骤。混合 batch 的后续兄弟工具与 Recipe/plugin 沿现有顺序停住/续跑。

**避免 E6 真实重入冲突：** 若把外层 goal 放入 `guardConversationBrowserTools` 的 `inFlight` 后再调 guarded child，会拒绝自身。goal 仅做宿主编排/原 lease 检查，actual child 操作才拿已有 inFlight；不直接执行 tool.execute。所有 child 来自本轮已 filter/profile/scoped 的当前 oracle，不能复用父全量工具目录或恢复已禁用工具。

每轮观察带 document ID/revision/snapshot，动态决定后的动作只绑定当轮 ref；等待人工期间页面变化时废弃旧 child，重新观察及重新审批新参数。不能给旧 approved ref 换元素。tap/goal 共用最小 document token/MutationObserver revision 与有效 ref 检查；观察器过滤 WebMount 自己写入的 ref 属性，避免 extract 自己使 snapshot 失效。无需为全部旧 WebMount 工具做旁路重构。

每步后观察，completion_text 只从实际 URL/标题/可见文本核验；仅 `dispatched:true` 或 Jev DONE 不记 completed。重复空滚/不变状态计 no-progress。hand 模式、不可用决策服务、budget、host loss、取消分别 handback/cancelled；started 后无法确定的动作返回 outcome_unknown，不自动重放。重启丢失 browser document 也不能恢复假页面。

## 5. tap：实际 DOM hit-test 与事件变体

双端现有 tap 本质也是 DOM 命中/点击：Android `bridge.js:986` 从 viewport 坐标 `elementFromPoint` 后发 mouse/down/up/click，iOS `IOSLocalToolExecutor.swift:4031` 点击当前 ref/坐标目标。并不证明 native trusted touch。

鸿蒙 `wm_tap` 冻结接受当前 snapshot ref（优先）或 viewport x/y，参数 JSON 编码，真实 hit-test/可见目标检查。在实际支持 Touch/TouchEvent 的页面按一次 touchstart/touchend dispatch；正确传 start 的 touches 与 end 的 changedTouches/空 touches。如果事件未 preventDefault，再执行一次 DOM click 以产生链接等默认动作，避免触摸 listener 已消费时双执行。页面不支持对应 constructor 就明确 `touch_event_unavailable`。回执固定 `event_mode: dom_touch`、`synthetic: true` 及是否执行 default click；不自动静默改成其他事件模式。

事件列表/cancel 语义按 [W3C Touch Events](https://www.w3.org/TR/touch-events/)；script dispatch 的 isTrusted 为 false，见 [DOM Standard](https://dom.spec.whatwg.org/#dom-event-istrusted)。这是 DOM synthetic 行为；真正的平台结果仍需前述 ArkWeb 事件计数/默认动作验证。

`mandatoryApproval=true`、`allowsAutoApproval=false`，沿现有站点/bot-hand/page owner gate。回执分别标 `event_mode`、`dispatched`、`page_changed`、`goal_verified=false`；真实结果由下一观察验证。Canvas/cross-origin iframe 中要求 native trusted event 的行为返回 `native_touch_unavailable`，可交用户接管；不新增 WindowManager 注入或远程桌面。

## 窄 ownership 与实施顺序

| Owner 范围 | 负责内容；禁止交叉改动 |
|---|---|
| domain OAuth/HAR/profile worker | 新局部 domain OAuth config/protocol、HAR parser/template ledger、只读 profile projection；必要 ports/models。不改 index、tool_loop、DI、Page。 |
| platform browser/OAuth worker | `WebMountSession.ets` 的 doc token/async results/capture，新增 `WebMountAdvancedTools.ets`、局部 OAuth runtime/credential adapter、document-start rawfile；原 `WebMountTools.ets` 只接增量 factory。一个 owner 独占这些实际共享浏览器模块。 |
| goal worker | 新 domain goal adapter/checkpoint/decision port，必要 focused fixture。只交所需 root 接线字段；不自行改共有 tool_loop/recipe_loop/权限 UI。 |
| settings/UI worker | 只改现有 WebMount 设置页的小分区或提取小卡片：站点应用配置、OAuth pending/complete、HAR 导入/覆盖率。不改浏览器 core/DI。 |
| root integration | E11 decision adapter、tool_loop/recipe_loop optional goal slot、当前 Service/subagent/中心 oracle、导出与 route、approval summary 小接线；独立逻辑/调用链/UI review及统一验证。 |

先 OAuth/profile 的纯结构与端内消费者，再 doc-start/async fetch/真实 HAR 来源，再 tap 与 goal 的 child 宿主接线。五个功能的单入口都有消费者后才从登记移除；注释里其他 profile_synthesize/recipe_candidates 项不顺手变更范围。

## 必要验证及可宣称层

正式实施只保留关键影响检查，不写站点 × auth × method × 模式大矩阵：

1. 一个 domain 组：真实配置 → S256/state/URL 编解码 → callback 兑换/refresh/固定 Bearer origin，错误 state 零兑换；完整 HAR entry 与真实 page ledger 的 opaque template/预算/禁方法；一份代表站点 HTML 的真实 profile projection（不能当实际站点已验收）。
2. 一个真实 loop 组：actual tool_loop/dispatcher/保存与 goal adapter 的两步目标，ASK 停 sibling，批准后 started-before-effect且一次执行；完成来自新 observation；host loss/started unknown 不重放。E11 服务端/ArkWeb用 controlled port 时明确只是协议/宿主逻辑验证。
3. root 一次受影响必要 ArkTS lint/types/build；新增实际 SDK API 必须按 API12 回喂诊断。统一实际 ArkWeb 本地站点：页面自身发 fetch/XHR → inspect template → 批准 replay → 本地 server 记录真实再次请求与响应，完整 HAR 导入来源/同源拒绝，touch 页面事件计数/目标核验，active goal 至少两步完成。没有真实决策凭据只验明确 handback，不能报告 active 外部服务 PASS。
4. root 必要亮/暗色配置、callback pending/failed、HAR覆盖提示、child approval 参数截图，关注原 CardRow/CardGroup/GUTTER、长 endpoint 换行和键盘区。取得证据后收口，不反复巡检设备。

实际 OAuth 站点应用、真实第三方 DOM、active Jev 与最终设备网络验证，各自报告已验/未验/具体 unavailable，不用本地 fake token/controlled JS 或构建成功替代。此准备没有产生任何上述 PASS。
