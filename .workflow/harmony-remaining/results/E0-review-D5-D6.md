# E0 补审：D5 / D6

审查基准：D5 `b167cf276`，D6 `7909b5890`；审查当前工作树中的对应链路。已读取 Harmony ArkTS skill 与全量补齐计划。只读业务代码；没有运行构建、签名、安装或截图。

## A / B / C 结论

- **A 逻辑闭环：FAIL。** Jev 多候选题没有候选绑定；动态席位可能全部被容量裁剪却仍显示为已选择；活动条把失败/取消显示为成功。
- **B 调用链：FAIL。** 设置→持久化→模型 override 消费、动态席位→validator→runner 主链存在；D6 新网络工具丢弃取消信号，飞书工具没有沿用 WebMount 实时许可门。
- **C UI：FAIL（静态），视觉未验。** 新模型选项行高度 36，与计划规定 52 不符；完成态语义错误。没有截图，不能声称像素对齐、深色主题、长模型名布局已验证。

## 可证明的问题

### 1. [P1 / D5] Jev noul 批量题缺少候选绑定

- 位置：`harmony/entry/src/main/ets/platform_impl/JevSupport.ets:194-204`，答案回绑 `:208-211`。
- 触发：启用 ACTIVE，记忆召回或 recentChats 至少两个候选。
- 证据：题键是 `c0/c1/...`，每题 instructions / trueCriteria / falseCriteria 完全相同；state.candidates 按记忆 ID 或会话 UUID 存储，没有 `c0 → 实际 ID` 关系。`harmony/chat/src/main/ets/chat/jev_client.ts:20-28,49-52` 只逐字编码题目，不增加关系。响应却被按 keys[idx] 强行回绑，导致题目无法表达正在评估哪一条记忆/上下文。
- 最小修复：每题明确引用 `keys[idx]` 对应的候选（题目文字包含候选键，或 state 使用同一 cN 键并在题目明确引用），保留现有一次请求及本地失败回退。
- 窄回归：两个内容相反的候选，断言每题能唯一定位自己的候选，且两个概率回写到正确 ID；验证 SHADOW 保持原序。

### 2. [P1 / D6] 飞书工具与 signed_fetch 丢弃取消信号，停止后仍可发起写请求

- 位置：`harmony/entry/src/main/ets/platform_impl/FeishuDocsTools.ets:49,77,85-93,259-286`；`harmony/entry/src/main/ets/platform_impl/WebMountTools.ets:717,743-746`。
- 触发：执行 `feishu_docs_append`，根块 GET 或 token refresh 尚未结束时停止生成/离开聊天页；GET 返回后继续 POST。`wm_signed_fetch` 的请求也无法跟随停止取消。
- 证据：dispatcher 在 `harmony/chat/src/main/ets/chat/tool_dispatcher.ts:428-430` 正确传入 signal，但这些 execute 未声明/消费它，HTTP 全部使用 `signal: undefined`。运行时源码转译 + 假 HTTP 的定序复现结果：`GET(signalBound=false, aborted=false)` 后置 `signal.aborted=true`，随后仍收到 `POST(signalBound=false, aborted=true)`。
- 最小修复：仅在新网络工具 execute → resolveAccessToken/feishuFetch → HTTP 传递原 signal；每个后续请求前检查取消，抛 AbortError。不要声称能撤销已经到达服务端的写入。
- 窄回归：把 append 的根块 GET 或 refresh 挂起，abort 后释放；断言后续 POST 未发生。signed_fetch 检查 HTTP 收到同一个 signal。

### 3. [P1 / D6] 飞书文档族执行时不复核 WebMount 许可

- 位置：`harmony/entry/src/main/ets/pages/ChatPage.ets:3721-3725`；`harmony/entry/src/main/ets/platform_impl/FeishuDocsTools.ets:114-123,237-244,259-265`。
- 触发：工具集在全局 WebMount 开启时装配；执行前全局被关闭，且本次助手没有单独勾选 webmount。已装配的飞书工具仍使用 OAuth token 发请求。
- 证据：ChatPage 仅装配时判断许可，`createFeishuDocsTools()` 不接受 assistantEnabled；飞书任何 execute 都不读 `webmount_global_enabled`。对比 `WebMountTools.ets:100-106` 的真实规则是 `global || assistantEnabled`，每次执行复核。上面的运行时复现把 KV 全局值设为 false，仍完成 GET/POST，并确认全局开关键从未被读取。
- 最小修复：飞书装配传入本轮助手授权，执行前复核相同 OR 规则。**仅关闭全局但助手特批仍有效时应继续允许**；两者都为 false 时拒绝。
- 窄回归：覆盖 global/assistant 的四种组合；工具创建后改变 global，再执行，检验实时结果。

