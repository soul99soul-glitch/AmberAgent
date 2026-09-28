# E2 独立审阅：native SSH 与平台安全仓

日期：2026-09-28。审阅者独立于实现 worker，仅写本报告；没有修改实现、运行构建、操作设备或连接共享认证计数夹具。已读 `harmony-arkts/SKILL.md`、冻结 `E2-contract.md`、平台/native 最终报告与当前源码/回归日志；设备与应用最终验证由主 agent 收口。

## 结论

本审阅范围的调用链与两项新发现经精确修复后的代码通过 A/B 审阅；没有未解决的具体 P1/P2 逻辑发现。真实设备 Asset、Harmony CryptoFramework、后台生命周期和 UI 属于主 agent 的 C 验收，本报告不代替这些证据，也不宣称 API 12 设备已运行。

## 发现及后续差异复核

| 优先级 | 实际触发与原行为 | 修复与复核证据 |
| --- | --- | --- |
| P2，已修 | SSH 已启动后真实 TCP 断开产生 `network_error`，原 `Owner::Run` 只在人工 `stopReason` 存在时给 `disconnected`，实际断网给 `failed`，TaskStore 会记录 FAILED 而非 INTERRUPTED。 | `ssh_core.cpp:357–361` 将 transport `network_error` 收口为 `disconnected`、exitCode 为空；`host_native.test.cjs:140–158` 用真实 TCP proxy 销毁两端连接覆盖。`/tmp/e2-host-native-tests.log` 已读到该项 PASS，17/17。 |
| P1，已修 | 远端发 EOF + channel close，未发 exit-status；`libssh2_channel_get_exit_status()` 的默认值 0 被当作真实成功。上游 `channel.c` 用 CALLOC 分配频道，`packet.c` 只有收到 exit-status 才赋值，公开 API 原本不能区分该情形。 | `ssh_core.cpp:309–312` 先查独立 receipt flag，缺失时明确 `failed` / `channel_error` / null；新增 `libssh2-1.11.1-exit-status-presence.patch` 仅在实际 packet 分支置 flag，并公开只读 query。补丁明确标注 Amber project patch，未冒充上游修复、未读私有结构、未用 sentinel 占合法值。`Packet.exitCode` 与 owner 值改 int64，NAPI 用 `napi_create_int64`，保留协议 uint32。独立真实 fixture 红日志 `/tmp/e2-no-exit-status-red.log` 为 exited/0；绿日志 `/tmp/e2-no-exit-status-green.log` 为 failed/null，另一个真实 `0xffffffff` 包得到 4294967295。补丁及完整 patched source 均进入 SHA256 清单。 |

上游 server-sig-algs buffer 修复与平台 `arkts-limited-throw`、共享 writer 短写项是交接时已登记的修复，本报告没有重复列为新发现。已核查它们的最终源码差异：官方 libssh2 修复只有分配长度，保留出处/归属/补丁和 patched source hash；三处 typed rethrow 保留异常对象；`writeTextFileAtomic` 只改 UTF8 编码与完整短写循环，写满关闭后 rename，0/负数/过大 byte count 沿原有 cleanup 抛错。

## 调用链与边界

- CMake 将 `ssh_core.cpp` / `ssh_napi.cpp`、静态 libssh2 / libcrypto 接入既有 `amber_native`；`Init` 保留 `countBatch` 后调用 `RegisterSSH`，八个真实 native 方法与冻结 `.d.ts` 同名同字段。OHOS 编译器 target / sysroot / libssh2 toolchain 均明示 API 12，只有 arm64-v8a；没有把 API 24/26 测试机当 API 12 运行证据。
- `Probe` 只有 endpoint / timeout，握手后取指纹并关闭，没有 userauth。`Authenticate` 在实际握手得到的 SHA256 pin 逐字节匹配后才调用 password/publickey_frommemory；私钥没有落明文 PEM 文件。原生秘密副本正常认证、异常和析构均清理；错误文案不 dump connection 或凭据。
- 每连接一个串行 owner；nonblocking socket / EAGAIN / poll / wake pipe 驱动，不占 NAPI async-work pool 做长 read。注册 request 在启动线程前，立即 cancel 有对象可停止。`Read` 只锁缓存/复制 bytes，输出上限 1MiB 满时暂停 socket read，返回上限受 maxBytes 控制；0 / EAGAIN 不等于 EOF，缓存未交付时隐藏末态。
- NAPI 每 env 独立 runtime 与 pending TSFN；cleanup 先 stopping / abort，再 Shutdown，未把 env 留给晚到 DNS resolver。DNS resolver 只持 endpoint/addrinfo，没有 env / 凭据 / libssh2；取消 owner 及时收口，系统 getaddrinfo 的独立线程只能待实际返回后释放，此平台边界已有明确记录。
- 真实 EOF / drain / close 后读取已收到的 exit status；超时 / 取消 / 断网不造 exit 0。PTY write 保留 bytes、循环处理部分写/EAGAIN，resize 与 handle.kind 校验一致；active release 拒绝，终态 release 释放 registry，close/cancel 只承诺客户端连接停止，不承诺远端任意 daemon 被杀。
- NativeSSHTransport signal 注册前后均检查；取消 pending 调真实 sshCancel，完成后若 cancel/background 则先关闭产出的 handle 再 reject，settled 移除 listener。raw read packet 原样返回，真实 close failure 不隐藏。
- SSHCredentialStore 新 UUID ref 不覆盖旧值；32-byte key 放 Asset DEVICE_UNLOCKED / NEVER，随机 12-byte IV，AES-GCM payload 包含 host/port/username/authMethod binding，app-private 原子密文不进入通用备份。exists 仅查询 ATTRIBUTES，不把 SECRET 加载到 UI；load 区分文件缺失与损坏/Asset失败/绑定不符；staging 失败仅回收本次 key/ref。字节缓冲 finally 清零，immutable JS string 的清零能力没有被夸大。
- TerminalLogPort 仅允许私有 logs/<合法id>.log，不覆盖已有 log；APPEND/NOFOLLOW、逐实际短写前移并按路径串行，不在内存重写整个历史。AtomicJsonFile 此次修复共享影响已明确列出，不改 read/quarantine/mutex。

