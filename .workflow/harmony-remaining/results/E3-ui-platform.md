# E3 Python / Mosh UI 与平台消费者

2026-09-28。已读 harmony-arkts skill、E3-contract 和 E3-runtime-design。仅修改授权的新 PythonPage、NativePythonTransport、NativeMoshTransport、既有 TerminalPage、SettingSandboxPage、ChatAssistantLocalToolsPage、SSHPageErrors，以及平台 adapter 回归；未改 DI/ChatPage/EntryAbility/native/CMake/d.ts/index/domain，未操作设备、未 stage/commit，保留其他 worker 改动。

## Python 实际消费

- `createNativePythonTransport(): Promise<PythonTransportPort>` 在 root singleton 初始化时部署固定包内 `python/python314.zip` 和 `python/amber_python.py` 到 `filesDir/amberagent/python/`。沿现有 resourceManager rawfile / fileIo API；binary Uint8Array 按真实 byteOffset 切片，循环处理短写，关闭临时文件后原子 rename。resourceRoot 固定，不使用 Agent 参数或 Workspace 根；资源异常直接失败，不回退到远端或 JS。
- adapter version 调实际 `native.pythonVersion()`，execute 调实际 `native.pythonExecute(requestId,{source,stdin,timeoutMs,resourceRoot})`。signal 在 native 注册前挂载，pre-abort/后台不启动；native.pythonCancel 是同步标记，listener 一直保留到 Python Promise 实际返回，未伪造提前结束或硬取消。
- `PythonPage` 由 `getPythonRuntime(): Promise<PythonRuntime>` 消费同 domain singleton，提供代码/stdin/1–60000ms timeout、运行/取消、版本、实际 stdout/stderr/status/exitCode/errorCode，输出可复制。取消按钮只 abort 本页 controller，UI 保持“取消中”直到 execute settle；退出同样取消本页，后台由 root `interruptForBackground` 标记并停止，页面只禁止后台新运行，不抢先把 INTERRUPTED 变成 CANCELLED。
- 提示准确对应实际 helper：stdin 字符串或 `input()`，不指示禁用的 sys 模块。说明本机数据处理、Workspace read→stdin→write、256/64/128KiB限额、无文件/网络/pip、C调用阻塞不能硬取消；不称为系统级安全沙箱。
- 页面使用 V1/StorageProp theme、自有 token palette、CardGroup lambda只调用读取 state 的 @Builder、44vp按钮、16gutter及16+navBarHeight底部 padding。TextArea placeholder/theme墨色显式设置，未知错误保留原message。

## Mosh 实际消费

- `createNativeMoshTransport(): MoshTransportPort` 是同步工厂。start/read/write/resize/close直接调用冻结 NAPI；同步read包装Promise。start阶段signal挂载到实际start结算，取消后迟到handle必须先await close再拒绝，不留后台session；不打印或持久化session key。
- `TerminalPage` 默认SSH，显式SSH/Mosh两44vp按钮。连接前冻结协议/profile/所选serverLocale；共享同一固定本地xterm ready/input/resize通路。每个own session保存实际ownedRuntime，输入/resize/close都发回创建该session的runtime，不能因选择项变化串线。
- 切协议先await本页stop；若关闭失败仍RUNNING，保留原协议和订阅。关闭后清subscription/session并reset VT，再显示新协议。pending start由原operationVersion+controller取消，迟到会话实际stop，不重播/复用远端执行命令。
- Mosh请求使用真实 `MoshSessionRequest`，udpPort省略行为选服务器默认端口；真实packet connectionState=reconnecting才展示“Mosh重连中”。没有给Mosh捏造命令exitCode/日志路径。
- 真实Mac服务端只支持en_US.UTF-8后，新增52vp“服务器UTF-8区域”选择行，两展开按钮限定C.UTF-8/en_US.UTF-8，默认C.UTF-8；connect在第一个await前保存所选locale到本次request，不允许active/pending时编辑、不自动失败重试。Mosh提示远端mosh-server/UDP、后台新建会话、关闭未获确认时远端可能仍运行。
- SSHPageErrors只增加实际Mosh已知server-missing/bootstrap/UDP-timeout/background-interrupted中文，bootstrap指示所选UTF-8区域；network_error文案改为“远端连接”同时准确覆盖SSH/Mosh，未知message不替换。

