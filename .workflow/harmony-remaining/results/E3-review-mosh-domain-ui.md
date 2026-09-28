# E3 Mosh 独立审查：domain / platform / UI

日期：2026-09-28。reviewer：e2_platform_secure。基线 E2 `87f69a88cf4c91b2882935b5873ebcc0e80f7798`；依据 `packets/E3-contract.md`、`results/E3-runtime-design.md` 与 harmony-arkts skill。遵循最新约束：仅必要验证、具体源码证据后精准复现，不重复 owner 套件、不操作设备、不扩安全架构。本轮只读产品代码，只写此报告与 `/tmp` 单点复现脚本。

## 结论

A（冻结合同与源码）及 B（产品接线、所有权和可达性）通过复审，已发现并闭环一个手动连接目标冻结时机问题。C 已只读审阅 root 提供的两张暗色图，并确认真实 IME 仅剩两行的布局问题，窄建议已交 UI owner，暂不判 PASS。浅色/后台及修后必要图待 root 统一提供。SSP/native 并发与 API12 archive 由独立 native reviewer/root 收口，本报告不替代它们。

## 已发现并修复：手动目标在 reset 后才冻结

原 `TerminalPage.connect` 在点击时保存旧 profile 标签、protocol 与 locale，但 `captureTarget` 位于 `await terminal.reset()` 之后。同一个 profile 在 reset 等待期间修改并验证后，连接会采用新 digest，页面仍显示旧主机标签。E3 Mosh 分支沿用了原 SSH 的此时序。

单点脚本 `/tmp/e3-mosh-review-freeze.cjs` 抽取并执行真实 `TerminalPage.connect`，只对 xterm reset 和 runtime 输入边界使用夹具。reset 悬停期间把 capture digest 从 old 改 new：修前实际 request 为 `new-verified-target`，显示标签仍为 `old-user@old-host:22`，具体复现成立。

UI owner 最小修复到 `TerminalPage.ets:189`：`runtime.captureTarget(profile.id)` 在第一个 await 前执行，只冻结一次，由 SSH/Mosh 两个 request 共用。reviewer 重用同一脚本，只把正确断言改为 old 并执行一次，exit 0：

```json
{"actualTarget":"old-verified-target","visibleLabel":"old-user@old-host:22","protocol":"mosh","locale":"en_US.UTF-8","expectedFreeze":"old-verified-target"}
```

真实 `SSHTargetResolver.targetProfile`（17–24）会在后续 prepare 发现该旧快照与新 digest 不同并抛 `target_changed`；不会把重置等待期间的新主机重新冻结为获准目标。复现证明 UI 传参时机；resolver 的拒绝依据真实源码和 owner 既有必要测试。UI owner 报告此单页精确 lint 0 error / 0 warn；reviewer未重复 lint/build。

## A：domain 与 platform 合同证据

| 范围 | 当前源码证据及结论 |
| --- | --- |
| 严格目标、pin 与 credential 顺序 | `terminal/target_resolver.ts:12–36` 捕获 digest/default 身份；先核对 digest、default、pin 的 host/port，再 load credential；load 后重新检查 abort 与目标。`terminal/runtime.ts` 仅委托提取规则，公开依赖/API 保持原形。 |
| bootstrap 与同一次 SSH peer | `mosh/runtime.ts:76–113` 直接调用低层 SSH transport，使用固定 mosh-server command，认证 handle 的 `peerAddress` 为唯一 UDP peer；缺 peer 明确失败。没有借 SSH terminal job/session，也没有第二次 hostname DNS。UDP start 前再次校验冻结 target（67）。 |
| key 与启动输出边界 | `bootstrap.ts:14–24` 只接受唯一合法 MOSH CONNECT 端口/key；`runtime.ts:94–112` stdout 只在 bootstrap 方法内存中解析并转交 options，finally 清空；stderr 计入限额但不输出。Session、TaskStore spec/summary、工具 JSON 均不含 sessionKey 或原始 bootstrap 输出（runtime 22–31、57–61、126–136；tools 46–49）。错误仅安全 code/message（bootstrap 27–44）。 |
| 限额与 locale | runtime 33–46 在 credential/网络前验证尺寸、总 cells≤30000 并复制 request/target；bootstrap stdout+stderr 总 bytes≤65536、SSH exec 与轮询 deadline 各有明确 timeout。`bootstrap.ts:5–11` locale 仅 C.UTF-8 / en_US.UTF-8，经 POSIX quote；数字 port 白名单；无自动第二次 bootstrap 或 locale 猜测重试。 |
| 取消、后台与迟到 handle | runtime 53–73 注册 controller/任务后才进入 prepare；184–197 中止 bootstrap、UDP 与 starting，后台收口为 INTERRUPTED。`NativeMoshTransport.ets:13–42` 先检查、注册 abort listener、再检查，取消实际 native requestId；start 返回后若取消/后台，先 close 实际 handle 再 reject，finally 解绑 listener。 |
| 状态与背压 | `mosh/stream.ts:26–47` 每会话一条串行 read/write/resize 队列和 native reader；100ms/64KiB VT 增量通知 xterm，工具 read 仅缓存；54–67 映射真实 native 末态；84–104 close 先到 native，可唤醒堵塞 write，然后串行收口。`runtime.ts:163–175` 输入上限、复制 bytes，exec 排队发出前验证 signal/target。 |
| 冷启动 | `runtime.ts:142–155` 只读共享 TaskStore 历史 tail；旧运行态返回 INTERRUPTED，没有 key 恢复、自动 SSH bootstrap 或第二调度器。 |

