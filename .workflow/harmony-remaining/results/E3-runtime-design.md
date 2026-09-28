# E3 内嵌 Python / Mosh 前置调查

2026-09-28；只读代码与官方资料调查，未修改产品源、构建、操作设备。
计划锚点：`docs/plans/2026-09-28-harmony-remaining-execution.md:36`。
已读 `.agents/skills/harmony-arkts/SKILL.md`；API12/V1、官方文档、实机验证闸门照旧。

## 结论与真实边界

- Python 应是 **本机 HAP 内链接的 CPython**，与远端 `python`、ArkWeb JavaScript、Android Alpine/proot 区分；不需要先实现一个本地 POSIX shell。
- Mosh 必须是可信 SSH bootstrap → 真实 Mosh SSP 加密 UDP → 交互输入/窗口调整/网络漫游；SSH PTY 改标签、普通 UDP echo、只解析启动行都不算完成。
- 没有发现已有可直接复用的 Harmony Python/Mosh 实现。两件可以在独立 native/domain 模块并行实现，但统一 native 链接、DI、页面接线由主 agent 单独整合。
- 当前没有证明 CPython/Mosh 库无法交叉编译的硬阻断；**直接启动打包的 executable** 是有官方证据的手机平台阻断，不应反复尝试这条路线。

## 当前仓库与双端参考

| 事实 | 真实代码锚点 | 对 E3 的含义 |
|---|---|---|
| Harmony 文件工具已有 `/workspace` 规范化和 FS 端口 | `harmony/chat/src/main/ets/chat/workspace.ts:67,103` | 不复制第二套目录模型 |
| 默认根是应用私有镜像，也支持 picker 授权目录 | `harmony/entry/src/main/ets/platform_impl/WorkspaceStore.ets:32,77` | 本地根不是 SSH 的 cwd；持久授权可能需重新选择 |
| 现有 JS 执行是隐藏 ArkWeb，非 Python | `harmony/entry/src/main/ets/platform_impl/JsSandboxTool.ets:1,158` | 不能包装 JS evaluator 冒充 CPython |
| 终端 NAPI 已支持 SSH exec / PTY / read / write / resize / close | `harmony/chat/src/main/ets/chat/terminal/ports.ts:7` | 可复用 bootstrap 传输和终端 UI 字节通路 |
| E2 工具和结果明确 `remote_ssh` | `harmony/chat/src/main/ets/chat/terminal/tools.ts:39,101` | E3 必须返回准确 runtime，不无声换默认执行位置 |
| 可信目标摘要和凭据加载已经闭环，但 prepare 是 private | `harmony/chat/src/main/ets/chat/terminal/runtime.ts:32,37,124` | SSH/Mosh 真实重复的约 30 行可小范围提取共享 |
| 运行环境页现只提供 Remote SSH | `harmony/entry/src/main/ets/pages/SettingSandboxPage.ets:194` | E3 增加真实入口，不能只有工具未有设置/手动使用入口 |
| 当前 assistant terminal 开关就是 SSH | `harmony/entry/src/main/ets/pages/ChatAssistantLocalToolsPage.ets:68` | Python 最好独立开关；Mosh 标注交互会话用途 |
| 注册和 singleton 已有固定接入点 | `harmony/entry/src/main/ets/di/AppContainer.ets:433,1515`；`pages/ChatPage.ets:3692,3704` | 只增加消费者，不继续堆运行实现进 ChatPage |
| Agent 提示仍声称 Alpine、安装包、sync_workspace | `harmony/chat/src/main/ets/chat/agent_soul_prompt.ts:21`；`skills.ts:725` | 精准更新实际能力说明，避免 Agent 混淆本地/远端文件 |
| Android Python 属 Alpine/proot，包可通过 apk/pip 安装 | `feature/terminal/src/main/kotlin/app/amber/feature/terminal/AlpineRuntimeInstaller.kt:60,109`；`TerminalRuntime.kt:148,451`；`TerminalRuntimeInternals.kt:96` | Android ELF/loader、ProcessBuilder、Termux 不适用于 Harmony 手机 |
| iOS Python 是真实 CPython 3.14.7，只有受限 `python -c` | `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/IOSAmberShell.swift:416`；`AmberShellPythonRuntime/README.md:11,32` | 可参考结果、限制和取消语义，不移植 Objective-C/Apple framework |
| iOS helper 无网络和 open/os/pathlib/subprocess；限制不是安全沙箱 | 同 iOS 根 `AmberShellPythonApp/amber_shell_python.py:1,35,62,1034` | 最小闭环可以用字符串 stdin 与输出接已有文件工具 |
| iOS Mosh 仍 reserved / failedSnapshot，无消费者 | 同 iOS 根 `iosApp/IOSTerminalRuntime.swift:942`；`feature/terminal/api/.../TerminalRuntimeModels.kt:159` | 不是已有实现或已验证 GPL 闸门的 Harmony 依据 |