### 4. [P2 / D5] 动态席位显示已选，实际运行可能全部被静默裁剪

- 位置：`harmony/entry/src/main/ets/pages/CouncilPage.ets:140,184-185,313-328`；`harmony/deepread/src/main/ets/council/validator.ts:75-96`。
- 触发：默认 maxSeats=8，已有 3 核心席和 5 个视角席，再 AI 生成 2–3 个动态席。UI 展示可删除的动态席 chip；开始议会时这些动态席全部排在末尾被 slice(0,8) 去掉。
- 证据：`models.ts:7` 默认上限 8；validator 的追加顺序为核心→defaultSeats→extraLens→extraSeats，最后裁剪。CouncilPage 直接把未归约的 generatedSeats 显示并提交，没有容量提示或有效席位列表。
- 最小修复：基于同一个 buildDefaultSeats 计算实际席位，在生成/开始时明确剩余席位容量并阻止显示未被纳入的动态席；无需扩展 runner 架构。
- 窄回归：已有 8 个有效席位时追加动态席，UI 不得宣称其将参与；保留核心席、role 去重与现有 maxSeats 规则。

### 5. [P2 / D5] 活动条把 failed / cancelled 标为绿色“已完成”

- 位置：`harmony/entry/src/main/ets/components/SubAgentActivityStrip.ets:40-46,77-89`；`harmony/chat/src/main/ets/chat/subagent_manager.ts:505-516`。
- 触发：当前最后一个子代理失败或被用户取消。
- 证据：listActive 只返回 running 类状态；空列表被一律解释成 completed，保留的是上次 running 快照，随后固定绿色 check 与“子代理已完成”。真正终态可以通过 manager.snapshot(runId) 获得，但未读取。
- 最小修复：进入延时显示时按保留 runId 获取实际终态；分别显示完成/失败/已取消，保留四秒收起机制。
- 窄回归：completed、failed、cancelled 三种终态分别显示对应文案/状态颜色，四秒后消失，新 run 到来重置完成计时。

### 6. [P2 / D5] 动态席位生成不支持页面退出取消及过期结果隔离

- 位置：`harmony/entry/src/main/ets/pages/CouncilPage.ets:76-79,107-118,140-145`。
- 触发：点 AI 生成后返回上一页；或生成中开始议会后离开本页。
- 证据：新调用显式 `signal: null`，aboutToDisappear 只改变 pageAlive/loadToken；返回结果、错误与 finally 不检查该生命周期令牌，继续修改已离开页面的状态。既有 loadModels 已有相应令牌守卫，说明本页具有明确的退出边界。
- 最小修复：动态席位生成持有独立 abort controller；退出时 abort，结果按当前页面/调用令牌提交。生成中开始议会应等待/禁用，避免 UI 暗示待生成席位会参与。
- 窄回归：悬挂 generate，退出后释放；不写 generatedSeats/error，信号已 aborted；再入页允许新调用。

### 7. [P2 / C] 子代理模型选项行与既定点击高度不一致

- 位置：`harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets:583`。
- 触发：展开任一角色模型选择器。
- 证据：新行明确 `minHeight: 36`，全量补齐计划的每 phase C 清单规定 CardRow minH52。此处是完整宽度单选交互行，不是非交互说明文本。
- 最小修复：提高到 52 或复用现有 CardRow 的单选布局；保留模型文字单行省略与 check 标记。
- 验证：窄屏/长 provider/model 名 + 深色主题截图，核验每行点击区域与左右间距。当前没有截图，未确认具体文字溢出。

## 未发现的问题与验证边界

