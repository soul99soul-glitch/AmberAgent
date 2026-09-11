# Phase 5 W17：Remote SSH 可行性核查

日期：2026-09-09；Android 基线：`ab984f6df1d6b5f9bae2b007ccdcf43f1d467e62`。本次只读核查，不改产品代码、不跑 Gradle、不安装包、不连接个人远端。

## 结论

当前 Android 没有可直接复用的、由宿主管理的 SSH 客户端与信任后端。可以复用现有 `TerminalRuntime` 的 job owner、状态/通知、输出尾部和日志上限，以及 `SecretStore` 的 Keystore 保护；但 W17 不能靠新增一个 `termux_external` 选项或执行一条 `ssh` 命令宣称完成。首个实现前必须先选定一个可审计、可版本化的客户端来源。

## 已核实的现状

| 位置 | 真实能力 | W17 含义 |
| --- | --- | --- |
| [`TerminalRuntimeModels.kt`](/Users/mi/Downloads/AI/AmberAgent/android/feature/terminal/api/src/main/kotlin/app/amber/feature/terminal/TerminalRuntimeModels.kt:7) | 只有 `builtin_alpine`、`android_shell`、`termux_external`；没有 SSH profile、known-host 或 trust 状态 | `android_shell` 只声明 `/system/bin/sh`，不能假定 Android 系统带 OpenSSH |
| [`app/build.gradle.kts`](/Users/mi/Downloads/AI/AmberAgent/android/app/build.gradle.kts:495) 与生成的 Alpine 归档 | 只固定 `proot`、`libtalloc.so.2` 和 Alpine 3.24.1 minirootfs；归档 `world` 只有 `alpine-baselayout/alpine-keys/alpine-release/apk-tools/busybox/musl-utils`，没有 `ssh`、`ssh-keyscan`、`sshd` | 当前 APK 没有 SSH 客户端。`TerminalInstallPlanner` 可把任意包名交给 `apk add/pkg install`，但这是运行时网络安装，非固定生产客户端；通用校验是 `command -v <包名> || true`，不能证明 `openssh-client` 提供的二进制存在（[`TerminalRuntimeInternals.kt`](/Users/mi/Downloads/AI/AmberAgent/android/feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntimeInternals.kt:164)） |
| [`TerminalRuntime.kt`](/Users/mi/Downloads/AI/AmberAgent/android/feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:668) | Termux 通过 `com.termux.RUN_COMMAND` 调用用户侧 `/data/data/com.termux/files/usr/bin/sh`；只探测安装和 `RUN_COMMAND` 权限，不探测 `ssh`/`ssh-keyscan`、版本或 known-host | Termux 中用户自行安装的 OpenSSH 可执行，但版本、路径、配置和取消均由外部应用控制，不能作为 managed SSH backend |
| [`SecretStore.kt`](/Users/mi/Downloads/AI/AmberAgent/android/core/settings/src/main/kotlin/app/amber/core/settings/secret/SecretStore.kt:81) | `scope/ownerId/fieldName` 稳定 descriptor；SharedPreferences 密文由 Android Keystore AES/GCM 保护；DataStore 仅保存 reference/mask | 可为 SSH profile 存 password/private key/passphrase，但尚无 profile 引用、一次性 rehydrate、临时凭据输入或清理 owner |
| [`TerminalRuntime.kt`](/Users/mi/Downloads/AI/AmberAgent/android/feature/terminal/src/main/kotlin/app/amber/feature/terminal/TerminalRuntime.kt:750) | 本地进程有 `TERM`/`KILL` process-tree 取消；输出尾部按设置限制 64 KiB–512 KiB，文件日志 8 MiB；Termux 超时/停止只能标 `INTERRUPTED` 并说明外部进程可能继续 | SSH 应杀掉本地 client 并显示远端状态 unknown/interrupted，不能把 TCP/session 关闭冒充远端命令已停止 |
| [`AgentTaskStore.kt`](/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:213) | task snapshot/日志路径可持久化；启动时运行中的 task 被 [`AgentTaskRecoveryManager.kt`](/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskRecoveryManager.kt:31) 改为 interrupted/output-only；取消回调不持久化 | profile 可持久化，SSH session 不应承诺重启后重连；首版应采用一次性非 PTY command |

仓库依赖中没有 SSHJ、JSch、Apache MINA sshd、libssh 或其他 SSH 协议库；`feature:terminal` 只有协程、serialization、Koin 等依赖。宿主开发机的 `/usr/bin/ssh`、`/usr/bin/ssh-keyscan`、`/usr/sbin/sshd` 不能视为 APK 来源。

此外，当前 terminal job 的 command 会进入 activity `inputPreview`、AgentTask `summary` 和日志/输出链路。因此不能把密码、私钥、passphrase 或 `ssh` 参数直接拼进 command、环境变量、任务摘要或 UI activity；SSH backend 必须通过 profile ID + `SecretStore` 在执行边界取密，并保证输出脱敏。

## 客户端与最小落点

1. **先过客户端门。** 无新增依赖约束下没有现成 managed client。可行选项只有：把经过许可/SBOM 审查、版本和 SHA 固定的 ARM64 OpenSSH client（含 `ssh` 与 `ssh-keyscan`）作为新的 Alpine runtime asset；或明确批准一个已有稳定 host-key API 的 JVM SSH 库。两者都属于需重估的新增发布依赖，不能在本报告中偷偷引入。Termux 只能保留为用户外部终端，不进入 managed SSH。
2. **最小 Android 接线。** 在 `feature/terminal/api` 增加 profile/endpoint/known-host fingerprint 的纯模型，在现有 settings owner 持久化非秘密字段；SSH secret descriptor 使用 `scope=ssh`、`ownerId=profileId`、字段分开存储。`TerminalRuntime` 负责 profile 解析、client process/backend、现有 `AgentTaskStore`/`AgentToolActivityStore` 和输出 caps；不要复制第二套 job store。
3. **信任和状态。** 首次只做 host-key probe，用户明确接受后保存指纹；指纹变化在认证前阻断。首版只做非 PTY 一次性命令。超时、断网或取消终止本地 client，状态为 interrupted/unknown；只有远端明确回执才可写 completed/failed，不能推断远端进程生命周期。

## 受控测试服务证据

本机存在 OpenSSH 10.3p1。经批准的本机 loopback 临时测试在高端口启动了非特权 `sshd`，使用临时 Ed25519 host/client keys，未访问个人远端：`sshd -t` 通过；`ssh-keyscan` 得到 1 个 key；受信连接执行 `stdout/stderr/exit 7`，两路输出分离且退出码为 7；替换 host key 后 `StrictHostKeyChecking=yes` 退出 255 并报 `REMOTE HOST IDENTIFICATION HAS CHANGED`；错误 client key 退出 255 并报 `Permission denied (publickey)`；杀掉本地长命令 client 退出 255。服务日志只有 macOS BSM audit 的权限提示，不影响上述协议行为。

因此受控 SSH server 测试服务在开发机上可行，足以覆盖首次信任、mismatch、认证失败、输出/退出码和本地取消的 fixture；没有 Android Alpine/Termux 客户端的设备证据，也没有仓库内的 SSH server fixture。W17 实施后仍需在 Android 设备上复测超时、断网、重启后 output-only/unknown、输出上限和秘密不进入快照/日志。

**阻断项：** 当前缺少可发布 SSH client、profile/known-host 持久化与 trust UI/backend；Termux 的外部命令模型不满足 managed 语义；现有 package installer 的校验不能作为客户端保证。**不阻断项：** 本机受控测试服务和现有 job/output/cancel/Keystore 复用路径均已证实可作为后续实现基础。
