# E2 SSH 实施准备（只读核查）

日期：2026-09-28。范围：Remote SSH 本身；E1 tokenizer/NAPI 管线不重复调研，Python/Mosh 留在 E3。此报告不是 E2 实现或验收完成。

## 结论

采用 `libssh2 + OpenSSL 静态 libcrypto + 已有 C++ NAPI 模块`，实现 profile → 无凭据主机探测 → 明确信任 → 认证 → exec/job/PTY session → 工具/终端 UI。SSH 传输在原生层，端内 Web 只作本地 VT 终端显示，不接外部 WebTerminal。

已核实三处不能沿用的假设：

1. 鸿蒙已有 `terminal` 枚举、策略和结果预览，但没有 SSH profile、可信主机、密码/私钥安全仓、运行宿主或终端页面。
2. `PreferencesStorage.getSecret/setSecret` 仍是普通 Preferences，不等同于 Keychain/HUKS/Asset 安全存储。
3. 官方离线文档明确限制普通三方 SSH/Mosh 的长时间后台低频保活，不能将当前 DATA_TRANSFER 生成保活套到 SSH，承诺后台常驻。

## 现有鸿蒙边界及复用点

| 已有边界 | 当前证据 | E2 动作 |
| --- | --- | --- |
| 助手终端选项 | `harmony/chat/src/main/ets/chat/assistant.ts:26` 有 `'terminal'`；序列化 `assistant_serialize.ts:54` 保留它 | 无须再造权限枚举；实际工具接通后将助手页终端行设为可用 |
| 助手设置 UI | `ChatAssistantLocalToolsPage.ets:67` 终端行 `unavailable: true` | 改为可用；描述写 Remote SSH 远端工作目录，不声称本机 Alpine `/workspace` |
| Chat 工具组装 | `ChatPage.ets:3737` 读取 `localOpts`，该组中没有 terminal factory | 主 agent 在 `'terminal'` 门内注册真实工具；后续所有工具均经过现有 filter/registry/审批/loop |
| 工具发现 | `builtin_introspection_tools.ts:47` 已常驻 terminal exec/job/session 九项 | 直接复用；清单存在不等于工具已注册 |
| 风险/审批 | `tool_policy.ts:186,298` terminal 分类为 sensitive/explicit；`tool_registry.ts:45` 仅 high/mutates/显式needs触发审批；read/wait 不匹配 mutation | exec/start/send/stop 显式需要批准、关闭自动批准；read/wait 保持 `needsApproval=false`，轮询不重复审批；不用改整个terminal风险分类 |
| 取消协议 | `tool.ts:40` 的 `execute(input, signal?)`、registry `:108` 原样传 signal | 工具收到取消必须到 native owner/socket；不能只取消 Promise/UI |
| 工具输出 | `ToolResultPreview.ets:98,107` 识别 `command`、`exit_code`；`AgentToolCapsule.ets:39,56` 有终端分类 | 沿用 JSON 字段，不另造结果协议；真实未完成时 `exit_code: null`，不得用 0 表示“已启动” |
| 活动预览 | `tool_activity.ts:521,597` 默认标题/runtime 仍写 Alpine/proot | 按结果 `runtime: remote_ssh` 消费；E2 必须精准改相应终端文案，不能让 SSH 显示 Alpine |
| 作业存储 | `agent_task.ts:107` snapshot 支持任意 type/runtime、outputRef；`:546` register 有真实 cancel callback；`:683` cancel | 复用 `type: terminal`、`runtime: remote_ssh`、私有 `terminal_log`；终态清 cancelCapability；重启恢复已有 INTERRUPTED 规则 |
| DI | `AppContainer.ets:409,428,974` 有 TaskStore/Scheduler/ActivityStore 单例 | 新 TerminalRuntime 做应用级单例，生命周期与页面分开；ChatPage 只作接线 |
| 运行环境入口 | `SettingSandboxPage.ets:194–197` 当前 `终端运行时 / builtin / 鸿蒙当前不提供 shell` | 实现后改真实 Remote SSH 选择、profile 和终端入口；保持本机 shell 未提供这一准确边界 |
| 网络权限 | `entry/src/main/module.json5` 已有 INTERNET | 原生 TCP 不需要扩大受限 ACL；不要为终端新增 TASK_KEEPING 权限 |
| 加密/文件写入 | `SyncCryptoPort.ets:94` 已有 AES-GCM、随机 bytes；`AtomicJsonFile.ets:11` 已有原子文本写入 | 可复用实际 crypto/file 实现；不用把整个同步子系统搬入终端 |

