# 小说创作对齐计划(2026-08-30 三轮深挖驱动)

基准:iOS worktree Kotlin 多平台 novel 模块(域 10126 行/22 文件 + UI 21469 行)。
鸿蒙现状:域 1273 行(8 文件)+ UI 1069 行(Projects 264 / Workspace 805)。
骨架已有:项目 CRUD、三页签工作区、discuss/write×2 模式、流式生成、收录、素材建议、上下文装配。
执行纪律同深读对齐:每 Phase 审查(逻辑闭环/调用链/UI 细节)→精准修复→构建+lint+deepread 636 单测→真机截图→提交。

---

## N1 — 地基(模型非流式 / 6 种 Run / 分支与版本域 / 导出 / 快速开始)

1. **模型适配器切非流式**:NovelModelAdapter 弃 fetchStream(深读已实证 NetStack
   挂死),改非流式 fetch + 一次性事件(completed/failed);保留事件端口不变
2. **域:6 种 RunKind**(quick_start/discussion/prose_continuation/prose_whole_chapter/
   polish/regenerate)+ NovelPromptCatalog(照搬 234 行:5 套 system 模板 + 润色哨兵
   `<AMBER_NOVEL_POLISH_COMPLETE>`);context_builder 接受 promptKind 参数
3. **域:分支与版本**(models 补 NovelBranch{active}/ChapterVersion{kind,at}/
   checkpoint;mutations 补 forkBranch/selectBranch/setMainBranch/undoHead/
   renameBranch/saveChapterVersion/restoreChapterVersion;setChapterDiscarded)
4. **持久化**:revision 乐观锁(commit 校验)+ restorePrevious(软删恢复)
5. **UI-Workspace**:分支栏(当前分支/切换/设主/undo)+ 导出 Markdown(剪贴板)+
   快速开始按钮(空项目时 kick)
6. 单测:RunKind→PromptKind 映射、branch fork/select/main、revision 冲突、润色哨兵解析

验收:636+新增单测绿;真机非流式生成一个整章成功。

## N2 — 章节工作流

1. **单章重新生成**(regenerate:校验 head → whole_chapter regeneration 模板)
2. **单章润色**(polish:哨兵协议→剥离→BASIC→STANDARD 质量升级)
3. **批量润色**(逐章队列 + 进度 + 结果面板 + adoptPolish 单章采纳)
4. **章节版本历史**(sheet:版本列表 + kind 徽标 + 恢复)
5. **章节废弃**(废弃标记 + 目录中折叠/删除视觉)
6. **手动编辑同步**(编辑器保存 → syncManualEdits;冲突时以编辑为准+版本存档)
7. **全屏阅读器**(章节沉浸阅读 + A±字号)
8. UI:章节行操作菜单(重新生成/润色/版本/废弃/编辑)

验收:真机走通 生成→编辑→润色→版本→恢复→导出 全生命周期。

## N3 — 讨论与分支深水区

1. **讨论归档蒸馏**:归档 sheet → LLM 蒸馏摘要 → 逐条 decisions 采纳进活资料
2. **设定提案卡**:讨论中 AI 提议设定变更(严格 JSON)→ 提案卡 UI → 采纳/拒绝
3. **收录资格判定**:isCollectHeadCandidate/collectBlockReason(非头消息/已收录/
   空文案禁用 + 理由提示)
4. **ask-user**:讨论模式 AI 可发问(NovelDiscussionAskParser),Composer 显示回答框
5. **InjectionPlanner**:按 token 预算裁剪注入段 + 注入收据(receipt)展示
6. **连续性审计**:runContinuityAudit(全文一致性检查 → 报告卡片)
7. **restorePrevious**:项目删除后恢复入口(列表页"最近删除")

验收:真机走通 归档蒸馏→采纳、提案采纳、ask-user 应答、审计报告。

## 里程碑

- M1(N1):模型可用 + 域地基 — 约 3 天
- M2(N2):章节工作流完整 — 约 4 天
- M3(N3):讨论深水区 — 约 4 天

## 明确不做(登记)

- 模板 Agent 生成/工作台(小说模板非重点,继承深读 P8 模式后置)
- iOS Swift 序列化兼容层(NovelSwiftCompatibleJson,鸿蒙无历史包袱)
- injection 多模式枚举(先落单一 budget 模式,多模式待实际需求)


---

## 执行记录(2026-08-30 当日完成)

N1/N2/N3 全部落地,3 提交:00e6e5a19(N1)/ c180ddf12(N2)/ 5ea9b8437(N3)。

- N1:模型适配器非流式(NetStack 挂死规避)、6 种 Run + PromptCatalog、
  分支/版本/废弃 mutations、revision 乐观锁 + restorePrevious、导出 Markdown
- N2:单章重生成/单章润色(哨兵协议)/版本历史 sheet/章节废弃/操作菜单
- N3:归档蒸馏(接 analyzeChapterSuggestions)/恢复删除入口/收录资格助手

审查修复(部分):@Builder 函数参数推 any → 字符串 action 分发;多次 import
重复/双逗号;menuRow 嵌套 lambda 替换失败 → 手工核对逐处修正。

验证:deepread 641(+5)/ chat 2012 单测全绿;全仓 lint EXIT=0(0 error,
warn 125 ≤ 基线 126);真机安装运行正常。生成 UI 终验待用户手动
(uitest inputText 在 TextArea 不注入,自动化受限)。

## 明确不做(执行确认)

- 模板 Agent 生成/工作台(后置)
- iOS Swift 序列化兼容层(无历史包袱)
- injection 多模式枚举(单一 budget 模式已够)
