# E2 terminal domain 实现与验证

日期：2026-09-28。owner：domain runtime worker。冻结依据：`packets/E2-contract.md`。

## 已实现的实际调用链

- `SSHProfileStore`：无秘密的单 KV metadata，store 内串行修改和 revision CAS；snapshot/get 副本；host/port 改变失效 pin+credential，username/authMethod 改变仅失效 credential。验证后的新凭据先暂存新 UUID reference，再一次 KV 提交；KV/CAS/Asset 失败保留旧 profile/secret 并清新 ref。成功提交后旧 ref 清理错误仅记录无秘密错误。
- `TerminalRuntime`：probe 没有凭据；验证主机 candidate、draft digest、revision 和明确信任后，真实 pinned auth-check startExec/drain/release；验证后再 CAS 提交。执行批准对象包含 endpoint/user/auth/pin/reference/default identity，秘密 load 前后和 native 调用前都重验。请求复制后使用固定对象，外部 draft/request 原地变化不能偷偷 retarget。
- `TerminalJobRuntime`：native start 前登记共享 TaskStore 的真实 cancel callback、terminal/remote_ssh metadata、private terminal_log 路径、非 retryable。start signal 只覆盖启动阶段；execute 同一个 job 等 5s 并返回真实 RUNNING/末态；execute 等待取消会停止 job；wait 取消只停止等待。read 可从已有 TaskStore 恢复 metadata/tail/log 引用，未完恢复为 INTERRUPTED，不重新发命令。
- `TerminalStream`：每连接唯一 native reader，100ms/64KiB。read/append/write/resize及关闭尾包与末态共用串行队列；取消关闭请求立即到 native owner 以唤醒阻塞 write。每流 UTF8 跨块 decode，stdout/stderr tail 各最多16Ki字符、combined最多32Ki，截尾不切 surrogate pair。日志 append 保存完整文本，stderr 标签明确，TaskStore offset 是实际 UTF8 日志字节数。末态 drain 后 release；read=0 保持 RUNNING；log/read/write 故障真实 FAILED+关闭。
- `TerminalSessionRuntime`：真正 PTY port，固定 xterm-256color，POSIX quoted 初始远端 cwd，真实 bytes 输入/resize/stop。首次 native pump 从 start resolve 后下一个100ms开始；subscribe 立即 snapshot；read 工具只读缓存，不吃 UI bytes；session_exec 重验批准目标后在同一串行 write 队列写 command+newline，返回会话状态，不虚构逐命令 exit code。
- 后台：runtime abort probe/verify/secret-load pending operations，以及 job/session pending 握手的自有 controller；active owner 用 disconnected close，状态 INTERRUPTED，无重发。平台前/后 AppStorage 后台 guard 与 root EntryAbility 接线组合阻止后台新连接，不扩 DATA_TRANSFER/TASK_KEEPING。
- 九件工具：factory 先捕获所有显式 profile targets 和默认 target；缺省/null/空 profile_id 对应捕获时默认。exec/start/stop/session exec 必须批准且 allowsAutoApproval=false；read/wait 无二次批准；JSON 保持 remote_ssh、profile/job/session id、真实小写 status/running/null exit_code 与日志路径。共享 AgentToolActivityStore，RUNNING 不标成功，观察末态后才 completeWithExitCode/fail/cancel。
- `credential_codec.ts`：平台 AES-GCM 明文 payload 的严格字段/类型/binding 校验，保留 secret/passphrase 空白，支持 >1024-byte 私钥，secret UTF8 最多64KiB，不新增 SDK/NAPI 依赖。

## 文件与导出

新增 `chat/terminal/{models,ports,profile_store,runtime,job_runtime,session_runtime,stream,control,utf8,credential_codec,tools}.ts`。最大实现文件186行，按真实职责拆分，无第二个任务调度器。worker 没有修改 index、AppContainer、ChatPage、EntryAbility、SDK adapter/UI/CMake 或 git add/commit。

root 已接公开导出：models 所有 SSH*/Terminal* 类型；ports 三端口；SSHProfileStore/SSHProfileStoreDeps；TerminalRuntime/TerminalRuntimeDeps；createTerminalTools/TerminalToolsDeps；encodeSSHCredential/decodeSSHCredential。stream/control/UTF8/子 runtime 是内部实现。

## 验证证据

