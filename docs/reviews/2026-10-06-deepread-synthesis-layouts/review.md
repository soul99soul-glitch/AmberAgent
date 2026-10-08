# iOS 新生成版式迁移至鸿蒙

本轮范围：将当前 DeepRead iOS 的自动选择、速览简报、问答解读、观点交锋、时间线报道、评测汇总移至鸿蒙独立深度阅读。既有 8 种阅读样式与 4 种杂志编排继续保留。未提交、推送或发布；保留任务开始前的未提交及未跟踪内容。

## 实现

- 设置与创建阅读面板共用生成模板目录；具体模板在创建时捕获，历史文章保留模板快照。
- 模板复用真实资料收集、AI client、运行器、取消信号与 Repository。具体模板整稿生成；自动模板先选择，并在写正文前保存选择结果。选择普通杂志或无法解析选择时进入既有杂志流程。
- 新正文使用独立 `templateArticle` 数据结构，保存问题与回答、阵营与引述、事件与转折、共识分歧、规格与评分，不伪造传统四章节完成状态。RDB 更新与冷读保留数据。
- 已有经典部分稿继续经典流程；模板重试按整稿处理；强制重生成失败保留原完整文章。
- HTML、原生 Web 恢复显示、Markdown、TXT 共用完整结构；PDF 复用 HTML 渲染入口。TXT 使用现有 Markdown 转纯文本函数；立场标签在导出及原生恢复中保留。
- Aa 的“版式与样式”打开真正的外观设置。文章背景与工具栏跟随阅读画布。
- 纯 `synthesis_article.ts` 集中文章类型、完成判定与 Markdown formatter；读取端不会加载生成提示与解析依赖。

## 验证

- 最终 `npm run test:harmony`：4427 通过，0 失败。涵盖取消、选择持久化、重试、缓存、旧稿保留、RDB phase 更新/冷读、导出、HTML 字段与引用、安全 Markdown、路由。
- 本地 HTTP/SSE 协议集成通过：使用 MiMo v2.6 Flash 配置，经真正 AiClient/RunManager 解析分片最终 JSON，并与 `reasoning_content` 分开；这是本地模拟服务器，并非 Xiaomi 实际服务调用。
- ArkTS 静态检查：本轮源文件检查无 error，有既有性能提示 warning；不是零警告。
- 最终独立应用完整构建成功（55.634 秒），签名摘要校验通过。
- 最终 HAP 已在隔离模拟器覆盖安装并成功启动；移除临时种子注入后，冷启动仍可读取保存的评测汇总稿。
- 模拟器实际截图检查五种模板的正文、来源、表格、立场卡片及时间线，包含深色问答。实际点击 Aa 验证外观路由，设置页生成模板选择器显示全部选项。
- 最终包在 660vp 宽度模拟器检查评测汇总的标题、规格表格、评分卡片与来源列表，未见越界或重叠；证据 `wide-density-top.jpeg` / `wide-density-body.jpeg`。此宽屏检查不等于横屏旋转验收。

## 证据与边界

日志、截图、任务范围补丁及最终包位于 `/tmp/amber-deepread-layouts-20261006/`。关键文件：`tests-all-final.log`、`build-final.log`、`signature-final.log`、`install-final.log`、`launch-final.log`、`root-task.patch`、`picker.jpeg`、`appearance.jpeg`、各模板的 `*-top.jpeg` / `*-body.jpeg`、`final.jpeg`。

最终包：`deepread-layouts-final-signed.hap`；SHA-256：`342131c4dd23687ae7c0ffdf974b7760b9b9534b2170acdd130dc2689c8bbb6e`。

未验收：真机安装与触控体验、真实 MiMo 生成、系统打印实际生成 PDF、API 12 真机运行。横屏自定义模拟器在系统启动时黑屏，不能据此声称横屏视觉已通过。iOS 单原文精读路由与隐藏彩蛋不属于本次迁移，不能将本轮结论扩展为完整 iOS 功能对齐。