## 入口与 root 接线

| 消费者 | 接口/路由 |
| --- | --- |
| 运行环境→本机执行 | `pages/PythonPage`，无参数，需root main_pages注册 |
| Python DI | `await createNativePythonTransport()` → domain PythonRuntime → `getPythonRuntime()` |
| Mosh DI | `createNativeMoshTransport()` → domain MoshRuntime → `getMoshRuntime()` |
| TerminalPage | 默认SSH；Mosh selector选择真正getMoshRuntime，既有profileId参数保持 |
| 助手工具设置 | root冻结`LocalToolOption='python'`独立开关；terminal显示SSH命令/Mosh交互及批准要求 |

SettingSandboxPage既有Workspace与执行参数保持；只增加Python入口并更新远端运行时描述。root已接公共exports、native声明和DI；Agent catalog/approval、EntryAbility后台、native链接及设备仍由各owner/root负责。

## 验证与实际边界

- `node --test harmony/tests/e3-native-adapters.test.cjs` 6/6 PASS：固定rawfile精确binary/短写/close后publish/拒绝外部root，Python注册取消0启动/实际settle才结束/后台0启动，Mosh迟到handle取消等close、原bytes和resize/read状态透传。测试transpile实际adapter源，替身仅SDK文件系统/AppStorage/NAPI；这是平台边界逻辑回归，**不是实际CPython或SSP/UDP验收**。一次测试初始用固定microtask次数抢先断言，已改为等待真实mock close回调信号，未通过sleep掩盖。
- 六文件初次和七文件修stdin/后台/错误后窄lint `/tmp/e3-ui-platform-lint.log` 为 `No defects found`，0 errors/0 warnings。新增locale后TerminalPage/SSHPageErrors最后单独复查另记。
- locale 最后两文件窄lint `/tmp/e3-mosh-locale-lint.log` 为 `No defects found`，0 errors/0 warnings；所有权内tracked diff-check通过。根据用户最新仅必要tests、不重复模拟器的要求，到此停止扩展检查，不新造状态组合/UI测试；root做必要统一build与一次核心亮暗验证。
- 页面/adapter完整ArkTS类型与SDK资源装包由root统一build回喂；本worker未自行build/sign/install。原生host/API12 ELF/真实CPython/Mosh功能、亮暗截图/按钮bounds/键盘/后台/实际LAN UDP漫游仍须root和独立review验收，不能以lint或stub回归宣称完成。
- root首轮build回喂仅NativePythonTransport注册后signal检查的ArkTS控制流收窄错误；沿NativeSSH的requestStopped helper在两处统一即时读取signal/background，语义不变。单文件lint `/tmp/e3-native-python-controlflow-lint.log` 0 errors/0 warnings，未重复6条adapter回归；待root下一轮完整build。
- 独立A/B复现reset等待期间profile摘要更新会使旧可见label搭配新目标；`connect()` 已在首个await前仅调用一次所选runtime.captureTarget，SSH/Mosh请求共享该SSHTargetSnapshot，domain在摘要变化时据此拒绝而不换目标。单页lint `/tmp/e3-terminal-target-freeze-lint.log` 0 errors/0 warnings，diff-check通过；root复用既有 `/tmp/e3-mosh-review-freeze.cjs` 做修后必要验证，未新增suite。
- root真实IME AX为Mosh 43×2，说明与禁用52vp locale行挤占终端；只将该Mosh块显示条件限定为未RUNNING/QUEUED且非connecting/disconnecting，活动/连接/重连隐藏说明、locale与choices，终止待重连恢复。profile/host/协议/断开保留，未增加IME监听/折叠框架。单文件lint `/tmp/e3-terminal-ime-space-lint.log` 0 errors/0 warnings，未写测试/重跑既有测试或操作设备；真实rows仍由root修后必要图确认。