## 官方约束与交叉编译

1. 离线官方 `faqs-ability-112` / `faqs-ability-29` 明确：普通手机应用不能直接 fork。
   查询与全文：`harmony/scripts/devecocli.sh docs search 'fork 使用限制'`，`docs read <完整命中路径>`。
2. 离线官方 `capi-native-child-process-h`：模块从 API12，但 `OH_Ability_CreateNativeChildProcess` 在 API≤13 仅 PC/2in1，API14+增加 Tablet，手机返回 `NCP_ERR_NOT_SUPPORTED`。
   `OH_Ability_StartNativeChildProcess` 从 API13，同样不支持手机；不是通过 `canIUse()` 就能获得的手机硬取消方案。
3. [CPython 官方交叉编译](https://docs.python.org/3.14/using/configure.html#cross-compiling-options)：build Python 与 target Python 版本必须匹配，提供 `--build`、`--host`、`--with-build-python`、`CONFIG_SITE`。
   当前 iOS 版本是 3.14.7，可锁同版官方 tarball 与 SHA256；不要用系统 Python3.9/3.12 生成 3.14 冻结产物。
4. [CPython 3.14.7 configure.ac](https://github.com/python/cpython/blob/v3.14.7/configure.ac#L320) 的泛 Linux 分支可覆盖 linux-ohos；[config.sub](https://github.com/python/cpython/blob/v3.14.7/config.sub#L1725) 没有 ohos OS 识别。
   建议优先锁定最小 config.sub 补丁；另一可验证方案仅让 configure 使用 linux-musl 分类，实际 CC/LD 始终用 OHOS triple/API12 sysroot。
   这是编译建议，未运行 configure、未证明所有 libc 特性可用；不能批量伪填 configure cache 为 yes。
5. E2 已有 PIC/静态依赖、OHOS compiler wrapper 和 SDK Ninja 选择：`harmony/native/ssh/scripts/build-deps.sh:34`，`harmony/entry/src/main/cpp/CMakeLists.txt:28,43`。
   新产物应 arm64、API12、无 Android/JNI/macOS依赖；stdlib/helper 作为 app-owned 数据打包，初始化路径固定。
6. [Python isolated 初始化](https://docs.python.org/3.14/c-api/init_config.html#isolated-configuration) 适于嵌入，关闭环境/site/信号处理，固定搜索路径；隔离配置本身不是权限沙箱。
7. [Mosh 官方构建配置](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/configure.ac)：依赖 Protobuf、匹配的 host protoc、crypto、term­info/ncurses；不要把 Mac 的 protoc 动态依赖当成目标库。
   官方 latest release API 当前返回 [mosh-1.4.0](https://github.com/mobile-shell/mosh/releases/tag/mosh-1.4.0)；下载、source/patch hash、notice 像 E2 一样锁定。
   复用 E2 OpenSSL libcrypto；Protobuf 编译其目标静态库，选择实际兼容的版本后冻结，不凭空假定新旧生成器 ABI 兼容。
8. 离线官方 `faqs-network-117` 确认 Native socket/非阻塞 poll 可用；`module.json5:43` 已有 INTERNET。
   `bpta-reasonable-network-use` 要无长时任务时退后台断开 TCP/UDP；不能以 Mosh 漫游承诺后台保活。

## Python 最小可用设计（建议接口，尚未冻结）

- 独立 `python/` native + domain 模块；一个 interpreter/owner 线程串行执行，避免长期占 NAPI async worker 池。
- native：`pythonExecute(requestId, source, stdin, timeoutMs): Promise<Result>`；`pythonCancel(requestId): void`。
  `Result = {runtime:'embedded_python', status, exitCode:number|null, stdout, stderr, errorCode}`。
  cancel 仅原子标记/唤醒，执行线程的 trace 检查单调 deadline；返回后再释放 control，不能先宣称已停止。
- 参照 iOS whitelist/helper 的有证据边界，支持函数/循环、json/math/统计等数据处理；每次 fresh globals，结束恢复 stdio/module 状态。
  代码、stdin、输出采用 iOS 现有的 256/64/128KiB 界限，UTF-8 按 bytes 计算；缺失模块明确列出。
- 首个闭环不向 Python 暴露文件 descriptor、`open`、os/pathlib/网络/安装包。
  本地 `file_read` → `python_execute(stdin=文本)` → `file_write` 已能处理用户文件；不写第二套文件接口或远端同步。
  测试直接 Python 文件访问被拒、文件工具路径越界仍失败；UI 明确显示“文件通过 Workspace 工具输入/保存”。
- 新 `python_execute` 独立 assistant 开关和 catalog 声明；沿用已存在代码执行批准机制，不调用 SSH 默认 profile。
  每次执行登记 AgentTaskStore/Activity、接 AbortSignal、真实取消按钮，成功/异常/取消/超时清理 cancel capability。
- 设置入口打开小型代码输入/输出页，UI 与 Agent 工具使用同一 singleton；显示 CPython 版本和可用模块。
  不顺手扩成 pip、Notebook、多 interpreter、包中心或本地完整 shell。
- **诚实限制**：trace 只能协作式打断 Python 字节码，无法强杀正在阻塞的 C helper/extension；与 iOS `README.md:12` 一致。
  不允许第三方 native extensions；对 `try/except BaseException` 吞取消、异常清理、连续运行专门测真实 native bridge。
  系统级进程隔离/硬内存限额没有当前手机支持证据，不能靠线程 `kill` 或超时 Promise 冒充。

## Mosh 最小可用设计（建议接口，尚未冻结）

- 复用 SSH profile/Asset credentials/严格 pin/目标 digest，bootstrap 使用 **低层 SSHTransportPort**，不要调用普通 terminal job。
  原因：`harmony/chat/src/main/ets/chat/terminal/stream.ts:66` 保存输出；启动行中的 Mosh session key 不得进日志、Activity、TaskStore、聊天消息或 Preferences。
- 固定支持的 `mosh-server new` + locale/port 参数通过明确构造和 shell quoting；服务端必须真实安装 mosh-server，不能由端内伪造启动行。
  允许明确 UDP port/range 配置；端口范围和空/非法启动行及时失败，错误可指导用户检查安装、UTF-8 locale 和 UDP 可达性。
- [官方 bootstrap 脚本](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/scripts/mosh.pl#L377) 读取 `MOSH CONNECT` 的 port/key，再切 UDP。
  session key 仅内存转交 native；采用经过同一次可信 SSH 确认的服务器地址，不因任意输出悄悄换 UDP 主机。
  host 为 DNS 时，需要取得这次 SSH 已连接的 peer IP，或明确定义并测试同一地址解析；不能用另一轮随机 DNS 命中代替同主机。
- native 自有 `MoshHandle`，接口 `start/read/write/resize/close`；read 返回 VT bytes + connection state/last-heard，不给不存在的每命令 exit code。
  同 owner 线程处理 UDP transport + 用户输入队列 + resize + cancel，env cleanup 跟 E2 的已验证做法。
  [上游 timestamp.cc:61](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/src/util/timestamp.cc#L61) 的 frozen clock cache 是全局可变值：每轮必须 `freeze_timestamp()`，所有 Mosh transport 先共用一个 owner，不照搬 SSH 每 handle 独立线程引入数据竞争。
  中文/组合字符及 `wcwidth` 所需 UTF-8 locale 要真实测试；不向 app 全局 setenv(MOSH_KEY/TERM) 或安装 CLI 信号处理器。
- [上游 STMClient](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/src/frontend/stmclient.cc#L228) 用全局 stdin/stdout、termios、信号、exit，不能直接在 NAPI 线程调用 `main()`。
  最少复用 `Network::Transport<Network::UserStream,Terminal::Complete>`、`Parser::UserByte/Resize`、`Terminal::Display(false)`；帧差分进入既有 xterm。
  独立 embedded driver 替代 CLI 的 I/O/信号循环；不重写 SSP/Protobuf/crypto。
- [Display(false) 官方实现](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/src/terminal/terminaldisplayinit.cc#L83) 可跳过 TERM 数据库查询；仍先尝试正常静态 tinfo 构建，若删依赖必须有精确链接/source 证据。
- [上游网络层](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/src/network/network.cc#L511) 用认证后 packet/sequence 更新 client 地址，实现漫游；不能用新 SSH shell 重连冒充原会话。
- 独立 Mosh 交互入口/协议选择，默认现有 SSH 不改变；只提供会话 start/exec/read/stop 工具，不接异步 exec job/包安装或无出处的精确退出码。
  tools 可以新命名 `terminal_mosh_session_*`，最小扩充现有批准捕获和预览；目标/协议在 pending metadata 冻结，修改 profile/default 后旧批准不能偷换协议。
- 运行期间网络掉包可显示“重连中”，按 SSP 状态恢复；后台主动关闭 UDP，标记 interrupted，回前台不自动执行命令。
  冷启动无 session key 时重新 SSH bootstrap 明确创建新会话；不假装恢复旧 shell。远端残留 server 在关闭未获 ACK 时诚实提示。
- [Mosh GPLv3+ 与 OpenSSL 例外](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/src/frontend/mosh-client.cc#L1) 要保留 notice/patch/source；当前仓 `README.md:89` 为 AGPL3 开源线。
  不把 iOS 的未实现许可提示当成 Harmony 技术阻断；也不能声称引入 GPL 后原商业双许可产品仍可无条件闭源发行。

## 可验证分工与先后依赖

| owner | 独占实现范围 | 首个可验证交付 |
|---|---|---|
| Python native | `harmony/native/python/`；新增 `entry/cpp/python_core*`/`python_napi*` | host addon真实 CPython + OHOS archive/API12 ELF；纯 Python loop 取消/timeout、stdio/module清理 |
| Python domain/UI | 新 `chat/.../python/`、平台 adapter、小型 Python page（先冻结 native 接口） | fake ports 的状态/取消用例；真实 Agent调用与手动页共同消费 singleton |
| Mosh native | `harmony/native/mosh/`；新增 `entry/cpp/mosh_core*`/`mosh_napi*` | real mosh-server + UDP driver，VT帧、输入、resize、丢包恢复/源端口变化原会话继续 |
| Mosh domain | 新 bootstrap/session 模块、工具与测试（接口冻结后） | 不持久化key、pin/digest before auth、启动行/UDP timeout/cancel、同会话状态链 |
| 主 agent | CMake/napi_init/d.ts、DI/routes/生命周期、既有 settings/TerminalView 接线 | 合并构建/签名、独占设备操作、各模块真实链路及 UI截图 |
| 独立 reviewers | 新 native及信任边界、domain/task/approval、截图 | A 逻辑+B 调用链+C 亮暗 UI；逐条具体问题回喂 |

先 Python native host/OHOS smoke 与 Mosh host real server，成功后再扩大 UI 接线。
Mosh fixture 用 root 管理的本地 SSH + **真实 mosh-server**，单独 UDP转发/故障夹具；不触碰 E2 活跃服务器或设备转发。
HDC rport 的 E2 TCP转发不证明 UDP可达；模拟器和真机须各自验证实际UDP路径，实机LAN访问主机地址需从当前状态取证。

## E3 完成证据清单

- Python：设备关闭网络仍真实执行 CPython版本/Unicode/stdin/json/math；异常、128KiB输出上限、循环取消、timeout后下一次成功。
- 文件：真实 Workspace文本输入→Python计算→已有file_write保存；Python直接 open/绝对路径/遍历/网络/subprocess拒绝；不宣称系统级沙箱。
- Mosh：严格 pin认证后服务器bootstrap、真实UDP VT输出/input/resize/Ctrl-C；server缺失、非法启动行、UDP阻断及时反馈；key不进任何持久输出。
- 漫游：受控 UDP proxy 改变对服务器的源端口/短时掉包，保留远端PID与变量，记录无第二次 SSHbootstrap；真机联网与协议证据分开。
- 取消/后台：pending bootstrap、本地执行、活跃 Mosh均释放；回前台不重发；窗口重复连接无旧VT/监听者/owner泄漏。
- 构建/API12 ELF/签名/双设备安装启动、亮暗输入页/终端/批准卡截图、领域测试/类型检查/lint及独立review分别登记。
- 未证明的层：本报告没有 native编译或设备验证；没有系统级Python沙箱/任意C调用硬取消/长期后台保活/冷启动恢复Mosh。
