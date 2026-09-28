# E2 SSH profile / 本地 PTY UI

2026-09-28。UI worker 修改本报告、新 SSH 页面/组件、`rawfile/terminal/` 和回归测试；root 后续扩展所有权至两个审批组件中的 SSH 显示，以及 AgentToolCapsule 的审批行布局。未改 root 所有的 DI、路由、EntryAbility、ChatPage、既有设置或领域/原生模块，未操作设备、未 stage/commit。共享工作区中保留他人改动。

## 实际调用链

- `SettingSSHProfilesPage` 从 `getSSHProfileStore(): Promise<SSHProfileStore>` 和 `getTerminalRuntime(): Promise<TerminalRuntime>` 取得同一 root 单例，订阅 metadata。新建/编辑、`setDefault`、`deleteProfile`、纯重命名 `saveMetadata` 已有消费者。
- 编辑表单不携带 pin/ref，不读取 credential store。打开时保存 revision，probe/verify 前核对当前 revision；字段变更取消在途操作并清候选；迟到 callback 用 operationVersion 拒绝。密码/私钥不 trim，passphrase 可选；留空只允许 runtime 按仍匹配的旧 binding 加载原凭据。退出、后台、取消、成功和认证失败清 UI 秘密。
- `runtime.probe` 返回算法、完整 SHA256 和 trusted/untrusted/mismatch。指纹可按字符换行及设备内复制；mismatch 同时显示完整原指纹。只有用户点「信任并验证」才传 `acceptFingerprint=true`。正式认证成功由 domain 保存；失败/取消不写旧 profile/credential。
- `TerminalPage` 等本地 Web 真实 ready/fit 后才开放连接：`captureTarget → startSession → subscribeSession`，立即订阅首包。页面手输和工具键由 `sendSessionBytes(Uint8Array)` 消费；实际 fit 调 `resizeSession`。未注册任何模拟 WebTerminal 运行入口。
- `TerminalViewController` 串行向固定本地函数传纯 number[]，host 端 `new Uint8Array → xterm.write`，不把远端输出拼为 HTML 或可执行代码。host 内 write/reset 共用队列，write 以真实 xterm callback（VT 解析完成）收口，reset 因此排在上一 session 的 VT 解析之后；闭包捕获原 Web controller 避免 remount 迟到写入新视图。
- 终端退出/切配置只 stop 本页 own session；不取消其他 Agent job。切后台页面取消 pending start/禁输入，root EntryAbility 的 `interruptForBackground` 负责 active runtime 释放与 INTERRUPTED。回前台/回页须手工连接，未重发命令。

## 本地 VT 资源

`rawfile/terminal/` 固定 `@xterm/xterm@5.5.0` 和 `@xterm/addon-fit@0.10.0` 的官方 npm dist；下载校验 registry SHA512 integrity，归档 SHA256/来源/版本写在 `vendor-manifest.json`，两个 MIT LICENSE 一起进包。没有 CDN、WebSocket、外部页面、clipboard/link/webgl addon。OSC8 linkHandler 禁止打开外链。

host 使用本地脚本和 CSS，CSP 禁网络/对象/表单，仅本地 script，inline style 是 xterm 注入样式所需。桥只暴露 `ready/input/resize`。fonts ready 后 fit.proposeDimensions + 非零容器测量成功才发 ready；native 不以 80×24 开始连接。ResizeObserver、visualViewport 与 native onAreaChange 触发 fit，后台/未连接不发送输入。

工具键 Esc、Tab、Ctrl-C、方向键、Enter 与软键盘都成为原始 bytes；方向键根据 xterm `applicationCursorKeysMode` 选择 CSI/SS3，保留 TUI 光标模式。UI 采用 V1 状态、StorageProp 主题监听和 token palette；CardGroup 只调用 @Builder 方法。没有把编辑 state 作为 Builder 值参数。

## root 接线清单

| 入口 | 路由 / 参数 |
| --- | --- |
| 运行环境的 SSH 配置 | `pages/SettingSSHProfilesPage`，无必需参数 |
| 运行环境 / Chat 终端 | `pages/TerminalPage`，可选 `{ profileId: string }`，缺省使用默认配置 |
| 配置列表每项「终端」 | 已 push `pages/TerminalPage` + profileId |
| 终端顶栏「配置」 | 已 push SSH 配置页，并先释放本页 session |

新增页面需要 root `main_pages.json` 注册。root `index.ts` 导出冻结模型/ProfileStore/Runtime；DI Promise factories 和 EntryAbility 后台释放已由 root 对接。本报告不代替这些入口集成验证。

