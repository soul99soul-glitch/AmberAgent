# E3 Mosh domain 实现与验证

日期：2026-09-28。owner：domain runtime worker。基线 E2 `87f69a88c`；合同：`packets/E3-contract.md`。

## 实际实现

- 仅窄提取 `terminal/target_resolver.ts` 的 `SSHTargetResolver`：captureTarget、目标摘要/default 身份/严格 pin 验证、credential load 前后重验及 SSH connection 构造。原 TerminalRuntime 公开 deps/API 不变，委托同一规则；SSHHandle 仅新增可选 `peerAddress`。未改 profile/credential 持久化或批准协议。
- `MoshRuntime` 使用低层 SSHTransportPort 做一次固定 `mosh-server new -s -c 256 -l 'LANG=...' -l 'LC_ALL=...'` bootstrap；可选数字 UDP port 白名单，缺省让服务端选择。stdout/stderr 总量最多64KiB，stdout只在方法内存中累积/解析；stderr、CONNECT key 和完整启动输出都不进入 snapshot、TaskStore、Activity 或日志。只接受一行合法22字符 base64 key 和1..65535端口；显式请求端口必须匹配服务端回传。bootstrap 成功/失败/取消均释放 SSH handle。
- UDP peer 仅取这次严格 pin 认证成功的 SSHHandle.peerAddress，缺失返回明确失败，不用 profile hostname 再做 DNS。进入 UDP 前再验证冻结 target；request/target/尺寸/端口/locale 在首个 await 前复制。
- 根据真实 Mac 服务端 C.UTF-8 不可用的回喂，最小增加 `MoshServerLocale = 'C.UTF-8' | 'en_US.UTF-8'`。request.serverLocale 可省略，默认 C.UTF-8；tool `server_locale` 是同一严格 enum。两个 locale 经 POSIX quote 构造参数，不从错误猜 locale，不自动第二次 bootstrap。官方 [1.4.0 bootstrap 脚本](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/scripts/mosh.pl#L351) 和 [server 参数解析](https://github.com/mobile-shell/mosh/blob/mosh-1.4.0/src/frontend/mosh-server.cc#L110) 支持 `-l NAME=VALUE`。
- `MoshStream` 每会话一个 native reader，100ms/64KiB；真实 UDP帧作为增量 bytes 通知 xterm，read仅查缓存。UTF8跨包 decoder、32Ki字符 tail 不切 surrogate。native running/reconnecting/lastHeardMs原样反映，reconnectLastHeardMs表示距最近认证 SSP 接收的毫秒，不是时间戳；不因重连重跑 SSH。尺寸每边1..1000且总cells≤30000；输入最多64KiB，resize/send/exec走同一串行数据队列。
- native close 立即收到关闭请求，输出/末态随后在原串行队列收口，可唤醒阻塞 write；迟到的取消 write reject不覆盖 INTERRUPTED/CANCELLED。Agent exec signal 在实际排队发送前检查，取消未发命令保留现有 Mosh 会话。
- 会话在 credential load/SSH handshake/UDP start 前进入现有 controller/map 与共享 AgentTaskStore；真实取消 callback、非 retryable、runtime remote_mosh、精确 sourceToolName。后台关闭 pending bootstrap 与 active UDP，INTERRUPTED 后不重发。冷启动从共享 TaskStore读取历史 tail/元数据，旧运行会话显示 INTERRUPTED，缺 session key 不能恢复；没有第二调度器。
- `createMoshTools` 提供四件专用工具 `terminal_mosh_session_start/exec/read/stop`，factory冻结所有显式目标/默认目标；start/exec/stop必须批准且auto=false，read无重复批准。工具 JSON与Activity都是 remote_mosh，真实session状态、connection_state/lastHeard，完全不提供逐命令exit_code。Activity通过已有start(state)替换累计tail，避免SSH E2曾出现的累计重复追加。
- 错误仅接受 native/domain固定安全code，不打印 rawError 或含key启动输出。missing server引导安装mosh-server，bootstrap失败引导检查所选UTF8 locale，native connect_timeout引导UDP端口/firewall；SSH bootstrap connection_timeout单独说明SSH阶段。

## 冻结的公开接口

- `SSHTargetResolver({profiles,credentials})`、`SSHTargetResolverDeps`；captureTarget(profileId|null)、targetProfile(target)、prepare(target,signal?)。
- `MoshRuntime({targetResolver,sshTransport,transport,taskStore?})`、`MoshRuntimeDeps`。
- `startSession({target,columns,rows,udpPort?,serverLocale?,sourceToolName,sourceConversationId},signal?)`；captureTarget/readSession/subscribeSession/sendSessionBytes/execSession(id,command,target,signal?)/resizeSession/stopSession/interruptForBackground。
- 新 `MoshSessionSnapshot`：runtime/sessionId/profileId/status/connectionState/reconnectLastHeardMs/outputTail/columns/rows/errorCode/errorMessage，无exitCode或outputLogPath。`MoshSessionEvent`为 snapshot + SSHOutputChunk[]，保持既有xterm真实bytes消费结构。queued时connectionState为connecting，nativepacket枚举不变。
- `MoshTransportPort`：start(requestId,options,signal?)、read(handle,maxBytes)、write/resize/close均Promise，对应冻结native合同；models导出 MoshCloseReason/MoshReadState/MoshConnectionState/MoshServerLocale/MoshHandle/MoshOptions/MoshPacket/MoshSessionRequest/MoshSessionSnapshot/MoshSessionEvent。
- `createMoshTools({runtime,profiles,activityStore,conversationId})`、`MoshToolsDeps`。bootstrap/stream内部实现无需export。

## 必要验证与真实边界

首bootstrap业务测试在缺target_resolver时红，工具链在缺tools时红；实际总cells限制、native connect_timeout精准引导、en_US.UTF-8参数与冷启动读取先各有具体失败，再最小修复。没有扩大压力/数字排列组合测试。

当前8个必要Mosh测试全部通过：pin/actual IPv6 peer/key不持久、四件专用工具/输入、port及真实nativecells前置界限、安全UDP timeout、locale白名单/quote不重试、TaskStore冷启动不重发、UDP增量bytes/实际reconnecting恢复/输入resize/排队取消/阻塞write后台收口，以及pending SSH真实signal取消→0UDP。

最后一次（cwd harmony/chat）验证：

```text
tsx --test src/test/terminal*.test.ts src/test/mosh*.test.ts
43/43、0 fail、0 skipped，约7.42s（既有SSH35 + Mosh8）
tsc --noEmit
exit 0
```

这是共享SSH提取后的单次完整受影响回归；未重复fullchat。精确lint仅新mosh六文件+resolver/runtime/models三文件：exit0、0error/818warn，主要为官方linter无法解析跨TS模块后的no-unsafe warning，不能报告为零warning。首次sandbox拒绝linter写Huawei自身日志，原命令require_escalated重试通过；日志 `/tmp/e3-mosh-domain-lint.txt`。tracked SSH改动 diff-check通过。

本worker只改授权domain/tests与此结果文件，未修改native/index/DI/UI/ChatPage/批准guard/共享Activity或TaskStore，未git add/commit，未操作设备或build。root/design worker独占持久协议审批链并报告真实factory/runtime接线109项通过；本worker没有把该报告或native/设备结果当作自己的fakeport证据。真正SSP/UDP漫游、服务端PID不变、native线程/fd回收、亮暗xterm、API12编译和双设备签名安装仍由root/native/UI/reviewer统一验收。

## 手动回车疑点的窄只读查证

root在手动Mosh页曾看到回车回显 `^M`，要求检查bootstrap是否需要SSH PTY。本worker只读核对本地官方1.4.0源：scripts/mosh.pl默认ssh_pty=1并传`-tt`；mosh-server.cc服务端另行`forkpty(..., NULL, NULL, &window_size)`，因此仅默认`-tt`证据不足以把此次现象归因于domain startExec，不能据此改启动协议。

在真实host CR/LF对比尚未启动时，root已在同一原Mosh session、未改源/PTY的条件下，用physical keyEvent提交`echo e3`并得到`e3`与新prompt（`/tmp/e3-mosh-dark-physical.jpeg`）。root同时查明前次UITesttext剪贴板没有插入command，IME动画未完成；原截图`^M`是空输入过渡。本组停止扩验，没有修改bootstrap、runtime、test、fixture、native或UI，没有重新运行已有绿色测试。该physical/device结论是root实测证据，本worker只记录来源，未操作设备或声称独立截图验收。
