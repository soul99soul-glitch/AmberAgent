# E3 独立审阅：Mosh native 与 SSH peer 扩展

2026-09-28。范围：`native/mosh`、`cpp/mosh_core*` / `mosh_napi*`、root 的 SSH peer 最小扩展。已读 Harmony skill、E3 合同/前置调查和 native 最终报告。仅写本独占报告；未改产品源、构建共享目录、操作设备或操作 root bootstrap 服务。独立复现均用既有 host addon 和自己临时进程/UDP端口，未输出 session key 或测试密码。

## 结论

此范围 A 逻辑 / B 接线通过；独立发现的一个 P1、两个 P2 已精确修复并复核。当前无遗留具体 P1/P2。API12 编译/链接与实际设备 API24/26 是不同证据；完整 HAP、可信 SSH bootstrap→设备 UDP、背景/界面/IME 仍由 root 阶段 gate 收口。

## 发现及精确回喂

| 项目 | 原触发与证据 | 修复与复核 |
| --- | --- | --- |
| P1：最后 env 终止导致自 join / 进程 abort | parent 不加载 addon，仅 Node worker 加载→terminate。`SharedEngine` 用 weak lifetime，Shutdown 的 owner 队列 closure 持 engine；最后引用可在 owner 上释放，析构 join 自己。`/tmp/e3-review-env-lifetime.log` 实际报 `thread::join failed: Resource deadlock avoided`。 | `mosh_core.cpp:207–212` 改 process-lifetime 强 singleton；env 只清理 tenant，未 detach，不能新旧 global-clock owner 并存。无会话时 condition wait，非 100Hz 空转。按用户只必要测试约束，独立只复验 3 次原最小 worker-only 情形，`/tmp/e3-review-env-lifetime-green.log` PASS；worker 对应 case 已收窄 2 次，非新增压力套件。 |
| P2：旧认证密文重放让掉线状态假 running | 独立官方 mosh-server + ephemeral UDP proxy，停止所有 fresh server UDP，只周期重放同一已认证旧 packet。5.2s 后仍 running、lastHeard=156ms，`/tmp/e3-review-replay-state.log`。upstream `network.cc:515–516` 对 old sequence 返回 payload，却不更新内部 last_heard；driver 原本对任何 recv 正常返回刷新自己的时间。 | 两个只读 observer patch 把 Connection 的真实 last_heard 透传到 Transport；driver 只采该 fresh 值。不改 crypto、SSP、ACK、漫游规则。独立同一精确用例绿验一次：`/tmp/e3-review-replay-state-green.log` 为 reconnecting / lastHeard=5462ms，PASS。重放不会刷新状态，正常 idle ACK 的 3000ms 周期与 reconnect 5000ms 阈值也分别登记。 |
| P2：提前末态 / close 空 bytes 丢失缓存 VT | native Read 原本只读 maxBytes 却立即暴露 cache 末态；domain `mosh/stream.ts:54–64` 收到末态就 release。Finish/Close 原只给 cache，未给 output 队列，所以未交付尾字节被丢。 | `Session::Drain` 有余量则暂 running 且不暴露末态 error，最后包给真实末态；Finish/Close drain 全部缓存（上限 1MiB）后结算。已逐行复核。worker 只在既有 host 文件运行一个 focused case：1byte read 直到 closed、完整 ✓ 文本、末态后无余量；另验证 close 返回 pending bytes。`/tmp/e3-mosh-final-tail.log` 1/1 PASS，非全套重复。 |

## 调用链和实现边界

- 使用官方 Mosh1.4.0 的 SSP / Protobuf / AES-OCB / screen state 与 `Display(false)`，没有普通 UDP echo、重新 SSH shell 或假命令 exitCode。既有真实 host 五项结果已读：Unicode/组合字符/emoji、Ctrl-C、24×80→31×91、首次 UDP deadline/cancel、5.2s drop→重连、改变对 server 源端口后同 PID 和变量继续，且只有原 bootstrap；不是界面验收。
- process singleton owner 承担 transport、timestamp / crypto sequence / compressor 全局缓存；每轮 freeze_timestamp。cache read 只锁复制 bytes，跨 env handle 查 tenant；cleanup 先停止 NAPI delivery，再在同 owner 清自身会话，不能毁另一个 env 的会话。没有 CLI main/stdin/stdout/termios/global signals/setenv/setlocale；UTF8 locale 只用 owner 的 thread locale。
- key 仅内存进入 transport，Options 字符串清理，没有进入日志/TaskStore/Preferences/native 错误；driver 不传播 raw upstream exception。canonical22-byte base64、numeric IPv4/IPv6、port、timeout 与30000-cell限制明确；未知/错误 kind/tenant handle 不能误用。
- input 与未 ACK state 各有64KiB边界，output1MiB背压保留已显示 framebuffer，在 reader drain 后到最新 framebuffer 作一个正确 diff。首次无认证 UDP 在 connectTimeout 内结束；真实 last-heard 驱动 reconnecting。取消同步置 flag，先于尚未消费 input 生效；close 最多200ms尝试 SSP shutdown ACK，再释放，不承诺 ACK 丢失时远端 daemon 必然已退出。
- TSFN 与 env cleanup 使用独立 owner/env refs；异步投递不用缓存 napi_env。已读 worker 原两项 env 隔离/cleanup证据及修复后 focused worker-only证据，独立 P1 绿验用于补足原 parent 保持 Engine 引用的盲点。
- root SSH 扩展只在实际 socket 上 `getpeername` + `getnameinfo(NI_NUMERICHOST)`，把 numeric peer 存 handle 并在 start 成功（pin/认证/打开 channel 后）返回。ReadHandle 不从调用者信任 peerAddress；Mosh 输入只接受 numeric address，不做第二 DNS。`/tmp/e3-ssh-peer-tests.log` 17/17已读，实际 handle.peerAddress=127.0.0.1。SSH 与 Mosh host 证据分别存在，不借此宣称设备整条 bootstrap 已验收。
- Display patch 用 AMBER_MOSH_EMBEDDED 精确包住 terminfo headers/helpers 与 environment branch；`Display(false)`仍用原帧渲染，true在embedded明确拒绝；普通 CLI分支保留。fatal/crypto patch仅替换 reachable invariant 的进程 abort/exit 行为，未调用原 CLI core-limit函数。fresh timestamp的两patch仅公有只读 getter，没有窥探私有 layout。
- Mosh/Protobuf官方 archive SHA、五个patch与完整patched source SHA均锁；Protobuf21.12匹配 host protoc和target lite，OpenSSL复用E2。GPL文本、Protobuf文本、源码OpenSSL例外与patch出处保留。API12 arm64 compiler/toolchain明确，latest `/tmp/e3-mosh-api12-link-final.log` 在三项修复后更新，`--no-undefined`、NEEDED仅ace_napi / z / c++shared / c；不修改SDK/系统配置。

未增防御框架、协议替身、无依据重试或第二调度器。后续只需 root 原本必要的最终打包/设备/界面 gate，不因本报告再扩大测试或反复模拟器流程。
