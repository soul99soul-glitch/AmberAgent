# P6 模板、阅读库与导出独立审查

2026-10-03；reviewer `/root/p2_exchange_recovery`。只读生产实现，独立执行实际方法、RDB 端口和生成调用链；未构建、安装或操作设备。

结论：本范围最终 source gate **通过**。模板冻结、实时阅读库和导出调用链已通过实际消费者测试；无来源失败续跑的模板漂移、工坊同 ID 保存后的 V1 旧行/编辑闭包均已精准修复并独立复验关闭。最终设备版式、系统文件选择/分享和 ArkWeb PDF 输出仍由 root 验收，本报告的 Node 端口测试不替代设备证明。

## 审查范围与闭环

| 能力 | 实际调用链 | 独立结论 |
| --- | --- | --- |
| 新文章选择模板 | Compose `loadTemplates/createArticle` → `captureDeepReadTemplate` → RDB upsert → Article；热点首次输入通过 `saveDiscoveryInputs` 捕获 | 所选自定义 HTML 和原始资料先落盘；模板删除或非法时不创建文章、不导航；迟到 capture 不写离页状态 |
| 冻结与续跑 | Article `runNow` → scheduler 的第四个 factory options → run manager → section writer → `DeepReadRunRepositoryAdapter.save` → RDB output JSON | 普通续跑保留原快照；Article 明确重新生成传本篇快照；首次、失败和完整稿均保留归属，失败强制替换保旧正文及快照 |
| 阅读与 PDF | Article `applyOutput/editorialHtml` 与 ExportPanel `openPdfPreview` → `renderCapturedDeepReadTemplateHtml` | 两个消费者读文章的持久快照；改全局模板、编辑/删除原模板不改变保存稿；缺失/非法旧快照显示明确默认归属；临时原生阅读不改文章/PDF 快照 |
| 可用 HTML 工坊 | 合法 starter → 实际 preview renderer → saveCustomTemplates → durable reload → settings select | 默认草稿无需修改即可预览、保存并选用；createdAt 保留、updatedAt 更新；保存失败与仅选用失败准确区分；旧文件/AI结果不覆盖新编辑器 |
| 实时阅读库 | RDB `observeHistory` + scheduler `observeActiveRuns` → `observeDeepReadLibrary` → HistoryPage 生命周期订阅及检索 | 创建、分段状态、phase 和删除真实发布；活动任务来自真实准入/退出，未保存稿用 entry:null；无轮询；返回重订阅、离页释放两个事件源，旧初始查询不覆盖新提交事件 |
| 正文与文件导出 | 一份 repository 保存稿 → domain serializer → TXT 的实际 Chat Markdown AST converter → save/share helpers；PDF 当前文档 ready → createPdf → exact bytes | TXT 仅转换正文 Markdown，标题、实体、图注、来源、URL 保持字面值；列表、表格、代码和链接事实保留；部分稿明确标识且不开放完整 PDF；取消和旧面板 picker 不写文件 |

当前 iOS 的每篇任务保存 templateId，并按 ID 查询可变模板库；Harmony 本次额外冻结完整自定义 HTML。这里记录的是已验证的 Harmony 归属保证，没有将冻结语义误称为现有 iOS 完全相同行为。

## 精确发现与复验

**P6-TEMPLATE01，无可用来源的失败续跑覆盖原模板：已关闭。** `createRunContext` 已在非 force 路径优先采用缓存快照，但 `generateStages` 的首次失败记录原来优先选择新的 opts/deps 快照。全部来源失败的文章续跑时，正文尚无可读段落，失败出口会把原自定义模板改成新的默认模板。storage owner 用真实 manager/RunAdapter/RDB 场景先复现 RED；runtime owner 只在该失败出口按同一非 force 优先级计算 `failedTemplate`。本 reviewer 重新执行最终 10/10 模板 pipeline，并用独立跨链脚本确认无来源失败记录仍持有原 HTML。

其他已关闭 UI 发现没有重复扩展修改：Sources 旧初始读覆盖新订阅、Reader 偏好/首用确认迟到写状态、阅读库 active-only 刷新错误隐藏，分别由对应 UI 独立报告记录。这里重新检查真实仓库事件和调用参数，以确认这些修复没有断开数据链。

