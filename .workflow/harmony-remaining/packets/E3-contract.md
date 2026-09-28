# E3 执行合同：本机 Python 与真实 Mosh

基线为完成 E2 的 Remote SSH / xterm 管线。研究见 E3-runtime-design.md。
只纳入这两件，不实现 Alpine/proot、本地完整 shell、pip、Notebook、SFTP、
永久后台保活或无 session key 的冷启动恢复。

## Ownership

- Python native：native/python/、新增 cpp/python_core.* 和 python_napi.*、Python helper/stdlib 打包资源、host及target原生测试；不得改 CMake/napi_init/d.ts/DI/设备。
- Mosh native：native/mosh/、新增 cpp/mosh_core.* 和 mosh_napi.*、host及target原生测试；不得改 CMake/napi_init/d.ts/DI/SSH既有源/设备。
- Domain：冻结原生接口后新增 python/、mosh/ 领域模块/测试；真实共享的 SSH target+credential prepare 可窄提取，统一由 root 授权 owner；不得改 native/UI/设备。
- UI/platform：领域接口冻结后新增 adapter/Python page、既有TerminalPage协议选择和适用设置入口；不得改 native/DI/ChatPage/设备。
- Root：CMake、napi_init、原生声明、DI/routes/ChatPage、SSH peer IP最小扩展、设备/统一构建/签名/阶段提交。
- 独立 reviewer：逐项 A 逻辑、B 接线、C 真实截图，精准回喂。每位worker知道其他人同时工作，不回退别人修改。

## Python native v1

NAPI新增：

```
pythonVersion(): string
pythonExecute(requestId: string, options: PythonOptions): Promise<PythonResult>
pythonCancel(requestId: string): void
```

```
PythonOptions { source: string; stdin: string; timeoutMs: number; resourceRoot: string; }
PythonResult { status: 'completed'|'failed'|'cancelled'|'timed_out';
 exitCode: number|null; stdout: string; stderr: string; errorCode: string|null; }
```

resourceRoot 是平台 adapter 初始化的应用私有固定目录（helper/stdlib），不暴露为Agent入参。
CPython锁官方3.14.7。须真实host addon执行，再OHOS API12静态库/ELF，禁止把远端Python或JS冒充本机Python。
同一个native owner串行管理interpreter，不长期占NAPI async pool；全程持有真实cancel control，
取消只在实际停止后结算，不超时Promise伪造停止。直接fork/exec手机不支持，不能绕此事实。
使用isolated PyConfig、固定stdlib/helper搜索路径、禁environment/site/native signal handler。
source/stdin/output上限分别256/64/128KiB（UTF8 bytes）；timeout 1–60000ms，默认15000ms。
参照iOS同产品helper，只开放已查证数据处理能力（json/math等）；不开放open/os/pathlib/network/
subprocess/第三方native extension。文件使用既有Workspace read→stdin→write；这不是系统级安全沙箱。
每次fresh globals，stdio/modules的跨次污染有真实用例；异常、纯字节码循环取消/超时、
吞BaseException的循环和后续成功均有真实native回归。C helper阻塞不能硬杀要明示，不伪造通用硬取消。
不打印source/stdin中的用户敏感数据来充当诊断；错误输出是用户执行结果，底层初始化日志无输入。

## Mosh native v1

NAPI新增：

```
moshStart(requestId: string, options: MoshOptions): Promise<MoshHandle>
moshCancel(requestId: string): void
moshRead(handle: MoshHandle, maxBytes: number): MoshPacket
moshWrite(handle: MoshHandle, bytes: Uint8Array): Promise<void>
moshResize(handle: MoshHandle, columns: number, rows: number): Promise<void>
moshClose(handle: MoshHandle, reason: 'cancelled'|'disconnected'|'release'): Promise<MoshPacket>
```

```
MoshHandle { id: string; kind: 'mosh'; }
MoshOptions { peerAddress: string; port: number; sessionKey: string;
 columns: number; rows: number; connectTimeoutMs: number; }
MoshPacket { state: 'running'|'reconnecting'|'closed'|'cancelled'|'disconnected'|'failed';
 bytes: Uint8Array; errorCode: string|null; lastHeardMs: number|null; }
```

使用Mosh1.4.0真实SSP/Protobuf/crypto及Display(false)帧差分，不调用CLI main，
不碰进程全局stdin/stdout/termios/signals/exit。上游timestamp全局缓存要求所有Mosh
session在同一个owner线程，循环先freeze_timestamp。排队input/resize/cancel由同owner处理，
异步完成和env cleanup遵循已验证TSFN做法；session key仅内存、清理后不持久化，不进错误或日志。
限制每次input/read与native缓存，按具体背压行为实现，不另建无限输出重播。
不能提供虚构的每命令exitCode。reconnecting由真实UDP last-heard/SSP状态产生；首次无UDP
响应要在connectTimeoutMs内失败；临时丢包/源端口变化允许同远端会话恢复，不能重跑SSH冒充。
所有依赖、补丁/source hashes、许可证/notice锁定；不改SDK或系统配置。

## 消费与验收

SSH bootstrap必须使用低层SSHTransportPort，禁止调用普通job/log pipeline，避免把
MOSH CONNECT的key持久化。strict pin/冻结target/凭据规则复用E2，UDP peer必须是本次
已认证SSH socket的peer IP（root最小扩展handle），不进行可能换服务器的第二轮DNS。
端口/启动命令固定支持并明确校验，服务端必须实际mosh-server；stdout只在内存解析。

Python独立assistant能力开关+python_execute工具+手动输入结果页，使用同DI singleton/
AgentTaskStore/Activity/AbortSignal。代码执行沿既有批准门，不借SSH默认主机或远端cwd。
Mosh交互协议选择及专用session tools，复用可信profile/xterm字节视图；pending metadata冻结
目标/协议，不能把用户批准的SSH暗换为Mosh。默认SSH保持原行为，job exec仍准确remote_ssh。
后台同时中断Python与Mosh，回前台不重发；Mosh关闭不能保证未获ACK的远端daemon已停止。

验收必须有真实native、API12编译/链接、领域红绿、类型/lint、统一HAP签名、双设备安装/
启动、亮暗UI和独立审查。Python断网仍执行版本/Unicode/stdin/json/math，文件入口/限制/
异常/输出上限/取消后下一次成功；Mosh真实mosh-server/UDP输入/resize/掉包/源端口改变，
保持远端PID与变量且无第二SSH bootstrap。HDC TCP rport不证明UDP，root单独实测实际LAN。
外部服务/账号未测层和当前设备API24/26分别登记，不把API12编译当API12实机执行。
