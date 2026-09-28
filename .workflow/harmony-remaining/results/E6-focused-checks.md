# E6 focused service integration

2026-09-28。唯一新增 `harmony/chat/src/test/conversation_run_service.test.cjs`：原1个宿主集成组，后 root 针对真实 projection P1 明确授权增加1个最小受影响 case。未修改任何生产源、Page/DI/service/shared domain，未运行 full suite/native/device/build/sign。

## 真实执行与受控边界

Node TypeScript transpile 直接加载当前实际 `ConversationRunService.ets`、`EntryAbortController.ets`、`ConversationRunGuard.ets`；真实 chat-domain `createChatKernelHost/runInteractiveTurn/runChatTurnWithTools`、队列读写/audit、stop/checkpoint helpers、memory ConversationRepository 与 KV 实现均参与执行。没有替换 Service/kernel 或复制执行引擎。

仅注入宿主端口：DI getter、已水合最小 seed、controlled stream provider、无工具 catalog、空 auxiliary/browser-release、AppStorage 与 keepalive。repository 包装真实 memory 实现，增加写 barrier 与一次真实 Promise reject，以观察实际 Service 序列。此证据不等同 SDK RDB、真实网络、完整依赖水合、ArkUI Page 或设备。

## 同一个必要组的结论

- A/B 在异步读取前同步登记，两 ID 同时 active；同 A 第二次 send 立即拒绝。真实 restore guard 在 A 活跃时禁止恢复，A 终态释放后 B 仍被保护。
- A raw checkpoint 写入阻塞时 B raw 内容继续落库。匹配 ID/runId 的 cancel 只 abort A，错 ID/旧 runId 不影响 B/新 A；A done 与 busy 锁在实际写 barrier 释放前保持，释放后 partial-A 保留且 phase=cancelled。B signal 未 abort、sending/activeCount/chatGenerating 保持，keepalive 未被 A 停止。
- 新 A 开跑后，旧 A context.publish/迟到 chunk 不污染新 A/B；旧 context 跨 ID save 明确 reject。两个 ID 的实际 repository 写入最大并发各为1，包含 user/raw/final 保存。
- 一次 actual repository save Promise reject 进入 Service 写队列，A done 以真实存储错误拒绝、phase=failed；不吞成成功或普通取消，B 独立继续并最终落 partial-B。聚合到所有活 run 完成才归零/停止 keepalive。
- B provider 仍运行时，经实际 mutateStoredConversation 在同 session mutation tail 置 isPinned=true；后续较旧 engine conversation 的最终 save 仍保留真 pin 字段，未覆盖回false。
- 排队输入 `queued-once` 在 provider 请求前已真实落库一次。随后 provider 明确失败，队列不回灌这条已 committed 输入；resumePending 后 provider 请求数不增加、同文本 user 节点仍恰1个，未重播。
- explicit fresh seed 登记一次、真正 save 后才派发首输入；重复 seed 拒绝。保留同 ID snapshot 的情况下删除 repository 行，再 send 明确不存在，未以旧 latest 复活，也未发第二个 provider 请求。
- UI 订阅退订后已结束且空队列的 session 释放；所有 A/B 快照始终带原 conversationId，B canonical 从未含 A 输入/迟到输出。

## 回执

从 `harmony/chat` 运行唯一组：

```sh
node node_modules/tsx/dist/cli.mjs --test src/test/conversation_run_service.test.cjs
```

首次 `/tmp/e6-focused-initial.log`：**1 PASS / 0 FAIL**，组内297ms、进程总524ms。没有行为失败，本 owner 无产品修复。

首次后 actual app compiler 回喂 command inline union 的 ArkTS 类型约束，Service owner 只换具名 exported command interfaces/local typed const，无调度行为变化，该纯类型修复没有单独重跑。之后 root/reviewer 查实 pin 竞争，owner 将实际 isPinned 加入 latest-field merge；按 root 明确要求，本 owner 只在既有组增加3行 pin mutation→final save断言，受影响同组再跑一次 **1 PASS / 0 FAIL**，组内188ms、总396ms，`/tmp/e6-focused-pin.log`。未新增组或重复其他 suite。

当次实际 source SHA256：

- Service：`45bc406187c9429dc09d9bedab00e2bffc17865879824efef6ab92fab12af5f6`
- fixture：`dfa44e32bb897cbb7e0facd9d6cfe836fca58ec6196c7f48401e5950526b4ec8`

