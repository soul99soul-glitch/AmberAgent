# 深度阅读信息层级同步与验证

> 此报告记录测试清理前的实现快照；原始日志见 [归档索引](verification-log-index.md)。

已读取用户指定会话 `bfdb7b4e-cb56-4d4c-8511-ada4f3a41131`，并核对当前 iOS 独立产品 `/Users/arquiel/Downloads/AI/DeepRead` 的实际实现。Harmony 复用现有 output、writer、持久化和阅读链路，完成本次信息组织调整。

此次 scope 包含 18 个生产文件与 12 个测试文件，清单及最终 SHA 见 [scope.json](scope.json)。仓库原有大量 WIP 保留，HEAD 仍为 `b2d054bd7a81c64795497098d54cf30ad049e04f`；没有提交或推送。`scope.json` 的 `/tmp` snapshot 是初始实现冻结快照，不是变更前基线，不能据此归责整个工作树的差异。

## 能力对应

| 内容 | 当前 iOS 依据 | Harmony 落点与行为 | 验证 |
| --- | --- | --- | --- |
| 结论与导语 | `Core/DeepReadStructures.swift`、`Core/IOSDeepReadStructuredRenderer.swift` | `domain/models.ts`、`agent/section_writer_tools.ts`；HTML、两条 Native、导出显示 bottomLine 后再显示 summary | 字段测试、构建、模板首屏截图 |
| 判断与编号证据 | 同上 corePoints/sources | 判断优先；保留 1-based 编号、非法编号过滤；HTML 本文内跳转，Native 安全来源点击 | cold identity/reorder 测试、HTML 实点定位 |
| 按话题组织章节 | `IOSDeepReadStructuredRenderer.Section.order` | event、opinion、product、person 与 iOS 顺序一致；空段不显示 | HTML 与 Native 排序测试 |
| 脉络与立场 | timeline.why、perspective interest/quote/quoteBy/sources | 关键时间节点显示转折；立场显示诉求、原话、署名及依据 | 解析、渲染、导出测试；中段截图 |
| 影响与待核实 | impacts/watch/uncertainties | 影响对象、短期/长期、后续观察；单一来源/来源矛盾/待官方确认，不增加评分体系；兼容旧字符串 uncertainties | 三条展示/导出测试；尾段截图 |
| 统一来源与旧稿 | iOS 新 sources 与 legacy split fields | 新稿统一编号来源；混合稿额外旧参考/扩展链接去重保留；完整旧稿直接阅读，旧 quotes/implications/图文保留 | 新旧/mixed 回归、旧稿重开截图 |
| 正常生成与恢复 | `Core/DeepReadCore/IOSDeepReadDraftGenerator.swift` 三个 JSON stage | 独立深读固定 structured；规划一次 + overview/narrative/analysis 三次模型请求；来源本地整理；旧三段完整、有保存来源时可零模型补齐 | RunManager + 实际 HTTP 配置链路测试 |

表内 Harmony 域文件位于 `harmony/deepread/src/main/ets/`。HTML 为 `harmony/entry/src/main/ets/platform_impl/DeepReadTemplate.ets`；两 Native 为 `components/DeepReadNativeArticleBody.ets` 和 `components/deepread/DeepReadArticleMagazine.ets`。RDB 按完整 output JSON 保存，无新增字段白名单遗漏。

“四次”指正常首次合法成功路径。输出纠错、定向重试和失败恢复按既有规则处理，不承诺出错时也只调用四次。仅补来源阶段不调用模型；缺少真实保留来源时保持明确失败，不把旧稿或空来源当作成功生成。

## Review 发现及修复

- Standards 的冷启动来源身份碰撞 P2：旧 counter `src-1` 重启后可被新网站复用。structured 边界现在网页以 URL、文本/文件以持久 input ID 形成稳定身份；Evidence、Plan、stage prompt 与编号表同用该身份。旧来源位置保留，新来源追加。真实 createRunContext probe 与红绿回归均验证旧=1、新=2。
- Spec 的旧参考资料漏显 P2：Native 混合稿保留旧参考为无生成编号的“补充来源”。随后复查发现重复扩展链接 P3，已抑制新 sources 非空时的独立扩展段；旧稿仍保留原展示。
- 旧三段没有 sectionStates 的来源定向重试：补状态推断，使本地来源恢复闭合；无来源及存储失败仍按实际状态处理。
- 模拟器点击引用仍不移动：原 `loadData` 空格 base 不能给新增相对锚点有效定位。Article 与 Workbench 使用各自固定 HTTPS `.invalid` 文档地址作为 base/history，并放行本页 fragment；外链门控保持。最终安装后实点通过。采用官方离线 `Class_WebviewController/arkts-apis-webview-webviewcontroller` 的 loadData 参数说明，以及仓库既有 PDF 文档地址用法。

