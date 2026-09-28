# E7 独立 UI / 调用链只读审查

2026-09-28。只读五个 Memory 页面、实际 MemoryTopicOps/MemoryStore/MemoryDocumentStore、domain 文档 DTO/投影，以及根 main_pages/EntryAbility 接线。加载 harmony-arkts skill、E7 contract/design。仅写本报告；没有修改产品或测试，没有运行测试、lint、构建、签名或设备。源码审查不能替代设备视觉验收。

## 具体发现（已即时发 C owner 与 root）

1. **P2：可见核心记忆列表不响应源 revision。** MemoryPage `:31–74` 没有 memoryRevision 订阅；另外四页都有。实际触发：E6 后台会话完成后 auxiliary 核心提炼保存记录，MemoryStore `:125–126` 成功写源并发布 memoryRevision，但仍可见的 MemoryPage 计数和列表不重新读取，直到 hide/show。窄修为现有生命周期 guard 下的 revision reload；编辑弹层开启时应保留 saveDraft `:120/125` 的 loadSeq 回执，否则自身写入先发 revision→reload 改 seq→成功回调被跳过，会留下已成功新增的 draft 再次提交。已提示 owner 可编辑期间延后，closeDraft/成功保存再 reload。
2. **P2：旧核心 CRUD 点击目标小于本期 44vp 要求。** MemoryPage `:217–228` 的 pin/edit/delete 分别在 14/15vp 图标或文字上直接 onClick，没有 44vp 容器。Icon 实际 `:181–196` 只按 iconSize 设置宽高；margin 不增加足够的点击目标。建议只包 44vp 动作容器，保留原字形尺寸与业务调用，避免重排整页。
3. **P2：来源按钮声称定位消息，实际定位当前分支的节点。** MemoryRecordPage `:79–80/140–143` 在 node.messages 任一 alternative 匹配 sourceMessageId 后仅传 nodeId。ChatPage `:1032/1104–1119` 只定位节点，展示 node.selectIndex 对应的当前消息。触发：提炼记忆来自旧助手消息，随后该节点 regenerate 并选择新分支，再点“定位来源消息”，会看到同一节点的新分支。合同允许真实 node 定位；窄修建议将按钮与说明改成“来源消息所在节点 / 当前显示分支可能不同”，不为此写仓库 selectIndex 或新增引擎。

4. **P2：只打开主题列表时，到期成员仍显示为有效主题。** MemoryStore `:509` 的 snapshotForTopics 只 guarded raw read，MemoryTopicsPage `:61–65/204–205` 直接消费未剪枝 memberIds/archived。实际触发：两成员主题的一条源刚超过 expiresAt、没有后续源写入且默认模型/写许可关闭，打开主题页仍显示“2 条源记忆 · 自动维护”和旧摘要；同页有效源计数已经只有 1。prune 目前仅在 saveRecords `:124` 与 refreshDocuments `:500–504` 调用，必须进入文档读取/刷新才更新状态。已同 B owner 核实这是当前缺口并即时告 root。最窄建议是 snapshot 返回现有 pure prune 的当前视图，不为 UI 读操作写源或绕过 topicWrite 门。

发现对应的是审查时源码位置，修后位置会变化；四项均已由现有 owner 窄修，下面记录当前源码的精准差异复查结果。

## 调用链已核对

- MemoryPage 新入口→MemoryTopicsPage/MemoryDocumentPage；topicId、memoryId 用正整数 ID 的 string 参数传递，目标页解析并校验；document relativePath 来自真实 descriptor，目标页先 descriptor 精确匹配，再读取。main_pages 已声明四个新页面。
- Topic detail 以 memberIds 精确映射源记录；成员缺失、归档/过期、topic 缺失均有实际状态。topic 无正文编辑或普通 pin 操作，显式删除仅调 deleteTopic，不删除成员；源编辑器再次拒绝 kind=topic。派生文档没有保存/编辑入口。
- 来源按钮再次读真实 conversation；已删 conversation 不 push 虚构路由，message 找不到 node 有明确错误。旧分支身份的措辞问题单独列为发现 3。
- 主题 run 订阅返回退订；页面 hide 退订，return 重新订阅并读取；各新详情页 revision/return reload，loadSeq 拒绝隐藏页面的旧读取完成。Record 编辑保留 dirty draft，成功操作回读 source；source JSON 保存成功、文档失败通过 MemoryDocumentRefreshError message 与 sourceSaved 分开，旧 MemoryPage 新增成功后文档失败会关闭已保存 draft，避免再次新增。
- 手动 run 调 run(true)，按钮取消调 coordinator.cancel；missing_model/skipped/stale/failed/documentError 有 UI 展示。平台 re-read feature/write gate，并在同 source queue 提交前再次检查；实际 provider signal、120s 默认超时关闭写 gate。UI 没有声称 hard transport termination。EntryAbility foreground/background 已薄接线，主题作业不进入 Chat aggregate。
- document list/read 经过源 guarded read 和 refreshDocuments；read error 不用空源清文档；相同正文不重写文件。页面同时提供显式文档刷新/重读。页面将常规生成索引链接转普通文字，并提供真实 descriptor 按钮导航；这些按钮不把 topics 相对路径交给外部路由。