## 检查与边界

- 精确 `lint-arkts.sh` 四文件：2026-09-28 最终方向键调整后复查得到 `No defects found`，0 errors/0 warnings。初次因工具自身日志目录 EPERM 原命令不变获权重试，未改变工具配置。
- `node --check rawfile/terminal/host.js` 通过。
- `/tmp/e2-terminal-host-check.cjs` VM 桥逻辑检查通过：fonts 前无 ready；零尺寸无伪固定值；首次实测 ready；连接前后输入门禁；中文/emoji UTF8、binary 0/255；输出控制与 UTF8 分块保持原 bytes；fit 后 resize；普通/应用光标模式方向键及 Ctrl-C。此检查使用 browser/xterm stubs，**不是实际 ArkWeb 或 PTY 验收**。
- 未自行 build/sign/install；root 统筹编译诊断、真实 SSH fixture 和设备。页面源码 44/52vp 与 16gutter 仅实现规格，不能作为真实 bounds/软键盘/横竖屏/亮暗色截图已验。
- root 第一轮统一 build 的唯一 UI compile error 是 Select 不支持 `.fontSize`；查离线官方 `Select.font(value: Font)` 后局部改为 `.font({ size: 14 })`，交 root 统一复编；其他 UI 无 ERROR。

## 官方 API 查证

