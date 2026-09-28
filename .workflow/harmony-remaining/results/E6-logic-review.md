# E6 独立逻辑与调用链审查

2026-09-28。结论：**A 源码审查通过，已回喂的 3 项具体 P1/P2 均在最终源码关闭；无新增明确 P1/P2。B 仅引用其他 owner 已完成的必要验证；C 设备验收仍待验证。** 不据本报告宣称 E6 全阶段完成。

本 reviewer 已读取 `harmony-arkts` skill、E6 contract、四模块及实际 Page/DI/kernel/subagent/delete consumer；仅写本报告，没有修改产品、测试，没有运行 tests/lint/build/sign 或操作设备。本文行号以收口源码为准，函数名是后续定位锚点。

## 已发现问题与最终关闭证据

### P1：置顶/改名保存旧完整记录，覆盖后台生成

具体触发：A 在后台生成，列表或 B 的抽屉读出 A 的旧完整 Conversation；A checkpoint/final 保存后，置顶或改名再保存旧对象，覆盖新消息。即使没有这一时序，宿主若继续保存旧 `isPinned` 也会把刚置顶的值恢复。

最终 `ChatListPage.ets:621–624/760–763`、`ChatPage.ets:3519–3522` 都只提交字段变更，消费 `mutateStoredConversation`。`ConversationRunService.ets:429–442` 在同一个 session writeTail 内读实际最新仓储、应用字段、await 保存并发布 canonical；引擎 `saveContext:400–402` 保留最新 title/chatSuggestions/isPinned。没有旧 Page 完整对象成为第二个消息写者。**源关闭。** 既有宿主组的 pin 必要受影响断言由 fixture owner 报告 1 PASS，本文未重跑。

### P2：隐藏期间已完成且 session 释放，返回原页仍显示旧内容

具体触发：A 开跑后 push 子页，Page 退订；A 在隐藏时完成、队列为空、session 释放；返回原 Page 时新订阅只给空 snapshot，旧 rows 不会被普通 restore-version 检查刷新。

最终 `ChatPage.ets:757–761` 调 `attachConversationRun(id,true)`；`:558–575` 仅在宿主没有该会话 canonical owner 时冷读实际仓储，结果落 UI 前再核 pageAlive/pageVisible/同 ID/宿主 ownership。成功读取刷新既有尾窗、title/suggestions；缺行清旧展示，不拿 Page 对象重新保存。实际活动 session 即时回放，迟到冷读不会盖新 run。

关联 fresh 边界也已关闭：`ConversationRunService.ets:181–185` 不释放真正未落库的新建 seed，`:314–324` 只有实际仓储缺行且存在该显式 seed 才首存，已保存行或旧 latest 不能复活删除记录。`ChatPage.ets:505–509` 只在实际 disappear 通过 guarded `discardNewConversation:307–312` 丢弃未发送 seed，push 子页 hide 不丢。**源关闭；回页设备行为尚未实测。**

### P2：删除 awaiting+queued 的非活动会话，残留热 snapshot 与待发队列

具体触发：A 等审批且有 FOLLOWUP，drain 因 pending tool 暂停，`isBusy=false`；实际仓储/KV 删除后，内存 queue 使 session 继续存在，旧路由可以回放已删除 canonical 与待发内容。

最终 `forgetDeletedConversation:288–298` 同步拒绝活动/待保存会话，清 queue/latest/new seed，发布 `conversationDeleted` 后移出 Map。`ChatPage.ets:598–603` 清实际会话、rows、suggestions 和 unsaved seed。三个实际删除 consumer 分别为 `ChatPage.ets:2834–2841`、`ChatListPage.ets:680–685`、`ChatAssistantsPage.ets:329–346`，均在既有 restore guard 内按 delete→forget→cleanup 顺序执行；cleanup 失败也不会保留删除前热回放。`queueOperation:216–224` 在真正 queue await 前登记同一 run guard，删除不能穿入未结算队列写。

删除的 paused Plugin VM 同样按可信 owner 清理：Service `:290/294` 调 `cancelNativePluginJsConversation`，`NativePluginJsTransport.ets:40–43/76–85` 只匹配 transport 创建时记录的 conversationId，再调用原 native cancel；没有从 checkpoint 接受另一执行 ID。即使宿主 session 已释放仍会清实际 native owner。**源关闭；awaiting+queued 删除设备行为尚未实测。**

## 其余 A 调用链结论