## UI 源码边界

新四页使用 V1 State/StorageProp、SSHUIColors 快照及现有 CenteredTitleBar，左右 16vp，底部 16+navBarHeight，标准标题栏使用 statusBarHeight；主要动作 44vp，列表卡 minHeight52。长详情标题、路径、ID 与正文有宽度/换行，列表预览有明确截断；editor 使用原 KeyboardAvoidMode.RESIZE 并在销毁恢复 OFFSET。旧 MemoryPage 的动作目标不足已由 C 修正，源码尺寸复核如下。

共享标题栏/卡片、Markdown 字体排版、窄屏长文和键盘位置、亮暗主题的实际视觉效果仍需要 C 的截图验收；当前设备离线，**无视觉 PASS**。本审查也不声称真实 provider、SDK RDB/文件写入或整包编译通过。

## 修后精准复查

- **发现 1 已关闭（源码）**：MemoryPage `:36/57–59` 订阅实际 memoryRevision，只在 alive/visible、draft=null、!memorySaving 时 reload。togglePin/saveDraft/removeMemory `:95–111/128–148/164–174` 在 await 前同步置 memorySaving，保护 Store publish revision→Promise settle 期间的 loadSeq 回执；finally 解除标记并回读。saveDraft 的真实成功及 sourceSaved 文档失败仍关闭已提交草稿。closeDraft `:122–126` 关闭未提交草稿后回读暂缓的外部 revision。没有新调度器或额外 Store 协议。
- **发现 2 已关闭（源码）**：MemoryPage `:238–252` 将 pin/edit/delete onClick 放在明确 44×44vp 的 Row 内，glyph 尺寸保持；back `:206–207`、新增 `:294–296`、草稿保存/取消 `:272–275` 同样有 44vp 高度。只检查声明和事件绑定，不把尺寸声明当作截图验收。
- **发现 3 已关闭（源码）**：MemoryRecordPage `:205–209` 明确“定位来源消息所在节点”及“当前选中的分支可能与此来源消息不同”；原真实 sourceMessageId→nodeId 调用保持，未写 selectIndex 或改变 Chat 展示分支。
- **发现 4 已关闭（源码及已有 focused 回执）**：MemoryStore `:509–512` 保持原 guarded enqueue 读取，在队列内用当前 Date.now() 调用现有 pure prune，只返回可用性视图。该函数不写 JSON、同步文档或发 revision；所有普通源记录仍保留，失效 topic 的 archived/memberIds 以当前时间投影。既有唯一平台 group 的 `memory_topic_store.test.cjs:152–158` 使用真实源文件验证 archived=true、memberIds=[]、普通源数量不变、原始 JSON 逐字不变。已读 owner 留存 `/tmp/e7-snapshot-expiry-red.log` 0/1 与 `/tmp/e7-platform-final-green.log` 1/1；没有另行执行测试。
- **root/B 提交时间修正精准复核**：MemoryStore `:518–521` 在 beforeCommit await 返回后固定 commitNow=Math.max(now, Date.now())，同一时间用于 apply 与 unchanged syncProjection。避免队列/模型等待期间源已过期，建议虽被拒绝却仍用旧时间重新生成有效索引。已读既有 `memory_topic_store.test.cjs:142–150` 真实 fs 断言及 `/tmp/e7-commit-clock-red.log` 0/1 → `/tmp/e7-commit-clock-green.log` 1/1；最终同组绿回执覆盖该断言。不扩展测试组或设备。

本轮只读复核，没有执行任何测试、lint、构建或设备操作。C owner 报告受影响两文件窄 lint 通过，证据位置见 `E7-memory-ui.md`；本报告不将其描述成独立执行结果。上述四项具体问题全部关闭，追加提交时间差异已核证；限定审查范围无仍待修复的具体 P1/P2。离线设备视觉 gate 保留。
