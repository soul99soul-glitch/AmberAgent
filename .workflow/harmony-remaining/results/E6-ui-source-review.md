# E6 UI/source 独立只读 review B

最终源码复核：下述四项历史 P2 均已由 root 修复，本轮 affected 调用链复核关闭；没有确认的残留 P1/P2。最终 lint/build/sign 与 C 设备验收仍未在本 agent 验证，C offline。

2026-09-28。审查实际 `ChatPage.ets`、`ConversationRunService.ets`、其 dependencies/tools/auxiliary 与真实 domain regenerate/checkpoint/dispatcher、浏览器宿主/租约调用链。没有修改产品源码，没有运行测试、lint、build、签名或设备；C 设备 offline，UI 布局/亮暗/IME/真实 WebView 挂载未验证。以下行号是本轮审查时的源位置，并附函数名供并行收尾后的定位。

## 确认的 P2

### 1. Canonical checkpoint 与 resume/regenerate 活动尾巴重叠；assistant placeholder 会移除 tail 锚点

- `ConversationRunService.ets:428–433` (`updateVisual`) 将所有 non-send 的最后一条消息投影为 `regenTail`；`perform:473/487–489` 却以开跑前全部节点数作为 `historyNodeCount`，把 user/assistant regenerate 的目标 ID 都当作显示锚点。
- `ChatPage.ets:656–658` (`applyConversationRunSnapshot`) 从已经变动的 full canonical 按旧 count 截取历史；`:3735–3738` 无条件渲染该 row；`:3790–3793` 再在目标 row 下渲染 `regenTail`。
- **user 中间节点重生成/编辑重发**：实际 `chat/regenerate.ts:108–114` 先截断到该 user 并保存；`stream_checkpoint.ts:119–126` 随后 append 新 assistant。旧 full count 大于截断后长度，新 assistant checkpoint 因此留在 rows，同时在旧 user 下再渲染同一个 `regenTail`。旧后续节点必须消失，当前回答不能双份显示。
- **resume/批准续跑**：canonical 最后 assistant 原位更新，原 row 与 `regenTail` 含同一条消息，出现重复正文/工具卡。
- **assistant 再生带工具循环**：`tool_loop.ts:626–632` 真正 working 是 prefix + 新 ID placeholder，循环 `store.save` 将原目标 ID 暂时移出 canonical。Page 的 overlay 只挂在旧 `regenNodeId` row 下，保存后该锚点不再存在，活动 tail 因而不显示；assistant 再生结束时 domain 仍会保留后续节点，不能用 user 的截断方案替代。
- 已回喂 root。需修显示投影与实际 regenerate 种类的边界，保留 engine 的真实 canonical 保存，不复制 engine 或持久化第二套历史。

### 2. 分支切换/删除绕开 session write/apply，热重挂会重放旧消息状态

- `ChatPage.ets:1801–1812` (`switchBranch`) 和 `:2623–2633` (`confirmDeleteMessage`) 只更新本页并直接 `getChatRepository().save(updated)`，没有更新 `ConversationRunService.session.latest/state.conversation`。
- 完成一次 run 后可见页面仍订阅 idle session；切换分支或删除消息后服务 snapshot 仍是旧值。随后页面重新 `onPageShow`/`attachConversationRun` 即时订阅回放旧 canonical，恢复旧 selectIndex 或已删除的 row；`serviceOwnsConversation` 又跳过冷加载。
- 删除弹窗的确认与 full-load 完成回调也没有重新核 `sending`/same-ID busy，可与确认期间开始的后台运行直接并发写全量旧会话。
- 已回喂 root。该两处消息 mutation 应在同 ID 服务写队列中读取最新、保存并 apply，确认执行时重新检查 busy。

### 3. Plugin WebMount scope 在 conversation lease 拒绝之前已修改另一页的全局浏览器

- `plugins/runner.ts:134–136` (`dispatchChild`) 在 `port.dispatch` 外先进入 `webMount.withScope`，`wm_open`/`wm_tab_select` 传 `requireCurrent=false`。
- `PluginWebMountGate.ets:27–28` 先绑定共享 `WebMountSession` 的插件 URL gate；`:30–34` 在 operation 后检查全局当前 URL，不符合插件域就 `resetToBlank()`。
- `ConversationBrowserGuard.ets:45–46` 的同会话可见性检查发生在内层 primitive 执行。`tool_dispatcher.ts:434–445` 把这个普通 Error 转为失败 output 返回，因此 scope 的 final URL 检查仍继续。
- **具体交错**：会话 A 离页后继续运行；用户在 B 的实际 WebMount 页面打开域 B；A 插件请求 `wm_open` 到域 A。A primitive 被 lease 正确拒绝，却先绑定域 A gate，随后因当前 URL 属于域 B而清空 B 的实际页面。denied 操作不应影响别的会话宿主。
- 已回喂 root。可见 owner admission 必须包住整个 scope 的 bind/operation/final-check/reset，而非只包 primitive；继续复用同一租约，不制造假后台 WebView。

### 4. Preparing 热重挂时真实停止按钮被禁用且 stop handler 没有 ID

