# E5 remote 平台模块

2026-09-28，owner：`/root/e3_native_python`。两个窄平台模块已实现，必要 host / SDK 类型 / lint 检查通过。独占新增 `PluginHttpPort.ets`、`PluginWebMountGate.ets` 与一组必要 fixture；未改 `RcpHttpClient` / `WebMountSession` / domain runner / DI / ChatPage，shared 最小入口由 root 负责。统一接线与真实 Harmony 网络/设备验收不由此报告代替。

## 官方 API12 依据

已读 `.agents/skills/harmony-arkts/SKILL.md` 与 `E5-plugin-design.md`，再查实际 `MiniAppBridgeImpl.ets`、`MacGatewayClient.ets` 的 RCP 先例。设计中说通用 `RcpHttpClient` 无插件 redirect gate 是事实；MiniApp 已有独立 RCP 手动跳转先例，可以复用 API 形态，不能复用其全量响应后检查的内存策略。

本地离线官方文档经 `harmony/scripts/devecocli.sh docs search/read` 读取：

- `API参考/网络/Remote_Communication_Kit_远场通信服务/ArkTS_API/rcp_数据请求/remote-communication-rcp`，全文缓存 `/tmp/e5-plugin-rcp-official.txt`。`Session.fetch/cancel/close`、`TransferConfiguration.autoRedirect/timeout`、`TracingConfiguration.httpEventsHandler`、`HttpEventsHandler.onDataReceive` 均 API11。`OnDataReceive` 使用后 Response.body 不再含有数据；回调可在 API12 返回 void，但其 request 参数是 API20，本实现不使用它。`HttpMethod` HEAD/PATCH 在 API11，无需 legacy NetworkKit 的 API23 `maxRedirects` 或 API26 PATCH。
- `API参考/ArkTS_方舟编程语言/ArkTS_API/ohos_url_URL字符串解析_/js-apis-url`，缓存 `/tmp/e5-plugin-url-official.txt`。`url.URL.parseURL(raw,base?)`、`URL.params` / `URLParams.append` 均 API9；使用标准 URL 解析/相对跳转和 query 参数追加，拒绝 userinfo，不用自制 authority split 或废弃 searchParams。
- SDK声明逐项复核 `/Applications/DevEco-Studio.app/Contents/sdk/default/hms/ets/api/@hms.collaboration.rcp.d.ts` 与 `openharmony/js/api/@ohos.url.d.ts`；没有从当前 SDK 能编译推断 API12 能运行。

## WebMount 已查明的 shared 缺口与 root 最终修复

真实覆盖路径：`WebMountTools.ets:125` 每次 guardPage 执行 `setUrlGate(makeSiteGate(sites))`；`WebMountSession.ets:203` 在站点刷新时直接重赋同一 urlGate。因此 platform 暂存旧 gate 再调用 setUrlGate 组合插件限制，会在真正 dispatch 中被覆盖。必须保持原站点门并增加单独的临时插件门。

另一个实际断点是 `back/forward/click` 沿当前工具协议只返回动作 ack，不保证导航先结束；如果 dispatch 返回即撤 gate，随后 `onLoadIntercept/pageEnd` 将失去插件域限制。root 最终采用一个直接的页面权限寿命：独立 `pluginUrlGate` / `pluginScopeActive`，`bindPluginUrlGate` 遇 active 明确 busy；unbind 仅释放 active owner，门仍属于已加载页面。下一次普通工具 `setUrlGate` 且无 active scope 时才移除插件门，显式接管页面；`applySitesSnapshot` 始终只改站点门。`isAllowedNav` 取站点门与插件门交集。此方案覆盖工具 ack 后的晚到导航，未使用 sleep、导航框架或 DOM 黑名单。

平台 `withScope` 仅围批准后的真实 dispatch，不在审批等待时持有 active 门；默认先核当前页，操作后再核实际 URL，finally 释放 owner。若 open/reload 在 pageEnd 前取消/失败且页面未 ready，使用现有 `resetToBlank` 丢弃未完成页面再释放 owner。对于明确新目标 `wm_open/tab_select`，runner 传 `requireCurrent=false`，不会被此前普通工具的不同域页面挡住；后续真实导航和终态仍受交集限制。当前统一支持动作由 domain `broker.ts:supportedPluginWebMountActions` 定义，platform 不维护第二套白名单；`wm_eval/signed_fetch/站点管理/私有记忆` 不进入受限 plugin host 路径。导航 ack 沿现有工具语义，不能声称 ack 即已加载成功。

## 冻结接线与实际 HTTP 行为

```ts
createEntryPluginHttpPort(): PluginHttpPort
createEntryPluginWebMountPort(session: WebMountSession): PluginWebMountPort
// 已冻结 plugins/ports.ts：
execute(request: PluginHttpRequest, signal?: AbortSignalLike): Promise<PluginHttpResult>
withScope<T>(networkDomains: string[], operation: () => Promise<T>, requireCurrent?: boolean): Promise<T>
```