- 子代理模型选择→overrides Map→settings port→refreshSubAgentSetting→subagentApplyOverride→resolveTaskChatModelChoice→generation 的主链存在。
- wm_signed_fetch 非 GET 审批由 `tool_policy.ts:510-518` 按参数执行；不能仅因 makeAgentTool 没写 needsApproval 就判断绕过审批。
- 飞书 create/append 被 `tool_policy.ts:226-239,398-409,531-534` 的变更名称与 fail-closed 规则捕获；本轮没有证据证明审批绕过。
- 记忆污染检测的 search_web / scrape_web / mcp__ / mcp_call_tool → extraction_skipped 路径存在。检查没有扩展为未经范围要求的“所有工具均污染”。
- 现有窄测试：chat `memory_extractor / memory_recall_store / subagent_runner / webmount_oauth / tool_policy` **92/92 通过**；deepread `council_validator / council_manager` **30/30 通过**。现有测试未覆盖上述 entry 网络/页面异步缺陷，不能据此视作闭环。
- 运行时定序复现使用当前 FeishuDocsTools 源码转译与假 token/HTTP，不接真实供应商、不产生外部写入。

## 修复后独立复审（E0 当前工作树）

上面的 FAIL 为初审历史结论；以下复审只检查针对这些问题的实际改动，没有重复扩展范围。

**A / B：PASS；C：静态问题已关闭，设备视觉仍未验。** 初审七项均已在代码层面关闭；复审追加发现的 Council 禁用门也已最小修复。本 reviewer 没有执行构建/安装/截图，不能据此宣称 UI 视觉验收通过。

- **问题 1 关闭**：`JevSupport.ets:194-198` 调用生产 `buildJevNoulBatch` / `readJevNoulBatchProbabilities`；`chat/jev_client.ts:25-50` 每道 cN 明确引用自己的原候选键，JSON 转义及回绑顺序一致。ChatPage 中 SHADOW 的既有旁路仍在，未改变筛选阈值和失败回退。
- **问题 2 关闭**：`FeishuDocsTools.ets:72,89,111,155,187,209` 六 execute 均消费 signal。`FeishuDocsClient` 的 token refresh、各 fetch、append 根块 GET/最终 POST 接同一信号；真实生产 `webmount_request.ts:13-30` 在许可 await 前后、响应后检查取消，并在 HTTP 层传入信号。`WebMountTools.ets:719,743-746` 的 signed_fetch 使用同一 helper。
- **问题 3 关闭**：`ChatPage.ets:3726` 传入助手 webmount 特批布尔值；`FeishuDocsTools.ets:59-64` 许可回调直接复用 `syncEnabled`；`WebMountTools.ets:100-106` 仍是 global || assistantEnabled。每次真实请求前均检查该回调，全局关闭但助手特批有效的路径未被误拦，两者都 false 会拒绝。
- **问题 4 关闭**：`validator.ts:106-118` 的 `resolveCouncilDynamicSeats` 直接复用 runner 的 `buildDefaultSeats`，只返回实际有效 seatId。CouncilPage 设置读取、视角切换、生成结果和发起前均使用它，显示实际剩余容量；满容量不发 AI 请求、不显示被裁剪动态席。初审中的 8 席已满案例有生产 helper 回归覆盖。
- **问题 5 关闭**：`SubAgentActivityStrip.ets:43-54` 进入延时状态时按原 runId 获取真实 snapshot；显示使用 `subAgentActivityPresentation` 的 completed / failed / cancelled 等文案及 success / error / warning 色义。仍保留四秒收起及新 active 归零。设备侧计时与颜色视觉待主任务。
- **问题 6 关闭**：CouncilPage 的独立生成 controller 在 `aboutToDisappear` abort 并递增调用 token；设置 await、生成返回、catch/finally 有当前页面/调用守卫；生成中开始按钮及 startCouncil 同步路径都拒绝发起。
- **问题 7 关闭**：`SettingSubAgentPage.ets:583` 已为 minHeight 52，原单行省略/check 保留；窄屏、长模型名及实际点击区域仍需设备截图。
- **复审追加禁用门关闭**：曾发现 CouncilPage 读取 base.enabled 却仍可调用 AI 生成。当前 `generateDynamicSeats` 在 base 读取与生命周期检查后立即拒绝 `!base.enabled`，文案与 startCouncil 相同；AI 按钮灰态、enabled 和状态提示也消费同一值。无需新增恢复或 fallback 层。

独立重跑实际生产 helper 测试：chat `jev_noul_batch / subagent_activity / webmount_request / webmount_oauth` **19/19 PASS**；deepread `council_validator` **19/19 PASS**，合计 **38/38**。本轮未保留 ETS 源码转译测试或再造页面类测试；页面异步守卫及许可 OR 的宿主接线按当前生产代码审查。

## 暗色设备截图独立目测

