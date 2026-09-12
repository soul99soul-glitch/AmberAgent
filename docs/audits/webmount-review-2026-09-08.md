# Android WebMount 审查与修复记录

审查目录：`/Users/arquiel/Downloads/AI/AmberAgent/android`。基线：`0afce4365989cf9472ee91fe96e585f4ee9a8c3c`。

覆盖设置页、站点注册与持久化、Cookie/OAuth 登录、七个原生 adapter、WebView 会话与 RPC、JavaScript 操作/观察/网络、工具权限与持久化恢复、Recipe 调用链。修复与本报告一并提交；未推送或安装到设备。

| 编号 | 级别 | 真实触发与影响 | 修复位置与结果 | 验证 |
| --- | --- | --- | --- | --- |
| WM01 | P1 | `wm_eval`、`wm_site_remove`、`wm_signed_fetch(POST)` 执行中断，静态副作用分类可能仍是只读，恢复时跳过结果未知处理。 | `ToolRegistry` 按真实参数分类；`DefaultRunKernel`、`AgentToolDispatcher`、`RecipeRunner` 传入实参。写操作进入 `OUTCOME_UNKNOWN`，GET 保持只读。 | Dispatcher → Room ledger → RunRecoveryService 定点测试 |
| WM02 | P2 | Recipe 包含 signed POST/动态 method，却在导入预览中计为零写步骤。 | `RecipeManifest.toDefinition` 用 step args 求 invocation policy；未解析的 method 保守计写，执行时再按解析结果判断。 | `RecipeRuntimeTest`；独立复核运行时消费 |
| WM03 | P2 | 设置页探测中离开页面，adapter 吞掉协程取消后，Manager 将取消持久化成站点 ERROR。 | `WebMountManager.probe` 检查父协程取消，恢复探测前状态并重抛。 | 实际 probe API、内存状态和新 Manager 的持久化读取测试 |
| WM04 | P2 | 导航登记 LOADING 后，协程在 Main 执行前取消，页面未导航但会话卡在加载中。 | `SessionHandle` 在 Main 同一段内登记请求并导航；失败、超时、取消只清理所属请求。 | 排队 Main dispatcher 的取消测试 |
| WM05 | P2 | RPC 已登记 pending，Main dispatch 前取消或 evaluateJavascript 抛错，清理 finally 尚未进入。 | `callBridge` / `callPageFn` 将 dispatch 纳入既有 finally。 | 编译与独立调用链复核；真实 WebView 异常注入未做 |
| WM06 | P2 | 页面跨 origin 重定向后，仍宣称旧 origin 的 document-start 注入覆盖。 | `SessionHandle` 根据回调 URL 和实际已安装 handler 更新覆盖字段。 | 独立复核；Robolectric coverage 用例受 provider 支持限制 |
| WM07 | P1 | 点击工具给出的内部登录 URI 时，外部 ACTION_VIEW 无法可靠启动非导出的登录 Activity。 | `Context.openUrl` 对精确的 WebMount 登录路径使用显式 Activity Intent，保留参数和 Context 启动标志。 | `WebMountLoginLinkTest` |
| WM08 | P1 | 自定义站点或仅有 profile 的站点收到登录 helper，InlineLoginActivity 却只识别原生 adapter，直接退出。 | 登录目标依次解析 UserSite、带 cookie hint 的 profile、adapter；profile 登录不擅自添加设置条目。 | 自定义 profile 目标测试与 helper → Activity 独立复核 |
| WM09 | P2 | 设置页、内联登录和 `wm_stations` 使用不同 cookie 判断；X 只有 ct0 也可能被报已登录，合成 profile 的登录 cookie 在 UI 不生效。 | 三处复用 `WebMountLoginTarget` 和 CookieSnapshot，检查完整 required set，读取 profile hint。 | 现有 Cookie/登录检测测试、新 profile 目标测试、调用者复核 |
| WM10 | P2 | 缺 OAuth token 时 helper 引导 Cookie WebView；反向地，飞书 Cookie 登录成功又被 OAuth-only adapter probe 阻止完成。 | OAuth helper 指向设置凭据/Connect；Cookie 登录只绑定具备 COOKIE 能力的 adapter probe。 | 登录/设置/工具/Controller 链路独立复核 |
| WM11 | P2 | 过期 OAuth token 仍显示 logged_in/Connected，重连按钮被隐藏。 | 物理存在与有效期分开；有刷新材料时状态 unknown，缺材料才 logged_out；过期时保留 Connect 和 Disconnect。 | 既有过期边界测试、OAuthClient 刷新链路与 UI 消费独立复核 |
| WM12 | P2 | 多个纯中文站名均生成 `user_site`，后续站点无法添加。 | Settings 和 `wm_site_add` 共用 `userSiteId`；非 ASCII/截断名称追加稳定摘要，保留普通 ASCII 名称规则。 | 两个中文站点添加与重载测试 |
| WM13 | P2 | 用户明确选择匿名站点，重新创建 Registry 后旧迁移又把它改成 COOKIE。 | 用户匿名迁移受 seed version 约束，只执行一次。 | 新旧持久化数据迁移测试 |
| WM14 | P2 | 删除先移除 Registry，再清理 Cookie/OAuth/profile；中途失败后重试缺少站点信息。 | 工具和设置页在清理结束后才删除 Registry 条目，保留中断后的重试信息。 | 两个入口及清理顺序独立复核；进程终止注入未做 |
| WM15 | P1 | 飞书 callout 把完整子块对象嵌入 `children`，与 API 的字符串 ID 契约不符，写入失败。 | 使用单个 `/descendant` 请求，`children_id` 和 `descendants` 关联 callout 与 text；普通块路径不变。 | Ktor MockEngine 请求/返回测试；官方 SDK 契约核对 |
| WM16 | P2 | 飞书 client probe 把所有 HTTP/API 错误吞成 false，adapter 的 token 失效分类无法执行。 | probe 传播真实错误给 adapter；合法空文件列表仍成功。 | 生产编译、独立错误传播链复核 |
| WM17 | P2 | Reddit 评论分页中的 `more` 被解析为缺内容的 Post。 | 仅 t1/t3 构造 Comment/Post，其余占位节点不伪造内容。 | Reddit MockEngine 解析测试 |
| WM18 | P1 | 新 snapshot 从 1 重编号，旧 ref 可能命中新元素并执行错误操作。 | ref 包含页面 realm 与 snapshot 编号；缓存恢复保留原 ref 与指纹校验。 | JS 行为断言和缓存恢复独立复核 |
| WM19 | P2 | `text=...` 先命中包裹按钮的 div，工具返回成功但按钮未触发。 | 优先匹配真正可操作元素，再消除包含同名后代的祖先候选。 | JS 按钮事件断言 |
| WM20 | P2 | 禁用 click/select 或只读/禁用 type 无效果，却返回成功。 | 对真实控件状态返回明确错误；禁用 option 不再被程序强选。 | JS 控件行为断言 |
| WM21 | P2 | 标准 `label for` / 包裹式 label 的输入框被输出为无名控件，难以定位。 | 可访问名称读取标准 `labels` 关联。 | JS label 与目标描述断言 |
| WM22 | P2 | fetch 已成功返回 Response，但读取响应体失败时没有回传 RPC，Kotlin 一直等到超时。 | `fetch_replay` 为 body Promise 增加失败返回。 | JS body rejection 断言 |
| WM23 | P2 | 小预算 observe 后立即加大 text/node/visual 预算，仍命中旧的截断结果。 | 规范化后的观察参数参与缓存 key，并与实际 bridge 参数共用。 | 既有缓存测试新增预算隔离用例 |
| WM24 | P2 | 正文 4000 字以后变化、输入值/勾选/选中状态变化不影响语义指纹，缓存或 action_verification 误报未变。 | 默认文本覆盖 60000 字符、交互节点覆盖 300 个；加入控件状态，值只参与哈希。 | JS 正文尾部与控件状态断言 |
| WM25 | P2 | visual 返回完全离屏候选，但后续 visual_read 只裁视口；零候选预算也被默认值覆盖。 | 视觉候选限于视口相交区域，保留部分可见；observe/visual 的零预算均生效。 | JS 视口和零预算断言 |
| WM26 | P2 | 网络信号过滤的 `/log` 子串同时屏蔽 `/login`、`/logout`。 | 只按 log/logs 路径段过滤，登录请求正常更新信号。 | JS 网络事件断言 |

