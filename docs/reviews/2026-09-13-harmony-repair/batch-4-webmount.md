# Batch 4A — WebMount 安全与挂载收口（R07 / L01 / L02）

- 范围：`WebMountSession.ets` / `WebMountTools.ets` / `WebMountSites.ets`（本 agent 独占）。
- 未触碰：`WebMountOAuthSupport.ets`（已修，按要求不动）；`ChatPage.ets` / `AppContainer.ets` / `chat|deepread` `index.ts`（主代理独占，仅给出接线需求）；未新增多 session 框架（仍用全局单会话）。
- 未跑：hvigor 构建、全仓 lint、模拟器（主代理统一串行）；未触真实网络/模型/用户数据。
- 加载 skill：`harmony-arkts`。全部新增/改动仅用 API 12 基线内 API（`url.URL.parseURL` API 9、`onControllerAttached` API 10、`onLoadIntercept` API 10、`onDetach` API 12、`WebResourceRequest.isMainFrame/isRedirect/getRequestUrl` API 8），无 `canIUse` 需求。URL 解析用**官方标准解析器**，未自制 regex。

## 修改文件

| 文件 | 修复 ID | 机制 |
|---|---|---|
| `harmony/entry/src/main/ets/platform_impl/WebMountSites.ets` | L02 | `hostOf`/`siteAllowsUrl` 改为 `url.URL.parseURL` 标准解析：仅 http(s)、拒 userinfo、hostname 小写规范化（去尾点、保留 IPv6 方括号）；新增 `checkWebMountUrl`（返回规范化 href + hostname）与同步 `makeSiteGate`（`onLoadIntercept` 热路径用） |
| `harmony/entry/src/main/ets/platform_impl/WebMountSession.ets` | R07 | 就绪信号改由 `Web.onControllerAttached` 真实置 `hostMounted`；`onDetach` 对称复位；等待者超时/取消**正确 reject 并清理**；`AbortSignalLike` 贯穿 open/reload；新增同步 URL gate（`setUrlGate`/`shouldBlockUrl`）与 `markPageEnd(event.url)` 用真实事件 URL；`bindHooks` 返回按 owner 的 `unbind` |
| `harmony/entry/src/main/ets/platform_impl/WebMountTools.ets` | L01 | 所有动态值只经 JSON 字符串字面量注入（含 U+2028/2029 转义），删除 error 文案里的 `target` 二次拼接；`wm_click` 恢复 `needsApproval:true / allowsAutoApproval:false / mandatoryApproval:true`；每个页面工具前经 `guardPage` 重新载入站点 + 刷新 gate（revoke 后拒绝并复位）；`wm_open`/`wm_tab_select`/`wm_site_add` 用标准解析 + 规范化 URL；`wm_wait`/`wm_eval`/`wm_click`/`wm_reload` 消费取消 signal |

## 官方签名（本机 SDK 核验，`~/Library/Huawei/commandline/command-line-tools/sdk/default/openharmony/ets/`）

`@ohos.url.d.ts`（kit 导出：`import { url } from '@kit.ArkTS'`）：

```ts
class URL {
  static parseURL(url: string, base?: string | URL): URL;  // @since 9, throws 10200002 Invalid url string
  protocol: string; hostname: string; host: string; port: string;
  username: string; password: string; href: string; origin: string; // readonly origin
}
```

- `component/web.d.ts`：

```ts
onControllerAttached(callback: () => void): WebAttribute;                 // @since 10
onLoadIntercept(callback: Callback<OnLoadInterceptEvent, boolean>): WebAttribute; // @since 10
onPageEnd(callback: Callback<OnPageEndEvent>): WebAttribute;              // OnPageEndEvent { url: string }
onDetach(callback: Callback<void>): T;                                    // common.d.ts @since 12
declare class WebResourceRequest {
  getRequestUrl(): string;   // @since 8
  isMainFrame(): boolean;    // @since 8
  isRedirect(): boolean;     // @since 8
}
```

- `onLoadIntercept` 返回语义（官方文档 `ts-basic-components-web.md`，OpenHarmony-5.0.0-Release）：
  「返回值 boolean。**返回 true 表示阻止此次加载，否则允许此次加载。默认允许加载。**」

