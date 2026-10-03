# P6 文章模板归属、实时阅读库与 HTML 工坊

2026-10-03；DR03/DR07/DR11。沿用文章 output JSON、现有模板 storage、RDB mutation queue 和 scheduler 作业身份；没有模板第二存储、历史轮询或新文章删除入口。此子任务未构建或安装。

## 实际基线与实现

iOS `IOSDeepReadTask.templateId` 保存每文章模板归属，创建页选择当次模板，阅读页按任务 ID 读取模板。Harmony 原先只有全局 `deepread_template_id`，阅读/导出随全局或自定义模板变化；这里把 ID、名称、类型、自定义 HTML 和捕获时间作为文章拥有的 `DeepReadTemplateSnapshot`，保存在现有 output JSON，无需迁移 RDB schema。

- `DeepReadOutput.templateId/templateSnapshot` 为可选字段兼容旧文章；`DeepReadCacheEntry.templateId` 是从 output 投影的元数据。RDB 读回、章节状态更新、phase 更新、缓存物化与 RunRepository save 保留模板及原始输入。
- `captureDeepReadTemplate(storage,id)` 开始时校验实际自定义 HTML；缺失模板明确拒绝开始。`resolveDeepReadArticleTemplate(output)` 不读取最新全局偏好：完整快照使用原 HTML，即使模板后来被编辑或删除；旧文章缺归属、缺失或不可用的快照返回明确回退说明。旧内置 ID 能恢复原内置排版。
- 实际 scheduler options 与 run manager 接收同一 snapshot，首次/强制生成按显式选择、当轮默认、缓存回退依次取值；已有文章的非强制续跑保留缓存快照。首次无来源失败也保存捕获值；续跑无来源失败使用相同优先级，避免失败出口把原模板改为新默认。Compose、发现输入、普通新闻入口均通过真实调用捕获，writer 各阶段和持久输出保留该字段。
- `renderCapturedDeepReadTemplateHtml` 是阅读 Web 和 PDF 的共同捕获模板入口。默认原生阅读在 PDF 中使用现有安全全文 HTML 渲染，内容章节保留；自定义模板直接使用文章存下的 HTML。
- `DeepReadRepository.observeHistory(limit)` 复用真实 RDB 写入后的事件，捕获创建、状态更新和删除；`DeepReadScheduler.observeActiveRuns()` 在真实作业准入与退出时发布全体活动任务，不依赖缓存 phase。
- `observeDeepReadLibrary` 合并两个事件流，取消订阅释放二者；`queryDeepReadLibraryRows` 将尚未持久化草稿的真实 admitted run 投影成 `entry:null` 的活动任务行，不伪造缓存。库可先只订阅仓库，再接活动作业，避免模型配置阻断历史读取。
- `DeepReadTemplateSettingsPage` 为真实列表，内置/自定义模板选择使用既有 storage；列表进入工坊新建或指定模板编辑。工坊新建预填合法 `starterTemplateHtml()`，支持直接预览、保存并选用；保留 createdAt、更新 updatedAt。保存失败、只选用失败与未加载状态有准确反馈；文件选择、加载、模型请求和离页结果保持现有页面/编辑器归属。

## 验证

新增 `deepread_template_library.test.cjs` 六个实际生产方法场景，使用 SDK store port 执行真实 RDB adapter：自定义捕获→章节状态/phase 落盘→删原模板→重读及渲染；旧/缺失模板明确回退；starter 真实存储与时间；全历史事件/重订阅/释放；迟到初始读取不能覆盖新写入事件或回调退订页面；真实 scheduler 准入、未落草稿行、失败退出与聚合订阅释放。测试环境禁止 setInterval。

该文件另有四个真实管线场景，合计 10/10：模板捕获→scheduler 当轮 options→普通模型四阶段→真实 RunRepositoryAdapter/RDB；强制生成坏 JSON 保留旧稿和模板；改变默认及显式选择后的部分续跑仍保留原模板；无来源的失败续跑不改变原模板。最后一项先实际复现为 RED，runtime owner 仅统一失败出口优先级后 GREEN。

已有模板/历史、新领域管线与旧模型选用夹具共 51/51；新 UI 方法测试 12/12，实际 renderer/storage 验证新建→预览→保存→重读→选用，以及编辑/删除/迟到结果、同 ID 改名后的设置行刷新与工坊保存后重新编辑不会使用旧 HTML。设置列表和工坊列表都用名称与更新时间更新 V1 行身份。原 scheduler/runmanager/sectionwriter/library 回归 72/72。deepread tsc 通过；七个领域和平台生产文件 ArkTS lint 0 error，最后 RDB 复检 0 error，两个模板页面 lint 无缺陷。

独立 reviewer 已复验这 10 个领域管线场景，另通过真实 RunAdapter/RDB→structured manager→Reader 方法与捕获 PDF renderer 跨层探针：首次 5 次请求、续跑 2 次请求；强制生成失败保旧稿、无来源失败保快照。完整阶段构建、截图与其余搜索/发现能力由 root 汇总。

日志：`/tmp/amber-p6-template-final-tests.log`、`/tmp/amber-p6-template-pipeline-tests.log`（RED）、`/tmp/amber-p6-template-pipeline-green.log`、`/tmp/amber-p6-template-ui-tests.log`、`/tmp/amber-p6-template-lint.log`、`/tmp/amber-p6-template-rdb-final-lint.log`、`/tmp/amber-p6-template-final-tsc.log`、`/tmp/amber-p6-template-domain-regression.log`。