飞书 callout 契约来源：[官方 SDK 的 Docx 数据模型](https://github.com/larksuite/oapi-sdk-go/blob/v3_main/service/docx/v1/model.go)与[官方 SDK 的 descendant 请求路径](https://github.com/larksuite/oapi-sdk-go/blob/v3_main/service/docx/v1/resource.go)。这证明请求形状；未使用真实账户写入验证。

## 验证记录

- Kotlin/Android 定点任务通过：`122` 个测试，`121` 通过，`1` 跳过，`0` 失败，涉及 `27` 个测试类；同时完成 app 主代码与测试源码编译。
- 跳过用例：`SessionHandleLifecycleTest.cross-origin page callback reports page-finished coverage`，原因是 Robolectric provider 不支持 document-start 脚本注入。跨 origin 覆盖行为只有源码复核证据，未宣称获得真实 WebView 验证。
- JS 行为脚本 `scripts/webmount-bridge-regression.cjs` 通过，覆盖本次交互、观察、缓存 ref 恢复与网络错误；`node --check bridge.js` 和本次文件的 `git diff --check` 通过。
- 独立 subagent 在实现后复核登录、会话/RPC、持久化恢复、Recipe、适配器、缓存、删除和 JavaScript 操作链路，最终未发现本次修改范围内的新阻塞。发现的可达遗漏已经修复；未启用的写探针与未证实的真实浏览器候选没有纳入修复结果。

Kotlin 验证命令（在上述临时目录执行）：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :app:testDebugUnitTest \
  --tests 'app.amber.feature.webmount.*' \
  --tests 'app.amber.feature.runtime.AgentToolDispatcherLedgerTest' \
  --tests 'app.amber.core.ai.DefaultRunKernelDurableTest' \
  --tests 'app.amber.feature.recipe.RecipeRuntimeTest' \
  -Pksp.incremental=false --offline --console=plain
```

JS 验证命令（原工作区，使用环境已有 jsdom，未安装新依赖）：

```sh
NODE_PATH=/opt/homebrew/lib/node_modules/oh-my-opencode-slim/node_modules \
node scripts/webmount-bridge-regression.cjs
```

构建日志：`/private/tmp/webmount-isolated-test.log`。测试结果：临时目录中的 `app/build/test-results/testDebugUnitTest/`。

共享原工作区在本次验证期间有其他任务持续改动，曾在 `StoredResponseStopCancelTest` 与 Cron 编译处阻断。最终测试使用 `/private/tmp/webmount-verify-1a2g4361`：从上述 HEAD 建立临时副本，仅复制本次涉及的文件；`ContextUtil.kt` 仅带入内部登录的 import 与路由，保留基线图片导出代码。原工作区的其他改动没有被覆盖。

## 保留的设计与证据边界

- 滚动或布局变化后拒绝陈旧 ref 是既有保护，未改成自动猜测目标。
- `semantic_idle` 与 `network_idle` 条件不同，未把所有网络 pending 强塞进前者。
- 飞书浏览器 Cookie 登录与 OpenAPI OAuth 是不同用途，均保留。
- 未配置 cookie 名称的自定义站点继续使用 unknown；未知状态不等于已登出。
- `runWriteProbe` 当前没有 WebMount 生产调用者，也没有 adapter 实现；相关修改已撤回，不计入真实生产缺陷。
- 首轮未将只有模拟环境线索的 React 受控输入算入修复；第二轮已取得真实浏览器证据，详见下文。`press_enter` 的既有契约是派发合成 keydown，没有加入可能重复提交的原生 form fallback。
- OAuth 设置页的到期状态在重组时刷新，未新增后台计时任务；请求路径始终实时校验并尝试刷新。
- 未执行真实站点账号登录、在线写入、真机导航/崩溃注入和安装验收。MockEngine、jsdom 与 Robolectric 的结果各自只证明所覆盖层级。

## 第二轮补充审查

在首轮 26 项修复的基础上继续审查，不重复计算已有问题。

| 编号 | 级别 | 真实触发与影响 | 最小修复 | 验证 |
| --- | --- | --- | --- | --- |
| WM27 | P1 | `wm_type` 给 React 受控 input/textarea 直接赋 value，页面显示新文字并返回成功，但 React 状态和表单提交仍为空。 | 使用相应 DOM 原生 value setter，再派发既有 input/change；保留 clear、追加、contenteditable 和 Enter 语义。 | 真实 Chromium + React 18.3.1：修复前状态/提交为空，修复后两种字段的状态与提交均为 `amber`；独立 subagent 复核调用链通过。 |
| WM28 | P2 | `wm_visual_read` 对边缘或部分可见目标扩展 8px 留白后，整框视口校验失败；上游 visual_snapshot 明明提供候选，下游却无法读取。 | 先确认原始目标与视口相交，再将留白和截图框裁到视口；完全离屏仍明确失败。 | public captureRegion 的边缘截图尺寸和完全离屏失败测试通过。 |
| WM29 | P2 | ROI 截图在等待 Main 时被取消，`runCatching` 将取消变为普通 Failed，工具活动被记为完成。 | `captureRegion` 重新抛出 CancellationException，沿工具取消链传播。 | public captureRegion 取消回归通过。 |
| WM30 | P1 | 后台飞书工具刷新 token 时，用户在设置中 Disconnect；旧网络响应随后 putToken，让刚退出的登录态重新出现。 | token store 仅在原 token 仍未改变时保存刷新结果，与 put/clear 共用同步范围；失配丢弃旧刷新结果。 | Deferred 控制的公开 getValidAccessToken → disconnect 回归通过，迟到结果为 null、store 保持已退出。 |
| WM31 | P2 | 多个飞书只读工具同时遇到过期 access token，并发使用同一个一次性 refresh token，部分请求被拒。 | 每 provider 的 refresh Mutex；锁内重读 token，复用其他调用已完成的刷新。 | 两个并发 getValidAccessToken 调用均成功，provider 只刷新一次；官方 v2 契约核对。 |
| WM32 | P2 | HTTP 自定义站点手动导入 Cookie 时无条件添加 Secure，HTTP 请求拿不到登录 Cookie。 | 仅 HTTPS 写入目标添加 Secure。 | public injectCookies 后 HTTP Cookie 读取测试通过。 |
| WM33 | P2 | Bilibili 导入 `.bilibili.com` Cookie，但退出清理只向 www/api/passport 等子域写 host cookie 过期值，父域登录态残留。 | 四个清理入口传入已有 manualCookieFields；按已知 Domain/Path 生成匹配的过期写入。 | 真实 Chromium 复现父域残留与匹配 Domain 清除；四个生产调用者独立复核通过。 |
| WM34 | P2 | Dispatcher 只检查 parallelGroup 非空，同一批中同 session 的 WebMount 导航/读取仍同时执行，共用 WebView 的状态互相覆盖。 | 同批 `wm_*` 出现重复 group 时复用既有顺序分支；独立 session 与非 WebMount 批次保持并行。 | public executeBatch 的同 session 顺序、不同 session 并行两例通过；独立复核通过。 |
| WM35 | P1 | `wm_open(wait=none)` 后马上 signed_fetch，页面尚未就绪，代码用签名请求的 URL 代替页面 URL，通过错误来源做 profile 校验。 | 要求 READY 页面以及非空 currentUrl/committedUrl，移除请求 URL 替代。 | 编译与真实 loadState 更新/签名调用链复核。 |
| WM36 | P1 | `https:///host/path` 通过前缀检查但 origin 解析为空，ProfileBridge 跳过出站 allowlist；shim 的浏览器 URL 解析又将其变为有效 URL。 | 严格解析绝对 HTTP(S) URI 和 host；ProfileBridge 的出站来源参数非空必传，始终校验。 | 真实 createSignedFetchTool.execute 入口拒绝三斜杠 URL；所有 callSign 调用者编译。 |
| WM37 | P2 | signed_fetch 声明接受 JSON body，实际用 string helper 读取，JSON 对象/数组在调用 shim 前抛错；参数 schema 也仍限制 string。 | 原样保留 JsonElement，字符串保持字符串、缺省保持 null；body schema 移除错误的 string 限制。 | 主代码编译与参数 schema → provider 传输 → Kotlin → shim JSON.stringify 调用链复核。 |
| WM38 | P2 | 新站点已保存 loginCookieName，profile_synthesize 省略可选 login_cookie 时没有继承，后续 profile 提示和相关权限缺失。 | 显式输入优先，否则继承 UserSite.loginCookieName，沿既有 profile 生成路径写入。 | 编译与合成 → profile hints/permissions → wm_open/state helper 消费复核。 |

WM27 的实际路径是 `createTypeTool → SessionHandle.callBridge("type") → performType → React onChange → form onSubmit`。验证页使用实际 React 18.3.1 UMD 和仓库中的 bridge.js，没有模拟 React 的私有 tracker。临时复现页及服务位于 `/private/tmp/webmount-round2-browser/`；这是桌面 Chromium 证据，未代替 Android WebView 真机验收。React 的受控输入契约见[官方 input 文档](https://react.dev/reference/react-dom/components/input)。

WM31 的依据是项目实际使用的[飞书 v2 刷新接口](https://open.feishu.cn/document/authentication-management/access-token/refresh-user-access-token)：刷新令牌仅能使用一次，刷新后旧值失效。本轮未迁移 provider 版本，也没有保留已失效旧 refresh token 的 fallback。

WM33 的复现使用 `a.webmount.localhost` 和 `Domain=webmount.localhost` 的合成 Cookie：原来的两种 host 过期写法执行后仍存在，携带匹配 Domain 的过期写法执行后消失。Cookie 替换/删除的作用域依据见 [RFC 6265](https://www.rfc-editor.org/rfc/rfc6265.html)。Robolectric 4.14.1 的 `RoboCookieManager.parseCookie` 将过去的 Expires 直接解析为 null，`setCookie` 因而直接返回，不删除旧 Cookie，同时未实现完整 Domain/Path；该环境不适合验证真实删除语义，相关不成立的测试已移除，未更改生产实现去迎合 shadow。

第二轮保留的边界：未知 Cookie Path 没有扩展为扫描清理；未调用的 destroyAll、幂等关闭不存在 tab、LRU 容量策略、合成 Enter 的原生默认行为均未算作 bug。全页 Bitmap 延迟 GC 和 CSS/native 截图单位仍缺真机影响证据，没有引入资源生命周期或坐标换算框架。真实账号 OAuth、在线签名写入及 Android WebView 真机仍未验证。

复核时撤回了 OAuth 深链接 resume 的延迟消费修改及 fake provider 测试：当前 DI 只注册 `FeishuOAuthProvider`，且 `requiresLoopback=true`，生产 connect 不写 pending、不经过 dispatcher resume。该候选只有测试消费者，不能计为当前生产缺陷。飞书 loopback 在进程死亡后 socket 一并消失，未宣称支持恢复。

### 第二轮最终验证

- 新增确认并修复 12 项（WM27–WM38），连同第一轮共 38 项；没有将撤回的深链接 resume 候选计入。
- 最终定点回归：32 个测试类、142 个测试，141 通过、1 跳过、0 失败；同时通过 app 主代码与测试源码编译。跳过项仍为第一轮记录的 document-start provider 支持限制。
- 本轮新增的 ROI 三例、HTTP Cookie 导入、两个 OAuth refresh 用例、签名 URL 工具入口及两个 Dispatcher 并发用例均实际通过。ROI 测试通过 View 的公开 right/bottom 属性设置测试视口，未以此证明真机布局、DPR 或页面缩放。
- 原工作区 JS 行为脚本和 bridge.js 语法检查通过；本任务文件的 diff whitespace 检查通过。
- 修复后独立 subagent 分别复核 React、ROI/取消、Cookie 清理、签名/profile、同会话调度以及 OAuth 条件保存。复核发现的 schema 遗漏已修正；仅测试可达的 resume 修改已撤回。

最终 Kotlin 命令与首轮相同，额外加入 `--tests 'app.amber.feature.runtime.AgentToolDispatcherTest'`。运行目录仍是 `/private/tmp/webmount-verify-1a2g4361`，日志为 `/private/tmp/webmount-round2-test.log`，文件清单为 `/private/tmp/webmount-round2-verification.json`。本轮复制 16 个涉及的文件进入该验证副本；整体清单共 42 个文件。除已说明的 ContextUtil 导出代码隔离外，另一个任务新增的 `generate_image` policy 和对应 ledger 测试也未带入；已逐项核对这些差异与 WebMount 无关，原工作区改动均保留。