已读证据：`/tmp/e2-core-lifetime-tests.log` 的 50 probes / 20 authenticated exec+release 后 fd=3、threads=1、owners=handles=0，以及 delayed DNS owner 200ms 内取消、晚到 resolver 清理；`/tmp/e2-env-cleanup-tests.log` 的 10 pending + 10 live PTY worker env 终止与 socket 清理；平台报告中实际 adapter 经 transpile 的控制流测试和真实 /tmp 文件编排测试。它们证明主机原生/适配器控制流，不替代真实设备 Asset/SDK/应用生命周期验收。no-status patch 后最终 host / ASAN 日志各 17/17 PASS，no-status / uint32 为 2/2；`/tmp/e2-native-target-elf.txt` 已核查 AArch64、receipt query 存在、DT_NEEDED 只有 ace_napi / libc++ / libc，target `--no-undefined` 链接日志无错误。主 app 构建/加载和 Release 结果由主 agent 写入阶段 gate。

## 补审：真实 PTY 写入背压与关闭

仅只读核查 root 留存源和运行日志，没有连接 fixture、使用设备或改产品 source。`no_status_fixture.py` 是独立 loopback ephemeral SSH 服务，真正由 host NAPI/libssh2 走 TCP / SSH channel，与用户 SSH 主机无关；此夹具用于协议背压，并未宣称真实 OS shell 或设备 IME 验收。

已读 AsyncSSH 2.24 本地 `channel.py`：`session_started()` 后会自动 `resume_reading()`；`pause_reading()` 会停止 delivery 与 window adjustment，文档明确接收窗口耗尽时造成远端背压。夹具用 `call_soon(channel.pause_reading)` 在自动 resume 后暂停，窗口 16384，给出 ready marker。因此新增 pending 断言针对真实 libssh2 部分写/EAGAIN，没有替换 native transport 或造输出。

- `pty_close.test.cjs` 在实际 1MiB write 尚 pending 时关闭；cancelled / disconnected 两项均有 close 的 1s Promise.race，末态分别同 reason、exitCode=null、active writer 精确 cancelled / network_error。`/tmp/e2-native-pty-close.log` 2/2 PASS，证据有效。
- 补审发现原 `blocked_write_close.test.cjs` 的第二个 writer 没有真正入队：active 恰 1MiB 占满 `MAX_QUEUED_INPUT`，随后 1byte 立即 queue-full / channel_error。原 `/tmp/e2-blocked-write-close.log` 虽 2/2，但不能证明 queued writer 关闭，不能用于该结论。已交 root 精确修正测试，不改产品。
- 修正后的 active 长度 1048575，另 1byte 总计等于合法 1MiB cap；在 close 前实际等待 50ms 并断言第二 writer 尚 pending（同时 active 已 100ms pending）。随后取消/断开同时收口 active 与真实 queued writer：active 为 cancelled / network_error，queued 在 owner finish 路径为 channel_error，关闭 <1s，packet reason/null，释放后 read 为 unknown_handle。当前源码逐行复核与 `/tmp/e2-blocked-write-close-corrected.log` 两项 PASS 一致，queued 覆盖已闭环。

此补审没有新增未解决 P1/P2；这些测试只证明 native 关闭能唤醒真实 stalled 与 queued write。domain 串行取消、PTY 用户界面/中文 IME 与 Release 门禁仍使用各自证据，不能用此处主机结果替代。