先新增失败测试：初次执行 `terminal_profile.test.ts` 在不存在 profile_store 时失败；随后业务测试中的非法 port 同步 throw 暴露 Promise 签名问题，已改 async 方法让失败按 Promise 拒绝。runtime 测试先在缺少 runtime 实现时失败，再实现真实调用链。

最终命令（cwd `harmony/chat`）：

```text
./node_modules/.bin/tsx --test src/test/terminal*.test.ts
35 tests / 35 pass / 0 fail / 0 skipped，约7.39s
./node_modules/.bin/tsc --noEmit
exit 0
```

测试覆盖：profile KV/Asset/save/CAS 失败保旧+新ref回收、binding 失效、快照副本、秘密不入 metadata、旧ref清理失败后的已提交结果；codec shape/type/extra/binding/UTF8大小；中文emoji所有split；无认证probe/信任 gate/pin传参/认证期间编辑；批准后 default/edit拒绝；immutable await-load 请求；TaskStore pending cancel；空read、stderr、真实非0、timeout/cancel/disconnect、完整log与offset、restart读取；log故障owner关闭；probe/verify/job/PTY后台pending收口；execute等待取消和5s真实RUNNING；末态无迟到输出；独立session/input/resize/close；九件工具 registry无轮询审批、明确目标、Activity RUNNING与末态。

精确 lint（repo root）：`harmony/scripts/lint-arkts.sh harmony/chat/src/main/ets/chat/terminal`。
首次 sandbox 拒绝官方 codelinter 写自身 Huawei 日志目录，原命令用 require_escalated 权限重试成功。最终 exit0、11文件、0 error/1083 warn（多数 no-unsafe 类型解析 warning），不能报告成基线126 warn或零warning。

## 独立 review 后的两项修复

- P1 延迟日志启动窗口：reviewer `/tmp/e2-review-pending-log.mts` 与正式两项回归先复现失败，job/PTY 都在后台中断后因迟到 logs.create 继续认证（starts=1）。现 runtime 的已有 pending controller 覆盖 prepare→完整 start；各 manager 在日志 await 前创建 controller/map，启动 barrier 覆盖整个阶段。文件接口没有取消 API，因此仅取消等待并吞迟到文件 settlement，绝不消费它继续 native start。后台先收口，回前台后日志才完成仍 starts=0、INTERRUPTED。reviewer exact repro2/2及正式2/2通过。
- P2 Activity 累计 tail 重复：reviewer `/tmp/e2-review-activity.mts` 与正式回归先复现 idle '$ ' prompt 每100ms被 append 成重复行。现 RUNNING 状态通过已有 ActivityStore.start(state) 替换累计 snapshot tail，完整保留既有状态字段，保持1600字符边界；不靠 lastTail equality 去重。空 poll不再重复，真实第二个相同 prompt 输出仍保留。reviewer exact repro1/1及正式真实重复prompt回归通过。
- 两项修改后全30 tests与chat tsc通过。精确修复文件 lint：P1 control/stream/runtime/job/session 五文件 exit0、0 error/669 warn；P2 tools 单文件 exit0、0 error/241 warn，日志分别 `/tmp/e2-domain-pending-log-lint.txt`、`/tmp/e2-domain-activity-lint.txt`。

## 第二轮生命周期 review 修复