[Standards 复核](standards-review.md)、[Spec 复核](spec-review.md) 分别保留各自判断；最终均没有未关闭的本次 confirmed 缺陷。[UI review](ui-review.md) 对最终截图及节点几何另作独立检查。

## 验证结果

- 全量 `node scripts/test-harmony.mjs`：**4898/4898 通过**，184 suites，0 fail/skip。设备锚点修复后再次全量通过，日志 [full-tests.log](/tmp/amber-test-cleanup-baseline-20261005/verification-logs/full-tests.log)。
- 最终域 `tsc` 检查通过。ETS scoped lint 0 errors / 4 warnings；TS 域 scoped lint 0 errors / 1674 warnings。最后两文件再检查 0 errors / 2 warnings。此为明确的 warning 结果，未声称零 warning；SDK 构建成功。见 [lint.log](/tmp/amber-test-cleanup-baseline-20261005/verification-logs/lint.log)、[domain-lint.log](/tmp/amber-test-cleanup-baseline-20261005/verification-logs/domain-lint.log)、[web-document-lint.log](/tmp/amber-test-cleanup-baseline-20261005/verification-logs/web-document-lint.log)。
- 独立产品 `node scripts/harmony-standalone/build.mjs --product deepread --sign` 构建与签名验证成功，见 [build-sign.log](/tmp/amber-test-cleanup-baseline-20261005/verification-logs/build-sign.log)。安装并成功启动当前产品 `app.amber.deepread.reader`，目标 `127.0.0.1:5555`。旧包与小说产品未覆盖安装。
- 生成产品中的 **846 个主源码文件**与当前工作树逐字节一致（ProductIdentity 按 prepare 生成另行排除），18 个 scoped 生产文件均一致。包 SHA 与签名、安装结果见 [package-verification.json](package-verification.json)。
- 阅读偏好、模型/服务商设置及自定义模板值保持原值；只出现应用正常刷新产生的 `deepread_hotlist_cache` 变化。没有保存临时模板编辑，已退出预览回到阅读库。记录不含私密值，见 [settings-preservation.json](settings-preservation.json)。

## 模拟器目测与证据边界

| 验证点 | 最终证据 | 结果 |
| --- | --- | --- |
| 首屏信息顺序、长标题/结论换行 | [20-final-preview-top.jpeg](device/20-final-preview-top.jpeg)及同名 JSON | 结论→摘要→判断；无新增溢出或叠压 |
| 来源实点定位、待核实/影响/关注/来源 | [21-final-citation-target.jpeg](device/21-final-citation-target.jpeg)及 JSON | 点击首屏来源1后来源[1]与本文引用可见；标签与正文不重叠 |
| 立场诉求、原话及署名 | [22-final-stance.jpeg](device/22-final-stance.jpeg)及 JSON | 内容完整、卡片与文本正常换行 |
| 转折时间轴、关系图 | [23-final-timeline-diagram.jpeg](device/23-final-timeline-diagram.jpeg)及 JSON | 对齐与边距正常，内容可滚动读取 |
| 旧完整文章重开 | [24-final-old-article.jpeg](device/24-final-old-article.jpeg)及 JSON | 完成状态，直接展示已保存旧稿 |
| 临时预览退出、阅读库保留 | [25-final-library.jpeg](device/25-final-library.jpeg)及 JSON | 已恢复阅读库，原历史条目保留 |

15–19 为前一安装包的中间证据，16/17 引用未定位不能计作通过；20–25 才是最后文档地址修复包的验收。预览样例含全部本次新增字段，未调用真实云模型。Native 新稿与实际 Markdown/TXT 字节导出由测试、代码 review、构建验证，不能写成已做原生设备新稿或文件分享手动验收。

本次没有进行真机、新的云模型生成、不同字号/主题矩阵、帧率或动画性能验收。iOS 五种轻量模板及三种手动 layout override 未在本次扩建；已同步当前默认信息层级与四类话题顺序。保留这些边界，避免把本次层级同步表述为全部 iOS 能力已经完全复刻。
