# E3 native Mosh — PASS（原生范围）

2026-09-28。Ownership限定native/mosh及新cpp/mosh_core、mosh_napi；未修改主CMake、DI、
SSH既有源、路由，未操作设备，未提交。已读E3合同/调研及harmony-arkts技能。

## 实现

- 官方Mosh1.4.0真实SSP/crypto/network/state sync和Display(false)屏幕差分；
  Protobuf21.12匹配host protoc+target lite库；复用E2 OpenSSL3.5.8，系统zlib。
- 冻结6个NAPI，single process-lifetime owner，tenant隔离各env；上游timestamp/
  crypto sequence/compressor全局缓存只由该owner消费；idle condition wait不空转。
- 输入64KiB/未ACK合计64KiB，VT缓存1MiB、帧差分背压；30000-cell窗口上限；
  16个注册会话。无任意无限输出重播，无伪造每命令exitCode。
- numeric peer IP阻止第二DNS；key只输入内存，Options清理；不传播raw上游错误，
  不进入terminal普通日志。首次UDP deadline，5000ms真实last-heard重连状态，
  拒绝未经认证的数据包/重播刷新last-heard。临时网络错误保留transport。
- 本地关闭尝试SSP shutdown ACK最多200ms后释放；ACK丢失时远端daemon终止不承诺。
  不调用CLI main、fork、stdio/termios、全局signal/setenv/setlocale。
- AMBER_MOSH_EMBEDDED窄隔离终端数据库分支及process abort/exit invariant；
  原source/patch/结果source SHA锁和GPL3+OpenSSL例外完整保留。
- host mosh-server直接提取官方macOS1.4.0 pkg到私有build目录，无系统安装。

## 真实证据

| 层 | 结果 | 证据 |
|---|---|---|
| Host真实NAPI+official mosh-server | 5/5 PASS | `/tmp/e3-mosh-host-tests.log` |
| SSP输入/Unicode/emoji/组合字符、Ctrl-C、窗口24×80→31×91 | PASS | 上述真实远端shell stty/output |
| 首次UDP silence deadline+真实cancel后下一次成功 | PASS | 同上 |
| 丢包5.2秒→reconnecting；proxy更换对server源端口→同PID/变量kept恢复 | PASS | 同上；测试仅一次原server bootstrap |
| 未ACK input64KiB背压+close wins queued write | PASS | 同上 |
| 两环境隔离+pending environment cleanup | 2/2 PASS | `/tmp/e3-mosh-env-cleanup.log` |
| 下述P1 focused regression | PASS | `/tmp/e3-mosh-env-cleanup-final.log` worker-only case；reviewer原repro3次绿 `/tmp/e3-review-env-lifetime-green.log` |
| P2重播原repro独立绿验 | PASS | `/tmp/e3-review-replay-state-green.log` |
| P2尾包focused小maxBytes/Close pending bytes | 1/1 PASS | `/tmp/e3-mosh-final-tail.log` |
| 超长未经认证UDP不终止+旧包重放/掉包/换源端口同会话 | focused 1/1 PASS | `/tmp/e3-mosh-oversize-red.log`真实红→`/tmp/e3-mosh-replay-roam-oversize-green.log`真实绿 |
| API12 arm64 compile/link no-undefined | PASS | `/tmp/e3-mosh-api12-link-final.log`；NEEDED仅ace_napi/z/c++shared/c |

## 独立审查回喂及精确修复

独立reviewer发现并实际复现P1：parent不加载addon、仅Worker加载→terminate，
最后Engine引用可在owner队列lambda销毁时释放，Engine析构自join导致process abort。
失败原证据保留：`/tmp/e3-review-env-lifetime.cjs`、`/tmp/e3-review-env-lifetime.log`。

精准修为进程级静态强owner，不detach、不容许新旧owner并存；env只清理tenant会话。
新增对应worker-only parent无addon引用的focused regression，成功无SIGABRT。
修后真正host SSP5/5与原env2/2已复验；root最终约束仅必要测试后，未扩大设备/压力回路，
额外未提交core压力helper已停止并撤回，不作为任何验收证据。
独立reviewer原repro3次PASS：`/tmp/e3-review-env-lifetime-green.log`。

独立reviewer另复现P2：无fresh UDP、仅周期重放自己session旧密文5.2秒，
仍running/lastHeard156ms。失败证据`/tmp/e3-review-replay-state.log`。修为上游
Connection.last_heard只读getter、Transport透传，owner采fresh timestamp，不以recv返回刷新；
独立原repro绿验PASS：`/tmp/e3-review-replay-state-green.log`，
旧密文重放且fresh完全阻断后reconnecting/lastHeard5462ms。

另确定P2尾包：末态Read(maxBytes)尚有余量会促domain提前release；Close原只cache无尾字节。
精确Drain统一出口，Read有余量先running/error null、最后包真实末态；Close全部缓存≤1MiB。
仅focused VT逐字节到closed及Close pendingbytes断言1/1PASS，无全套重复。
上游read-only freshness两头文件patch/source锁已同步。

最后源核对发现MSG_TRUNC在解密前抛NetworkException，generic catch会误结束会话。
受控proxy发送4096字节未经认证UDP的同一既有roam case已142ms实际复现红；
只匹配upstream该明确function丢弃此包后，focused case真实绿，并保持原PID/变量、
旧密文不刷新freshness。没有新fixture文件、额外设备或全套重跑。

独立报告`E3-review-mosh-native.md`三发现均closed；额外收包异常分类由root
确认授权精准修，最终统一HAP必要build归root（此前API12link已通过，本改无新API）。

## root接线及未验证层

- `scripts/build-deps.sh ohos`；import build/ohos/install/lib/libamber_mosh.a和
  libprotobuf-lite.a；现有crypto加系统z；新增cpp2源，RegisterMosh。
- SYSTEM include install/include及include/mosh的crypto/network/statesync/terminal/util；
  编译AMBER_MOSH_EMBEDDED=1，Release普通NDEBUG；不需要TERM/terminfo数据库。
- 已发domain固定code/limits/lastHeard语义；后台interruption/approval/SSH-key-不持久化
  消费链路由root/domain统一验证。
- 本worker未证明双设备UDP可达、真实UI/后台行为、统一HAP签名；由root管线完成。
  API12是编译/链接验证，当前设备API24/26不能冒充API12实机。
