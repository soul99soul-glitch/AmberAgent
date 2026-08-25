# Parity L 级实施计划（2026-08-25，真机反馈驱动第二批）

复核结论：F3 DeepRead 三件套 / Markdown 链接 / ContextRing 未接线 / reasoning 自动折叠 / slash 挤压 均真阳性；user action footer 为有意适配（Phase 3 内核查长按菜单后定）。

## Phase 1 — DeepRead 生成管线接线（最大功能断链）
1a. `OpenAiCompatibleAiClient` 补全 AiClient 契约：序列化 tools + assistant tool_calls + role=tool 消息（tool_call_id）；支持 stream:true 走既有 openai_stream 解析；消费 params.stream/maxSteps（单步请求；工具循环由 deepread 侧 collectRun/run_manager 驱动——按域设计 0.1：循环在 runner 侧）。
1b. `RdbRepository` 真实 relationalStore 实现：建表 deepread_cache（topicId 主键 + output_json + phase/attempt/lastError + 时间戳），实现 get/getByUrl/getByTitle/upsert/updatePhase/updateSectionState/incrementAttempt/listHistory/delete/observe（轮询或内存通知）；进程内写队列串行。
1c. `DeepReadArticlePage` 接线：getScheduler()（AppContainer 装配 SchedulerDeps：repository+prefetcher+aiClient+fileStore+modelRegistry）→ run(topicId,title) → observe 进度 → 渲染 DeepReadOutput markdown（复用 MarkdownText）→ 完成写库；重读=force run；取消=页面离场 abort。
1d. BoardPage 热榜点击/HistoryPage 历史点击进入同链路；DeepReadHistoryPage 有了真实 listHistory 自然复活。
验收：真机可用（或模拟器），测试+构建绿。

## Phase 2 — Markdown 交互基础
- 链接可点：点击弹菜单（复制链接/浏览器打开——@ohos ability StartAbility ACTION_VIEW_DATA 或 https 隐式）；长按复制。
- 代码块操作栏：语言标签 + 复制按钮（clipboardKit），保持横向滚动。
- 图片点击预览：全屏 overlay（黑底+缩放+点闭），复用现有图片预览模式（ChatPage 已有图片预览 overlay 则复用）。

## Phase 3 — 聊天页 UX 对齐
- reasoning：autoCloseThinking 落地（流结束后 800ms 折叠 preview）+ 耗时 ticker（250ms 更新 duration）；设置驱动 showThinkingContent。
- ContextRing/ChatInputUsage 激活：替换 ChatPage 内联 5 段 meter（保持既有数据源 estimated/contextWindow/ratio/compactActive）。
- slash 面板 Popup 化：改 overlay（不挤压输入区高度）。
- user action footer：核查长按菜单覆盖（编辑/复制/收藏/删除分支/重新生成）；若覆盖完整 → footer 隐藏对齐 Android；否则保留但注明。
- drawer 尺寸对齐（336dp 宽/24dp 右圆角）。

## Phase 4 — 消息搜索
- SearchPage 增加"会话内容"页签或结果组：repository.search 已有标题搜索；扩展消息级：加载会话→currentMessages 文本匹配（精度优先，RDB LIKE messages blob 不做——会话量级可接受内存过滤+标题 LIKE 粗筛）。
- 结果点击 → ChatPage 定位到消息（ChatPage 已有首屏定位 nodeId 参数）。

## Phase 5 — 视觉系统批次
- TypeScale 契约：tokens 增 textStyles（size/weight/lineHeight/family），先落 Index/Setting/ChatPage 头部（不全站替换，避免大爆炸）。
- Sheet/Dialog 统一：ConfirmDialog/ShareSheet 加 OVERLAY 遮罩+居中/底部动画（复用既有 sheet 模式）。
- 主题：EntryAbility 先读 KV 再设窗口背景（消启动闪帧）；SettingDisplay 增"跟随系统"选项（@StorageProp darkMode 联动 ConfigurationConstant）。
- DotLoading 双实现合一（删静态版，保留动画版）。

## Phase 6 — 次级收尾
- QuickMessages 接入 Chat 输入（斜杠面板 quick 组或输入区快捷条——按 Android 语义选最小改动）。
- 系统分享：若 @ohos ability 可发 ACTION_SELECT（分享面板）则接；否则保持复制+toast 并注明。
- 多选导出/附件 picker/真 TTS：评估后若超本轮范围，诚实登记 backlog（不过度设计）。
- Cron 执行器：保持禁用 + 页面已诚实；登记为平台基础设施项（WorkSchedulerExtensionAbility 专项），不在本轮。

每 phase：实现（子代理/主会话）→ 构建+测试绿 → 独立 subagent 评审（逻辑闭环/调用链/UI 细节错位对齐边距）→ 修复发现 → 下一 phase。