主任务提供当前 HAP 的生产页面截图；reviewer 只读取图片，没有操作设备。已目测 `/tmp/e0-subagent-model-picker-dark.jpeg`、`/tmp/e0-subagent-dark.jpeg`、`/tmp/e0-council-run-dark.jpeg`。此前 `e0-council-dark.jpeg` 是设置页，未当作 CouncilPage 运行入口的验收证据。

- **子代理模型选择行 PASS**：三行等高，文字左边线一致、check 靠右对齐，选中“跟随默认”用 accent 区分；长 provider/model 文本保持单行，当前截图没有越出卡片或挤压选中标志。root 报告设备测得 182px / density3.5 = 52vp，与代码 minHeight52 一致。截图中重复 modelId 由 root 的输入 fixture append 误操作造成，不作为产品字符串缺陷。
- **子代理设置基页布局 PASS**：说明、分节、开关、计数步进、超时/预算尾值对齐，当前可见范围未见横向溢出或重叠。顶部截出的半块卡片来自滚动位置，不作为布局缺陷。
- **CouncilPage 可见布局 PASS**：议题、模式和席位卡片左右边线一致；核心席/可选视角席换行正常；动态席位标题与 AI 生成尾项对齐，容量说明没有碰撞。显示剩余 5 个与 8 席上限减 3 个核心席一致，模型库为空反馈可读，AI 生成灰态清楚。**这张图不能证明 persistedSetting.enabled=false 的视觉状态**：图中文字是“还可加入 5 个动态席位”，而最新生产代码关闭设置时应显示“模型议会已在设置中关闭”。可能是截图状态实际启用或 HAP 与最新补丁的时序差异；没有把它当作禁用设置门的设备验收。

### [P2 / C] 暗色下默认角色提示词占位文字不可读

- 位置：`harmony/entry/src/main/ets/pages/SettingSubAgentPage.ets:489-497`。
- 触发：暗色主题，展开 Explorer，systemPrompt override 留空。
- 设备证据：`e0-subagent-model-picker-dark.jpeg` 中默认 “You are Explorer … / === HARD BOUNDARIES ===” 在深灰 TextArea 上呈近黑字，明显比周围文字暗，无法正常阅读。
- 调用证据：默认提示词通过 placeholder=def.systemPrompt 显示；当前只设置 fontColor(INK)，没有 placeholderColor。正文颜色不能给占位文字提供清晰颜色。
- 最小修复：在此 TextArea 增加 `.placeholderColor(INK3)`，仅修该占位文字，不扩主题机制或修改提示词数据。
- 验收：同一展开状态在暗色重新截图；亮色也检查占位文字可读。当前 **C 仍有此一项 FAIL**，其余上述可见布局 PASS；运行中/失败/取消活动条、生成后的动态席 chip 尚未提供设备图。

### 暗色占位字修复复拍：P2 已关闭

独立读取新 HAP 设备图 `/tmp/e0-subagent-placeholder-dark-fixed.jpeg`，并核对 `SettingSubAgentPage.ets:495` 仅增加 `.placeholderColor(INK3)`。默认英文提示由近黑变为可读灰字，与“自定义提示词”说明颜色层级一致；TextArea、模型三行等高、左右边线与选中 check 均保持原布局，没有引入错位或溢出。

此 P2 的暗色缺陷已关闭。当前提供的暗色截图可见布局 **C PASS**；亮色尚未提供，本次没有把运行中/失败/取消活动条及生成后 chip 的设备视觉标成已验。

## 最终浅色复拍与 C 收口

独立读取最终 Release 图 `/tmp/e0-subagent-light-fixed.jpeg`：Explorer 默认占位提示为可读灰字；TextArea 内边距、说明与字段左边线、模型列表三行等高、尾部 check 均正常。长模型标签保持单行，未越出卡片；选中“跟随默认”颜色清晰，恢复默认按钮及下方角色分隔没有碰撞。重复 dummy modelId 仍按已知测试输入事实处理，没有发现产品显示映射问题。

**最终结论：A / B PASS；C 已提供设备截图范围 PASS。** 子代理模型选择器及默认提示占位字已完成深浅两色目测；CouncilPage 已提供暗色可见布局通过。初审七项及追加的禁用门、暗色占位字缺陷全部关闭，当前没有可证明的新阻断。运行中/失败/取消活动条、生成后的动态席 chip 和设置关闭提示未提供对应设备图，保留为未验状态，不把这些状态写成已目测。复审结束；本 reviewer 全程未操作设备、未修改生产源码。