当前鸿蒙没有可以直接复用的 SSH 数据模型或终端进程运行消费者。最低新增领域模型：`SSHProfile`、`SSHTrustProbe`、`SSHCredential`（只进安全仓/认证）、`TerminalJobSnapshot`、`TerminalSessionSnapshot`；与通用 AgentTaskSnapshot 映射即可，避免建立第二个通用任务调度器。

## 双端参考的真实行为

### iOS（Remote SSH 的主要行为参考）

根：`/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/`。

- `IOSSSHModels.swift:18–81`：profile 的 id/name/host/port/username/authMethod/knownHostSHA256/knownHostHost/knownHostPort；host/port 改变失效旧 pin。信任与凭据分别绑定，用户名改变不应改变服务器主机密钥的身份，但旧用户凭据必须失效。
- `IOSSSHModels.swift:163–192`：密码/私钥与 profile Codable、设置导出、工具入参、job snapshot 分离；最多 64 KB；日志 description 只输出 redacted。
- `IOSSSHModels.swift:195–308`：Keychain `WhenUnlockedThisDeviceOnly`、不云同步；保存是 update-in-place，不先删旧值；凭据 load 比较 host/port/username/authMethod；UI 摘要查询属性，不回填秘密。
- `IOSSSHRuntimeBackend.swift:119,168,469`：probe 不提供凭据，握手取得 host key 后终止；真正执行再次检查 pin，通过后才认证。
- `IOSSSHRuntimeBackend.swift:535`：OpenSSH `SHA256:` 指纹取 host-key wire bytes 的 SHA256，再做无 `=` base64。
- `RuntimeEnvironmentView.swift:1377–1502`：probe 保存 candidate + 当前持久快照；“信任”前和认证结束前再次比较两份配置；取消/失败不破坏旧 profile/credential；认证通过才保存；后台/退出清空秘密 draft。
- `IOSTerminalRuntime.swift:1004–1150`：SSH job 独立记录状态、stdout/stderr、超时/取消，末态后拒绝迟到 output。`:2055` 执行前比较批准时 target digest，防止批准后配置切换到另一个主机。
- iOS 现有 SSH 主要是 exec/非 PTY job；交互 PTY 文件 `IOSInteractiveIshTerminal*` 属 iSH，不可把它当 SSH PTY 已有实现。

### Android（工具名称/作业消费参考）

本仓库 `feature/terminal/api/.../TerminalRuntimeModels.kt` 只有 `builtin_alpine/android_shell/termux_external`，没有 Remote SSH。`feature/tools/impl/.../TerminalTools.kt:36–280` 的 exec/job/session 工具名和 JSON 契约可复用，运行实现不能搬到 OHOS。其 session 明确是非 PTY。

`TerminalRuntime.kt:54` 把长命令转 job，但“已启动”返回 exitCode 0 是旧 Android 习惯；鸿蒙新实现应返回 running/job_id/status 与 null exit code，以免把启动误报完成。Remote SSH cwd 与手机 `/workspace` 不自动同步；不要注册伪造的 `terminal_workspace_flush/terminal_install_packages`，也不要宣称已实现 SFTP。

## 原生依赖选择（官方源码查证）

### 推荐 OpenSSL