- **真实并行与同 ID 排他：** Service `reserve:350–370` 在首个 await 前登记 record/controller/runId；不同会话独立 record、writeTail、queueTail、活动 store。`saveContext:388–420` 校验 conversationId/runId 并 await 真正保存；失败记实际 error、abort 本轮，done 在写尾与取消队列结算后结束。`cancel:647` 只匹配该 conversationId+runId 的 controller/kernel，不调用全局 native interrupt。
- **取消与 checkpoint：** raw checkpoint、引擎保存和最终 stop projection 共用该 ID writeTail。`chat_kernel_host.ts:129–131` 只有真正 save resolve 后更新 lastPersistedConversation；Service `:591–638` 先等写尾再选择最新 raw/已存结果与 stop，再保存终态、释放 admission。失败或旧 run 发布不串写另一会话。
- **排队输入：** `drainLoop:756–790` 先真实 dequeue 持久化，再 dispatch。`inputCommitted` 只在实际用户/回答保存后置位；已落输入的 provider 失败不重复追加，未落输入才回灌当前队列并保留期间新到项目。pending 工具继续阻止 drain，不另造第二调度器。
- **稳定显示基线：** DTO 区分真正 canonical 和 visualConversation。Service `:407–410/531–566/459–468` 为普通发送、用户再生、assistant 再生和工具续跑固定基线/原目标，checkpoint 不改变正文显示起点；终态 `:625–628` 归 canonical。Page `:604–620` 活动时使用 visualConversation，完成时使用完整已存对象并沿既有 timeline revision 更新。用户再生重复显示的具体红例由另一 reviewer 回喂，必要受影响 case 已由 fixture owner 红→绿；本文复核最终源，无新增矩阵。
- **助手/模型/子代理：** `ConversationRunDependencies.ets:47–70` 用 Conversation.assistantId 解析并 await seed；ChatRuntime 保存实际 resolvedChoice。DI `buildSubAgentGenerationPort:737` 的回退模型/parent assistant 与父 seed 固定，`getSubAgentTools:902–903` 创建该 parent 的 runner；`subagent_manager.ts:557` 执行 RuntimeRun 自有 runner。后开 B 不替换 A 子代理的 parent/provider。
- **工具/MCP/activity：** `ConversationRunTools.ets:178–246` 的 catalog、search、Recipe/Plugin adapter、原 native guard 都消费同 ctx 与 profile；`:193/209–211` 使用本会话 activityStore 和显式 assistantIds，未靠全局当前助手改变 MCP scope。子代理仍用 original allowed scope，Recipe/Plugin 不获得额外 primitive 授权。
- **标题/建议：** Auxiliary 的 candidate 只经 `ctx.mutateConversation` 在实际同 ID 最新态上应用；title 和 sameConversationTail 条件防迟到辅助结果覆盖新消息/用户改名。无 Page/Scroller/UIContext 捕获，manual package 不额外调用 title/suggestion/memory 模型。
- **资源诚实边界：** Python/Plugin JS 继续实际 native busy/取消规则；SSH/Mosh 使用已有实际 session/job ID。WebView 不是跨页可重入资源：`ConversationBrowserGuard.ets:13–29/37–83` 固定 Page lease/revision、真实 inFlight 互斥，离页使旧链失效；`PluginWebMountGate.ets:23–25/37/45` 在 URL gate/read/reset 前和 await 后核归属，旧 scope 不重置 B 的页面。EntryPluginRuntime 的 ctx/parentConversationId 已传实际外层 port，不凭 detached ctx 宣称后台浏览器可用。
- **生命周期/聚合：** `ChatPage.onPageHide/aboutToDisappear` 只退订与清 UI，不停止生成/保存引擎。`aggregate:188–200` 每次发布真实活动 ID 集合，global chatGenerating/keepalive 只依 aggregate；A+B→B 不因 boolean 仍 true 漏列表更新。只有同 ID 可见 Page 的导航 collector 存在，后台工具不能顶开另一页。

## B/C 证据边界

`results/E6-focused-checks.md` 记录唯一实际宿主 A/B 集成组初次 1 PASS、pin 受影响同组 1 PASS，以及用户再生 projection 新受影响 case 真实 0 PASS/1 FAIL→1 PASS/0 FAIL。它加载真实 Service、kernel/InteractiveTurn/chat engine、memory Repository/KV、queue/stop/checkpoint；seed/DI/provider/browser/auxiliary 是明确宿主桩。它证明该受控执行范围，不证明 ArkUI、实际 MCP/native/WebView、OHOS RDB、回页或删除 UI。

本文没有新增或重复测试。Root 已回执 full chat strict types 0、最终改动 lint 0 error 和实际编译成功，最终签名由 root 继续；这些是 root 的独立 gate，不是本 reviewer 执行回执。**设备当前不可用，C 保持 pending；不将源码合理或此前 phase 截图当本 phase 亮暗/并行切页/取消/删除验收。**