具名 command 与 pin merge 后最终回执 source SHA256：

- Service：`c9e8e240c450815bc0b05ef05a2a732b6770bb6a3f12808da317fec84294ff30`
- fixture：`02abdd7047d26d23d38a29bf328cefa641f5ee212438916eae9f13c0a3d932f7`

测试直接执行 CJS/transpiled source，不把它当 ArkTS 编译证明。Service owner 已报告 SDK/domain 窄 strict types 0；本 owner 不重复其静态检查，root 最终 compile/sign/设备/Page A→B→A 链仍是独立验收。

## 后续仅 UI observer API 的只读核对

Pin 必要复跑之后 Service owner 新增 runningConversationIds()/subscribeRunning()，root 明确要求不再因此重跑整个组。本 owner 只读核实：subscribeRunning 登记后立即调用当前 IDs；aggregate 在 reserve/终态调用时先每次通知 ID listeners，再比较 chatGenerating bool，因此 A+B→B 即便 bool仍true也发布；退订移除原listener；IDs筛选与activeCount都取 record非null且sending。此新增 API 未执行 fixture，未作为运行PASS。只读当次Service SHA256为 `88834bc56191f4a6f8f4f6e05683d6d6c4fc53413e176c10a303e6447881c53f`。除具体失败，本 owner不追加测试。

## 真实 projection P1 的必要红例

Root/reviewer 回喂的具体失败：user 中间 regenerate 在 checkpoint 后将新 assistant 写进 canonical，Page 用 canonical 原历史长度截取 baseline，再叠 regenTail，导致同正文显示两份；assistant 暂时工作节点替换原 ID 与 resume 同类投影也受影响。按 root 明确授权，沿同一个实际 Service harness 机械提取公共 fixture（不复制 engine），新 counterexample 首次只按名称执行，未重复绿色 A/B 组。空 compact port 原先只支持 getCompacts，regenerate 实际还调用 deleteByConversation；补上该必要宿主方法后才获得产品红，前一次 port 缺失不算产品缺陷。

`/tmp/e6-projection-red.log`：**0 PASS / 1 FAIL**，189ms/总434ms。真实 user checkpoint 快照 `canonicalIds=[user-target,new assistant]`，旧 Page fallback 同样包含新 assistant，`markerCount=2`、`stableBase=false`；同时初始只含 user 的快照计数为1，证明重复来自后续真实 checkpoint。曾以同一新 case 观察 assistant 原 anchor 消失和 resume 重复；root 随后明确收窄，只保留约31行 user regenerate case。该收窄没有再跑旧红/宿主组。

Service owner 落 public `visualConversation` 独立稳定基线：user regenerate 保持 through-user prefix，将新正文放在 streamingTail。本 fixture 对 role 不复制分支实现，只检查稳定 baseline IDs 与所有活动 tail 合计：每次 canonical 已写新正文的实际 checkpoint publication，baseline仍只含 `user-target`，正文恰1份；最终实际 repository 包含 `user-live`。

唯一受影响绿执行：

```sh
node node_modules/tsx/dist/cli.mjs --test --test-name-pattern='focused active projection' src/test/conversation_run_service.test.cjs
```

`/tmp/e6-projection-green.log`：**1 PASS / 0 FAIL**，187ms/总398ms。没有重复原 A/B 宿主组，没有增加 assistant/resume/delete矩阵或设备验证。最终 fixture 为224行/2个tests；公共 harness 提取为机械共享，当次新测试执行真实 harness，不宣称原组在该次重跑。

- Service 当次 SHA256：`086997306cbd8c60f6bc2b3e769281e82f90470749e48c57e0ccb4ac0b5b03de`
- fixture 当次 SHA256：`f34c52ad0b75a0ae2c6e67721a07c6092469ccf965ff607505cff46bea61e40f`

同一 Service 源附有 root 的 deleted-session 收口，本 owner仅只读看到 `queueOperation` 登记既有 restore guard 至 Promise finally，forgetDeletedConversation 同步拒活跃/待保存，清 queue/seed/latest，发布 conversationDeleted 后移出Map。没有测试 waiting-approval/FOLLOWUP 删除消费者，不能把本次 projection PASS 当该 API 的执行证据；Page投影/删除显示与最终 SDK/设备仍由 root 独立验收。