> 说明：本机 `devecocli` 未安装（`/opt/homebrew/bin/devecocli` 缺失），改按 skill 允许的“SDK 声明核验”路线，直接读上述 d.ts 与官方文档原文，未凭记忆。

## 最小机制说明

### L02 — 授权边界（标准解析 + 逐导航/revoke 复查）
- `checkWebMountUrl`：`url.URL.parseURL(trim)` → 仅 `http:`/`https:` → `username/password` 任一非空即拒 → `hostname.toLowerCase()` 去尾点 → 返回。任何 try/catch 失败或空 hostname → `ok:false`（fail closed）。
- `hostOf(site.url)` 同样走该解析；站点 hostname 预解析进 `makeSiteGate` 闭包（同步、无 IO），供 `onLoadIntercept` 每次都调用：
  - 主框架（`isMainFrame()`）才判定；`about:` 内部占位页放行；**gate 缺失时 fail closed**（`shouldBlockUrl` 拒绝一切外部导航）。
- `WebMountTools.guardPage`：**每个**页面工具（extract/click/type/scroll/screenshot/wait/back/forward/reload/read_page）执行前重新 `loadWebMountSites` → `session.setUrlGate(makeSiteGate(...))` → 若当前 `session.getUrl()` 已不再授权则 `resetToBlank()` 并返回错误。`wm_site_remove` revoke 后同判并复位。
- `markPageEnd(event.url)` 用事件真实 URL（不再 `session.getUrl()` 旧值）；重定向落到未授权主机 → 保持 `ready=false`，`open` reject。

### R07 — 真实 attached 就绪 + 对称销毁 + 正确 reject
- `WebMountHost` 内 `Web` 增加 `.onControllerAttached(() => session.markControllerAttached())`（取代外层 `aboutToAppear` / 初始 pageEnd 假置位）；`open` 先 `ensureHost`（hook 请求挂载 → `waitForMount` 等真实 attached，10s 超时 reject），再 `loadUrl` → `waitForPageEnd`（15s 超时 reject）。
- `.onDetach(() => session.markControllerDetached())`：`hostMounted=false`、`pageReady=false`、`uiContext=null`，并把 mount/page 等待者全部 reject（不再悬挂）。
- 两类等待者统一 `SettleWaiter{id,settle,fail}`，`settle/fail` 幂等：清定时器、摘 abort 监听；超时/abort 时先从等待列表移除自身再 reject。
- 取消：`open/reload/click/eval/wait` 接受 `signal?: AbortSignalLike`；`aborted` 立即 reject（页面等待额外 `controller.stop()` 中断本次加载）；`wm_wait` 轮询每轮检 `signal.aborted`。
- `bindHooks` 返回 `WebMountHookBinding{unbind}`：仅当 `this.hooks` 仍是本次对象才置 null —— 旧页面 `unbind` 不会解掉新页面刚绑定的 hooks。

### L01 — 注入与审批
- 唯一动态注入原语 `jsonString(v)` = `JSON.stringify(v)` + 转义 U+2028/2029；`clickScript/typeScript/waitProbeJs` 全部把 target/selector/text/条件作为 `const x = <json 字面量>` 注入，**脚本正文里不再出现 `target` 插值**（原 135 行 error 文案 `'element not found: ${target}'` 已改为静态常量）。
- `wm_click` 审批合同与 `wm_eval` 对齐：`needsApproval=true`、`allowsAutoApproval=false`、`mandatoryApproval=true`；关闭 `wm_eval`（`includeEval=false`）不影响 click 自身门，且 click 已不能执行任意表达式。
- `wm_site_add` 用标准解析后的 `check.url` 落库；`wm_open`/`wm_tab_select` 用规范化 URL 传 `loadUrl`。

## 共享接线需求（给主代理；ChatPage 独占，我未改）

`WebMountHost` 自带 controller 生命周期自注册，**无需主代理改宿主调用点**（仍 `WebMountHost({ visible: this.webmountHostVisible })`）。唯一需要的是 `bindHooks` 现在返回 `unbind`：

```ts
// import
import { WebMountHost, getWebMountSession } from '../platform_impl/WebMountSession.ets';
import type { WebMountHookBinding } from '../platform_impl/WebMountSession.ets';

// 字段
private webmountHooksBinding: WebMountHookBinding | null = null;

// aboutToAppear（替换现有 875-877 行）
this.webmountHooksBinding = getWebMountSession().bindHooks({
  onMountNeeded: (): void => { this.webmountHostVisible = true; },
});

// aboutToDisappear（新增）
if (this.webmountHooksBinding !== null) {
  this.webmountHooksBinding.unbind();
  this.webmountHooksBinding = null;
}
this.webmountHostVisible = false;
```