**P6-TEMPLATE02，工坊同 ID 保存后 V1 行和编辑闭包保留旧内容：已关闭。** Settings 的自定义模板行已把名称和更新时间纳入 key；工坊 `ListCard` 原来仍只用 `t.id`，`onRowClick` 捕获旧 `t`。同 ID 保存新名称/HTML 后，保留行会显示旧名称，并通过旧 HTML 重新进入编辑。owner 只增加 `templateRowKey(id/name/updatedAt或legacy createdAt)` 并将真实 ForEach 接到该 key。新增实际保存→新 key→编辑当前对象→再保存回归通过，模板 UI 最终独立 **12/12**。另一个 reviewer 脚本运行生产 ForEach 的真实 CardRow/onRowClick，并模拟其按 key 保留行的合同：旧 key 得到 `OLD_NAME`/旧 summary HTML，当前 key 得到 `CURRENT_NAME`/新 analysis HTML。该合同反例不是设备截图证明。日志 `/tmp/amber-p6-template-workbench-key-independent.log`、`/tmp/amber-p6-workbench-foreach-review.log`。

## 独立执行证据

- 模板/library + ExportFiles/ExportPanel + 模板 UI：初轮 **58/58**；新增四个完整生成场景后模板/library 文件单独 **10/10**。日志 `/tmp/amber-p6-template-library-export-review-cjs.log`、`/tmp/amber-p6-template-library-export-review-pipeline.log`。
- Compose/Reading、library-entry、既有模板回归：**61/61**。日志 `/tmp/amber-p6-template-library-export-review-ui.log`。
- library 和正文导出 TS：**8/8**。日志 `/tmp/amber-p6-template-library-export-review-ts.log`。
- 以上最终独立运行加最终模板 UI 12/12，覆盖 **132 个不同仓库测试**，均通过（初轮六个模板/library 用例被最终十个用例替换计数；工坊行回归为新增一项）。
- 另外 `/tmp/amber-p6-capture-chain-review.cjs` 在实际 RDB 与 RunRepository adapter 上跑五次普通 JSON 生成请求，然后重读保存稿，通过实际 Reader 方法对比 PDF renderer 的 HTML 逐字相等；新的默认 HTML 未进入输出。仅 ANALYSIS 失败续跑再请求两次，保留其余段与快照；强制坏 JSON 保旧稿；无来源再次失败保原快照。日志 `/tmp/amber-p6-capture-chain-review.log`。

runtime owner 最终 freeze 包含上述失败出口修复，DeepRead tsc 成功、五个 runtime 生产文件 lint **0 errors / 917 warnings**（跨模块既有诊断和新增两处 lookup warning），记录在 `/tmp/amber-p6-runtime-tsc.log`、`/tmp/amber-p6-runtime-lint.log`。storage/template 和 UI owner 的 scoped lint、tsc 记录见各实现报告；这些是实现者的验证，不计入上面的独立测试数量。

storage/template owner 最终 freeze 包含工坊行 key 修复；RDB 窄 lint 无缺陷，领域 tsc 成功，工坊单页窄 lint 无缺陷。记录 `/tmp/amber-p6-template-rdb-final-lint.log`、`/tmp/amber-p6-template-final-tsc.log` 和模板实现报告。未因这两处发现扩大格式、状态机或重试机制。

## UI 和设备证据边界

源码检查已确认 Compose 使用可滚动内容、模板长名称可换行；阅读库搜索/刷新与筛选控件有 44vp 下限，筛选横向滚动；模板编辑动作和删除入口有触达尺寸，预览使用独立 sheet owner；导出面板正文/PDF 使用弹性区域，状态说明和错误有可见位置，PDF API 14 与 ArkWeb 能力均有实际 gate。

本范围最终截图/AX、实际系统浏览器/文件保存/分享、实际 PDF 文件与排版尚待 root 的本阶段统一签名包验收。本报告 source gate 已通过，未关闭上述设备 gate；设备总 UI gate 由 root/entry reviewer 单独收口。