请求确切为 `{url,method,input,networkDomains,timeoutMs,maxOutputChars}`；返回 `{status,body}` 完整 UTF-8。错误复用 domain `PluginHttpError(code,message,mayHaveApplied)`，不自造 DTO。root 将 factories 注入 platform deps；MCP 的 `resolveMcpPrimitive` 保留本轮实际 AgentTool/assistantIds snapshot 与 dispatcher，由 root/domain runner 接线，本模块不直接 manager.callTool。

- 每调用专用 RCP session，不携带应用/provider/WebMount 的 Cookie、认证头或共享会话。endpoint/method 从固定请求复制；input 仅为 GET/HEAD 排序追加的 query 数据，其他方法为 JSON body（≤256 KiB）。即使 input 含 `url/method/headers/authorization`，也不会更改实际 URL 主机/路径、固定方法或宿主 header；唯一固定头 Accept 与 JSON Content-Type。
- 标准 URL 解析，HTTPS、无 userinfo、域名精确或按 `.domain` 子域后缀相交。相对/绝对 Location 每跳先核域再发请求；关闭 autoRedirect，最多三跳，整个调用共享一个 deadline。固定非 GET/HEAD 只允许307/308按原方法/body续跳；301/302/303需要改变方法的响应明确 `redirect_method_change`，不偷偷把 POST 改为 GET。
- `onDataReceive` 接收时累计上限 `maxOutputChars * 4` 字节，超限立即取消且停止积累，不全量 Response.body 后截断。完成后 fatal UTF-8 解码并检查实际字符数；空正文为 `null`。非2xx、无效UTF-8、超限、取消和超时明确失败，不输出截断成功 JSON。runner 统一 coarse output / JSON / schema / expected_result 校验。
- abort/绝对 deadline 立即结算、取消 RCP，清 timer/listener；finally 关闭本次独立 session，迟到响应不会改写已结算结果。请求创建或 SDK fetch 同步失败也走同一 cleanup。取消发生在发请求前，`mayHaveApplied=false`；非GET/HEAD发出后出错/超时/取消等保留 true，runner据此收口 outcome_unknown，不能自动重放。

## 一组必要检查（通过）

`node --test .workflow/harmony-remaining/fixtures/E5-plugin-remote.cjs`，最终 **1/1 PASS，0 fail/cancelled/skipped**，约200 ms，`/tmp/e5-plugin-remote-fixture.log`。直接 transpile 实际两个 platform 模块、domain PluginHttpError 和 root 修改后的完整 `WebMountSession` class；只把 SDK RCP transport/controller事件替换为有界确定性 shims，未重跑 domain/store 或设备套件。

同一组连续验证：GET 原端点保留且输入仅 query、真实请求未注入认证头、同域相对 redirect、跨域零第二跳、HTTP/userinfo零请求拒绝、POST输入仍仅JSON body、method-changing redirect拒绝、stream接收超预算拒绝、发前/发后取消的未知副作用区分、绝对 deadline、每个 session 都关闭；实际 session 站点 refresh不覆盖plugin门、两门交集、active scope busy、解绑后晚导航仍被阻断、普通工具显式接管、终态越域不成功、新目标跨旧页域与下一scope可继续。加入 SDK 同步请求错误 cleanup 后仅回归这一小组，没有增加 suite 或轮数压力。

HTTP scoped strict types：真实 SDK `rcp/url/util` declaration 与冻结 domain models/ports，`tsc -p /tmp/e5-plugin-http-tsconfig.json` **exit 0**，`/tmp/e5-plugin-remote-types.log`。临时桥仅解决普通 TypeScript 的 kit alias / Harmony 数字 timer 签名；首次临时 ambient re-export与 Node timer配置失配已修临时 harness，没有为此放宽产品类型或改 SDK。

统一实际 ArkTS 编译回喂 `/tmp/e5-release-build.log` 指出 `PluginWebMountGate.ets:13` 的 constructor parameter property 不受支持。本 owner 已只改为显式 `private readonly session` 字段及 constructor 赋值，行为不变；未重复 remote fixture 或执行 build/device，最终编译复核由 root 负责。

两新增 `.ets` 窄 ArkTS lint：**exit 0，No defects found**，`/tmp/e5-plugin-remote-lint.log`。默认 sandbox 首次因官方工具写自身 `Huawei/.../codelinter.log` 被拒，仅按同一已授权命令申请必要写权限后完成。未运行完整 app build/sign/install、真实公网 endpoint 或设备网络。host shim 证明平台控制流和既有 session 门寿命，不等同实际 RCP DNS/TLS/网络接受；统一设备与阶段独立 review 由 root 负责。