其余接线点不变：`createWebMountTools(getWebMountSession(), includeEval)`（2988 行）与 `WebMountHost({ visible })`（4521 行）。宿主卸载时 `onDetach` 会对称复位 session，故 ChatPage 销毁后重开可再次 `onMountNeeded`。

## 测试与结果（targeted，未 hvigor）

新增 3 个隔离 probe（`docs/reviews/2026-09-13-harmony-repair/probes/`），全部转译**真实源码**（不复制业务实现），仅 mock 平台边（Node WHATWG URL 冒充官方 `url.URL`、假 controller/fake clock、vm DOM 沙箱）：

```
node docs/reviews/2026-09-13-harmony-repair/probes/probe-batch4-webmount-sites.cjs
node docs/reviews/2026-09-13-harmony-repair/probes/probe-batch4-webmount-tools.cjs
node docs/reviews/2026-09-13-harmony-repair/probes/probe-batch4-webmount-mount.cjs
# 三个均 exit 0（All batch-4 assertions passed）
```

- **sites（L02）**：finding 原样 deceptive userinfo URL → `siteAllowsUrl=false`/`hostOf=""`；`javascript:/file:/data:/ftp:/about:/chrome:` 全拒；大小写/端口/子域/尾点规范化匹配；`evilgithub.com`、`github.com.evil.com` 拒绝；userinfo 命中 host 仍拒；IPv6 `[::1]`；对所有可解析样本 `hostname === 标准 URL.hostname`（去尾点）；`makeSiteGate` 与 `siteAllowsUrl` 一致。
- **tools（L01）**：7 类敌对 target（单双引号、反斜杠、换行、模板、U+2028）`window.__audit` 恒 0；`wm_type`/`wm_wait` 同上；`wm_click` 三审批字段正确；abort signal 中断 click/eval；revoke 后 extract 拒绝并 `resetToBlank`（`resets=1`）且未越权访问 `javascript:` URL。
- **mount（R07）**：真实 attached 后 `loadUrl` 且 pageEnd resolve；mount/page 超时**reject** 且无残留 timer/waiter；`onDetach` reject 在途等待者；`AbortSignalLike` 取消 open；重定向到未授权 host → open reject 且 `getUrl` 为事件真实地址；`bindHooks` 旧 owner `unbind` 不影响新 owner。

改动文件 targeted lint（`harmony/scripts/lint-arkts.sh <file>`）：三个文件均 **No defects found**（该工具固定的 "Some error occurred during linting" 噪声行与基线一致，不影响结论）。

## 未解决 / 边界（不声称设备已验证）

- 未在模拟器/真机实跑：`onControllerAttached` 实际时序、移出可视区 Web 的渲染/截图、`onLoadIntercept` 对重定向逐跳与 `about:blank` 的放行行为，需主代理装机截图目测（暗色/首开/退出重开）。
- `onLoadIntercept` 对 `data:`/`blob:` 等子框架返回 false 放行（仅拦主框架外部导航）；是否需再收紧待设备观察。
- `getComponentSnapshot().get(WEB_HOST_ID)` 与宿主 `onDetach` 的竞态（截图进行中被卸载）未加锁；`markControllerDetached` 会清 `uiContext`，后续截图将抛 “no UI context”，属预期 fail closed。
- 原 `wm_back`/`wm_forward` 直接置 `pageReady=true` 未等 pageEnd，保留既有语义（仅加 gate 前置检查），未在本批扩大改动面。
- `WebMountHost` 仍使用单全局 controller；`visible` 首次置 true 后不复位（宿主常驻），如主代理希望离页释放可在 `onPageHide` 另设；当前由页面销毁 `onDetach` 收口。

## 追加 P2-1（review-batch45 P2-1）— 站点/开关变更即时生效