已读本仓 `harmony-arkts` skill 和 E2 合同。Web javaScriptProxy/deleteJavaScriptRegister 参照离线官方 `web-in-page-app-function-invoking`；KeyboardAvoidMode.RESIZE 为 API11，沿用 NovelWorkspacePage 先例；wordBreak.BREAK_ALL 为 API11，离线 `faqs-arkui-347`；TextArea.copyOption 为 API9，None 仍允许粘贴，离线 TextArea 文档。xterm write/onData/onBinary/onResize、fit addon 和 modes/linkHandler 查固定 5.5.0 typings 与[官方 Terminal API](https://xtermjs.org/docs/api/terminal/classes/terminal/)、[addon 指南](https://xtermjs.org/docs/guides/using-addons/)。

## 实际截图和独立 review 回喂后的局部修复

- 已查看 `/tmp/e2-profiles-new-dark.jpeg` 与 `/tmp/e2-profiles-empty-dark.jpeg`。前者 placeholder 和系统密码 eye 为黑色，后者 Scroll 内容垂直居中，均是实际可见问题。
- 仅 SSH 页全部 TextInput/TextArea 增加 `placeholderColor(colors.muted)`；离线官方 `faqs-arkui-356` 明确系统 passwordIcon 大小/颜色不能直接改，页面隐藏系统 eye，以现有单色 Icon eye/eye_off 和 `colors.secondary` 提供 44vp 显隐操作。清秘密同时恢复隐藏；未改全局控件或主题。
- 设置页 Scroll 按既有二级页加 `align(Alignment.TopStart)`，子 Column 显式 Start，空态从顶部开始；需 root 新截图复核实际位置。
- root 扩展所有权后，`ChatMessageTools` / `ChatMessageToolPreviewSheet` 仅对 `terminal_execute/job_start/session_start/session_exec` 新增 SSH 批准内容。pending 摘要出现在 ✓/✕ 之前：持久 `metadata.terminal_target_label` + command；session_start 显示实际会话操作/初始 cwd。详情显示完整 label/command，按字换行可复制。缺标签显示“SSH 目标不可用”，不读取 current profile/default，不显示 digest/ref/凭据。其它工具原分支保持一致。小 `SSHApprovalSummary` helper 只复用这两个组件的提取逻辑。
- reviewer 提出的实际 xterm pending write/reset 缺陷已复现：裸打包 xterm `write('OLD SESSION') → reset → 100ms` 仍得到 OLD SESSION；修复 host 内 VT callback 队列，未增加协议/重播 buffer。
- 新增 `node --test harmony/tests/terminal-host-reset.test.cjs`，**真实包内 xterm.js** 4/4 PASS：裸库旧问题复现、host reset 等 VT 完成后无旧文本、reset 后新中文/emoji 分块保留、实际 parser 的应用 cursor mode 导致 CSI/SS3 正确切换。测试仅省略 DOM mount 和 fit size adapter，VT parser/UTF8/write buffer/reset/modes 均不是 stub；这仍不等于实际 ArkWeb/PTY/键盘验收。
- 最终局部 ArkTS lint：SettingSSHProfilesPage / ChatMessageTools / ChatMessageToolPreviewSheet / SSHApprovalSummary 四文件 `No defects found`；`/tmp/e2-ui-review-lint.log`，0 errors/0 warnings。host.js syntax check 通过。root 负责统一复编与新截图确认。

## 真实连接和表单边界回喂

- root 实测 probe/pin、password 认证保存成功。端口输入错误与错误密码原先显示错误码；小 `SSHPageErrors` 只映射四个已知 SSH 错误：invalid_arguments 提示检查名称/主机/用户名及 1–65535 端口，authentication_failed 提示核对用户名/密码/私钥及口令，host_key_mismatch 提示重新核对指纹，profile_conflict 提示取消并重开编辑。两 SSH 页使用同一局部格式器，未知 Error 保留原 message，未知 thrown value 保留 String；没有通用 fallback 层。
- SSH 两页面、SSHPageErrors、两审批组件及 SSHApprovalSummary 共六文件窄 lint：`/tmp/e2-ui-errors-approval-lint.log`，`No defects found`，0 errors/0 warnings。
- 已查看 root 提供 `/tmp/e2-terminal-connected-dark.jpeg`：真实首 prompt 已展示、fit 为 43×27，但 Select 按钮名称空白且箭头黑色。官方 Select.value 与 selected 含义不同，arrowModifier 则始于 API20。为保持 API12，在 TerminalPage 局部替换成 52vp 的 profile 选择行和最多四行高的列表；名称、chevron/check 均使用现有主题 tokens，完整名字可按行展示，不新增全局选择系统。
- 实际 gesture indicator 覆盖工具键下沿，root 当时 navBarHeight=0。TerminalPage 按既有 EntryAbility 的 `navBarHeight` storage 消费系统避让高度，并在完整 52vp 工具栏下额外保留 `16 + navBarHeight` padding；未修改全局安全区。该页窄 lint `/tmp/e2-terminal-layout-lint.log` 为 `No defects found`。名称/按钮底部间距仍由 root 新截图复核。
- root 直接触摸 Web 后 IME 成功，真实文本+Enter 执行 `stty size` 返回 11 43，与键盘后的 fit 一致；这证明 native PTY/IME bytes 链路，但原生“键盘”按钮只出现 caret。`keyboardDisplayRequiresUserAction` 在离线检索、当前 SDK WebAttribute 和 [官方完整 Web 属性文档](https://github.com/openharmony/docs/blob/master/zh-cn/application-dev/reference/apis-arkweb/arkts-basic-components-web-attributes.md) 均不存在，未写入猜测 API。官方离线 `web-focus` 区分组件焦点与网页元素焦点；`faqs-arkweb-127/158` 说明脚本 focus 不等于自动弹键盘。先将按钮补为 API9 `WebviewController.requestFocus()` 后再执行既有 xterm focus，交 root 真机复核；没有自行 attach 输入法、引入平台 IME controller 层或改 fixture。
- TerminalView 的 requestFocus 局部检查 `/tmp/e2-terminal-focus-lint.log` 为 `No defects found`，0 errors/0 warnings；不将该静态检查宣称为 IME 弹出已验收。
- 已查看真实 provider SSE → RDB pending 截图 `/tmp/e2-ssh-approval-pending-dark.jpeg`：冻结 host/command 清晰，但胶囊按父宽测量后继续叠加按钮，✕ 被裁、✓ 在屏幕外，属于批准流程阻断，**不是 PASS**。仅在 AgentToolCapsule 相关 Row 加父宽限制和 approval pill 弹性 layoutWeight/flexShrink，已有单行标题 ellipsis 获得实际剩余宽度；两按钮各 44×44vp 并禁缩小，完整保留在卡内。非审批 pill weight=0 继续按内容测量；未动协议/回调。修复后实际按钮 bounds 和批准/native 闭环由 root 重新 build/provider 实测。
- AgentToolCapsule 窄 lint `/tmp/e2-approval-layout-lint.log`：0 errors、1 warning（第106行既有组件定义的 `avoid-overusing-custom-component-check`），未为消除与此次布局无关的性能建议重构组件。

## 后续预览、IME 与后台中断修复

- root build6 实测 `/tmp/e2-terminal-final-connected-dark.jpeg` 中配置选择行/底部留白 PASS，真实 fit 为 43×26；`requestFocus` 单独调用仍未弹 IME，该尝试不能视为键盘 PASS。
- 已查看 `/tmp/e2-approval-completed-stale-reopen-dark.jpeg`：成功输出卡 `width('100%') + left32 margin` 导致 output Text 超出父卡右界。仅该 preview 块增加父 Column 的 `padding({ left:32, top:4 })`，内卡按剩余宽度100%布局，保留原32vp缩进/卡内12vp padding/折叠交互。窄 lint `/tmp/e2-output-preview-layout-lint.log`：`No defects found`，0 errors/0 warnings。
- 已读官方离线 `js-apis-inputmethod` 与当前 SDK：`getController` API9，`showTextInput():Promise<void>` API10（仍符合API12）。该接口操作已绑定并获焦的编辑框，12800003/12800009 表示焦点/绑定不成立。显式按钮现在 `requestFocus → await xterm focus → await showTextInput`；没有 attach/detach、TextConfig、原生输入回调或第二套输入桥。focus 返回统一 Promise；按钮自己 catch 并显示 `sshPageError`，不借 terminal.onError 停止远程 PTY。是否已有 Web 输入绑定并可弹 IME，交 root build7 实测；错误不会吞掉或新建输入 client 兜底。
- root 实测 Home→前台显示“未连接”而非“连接已中断”，因为页面 `onPageHide` 提前 stop/unsubscribe/null，抢在 EntryAbility interrupt 前。精确删除这条冗余释放；配置入口本来显式 release，back/aboutToDisappear 本来 release，后台仍由 EntryAbility interrupt 和 backgroundChanged 处理，保留 session snapshot 订阅以展示 INTERRUPTED。未加延时或生命周期 fallback；真实 Home 回前台状态待 root 复核。
- focus Promise / showTextInput / onPageHide 删除最终两终端文件窄 lint：`/tmp/e2-ime-lifecycle-lint.log`，`No defects found`，0 errors/0 warnings。

## 最终 trusted Web 工具栏实现

- root build7 实测 native `showTextInput` 返回 `input method client detached`，PTY 未被此输入错误关闭，但键盘仍未弹出；该路径已移除，不能视为 IME PASS。再次检查本地官方/当前SDK：未找到 API12 的脚本 focus 键盘许可开关；相关 WebSoftKeyboardBehaviorMode 始于API22，不能用于基线。
- 按 root 授权将“键盘”及原8工具键移入同一固定包内 `host.html` footer：52 CSSpx高、按钮最少44×44 CSSpx、水平滚动；按钮默认 disabled，沿原 `setInputEnabled` 与连接/后台状态同步。键盘按钮的 DOM click 内同步 `term.focus()`，没有 await/native bridge/timeout/attach/controller；工具键沿原 bytes 输入桥，并根据实际 xterm application cursor mode选择CSI/SS3。pointerdown只阻止按钮夺走 textarea 焦点，click仍处理。真实ArkWeb触摸是否成功弹IME仍交root build8验证。
- host body为flex Column，terminal div占footer上方剩余高度（min-height0），fit仍测该div；没有固定80×24。toolbar背景、按钮墨色与accent随现有palette CSS变量更新，深浅主题保持可读。TerminalPage删除原生重复工具栏/TerminalKey，TerminalView删除focus/sendKey/inputMethod路径；原16+navBarHeight底部安全padding仍在TerminalPage，背景INTERRUPTED修复保留。
- SSHPageErrors仅追加实测已知 `network_error` 中文：“SSH 连接已断开，请检查网络后重新连接”，未知错误继续原message。
- `node --test harmony/tests/terminal-host-reset.test.cjs` 6/6 PASS，保留真实包内VT parser/reset/UTF8/application-cursor回归；新增从打包HTML取9按钮，验证点击同步focus、连接/后台输入门禁、工具键bytes与theme CSS变量。DOM mounting/焦点/fit adapter仍为测试替身，**不声称Node验证了实际IME或Web布局**；host.js `node --check`通过。
- root 已实测后台回前台 `/tmp/e2-terminal-background-interrupted-light.jpeg` 显示 INTERRUPTED，无重发且保留原prompt；审批按钮和成功preview bounds均通过。root领域/fixture最终both 17/17，总102/102，无需重复auth回归。本节未操作设备。
- 最终 TerminalPage / TerminalView / SSHPageErrors 三文件窄 lint `/tmp/e2-web-toolbar-lint.log`：`No defects found`，0 errors/0 warnings。交 root build8 做 trusted click/IME/footer尺寸/PTY resize 实测，本 worker不以静态或Node检查代替设备验收。
