# E2 gate — Remote SSH 终端与工具闭环

2026-09-28。E0/E1 后续阶段。结果 PASS。最终逻辑、调用链与 UI 均经独立 subagent 收口，Release 门禁由 root 实测。
独立审查见 E2-review-native.md、E2-review-domain-ui.md；row revision 和 TaskStore 取消竞态另由 E2-domain-runtime.md 独立复核。

## 实现

- OHOS API12 ARM64 libssh2 + 静态 OpenSSL libcrypto，8个真实 NAPI transport 方法；单 native owner/nonblocking poll/wake、1MiB 输出背压、清理与迟到 DNS 分离。依赖、两项源码补丁和许可证 hash 固定。
- 无凭据 host-key probe；用户看到完整 SHA256 后信任；实际连接再次校验 pin，在认证之前拒绝变更。配置 revision CAS，认证成功后单次 metadata commit，不把失败的编辑覆盖旧凭据。
- Asset 内随机 AES key + 应用私有 AES-GCM 凭据文件，真实大 RSA PEM/口令按设备绑定；普通设置、日志、备份无密码/私钥。私钥由内存送入 libssh2，无 native 明文 PEM 文件。
- 单例 Domain runtime 连通配置页、xterm PTY、9个 Terminal tools、AgentTaskStore、Activity 与完整私有日志；UTF8 增量解码，stdout/stderr tail 与真实 nullable exitCode。
- 执行/启动/修改/停止工具均显式审批，待批准时 frozen profile/default digest 和 readable user@host:port/command 持久绑定；工具 factory 重建、默认主机改变、旧无上下文调用不能偷偷换目标。
- 后台由 EntryAbility 断开并归为 INTERRUPTED，回前台不自动重发。没有宣称普通三方 SSH 可用 DATA_TRANSFER 永久保活或已实现 tmux 自动恢复。

## 当前验证证据

- Full Chat: `/tmp/harmony-remaining-E2-chat-tests-gate.log` **2237/2237**, 0 fail；tsc0。新领域35项，guard/approval15新项，timeline4项（基线3红1绿→4绿）；TaskStore17、相关分页/审批45独立联合62/62。
- Host NAPI17/17，ASAN17/17，owner/fd/thread lifetime4项，worker env cleanup3项；无 exit-status 与 uint32两项真实红绿。target API12 strict syntax/ELF link 无 JNI/Android/动态crypto。
- 真机和模拟器 cold/warm/final 六轮，每轮17项，共 **102/102**。日志 `/tmp/e2-{emu,device}-{cold,warm,final}.log`（phone最终刷新另 `/tmp/e2-device-final-refresh.log`）。实际 Asset 4KB RSA、AES binding/ciphertext/cleanup、password/encryptedRSA、unicode/stderr/exit7、abort、PTY prompt/stty resize/Ctrl-C/stop。
- 两组真实 native 背压关闭测试，各2/2，关闭 blocked input（1048575 bytes，预留真实队列空间）和 queued input 均 <1秒；null exit 与 cancellation/disconnected code 精确对应。见 `/tmp/e2-native-pty-close.log`、`/tmp/e2-blocked-write-close-corrected.log`。
- 实际 localhost SSE → 保存 pending → 重建 tool factory → 审批 → SSH auth/printf exit0 → 当前页 success/output → SSE continuation；非外部 OpenAI 验收。light最终 `/tmp/e2-approval-{pending,completed}-final-light.jpeg`，dark fixed pending/detail；output右界从1319修到1207（父卡1250内）。
- root控制夹具换host key0→1，UI完整新旧指纹，未经信任连接拒绝；rotated段服务端2个connection、0 auth事件。`/tmp/e2-host-rotation{,-blocked}-dark.jpeg`。夹具已恢复原测试key，不修改真实服务器。
- profile light/dark、password placeholder/eye、selector、footer均实际目测且独立复核。后台 Home→同页面明确“连接已中断，请重新连接”，无自动连接/重发：`/tmp/e2-terminal-background-interrupted-light.jpeg`。
- root DI/Entry/Settings/Task/Activity 7文件分块 lint0error；owners各自窄 lint0error。timeline/ChatPage当前0error/16warn（4既有性能、12SDK TS解析诊断）。最终 Debug7 SUCCESS12.183s，已有 AGC signed HAP 两设备安装。单独 sign-hap script曾签 /tmp/e2-local-sign 并验签通过，此测试CA产物未冒充设备安装包。

## 已修真实发现

- libssh2重复key算法列表的heap overflow，按上游最小补丁修；missing exit-status被默认为0，收口实际收到状态。
- background→foreground中 logs/create 和prepare窗口漏取消；关闭排在背压 write后死锁；queued sessionExec漏signal；activity重复累加快照；task cancellation回调覆盖owner终态。均先红后绿。
- UI Select暗色空字/箭头、密码黑placeholder/eye、空列表垂直居中、footer挨手势条、工具toggle超父卡、审批按钮挤出屏幕、output边距越界。
- immutable message替换而ForEach key仅nodeId导致审批/结果冻结：仅变化节点displayRevision递增，不改生成协议或全部重建，实际当前页验证闭环。
- xterm异步parser的旧write越过reset污染新连接：真实vendored parser4/4，写入与reset顺序封闭。

API12是编译/链接基线；当前实际运行设备为模拟器API24、HOP-AL00 API26，不声称API12实机测试。实际终端通过本机生成身份夹具验证；没有测试用户外部SSH主机或外部AI账号。

## 最终交互与发布门禁

- 无效 native requestFocus/showTextInput 路径移除；本地 Web DOM 工具条以真实点击同步 focus。所有按键走原字节门，未添加第二个 IME controller。真实xterm/HTML测试6/6，三 ETS lint0error0warn。
- `/tmp/e2-terminal-ime-final-{light,dark}.jpeg`：首次点击键盘即实际IME，页面43×10，真实Shell `stty size` 10 43。
- `/tmp/e2-terminal-tools-final-light.jpeg`：方向键调出历史、Ctrl-C清空，横向滚动工具条后直接回车执行 E2-WEB-ENTER；IME维持。`/tmp/e2-terminal-tab-final-light.jpeg`：真实文件名前缀被Tab补全并正确读取。
- `/tmp/e2-terminal-reset-final-light.jpeg`：断开→重连仅新prompt，43×26，无旧VT残留；`/tmp/e2-terminal-background-final-light.jpeg`：保留INTERRUPTED/中文说明、禁止输入，无重发。
- 最终 Debug8 SUCCESS7.454s，Release SUCCESS17.318s。使用已有AGC配置 signed HAP 两设备 install -r、aa start均成功；未卸载或切换用户签名身份。
- Release生成BuildProfile.DEBUG=false，两设备均传此前有效SSH selector及独立 release-blocked runId，`/tmp/e2-{emu,device}-release.log` 对应事件均0；Release不会运行debug认证夹具。

- Release实际正常入口再验：`/tmp/e2-terminal-release-dark.jpeg` 仍用持久可信配置、真实认证/PTY/DOM IME，命令 E2-RELEASE-UI + `stty size` 返回10 43。不是仅Debug夹具运行。
- 当前最终unsigned HAP再次经过sign-hap.sh，`/tmp/harmony-remaining-E2-final-sign.log` codesign、SHA256 digest与verify-app全部成功。此测试CA输出保留/tmp，实际安装仍为现有AGC包。

最终独立 C 复核确认上述七张终端交互/背景/Release截图及六项实际xterm/HTML回归通过；E2 无未修阻断项。