- **额外获准改动**：`harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets`（crypto owner 已结束）。
- **根因**：Settings `removeSite` 只 `kv.put`，未刷新 session gate；下一次工具调用（`guardPage`）才换 gate，期间旧 gate 仍放行页面自主导航（点击链接/JS 跳转）。
- **最小机制（集中收口，无 eventbus、无新框架）**：
  - `WebMountSites.saveWebMountSites` 持久化成功 → 单槽回调 `setWebMountSitesListener`（模块级 `let listener`，不是 eventbus）。
  - `WebMountSession` 在 `getWebMountSession()` 创建时注册该回调 → `applySitesSnapshot(sites)`：换 `makeSiteGate`，当前 `currentUrl` 失权则立即 `resetToBlank()`。**Sites 不 import Session，无循环依赖**（Session 单向 import Sites）。
  - 全局开关：`setWebMountGlobalEnabled(v)`（Session 模块导出）由设置页 toggle 与 `refresh()` 调用；会话未创建时只记模块镜像 `globalEnabled`，新建会话按其初始化。关闭 → `session.setEnabled(false)` 立即 `resetToBlank()`，且 `shouldBlockUrl` 对所有外部导航返回 true。
  - **OR 语义（产品原契约，LocalTools.kt:161-166）**：`global || assistant.localTools 含 webmount`。因此**不能在工具层用 KV global 单独禁用**（否则助手单独勾选失效）。工具改为 `syncEnabled(session, kv, assistantEnabled)`：读 KV global，`effective = global || assistantEnabled` 后 `session.setEnabled(effective)`，仅两者皆假才拒绝。`createWebMountTools(session, includeEval, assistantEnabled = false)` 第三参可选、尾置默认 false（既有调用不受影响），由 ChatPage 传 `rt.assistant.localTools` 是否含 `webmount`。
  - **全局开关非总禁用**：设置页显式关闭只调用 `setWebMountGlobalEnabled(false)` → 立即 `resetToBlank()` + `enabled=false` 终止**当前**会话；下一次合法建 tools / 页面操作时 `syncEnabled` 重新按 OR 求值，助手已单独勾选则恢复可用。设置页文案已注明（“关闭会立即结束后台会话页面，但已单独勾选 WebMount 的助手仍可继续使用”）。`refresh()` 不再回写会话（避免用旧 KV 值覆盖 OR），仅在用户 `onChange` 显式关闭时终止。
  - 工具侧兜底：`guardPage` 首查 `syncEnabled(...)` 结果；`wm_open` 同样。一律 OR 求值，无“只看 global”的死门。
  - `wm_site_add` / `wm_site_remove` 不再显式 `setUrlGate`，由回调统一处理（`wm_site_remove` 先算 `page_reset` 再 save）。
- **接线**：设置页 toggle `onChange` 仅在其为 `false` 时调用 `setWebMountGlobalEnabled(false)`（显式关闭即终止当前会话；恢复交由下次 OR 求值）；`restoreMissingSeedSites` / 添加 / 移除站点均经 `saveWebMountSites` 自动触发回调，无需改各自的 `.then`。**ChatPage 需改一处**：`createWebMountTools(getWebMountSession(), includeEval, assistantEnabled)`，其中 `assistantEnabled = localOpts.indexOf('webmount') >= 0`（`localOpts` 已在同处用于注册门）。工具注册门本身保持原 `(this.webmountGlobalEnabled || localOpts.indexOf('webmount') >= 0)` 不变。
- **回归（1 旧 probe 场景 + 新例）**：
  - `probe-batch4-webmount-mount.cjs` 新增 `[P2-1-T2]`：真实 `saveWebMountSites(kv, [])` revoke 后，**无任何后续工具调用**，`session.getUrl()===''`、`isReady()===false`（旧行为下旧 gate 仍放行页面自主导航）；`[P2-1-T3]`：`setWebMountGlobalEnabled(false)` 同步 `isEnabled()===false` 且 `shouldBlockUrl` 阻断（显式关闭终止当前会话）。
  - `probe-batch4-webmount-tools.cjs` OR 三例：`[P2-1-T1]` global=false+助手未勾选 → `wm_open`/`wm_extract` 拒绝 `disabled` 且不执行页面脚本；`[P2-1-T2]` global=false+助手已勾选 → 下一次工具调用按 OR 恢复可用（全局非总禁用）；`[P2-1-T3]` global=true+助手未勾选 → 可用。
  - 三 probe 复跑全绿；四个改动文件 targeted lint 均 "No defects found"。