## B：真实产品接线与 xterm 所有权

| 范围 | 当前源码证据及结论 |
| --- | --- |
| singleton 和共享边界 | `AppContainer.ets:433–495` SSH/Mosh 共用 SSHProfileStore、CredentialStore 与 AgentTaskStore；Mosh 使用独立 MoshRuntime，bootstrap 为低层 NativeSSHTransport，UDP 为 NativeMoshTransport。498–507 只中断已初始化 runtime；不会因后台初始化新会话。 |
| 手动可达与显式协议 | `SettingSandboxPage.ets:194–207` 远端配置及 TerminalPage 可达；`TerminalPage.ets:23` 默认 SSH，123–140 显式切换前停止实际 owned session，停止未成功不会切协议。Mosh locale 是明确选择，连接前冻结，不暗换 runtime/protocol。 |
| 同一 xterm 和输入门禁 | TerminalPage 211–219 保存实际启动的 ownedRuntime/sessionId 并订阅；233–268 事件按 sessionId 校验，input/resize 发给 ownedRuntime 且检查 running/background；272–305 断开/离页只关闭该页所有会话。403–409 仍为同一个 TerminalView 的 bytes/resize 回调。 |
| Agent 四件工具 | `mosh/tools.ts:87–123` 专用 start/exec/read/stop，runtime JSON 为 remote_mosh，无逐命令 exit_code；start/exec/stop 需批准且 auto=false；read 不重复批准。55–65 factory 冻结显式/默认目标，exec 根据实际 Mosh session profile 验证。 |
| root/provider/ChatPage 接线 | `AppContainer.ets:1553–1557` terminal 能力等待真实 SSH/Mosh 依赖；`ChatPage.ets:3695–3710` 注册两个真实工厂并传相同 profile/activity/conversation；3903–3905 dispatcher 使用 guard.before，captureInvocationMetadata 接入既有 tool loop。 |
| 审批协议与目标持久化 | `terminal_approval.ts:15–21,80–115` 按精确 tool name 保存 remote_ssh/remote_mosh、target、label；续跑先核对协议，再查对应 runtime/session 与当前 digest/default。Mosh 缺协议/缺 context/目标变化均 target_changed，不借 SSH 执行；旧 SSH 无 protocol 兼容仅限 SSH。 |

## 验证归属与 C

reviewer 自己只执行上述真实 connect 方法的一次修前复现及一次修后同脚本断言，没有新增仓库测试、重跑 owner 全套、build/sign/安装或设备操作。

已阅读并交叉核对 owner 报告，不冒充 reviewer 的独立运行：`E3-mosh-domain.md` 记录 8 个必要 Mosh + 35 个受影响 SSH 测试共 43/43、strict tsc exit 0、精确 lint 0 error / 818 warn；`E3-approval-target.md` 记录相关审批链 109/109、strict tsc exit 0、lint 0 error / 93 warn，真实 domain/factory 续跑默认目标改变时 SSH bootstrap=0 / UDP start=0。这些夹具/类型证据不代表 native UDP、Asset、签名设备或视觉验收。

## C：暗色连接与真实 IME 高度问题（待修复确认）

2026-09-28 只读查看 root 已有 `/tmp/e3-mosh-dark.jpeg` 与 `/tmp/e3-mosh-dark-input.jpeg` 各一次，没有巡检或操作设备。普通暗色图显示 Mosh 选中、en_US.UTF-8、明确 fixture/host 标签、已连接 43×19、断开按钮及终端，颜色和内容可辨。第二图显示真实 IME，但 root 说明截图处于键盘收起过渡，图中仍为 43×19；实际 IME 保持打开时 root 的 AX 证据为 **43×2**。这两个不同时间的状态不能混为视觉 PASS。

**P2：输入时两行终端不足以完成交互，活动会话仍占用不可操作的 Mosh 前置配置。** `TerminalPage.ets:56` 使用 KeyboardAvoidMode.RESIZE；329–351 只判断 `protocol === 'mosh'`，因此已连接时仍渲染多行要求说明和固定 52vp locale row。该 row 在活动/连接态已通过338的 enabled禁止操作，保留它只压缩下方 layoutWeight(1) 的 TerminalView。暗色图直接可见这两块与 profile/host/状态共同占据头部；真实 IME 的两行数值由 root AX 提供，reviewer未重复测量。

交给 UI owner 的最小建议是仅把329–351整块限定为：

```ts
this.protocol === 'mosh' && !this.isRunning() && !this.connecting && !this.disconnecting
```

连接/输入/重连期间隐藏 Mosh 说明、locale row 与 locale choices；断开/中断后待重新连接时恢复原配置。保持 profile/host 标签、协议、状态与断开操作可见。无需新增 IME 监听、折叠状态或通用框架，既有 connecting 时已清空 choosingLocale。可明确释放 52vp locale row、多行说明和间距；修后实际终端 rows 仍需 root 唯一必要输入图/AX确认，不能从源码承诺数字。

普通图中的 shell 回车显示 `^M` 未提交，root 已交 domain owner 排查 SSH bootstrap PTY 与上游 `-tt` 差异；这是另一已复现功能问题，本 reviewer 没有另做输入测试，也不从截图猜测根因。

当前 C：布局建议待 owner 修复及 root 修后必要图，回车提交问题待 domain 收口，浅色/后台图随后由 root 补。**暂不 PASS**；不扩测试、设备或主题矩阵。