- 本次打开 [libssh2 官网](https://libssh2.org/) 给出的稳定版是 1.11.1；建议固定 release tarball、SHA256 和许可证，不跟 master。
- [1.11.1 mbedtls.h](https://raw.githubusercontent.com/libssh2/libssh2/libssh2-1.11.1/src/mbedtls.h) 直接定义 `LIBSSH2_ED25519=0`、`LIBSSH2_AES_GCM=0`。这限制常见 OpenSSH Ed25519-only 主机连接；并非“mbedTLS 编译成功就全 SSH 可用”。
- [1.11.1 openssl.h](https://raw.githubusercontent.com/libssh2/libssh2/libssh2-1.11.1/src/openssl.h) 在 OpenSSL ≥1.1.1 开启 Ed25519，且支持 AES-GCM；不自行补密码学代码。
- [1.11.1 CMakeLists](https://raw.githubusercontent.com/libssh2/libssh2/libssh2-1.11.1/CMakeLists.txt) 只链接 `OpenSSL::Crypto`，不是 `OpenSSL::SSL`。关闭 zlib 压缩即可不需要 TLS、zlib、HTTP、curl。
- OpenSSL 使用 3.5 LTS；本次 [官方下载页](https://openssl-library.org/source/)列出 3.5.8，3.5 LTS 支持至 2030-04-08。实施时保存实际下载归档/hash，不依据本报告猜 future patch。

本机只查工具链/库文件：`/opt/homebrew/Cellar/openssl@3/3.6.3/lib/libcrypto.a` 是 macOS host 库，SDK 中找到的 libcrypto dylib 属编译工具自带 Python，也不是 OHOS 库。所查 SDK sysroot 未发现可供应用依赖的公开 OpenSSL target 包；必须本地源码交叉构建，不链接系统隐藏 crypto。

### 最小构建配方（待原生 worker 实际验证，不记作构建通过）

沿用 E1 已验证的 SDK/OHOS API12、arm64、PIC、工作中的 ARM CMake/Ninja。无需再引入 Rust ssh2 crate 给 C++ 外面套一层。

OpenSSL [官方构建说明](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5/INSTALL.md) 允许指定 CC/AR/RANLIB/target；[linux-aarch64 配置](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5/Configurations/10-main.conf) 继承 linux-generic64。使用 SDK clang `--target=aarch64-linux-ohos --sysroot=<same SDK sysroot>`，LLVM ar/ranlib，明确 `-D__OHOS_API__=12 -fPIC`：

```text
perl Configure linux-aarch64 no-shared no-dso no-module no-apps no-tests
  no-autoload-config no-pinshared --prefix=<local ohos prefix> --libdir=lib
make -j <bounded jobs> build_libs
```

只将 libcrypto.a 接入 final SO；可能顺带产出的 libssl.a 不进包。保留 default provider 内置能力；别盲加 `no-deprecated/no-ec/no-stdio/no-threads` 导致 libssh2 需要的 key parser/并发/算法消失。`no-asm` 官方定位为排障选项，不作为无需证据的生产默认。

libssh2 CMake：`CRYPTO_BACKEND=OpenSSL`、`OPENSSL_USE_STATIC_LIBS=ON`、`OPENSSL_ROOT_DIR=<target prefix>`、`BUILD_STATIC_LIBS=ON`、`BUILD_SHARED_LIBS=OFF`、`BUILD_EXAMPLES=OFF`、`BUILD_TESTING=OFF`、`ENABLE_ZLIB_COMPRESSION=OFF`、`ENABLE_DEBUG_LOGGING=OFF`、`CMAKE_POSITION_INDEPENDENT_CODE=ON`。1.11.1 OpenSSL 分支还会独立 find_package(ZLIB)，所以设置 `CMAKE_DISABLE_FIND_PACKAGE_ZLIB=ON`，防止静态目标悄悄夹入 host zlib。

最终 `libamber_native.so` 应仅动态依赖 SDK 公开 libc/libc++/ace_napi 等；`llvm-readelf` 查 AArch64、DT_NEEDED、目标符号，不出现 macOS 路径、Android/JNI、libssl/libcrypto 动态需求。保留 [libssh2 BSD-3-Clause](https://raw.githubusercontent.com/libssh2/libssh2/libssh2-1.11.1/COPYING) 和 [OpenSSL Apache-2.0](https://raw.githubusercontent.com/openssl/openssl/openssl-3.5/LICENSE.txt) notices。

## 凭据保存：准确 API12 边界

离线官方已读：`js-apis-asset`、`asset-js-add`。首批 API11，import `{ asset } from '@kit.AssetStoreKit'`，`SystemCapability.Security.Asset`；API12 可用 add/query/update/remove，不用14+加密标签或18+/26+群组/批量接口。

- `asset.Tag.SECRET` 仅 1–1024 bytes；ALIAS 1–256；DATA_LABEL 在 API12 只有512 bytes且**不加密**，不能放密码/私钥或敏感账号。
- `ACCESSIBILITY=DEVICE_UNLOCKED`、`SYNC_TYPE=NEVER`；不设 IS_PERSISTENT，不申请 STORE_PERSISTENT_DATA。query 属性用于“已有凭据”，需要认证时才读取明文。
- 对支持完整私钥的统一方案：每 profile 随机32字节 AES key 进 Asset，`{endpoint, authMethod, secret, optional passphrase}` JSON 用已验证 AES-GCM+新12字节 IV 加密进 app-private 文件；密文由 atomic file writer 替换。它解决已证实的1024限制，而不是新泛用加密体系。
- 读取时解密认证成功后比较当前 profile endpoint+username+authMethod；不匹配/Asset不可用时报真实凭据不可用，不降到明文 Preferences。资产 alias 只含随机 profile UUID；秘密不进 metadata/history/tool output/backup。临时 draft 在页面退出/后台清除；native copies 认证后清理。
- 使用普通密码也能直接 Asset 存≤1024 bytes；但若首版按此做，就不能同时声称任意 iOS 64KB 私钥已支持。推荐一次用上述统一加密文件方案，省去以后双仓迁移。
- “SSH 凭据仅本设备、恢复后重新录入”与既有产品的“通用备份不能绑定设备”裁定是不同对象：不要改变通用备份加密协议。当前 `settings_backup.ts:13` 只导出 assistants/providers，不会导出 SSH 新密文；UI 必须避免宣称备份里包含可迁移 SSH 凭据。

## 最小运行调用链

### 主机确认和认证

1. UI 当前 draft 标准化/验证；probe 只传 host/port/timeout，原生完成 TCP+SSH handshake。
2. `libssh2_hostkey_hash(...SHA256)` 返回32个原始 bytes，转 `SHA256:`+无 padding base64，不能当 C 字符串/hex 打印。官方：[hostkey_hash](https://libssh2.org/libssh2_hostkey_hash.html)。
3. probe 断开，不调用 userauth。未 pin：显示 host:port、算法、完整指纹和明确“信任并验证”；不自动接受 unknown host。
4. 保存 probe candidate/revision；编辑或切 profile 会失效 probe，迟到结果不得信任新 endpoint。
5. 正式连接重新握手并比较 pin；mismatch 在发送密码/私钥之前拒绝。probe fingerprint 不是正式连接跳过验证的通行证。
6. 仅通过 pin 后从安全仓取/发送 credential。password 用 `userauth_password_ex`；私钥用 [userauth_publickey_frommemory](https://libssh2.org/libssh2_userauth_publickey_frommemory.html)，不要明文落临时 PEM 文件。密码/未加密 Ed25519 OpenSSH、ECDSA PEM 至少覆盖 iOS 参考；RSA/encrypted key 若界面开放必须用夹具实际验收。
7. 认证成功（用可控 `echo amber-terminal-auth-check`）且 candidate/持久快照仍同一版本才更新安全仓/profile。failed/cancelled 留原配置，保存失败不删旧秘密。

### 原生对象所有权

libssh2 官方要求同一 handle 不能并发调用。建议每个活跃 session 有一个 native owner，将 handshake/auth/channel read/write/resize/close 串行化。使用 nonblocking socket + poll + `LIBSSH2_ERROR_EAGAIN`，关闭/取消能唤醒等待；不能长期占用 NAPI async-work pool 用阻塞读，更不能 JS 线程 connect/read。

最小 NAPI 面：`sshProbe`、`sshStartExec`、`sshStartPty`、`sshRead`、`sshWrite`、`sshResize`、`sshClose`。前两项以请求ID支持握手中取消；ID/状态/bytes/errors 构成明确 `.d.ts`；不返回原生指针，不让页面管理 native lifetime。read 返回字节及状态，output drain 与 terminal close 的所有权一致；UTF8跨 read 边界不能各块强行转字符串丢字。

PTY：open_session → [request_pty_ex](https://libssh2.org/libssh2_channel_request_pty_ex.html)(xterm-256color/cols/rows) → shell；UI resize → [request_pty_size_ex](https://libssh2.org/libssh2_channel_request_pty_size_ex.html)；输入是原始 UTF8/控制 bytes，Enter、Ctrl-C、Tab、箭头不是只拼命令字符串。

Exec：stdout(stream0)和stderr(stream1)均 drain；[read_ex](https://libssh2.org/libssh2_channel_read_ex.html) 返回0只表示当前无 payload，不能当 EOF；EAGAIN是等待。真正 EOF+close后取 exit status，运输中断不能被默认0当成功。取消发 [channel_signal_ex](https://libssh2.org/libssh2_channel_signal_ex.html) TERM（某些服务端可能忽略），并关闭连接；日志/状态只确认客户端停止，未获远端退出证据不能声称任意 daemon 已被杀死。PTY 的 Ctrl-C 与断开是独立操作。

### 领域 runtime、工具、状态

`TerminalRuntime` 应拥有 job/session maps；job start 立即返id和running，后续read/wait/stop共用对象。短 exec 用同一job运行，有限等待后返running/job_id继续跟踪；避免用字符串猜“长命令”。所有 terminal 输出先按真实界限脱敏/限制尾部，再到 ActivityStore+私有 log+tool JSON，末态拒绝迟到回调。

九件工具：`terminal_execute`、`terminal_job_start/read/wait/stop`、`terminal_session_start/exec/read/stop`。保留主工具现有命名，明确 `profile_id`（缺省当前默认）、`cwd`（远端）、timeout；session_exec是往已批准 session 发命令。UI手输原始bytes不借 Agent 工具绕审批。工具工厂捕获批准目标的 profile id/host/port/username/authMethod/pin；execute前重新比较，批准等待期间换默认/编辑目标不能偷偷执行到新机。profile在模型输入里可识别，credential永不作为工具参数。

后台 job 的 domain 状态映射 AgentTaskStore，取消callback须调用真正native close，日志输出路径在app-private边界。Job 成功/失败/取消/超时都清取消能力；应用重启后的未完作业用已有 interrupted recovery，不自动再执行原命令。

## 真实后台限制与计划修正建议

离线文档：`FAQ/网络/网络_Network/应用前后台切换后出现通信不可达_网络异常/faqs-network-95`。已全文读，明文：普通三方 SSH/Mosh 低频心跳不能利用 DATA_TRANSFER 长期保活，TASK_KEEPING需系统ACL；建议服务端tmux托管+前台重连。

当前 `EntryAbility.ets:355` 仅按 chatGenerating 申请生成保活；E2 不应把 terminalRunning OR 进去假装常驻。这里“异步 job”指 Agent 不阻塞等待，不保证系统后台网络持续。

建议 E2 默认：前台连接/任务完整；切后台主动释放交互连接、显示 INTERRUPTED/已断开，回前台重连须重新验证主机，**绝不重发未确认的 exec 命令**。若要远端持续会话，提供用户明确选择的 tmux attach/new 托管模式，并检测远端实际已安装；没有 tmux 时直接显示原因，不自动安装远端软件，不把普通SSH切换为网页代理。持续 job 必須明确是远端托管还是当前进程执行；不可只把可靠后台失败改登记后叫产品完成。

## 终端 UI 最小方案

现有 rawfile 没有 VT/xterm 资源，只有 Mermaid 等。为真正 PTY/TUI 支持，建议包内固定版本 xterm.js + fit addon，原生 ArkUI 页面作标题/连接状态/断开/工具键条，本地 Web 作VT显示。xterm [官方 API](https://xtermjs.org/docs/api/terminal/classes/terminal/)有 onData/onResize/write(Uint8Array)，[fit addon](https://xtermjs.org/docs/guides/using-addons/)计算真实 cols/rows。无Websocket服务、无CDN、无外部WebTerminal；保留MIT notice。

Web加载 ready 必须 onPageEnd/明确bridge握手后再喂buffer，不重复历史第一帧空白缺陷。远端输出仅作为终端 bytes传入write，不拼到innerHTML或任意eval；桥只暴露 input/resize/ready。不开 clipboard/link/webgl非必要addons。页面 @StorageProp 主题驱动terminal palette，resize根据实际容器/键盘变化，不写固定80×24或固定高度。

对齐验收：44vp居中标题；16vp外侧gutter；52vp最小操作行；指纹mono可换行完整可复制；长host与状态不挤连接按钮；键盘打开时工具键和末行不被遮挡；亮暗色cursor/selection/error/断开状态可读。终端页退出只销毁自己拥有的交互session，不能取消其它Agent job。

## 推荐文件所有权

| owner | 独占文件/责任 | 接口约束 |
| --- | --- | --- |
| native worker | `harmony/native/ssh/` 固定依赖/download-hash/build脚本；`entry/src/main/cpp/ssh_*`；native `.d.ts` SSH部分 | libssh2实际调用/所有权/取消/PTY；不要重写E1tokenizer文件；CMake总入口由主agent整合或先明确移交 |
| domain worker | `chat/terminal/{models,profile_store,runtime,tools}.ts` + 真实业务tests | KV只存无秘密metadata；typed platform port；映射既有TaskStore；index.ts由主agent接线避免竞争 |
| platform/credential worker | `platform_impl/{SSHCredentialStore,NativeSSHTransport,TerminalSupport}.ets` | Asset+AES file、安全绑定、native映射；不动普通PreferencesStorage/通用backup |
| UI worker | 新 `SettingSSHProfilesPage.ets`、`TerminalPage.ets`、`components/TerminalView.ets`、本地terminal rawfiles；`SettingSandboxPage.ets` | 页面只消费runtime；V1/state、独立sheet hosts、真实布局/暗色；API官方文档先查 |
| 主agent | AppContainer/ChatPage/LocalTools行/main_pages.json接线、工具metadata标题、EntryAbility lifecycle、nativeCMake合并、build/sign/install/截图 | root独占设备操作；检查所有UI入口和Agent入口走同一runtime |
| independent reviewer | 只读A逻辑/B调用链/C UI | 逐项检查不是看到README和工具名就认完成 |

所有worker须声明共享仓库，不回退其它E0/E1编辑。建议先冻结NAPI d.ts与domain port，再平行实现；不同时改ChatPage或CMake。

## E2 成功条件（需要真实证据）

1. 静态依赖和NAPI：API12 AArch64 HAP加载；host+target输出/符号依赖验证；libssh2真实 handshake，不只是编译出archive。
2. 主机信任：无秘密probe；首次拒绝/取消不会认证；正确pin可认证；改host/port失效pin；同endpoint换hostkey拒绝；probe后改draft/配置拒绝迟到信任。服务端fixture记录 auth attempt count=0证明。
3. 安全仓：保存/替换/清除/重启可读；UI不回填；读endpoint不匹配失败；磁盘密文篡改失败；>1024字节真实测试私钥可存；明文marker不出profileJSON/Preferences/历史/日志/backup。真正设备Asset调用测过，不用只测fake仓宣称安全。
4. Exec：真实shell `echo amber-terminal-smoke`、stderr和非0exit、UTF8切块、输出超过tail仍可查log；失败和超时正确；取消握手/认证/运行全过程可停止，native对象/fd/thread回收可观察。
5. Job：start→read/wait→最终状态、stop、错误→task板状态，应用重启INTERRUPTED；页面切换不断错误取消别的job；无exitCode0启动成功假象。
6. PTY：真实echo、`stty size`随UI/键盘resize改变；Ctrl-C/Tab/上下左右；颜色、clear/cursor移动/alt screen（如可控vi/tmux）；中文emoji不乱码；session关闭后不再推迟到output，第二次连接能正常工作。
7. Agent接线：助手terminal关闭时不注册；开启后tool_search发现真实九件；exec/start/send等待审批、拒绝不发命令；read/wait不重复审批；取消signal端到端；批准后换profile目标拒绝；result胶囊/preview显示Remote SSH和正确状态。
8. 前后台：后台网络释放/回来状态准确；重连不重发命令；tmux托管若入口开放，实际远端进程/输出在断开后保持并能恢复。不能把15秒静态连接或startBackgroundRunning返回值当后台长时成功。
9. UI：profile新建/编辑/删除/默认选择、host确认/认证状态/错误/取消、秘密输入、终端软键盘/横竖屏、亮暗色截图独立review并修具体错位。
10. 受影响领域单测/tsc、精确ArkTS lint0error、build、AGC签名安装、启动、模拟器及arm64真机夹具全部分别记录。API24设备运行通过不等于API12实机已验。

可控SSH测试服务建议 [AsyncSSH 官方 server examples](https://asyncssh.readthedocs.io/en/latest/#server-examples) + `/tmp` 下新生成host/client keys、虚拟fixture user/password、真实 `/bin/sh`+pty（固定测试目录）。本机当前cryptography可导入，asyncssh尚未安装；后续在独立/tmp venv使用即可，不能查用户账户密码/私钥。模拟器用hdc reverse forwarding；真机LAN仅开放fixture服务端口并用随机测试凭据。一次服务端切换hostkey能验证pin mismatch，没有真实SSH账户并不挡本地闭环验证。

## 本次已做/未做

已读skill、计划原文、鸿蒙真实代码、iOS/Android上述源码，离线官方Asset/网络生命周期文档，libssh2/OpenSSL/xterm/AsyncSSH官方文档及固定release源码。未读取用户账户密钥或密码，未操作设备，未改产品源码，未编译SSH依赖；下载到/tmp的是公开源码/文档查阅副本。唯一仓库产物是本报告。