- P1 阻塞 PTY write：正式 stop/background 两条与 `/tmp/e2-review-blocked-write.mts` 先复现失败（150ms close=0），根因是 close 排在 native EAGAIN write 后。现 close 立即将 handle 移交 native，同时缓存唯一 closeCompletion，关闭尾包/输出/末态再进入原有串行队列。由 close 导致的 write/resize reject 不调用 fail 覆盖末态；重入复用同一个关闭结果。job/session stop 先发 close 再等待 startup，因此初始 cwd write 也能唤醒。
- native worker 已只读确认 `ssh_napi.cpp Close→Runtime::Close` 的 action 同步执行，立即登记关闭、设置 stopReason 并 Wake；owner writer 与 close 各自 TSFN 投递，domain 不依赖它们的 JS Promise settle 顺序。
- P1 未发送的排队 Agent 命令：正式回归与 `/tmp/e2-review-queued-exec.mts` 先复现 abort 后仍发送 `echo UNSENT\n`。root 授权兼容扩展 execSession optional signal 末参数；tools→runtime→session 原样传递，在已有串行 write 的 validate 内、native 实际发送前 checkAbort。尚未发送的取消不杀现有 PTY。
- 新增5项正式测试：blocked write stop/background；queued exec abort 不发送且 PTY保持 RUNNING；native取消触发write reject时保留 INTERRUPTED、完整中文emoji日志和原始尾 bytes、并发stop单次close；startup初始cwd write 阻塞时后台及时关闭并返回真实 INTERRUPTED。这最后一项先暴露过 stale RUNNING 返回快照，已在初始cwd write 后再核对取消并等待关闭。
- 当前全35 tests+chat tsc通过；四份 reviewer exact scripts 合计6/6通过。独立 domain/UI reviewer 已复核新代码与6项exact、另两项正式拒绝/初始cwd测试，A/B相关缺陷关闭。未增 idle timeout、scheduler或自动重播。
- 本轮五个实现文件精确lint exit0、0 error/903 warn，日志 `/tmp/e2-domain-close-queued-lint.txt`。最后初始cwd小改另做 session_runtime 单文件复核：exit0、0 error/113 warn，日志 `/tmp/e2-domain-startup-cwd-lint.txt`。

## 主 agent 接线/验收边界

- UI 和 Agent 必须消费同一 profile/runtime 单例；EntryAbility 后台调用 interruptForBackground；真实 adapter 前后检查后台。
- 工具 loop 生成阶段持久化 approved terminal_target，并在 ChatPage 审批续跑重建 factory 后再次比较。factory guard 本身不能补偿续跑重建造成的批准快照丢失；这条独立修复由 root/design worker 独占。
- 曾给 root 标记 TaskStore.cancel 完成竞态。当前 root 已在 callback await 后重读 latest terminal/cancelCapability 并提前返回，避免已完成状态被取消或附上 Cancellation request failed；domain callback 仍只对真实 CANCELLED 返回 true。
- 上述 tests 使用 fake transport/credentials/log/file ports，是 domain 控制流证据。没有声称真实 native password/private-key/hostpin 先于认证、fd/thread回收、设备 Asset 密文、PTY stty尺寸/软键盘/xterm显示通过；这些仍需要 root 的真实 fixture、build/sign/install 与 UI review。

## root/reviewer 两项改动的独立只读补审

- `AgentTaskStore.cancel`：callback await 完成后重读 store 的最新 snapshot；owner 已进入非运行终态且取消能力已撤销时直接保留最新结果。因此真实 COMPLETED/FAILED/INTERRUPTED 不会被旧取消回调改成 CANCELLED，也不会被 false/throw 结果附上 Cancellation request failed。仍有活取消能力的子代理 INTERRUPTED 情形继续走既有 callback 语义，未把全部非运行状态一概当作不可取消。新增终态竞态回归与原取消/恢复语义均通过；未发现本次改动的明确问题。
- `buildChatTimelineRows` 与 ChatPage：核过批准→不可变 approvalState 替换→refreshRows、续跑输出→finalizeGenerationResult→refreshRows、失败落库回滚/重读、branch 切换、窗口重建、两条历史分页 merge 和 ForEach key 的完整链。旧行按 nodeId 找到其版本；相同引用或 JSON 内容相同的重载/克隆保持版本，只有完整 UIMessage、分支数量/选择、角色或最后助手标记改变才递增。审批文本相同但 approvalState 改变、output 数量相同且文本等长改变仍触发递增；无需把全文 JSON 放进 key。实际审批与 currentMessages 更新保持不可变，引用短路不会漏掉这条真实批准链。
- 分页仍按 nodeId 合并并保留既有行，窗口范围/offset/锚点逻辑没有改动；保留行版本不会因前插或重排清零。新增行从零开始，branch/最后助手标记变化按本节点更新。本次窄修复未发现新增分页、branch 或相同文本审批输出风险；页内最后助手计算沿用原映射语义，未扩为新的分页状态框架。
- 本 worker 当前重新执行 `tsx --test src/test/agent_task.test.ts src/test/timeline_rows.test.ts src/test/tool_approval.test.ts src/test/timeline_load.test.ts`：62/62、0 fail、0 skipped。其中 TaskStore 17/17，行版本+审批+分页45/45。fullchat2233/2233是 root 报告的集成证据；本补审未重复全量、未触碰实现、未运行 build/设备或声称截图验收通过。
