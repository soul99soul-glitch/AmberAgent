# E7 记忆主题 / 源记录 / 派生文档 UI

2026-09-28。本 owner 只新增四个页面并修改 `MemoryPage.ets` 的入口与新 Store 行为兼容；没有修改 domain、Store/TopicOps/prefs、DI、routes、EntryAbility 或别人的编辑。遵循已读 harmony-arkts skill、E7 frozen contract 与设计，不新增 CRUD 协议、模型槽、执行器或 mock。

## 路由与真实消费者

| 页面 | 参数 | 实际调用链 |
| --- | --- | --- |
| `pages/MemoryTopicsPage` | 无 | `getMemoryTopicStoreOps().snapshotForTopics` → 真 topic/non-core source 列表；`getMemoryTopicCoordinator().subscribe/state/run(true)/cancel` → 实际运行状态；`loadAgentRuntimeSnapshot/updateMemoryWorkerToggles` → 模型与写入两门；模型入口复用 `ChatTaskModelsPage`。 |
| `pages/MemoryTopicDetailPage` | `topicId:string`，正整数 record ID | guarded snapshot 精确找到 topic 与每个 member ID；只读原摘要和实际 archived 状态；专用 `deleteTopic(id)`，不会删成员；文档路径来自实际 `listDocuments().find(topicId)`，没有猜 slug。 |
| `pages/MemoryRecordPage` | `memoryId:string`，正整数 record ID | guarded snapshot 精确 ID → 真实 content 编辑；既有 `getMemoryLibraryOps().updateContent/deleteMemory` → await → snapshot 回读；topic kind 拒绝普通编辑，提供只读详情入口。 |
| `pages/MemoryDocumentPage` | 无：实际列表；`relativePath:string`：详情 | 实际 `listDocuments` descriptors；只有 descriptor 存在才 `readDocument(relativePath)`，null/失效明确展示；`syncDocuments` 显式重试。 |

`MemoryPage` 保留核心桶的新增/编辑/删除/置顶及自动记忆开关，新增主题/文档入口。旧置顶手抄 MemoryRecord 已改为消费 API 返回的真实完整 record，以保留 topicTitle/memberIds 新字段并避免伪造更新时间。子页返回会回读核心桶。新源写成功但文档 IO 失败时，核心页显示真实 `MemoryDocumentRefreshError.message` 并重新读取；真实 sourceSaved 时关闭已保存的编辑草稿。

## 状态、来源与写入边界

- 整理模型 `dreamModelEnabled` 与允许主题派生写入 `topicWriteEnabled` 是两个实际设置。没有用仅影响召回的 enableLongTermMemory 假造写许可。两门明确说明实际影响，写入许可默认由真实 prefs 决定。
- 状态消费 `MemoryTopicRunState` 的 running/status/error/sourceSaved/documentError/topicCount/staleCount；missing_model、skipped、失败、取消、超时、模型等待期源变化与文档刷新失败分别显示。取消/超时文案根据 sourceSaved，不无条件声称从未写入。
- topic 的 archived 原摘要被明确标为失效；成员逐 ID 展示真实源内容/分类/归档/到期状态。成员缺失显示该 ID 已不存在；普通编辑器不能改 topic。
- 源内容或删除操作即使抛文档同步错误仍回读真实 ID。只有源记录实际回读成功且 content 匹配，才显示已回读保存；回读确认 null 才显示已删除。已保存源不会因文档失败回滚，也不会把派生失败统一称作未保存；原错误保留用于诊断。
- 来源是实际 sourceConversationId/sourceMessageIds。读取真实仓库确认会话存在；每条 messageId 在真实 MessageNode.messages 中定位并传该 node.id 给 ChatPage。点击前再次核存在性；源会话/消息被删除如实标不可用，未编造 conversation、message 或定位参数。无来源会话时仍显示已保存的真实消息 ID，没有伪造可点链接。
- 页面返回与 Store 的真实 `memoryRevision` 变化回读；源页编辑草稿与最新 record 分开，刷新不悄悄覆盖脏草稿。loadSeq/pageAlive/pageVisible 防止已离页或旧请求覆盖当前显示。离开主题页仅退订，取消交给用户操作与真实后台 coordinator，页面不私自停止共用运行。

## 文档与布局

- 原生 `MarkdownText` 只读显示实际文档；生成的 topics/*.md/index.md 相对链接显示为文字，通过真实 descriptor 卡导航，避免共享 renderer 将内部路径交给外部浏览器。完整原始 Markdown 可展开复制，显示转换不写回内容。
- 主题详情使用真实 descriptor 关联文档；归档主题/移除源后旧文件缺失有实际空态，文档错误提供显式刷新。没有任意磁盘路径输入或第二份记忆真相。
- 四新页复用 CenteredTitleBar/CardGroup 与全 token 包装 SSHUIColors，V1 @State + theme StorageProp/watch；builder lambda 只调用 @Builder，可变页面状态由 builder 直接读。
- 左右 16vp、顶部 shared 安全区、底部 16vp + 实际 navBarHeight、44vp 按钮/52vp以上列表目标、Top 起始 Scroll。长 title/content/path/IDs 换行或有限预览省略；实际全文/完整路径可读可复制。源编辑沿已有 KeyboardAvoidMode.RESIZE API，不加新 IME 控制器。
- 每新文件 165–246 行，合计约 810 行；没有新增 UI 测试或框架。

## 验证与未验证

- 五文件窄 lint：`/tmp/e7-memory-ui-lint.log`，退出 0，No defects found。
- 源页最后将回读成功边界显式化，仅重验该文件：`/tmp/e7-memory-record-lint.log`，退出 0，No defects found；未重跑五文件或 domain group。
- 初次 lint 因工具要写 Huawei 安装目录的日志而 EPERM，授权重试同一窄命令完成；不是产品错误，也没有改工具链配置。
- 没有执行测试代码、build、sign、安装、模拟器或真机。root 统一必要类型/编译与独立 source review；C offline，亮暗/IME/点击/实际渲染设备 gate 明确保留，不能据 lint 宣称通过。

## 独立 B 审查局部收口

- 核心页新增真实 memoryRevision watch，可见且无编辑草稿/无本页写入时重新读取。实际 Store 在写入 Promise 返回前发布 revision，故本页写入期间暂缓 watch，settle 后回读；避免新增成功 ack 被新 loadSeq 跳过而留草稿重复新增，也保留 sourceSaved 文档失败的真实错误。关闭未提交草稿后立即回读外部变更。
- 旧核心记忆 pin/edit/delete glyph 保持原尺寸，以 44×44vp Row 提供点击区域；back/add/草稿保存取消也达到 44vp。未重排整页或改核心 CRUD 协议。
- 来源按钮改为“定位来源消息所在节点”，明确会话展示该节点当前选中分支，可能与源消息不同。真实 messageId 仍映射 nodeId；不擅自更改仓库 selectIndex 或创建 branch 展示协议。
- 仅复验两个受影响页面，日志 `/tmp/e7-memory-review-fixes-lint.log`，退出 0，No defects found；根统一编译与设备 gate 不变。