- `ConversationRunService.ets:339–341` (`reserve`) 同步发布 `sending=true`、新 runId 与 `conversation=null`；加载/水合期间该形态是合同允许的真实 snapshot。
- 新 Page 在 `ChatPage.ets:1083–1094` 热重挂分支直接 return；`:649–653` 只在 full 非 null 时置 `conversationLoaded=true`，所以 `ChatInput:4380` 的 `!conversationLoaded` 禁用仍成立。
- `ChatInput.ets:314/346` 让 stop 也受该 disabled 门影响；`ChatPage.ets:1239–1242` (`requestStop`) 还要求 `this.conversation !== null`，即便已有绑定的 conversationId/runId 也不调用 service.cancel。
- **具体触发**：离页后返回尚在慢 prepare/MCP 初始化的运行，页面显示运行状态，却无法按停止键取消；无需等待 provider 或 full canonical 就应能取消该实际 run。
- 已回喂 root。停止使用已绑定的两个 ID；加载门可以继续限制内容编辑，但不能阻断实际 sending run 的停止。

## 其余本轮核对结果与边界

- `aboutToDisappear`/`onPageHide` 实际只 detach 页面订阅、导航 collector、browser binding 与 UI timers，没有调用 abort、run save 或 native global interrupt。
- Page subscribe 即时回放；冷读取回调有 pageAlive/service ownership 门。source 已补热会话 `nodeId`/`initialMessageIndex` 定位；full canonical 与尾窗分离，没有让 engine 只收到分页 rows。
- 普通 send 的 `historyCount + 1` 起点固定，checkpoint 增长不会重新决定 streamingTail 起点；上面 P2 是 resume/regenerate 的实际区别。
- 页面 run completion catch 核对 pageAlive/pageVisible/conversationId/runId；服务 raw/visual save 核对 record，publish 核 runId。title/suggestion 的辅助字段经同 session mutation queue 条件合并。没有发现旧 run callback 直接覆盖另一个 ID canonical 的新 P1/P2。
- `JsSandboxHost()` 在 Page build 实际存在，seed `javascript_engine` 门经 snapshot 驱动；WebMount 使用实际 `WebMountSurface`/controller attach/detach，按需 hook 也绑定到可见 Page。不能据只读源宣称跨页 WebView 存活或设备 PASS。
- DeepRead 导航 collector 仅同 ID 可见 Page 绑定并在 hide/disappear 解除；普通 snapshot listener 不代替导航 collector。FeishuDocs 工具实际走 HTTP Client，不属于 WebView 操作，未误报它的名称过滤。

本报告只列以上四项有实际调用链证据的 P2。主 agent 正在并行收尾，发现项已即时回喂；修复后的源/必要验证结论应另行附加，不将当前截图或设备 gate 宣称通过。

## Root 修复后的 affected 源码复核

仅重读上述四项的实际调用链和 root 指定的热返回/新会话补接线，没有新增测试矩阵、运行测试/lint/build/sign、操作设备或修改产品源码。

1. **显示重复/锚点丢失：关闭。** `ConversationRunService.ets:507–542` 固定 `visualConversation`；user regenerate 把显示 prefix 截到目标 user，同步更新 history count、转为普通活动 tail，旧后续节点不再进入 rows。assistant regenerate 保留显示 base 的旧目标和后续节点，不从中途 placeholder canonical 重建显示锚点。`:459–467` 按固定目标索引拆分替换消息与新增 tail，checkpoint 保存不改变显示 base；完成 `:627` 恢复实际 latest。`ChatPage.ets:608–614` 使用 visual base，`:3689–3700` 通过 `displayedRowMessage` 在原行显示活动消息，原目标下的第二个 `AgentMessageRow` 已删除，复制也消费实际显示消息。root 已报告原 user case 红→绿；本 agent 只核源码，未重复运行。

2. **分支/删除 stale canonical：关闭。** `ChatPage.ets:1754–1764/2559–2582` 不再直接保存旧 Page Conversation；各自通过 `mutateStoredConversation` 的 latest callback 再检查同 ID busy。服务 `:427–438` 同 writeTail 内读取最新、await save、apply 到 canonical 并 emit，热回放不会保留旧 selectIndex/删除前消息。确认期间已开始 run 时 callback 拒绝。`onPageShow` 实际调用 `attachConversationRun(id,true)`；`:558–574` 仅在没有服务 canonical owner 时重新读取仓库，回调还检查可见性、相同 ID、service ownership。`conversationDeleted` DTO 分支 `:598–603` 清实际 rows/会话；未发送新会话 seed 保留在 service，`aboutToDisappear:509` 才调用 guarded discard，不丢子页面返回期间的 fresh seed。

3. **Plugin scope 跨页面 bind/reset：关闭。** `PluginWebMountGate.ets:23–25` 在任何 URL gate bind 前取得并校验同 conversation lease；`:37` 在实际 operation await 后重验，再读取 URL/复位；`:45` 在异常清理的 reset 前也重验，失去 owner 不会清另一页。`ConversationBrowserGuard.ets:37–51` 返回的检查闭包固定旧 lease/revision，A→B→A 也不会把旧 scope 当成新 owner。`ConversationRunTools.ets:255/261` 每次 catalog 构造和 `AppContainer.ets:832` parent 子代理 adapter 都传实际 conversationId，`EntryPluginRuntime` 再传入 WebMount port。中心 prepare-only adapter 仍不执行 primitive；没有把手动页面 prepare 当浏览器 scope execution。

4. **Preparing 重挂停止：关闭。** `ChatPage.ets:1192–1195` 使用 `runConversationId/currentRunId` 调用 service.cancel，不要求 full Conversation；`:4298` 在 sending 时不再把 ChatInput 停止按钮置 disabled。subscribe 即时回放提前设置实际 runId/sending，service.cancel 仍匹配两个 ID 和真实 record/controller，慢 prepare 也可取消。

结论只限这四项及指定补接线的源码闭环，没有以源静态检查替代最终编译、真实 WebView/点击状态或设备截图。C offline，视觉/运行设备 gate 保持未验证。
