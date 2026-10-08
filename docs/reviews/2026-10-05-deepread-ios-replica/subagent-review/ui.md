# DeepRead UI geometry review — 2026-10-05

本次为独立只读 review。主 agent 独占设备操作；此 reviewer 读取当前生产代码、本轮重新采集的截图和 UI dump，未操作 HDC / iOS GUI，未修改生产、测试或配置。审阅依据为 AGENTS.md、harmony-arkts、product-design audit；preflight 没有已保存设计上下文。

## 范围与证据约束

- 当前待审产物与源闭合见 [scope.json](scope.json)。Git HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`，审阅对象包括当前 WIP，而非仅该 commit。
- 本轮 Harmony 截图 **1320 × 2856 px**；不是上一轮记录的 2868 px。18 vp 外边距对应 63 px，density = 3.5。实际 layout `bounds` 是可见范围，`origBounds` 是未被 viewport 裁剪的范围；滚动裁剪不等于固定尺寸错误。
- 只将本轮 `subagent-review/device` / `reference` 中经 `view_image` 实际查看的文件作为视觉结论。源码与旧 motion 报告不能替代此次截图。
- 普通字体、单一手机宽度、暗色设置的健康结论不扩展到全部设备、系统大字体、读屏、实体键盘或动画帧率。

## 已查看步骤与页面健康

| 步骤 | 页面 / 状态 | 当前健康结论 | 本轮截图与 layout | 代码位置 |
|---|---|---|---|---|
| 1 | 设置：恢复的搜索区滚动位置 | 当前默认字体中首选服务、增加服务、结果数步进、6 个开关未见横向挤压、错位或文字截断；底栏覆盖 viewport 中间条目属于浮动底栏设计，不能单凭此图判为不可达 | [00-start.jpeg](device/00-start.jpeg), [00-start.json](device/00-start.json) | `DeepReadSettingsVisual.ets:71-143`, `DeepReadSettingsForm.ets:180-221` |
| 2 | 设置：滚到顶部 | 色环、5 个颜色名、模型主行、服务商副行的视觉层级和左边界一致；两行说明自然换行；没有见到色环与文字或卡边重叠 | [01-settings-top.jpeg](device/01-settings-top.jpeg), [01-settings-top.json](device/01-settings-top.json) | `DeepReadSettingsForm.ets:135-178`, `DeepReadSettingsVisual.ets:45-66` |
| 3 | 设置：滚到末尾 | 100% 标签右对齐，滑块在卡内；保存按钮完整；末尾“更多应用设置”完整露出，底栏没有永久遮住最后一组 | [02-settings-bottom.jpeg](device/02-settings-bottom.jpeg), [02-settings-bottom.json](device/02-settings-bottom.json) | `DeepReadSettingsForm.ets:224-283`, `SettingDeepReadPage.ets:945-969` |
| 4 | 阅读模板：内置与已存在的自定义模板 | 返回、大标题、内置图标框、标题和副标题、右侧选择勾及自定义操作行未见错位；现有长英文模板名称完整显示 | [03-templates.jpeg](device/03-templates.jpeg), [03-templates.json](device/03-templates.json) | `DeepReadTemplateSettingsPage.ets:288-373`, `DeepReadTemplateNavigation.ets:14-40` |
| 5 | 新模板编辑 sheet：稳定显示 | 名称、描述、HTML 输入框、导入 / 全屏操作的左右起点与内边距一致；sheet 标题居中；上方露出的父页标题是 92% sheet 背景，不是本 sheet 内容错位 | [09-new-workbench-real.jpeg](device/09-new-workbench-real.jpeg), [09-new-workbench-real.json](device/09-new-workbench-real.json) | `DeepReadTemplateWorkbench.ets:609-664`, `DeepReadTemplateNavigation.ets:14-40` |
| 6 | 模板编辑 sheet：键盘打开 | 名称及上部内容没有横向挤压；HTML TextArea 的 `bounds` bottom = 1757，`origBounds` bottom = 2302，当前是键盘裁掉 viewport，而非输入框被改小或内容丢失；键盘开启时末尾操作可达仍需单独验证 | [11-template-keyboard.jpeg](device/11-template-keyboard.jpeg), [11-template-keyboard.json](device/11-template-keyboard.json) | `DeepReadTemplateCodeEditor.ets:15-41`, `DeepReadTemplateWorkbench.ets:537-544` |
| 7 | 模板编辑 sheet：关闭键盘并滚到底 | 导入 / 全屏、校验、预览、保存及长占位符说明全部可见，没有底部被 home indicator 永久盖住；保存为灰色 disabled 状态不是错位，是否启用由 draft 状态决定 | [12-template-bottom.jpeg](device/12-template-bottom.jpeg), [12-template-bottom.json](device/12-template-bottom.json) | `DeepReadTemplateWorkbench.ets:630-664` |
| 8 | 新模板预览：正文已加载 | 标题自然两行；kicker、引文边线、tag、时间线和核心要点卡片均未横向溢出；标题 / 正文左边界一致；完成按钮完整可见 | [13-new-template-preview.jpeg](device/13-new-template-preview.jpeg), [13-new-template-preview.json](device/13-new-template-preview.json) | `DeepReadTemplateWorkbench.ets:808-847` |
| 9 | 发现页：刊头与多来源热点 | 外 gutter 对称、节日徽章与日期不相撞、两行长英文热点自然换行；**序号列宽造成标题起点轻微不一致 U3**；混合字号 center / baseline 是可见参考差异，未造成重叠 | [18-discovery.jpeg](device/18-discovery.jpeg), [18-discovery.json](device/18-discovery.json) | `DeepReadDiscoveryHeader.ets:182-214`, `DeepReadDiscoveryCards.ets:15-19,62-73` |
| 10 | 创建阅读 sheet：主题与来源表单 | 取消、居中标题、输入框、两行说明和模板选择行没有错位；**导入文件图标显示圆圈 U2** | [19-composer.jpeg](device/19-composer.jpeg), [19-composer.json](device/19-composer.json) | `DeepReadComposerSheet.ets:231-310` |
| 11 | 创建阅读：主题输入键盘开启 | 主题 TextArea 仍完整在键盘上方；来源区 `bounds` 被键盘 viewport 裁剪，未见横向压缩；此状态没有验证末尾生成按钮可达 | [20-composer-keyboard.jpeg](device/20-composer-keyboard.jpeg), [20-composer-keyboard.json](device/20-composer-keyboard.json) | `DeepReadComposerSheet.ets:231-238,291-320` |
| 12 | 创建阅读：关闭键盘并滚到底 | 模板选择、说明、完整生成按钮与末尾说明可见；按钮因空主题 disabled，对应生产状态；输入框上下间距稳定 | [21-composer-bottom.jpeg](device/21-composer-bottom.jpeg), [21-composer-bottom.json](device/21-composer-bottom.json) | `DeepReadComposerSheet.ets:307-318` |
| 13 | 阅读库：全部筛选与完整 / 失败卡片 | 搜索框、筛选胶囊、状态 / 日期、标题 / 摘要 / 失败说明各列对齐；最右筛选在横向 Scroll 内，不能把超出18vp gutter当作裁剪缺陷；页底卡被浮动底栏覆盖属于可滚动 viewport | [22-library.jpeg](device/22-library.jpeg), [22-library.json](device/22-library.json) | `DeepReadHistoryPage.ets:432-480`, `DeepReadLibraryCard.ets:55-79`, `DeepReadLibraryFilters.ets:21-38` |
| 14 | 文章：完成稿和来源失败说明 | 导航按钮未相撞，状态卡主副行及重试对齐；正文长英文标题自然两行、导语和tag未横向溢出；正文 Web layout bottom 2562 < 底栏 top2590，底栏未遮住 Web viewport | [23-article.jpeg](device/23-article.jpeg), [23-article.json](device/23-article.json) | `DeepReadArticleToolbar.ets:60-96`, `DeepReadArticleStatusPanel.ets:43-98`, `DeepReadArticlePage.ets:930-973` |
| 15 | 文章 Aa 菜单 | 六项完整显示，字号百分比灰色 disabled、其他操作可辨认；菜单覆盖正文是弹出菜单正常行为，未见溢出屏幕 | [24-article-type.jpeg](device/24-article-type.jpeg), [24-article-type.json](device/24-article-type.json) | `DeepReadArticleToolbar.ets:24-37` |
| 16 | 原始来源列表与展开内容 | 标题、类型、URL、正文状态和展开行起点一致；已展开英文正文自然三行，没有卡片截断；底部其他卡为正常滚动裁剪 | [25-sources.jpeg](device/25-sources.jpeg), [26-sources-expanded.jpeg](device/26-sources-expanded.jpeg), [26-sources-expanded.json](device/26-sources-expanded.json) | `DeepReadSourcesPage.ets:183-255` |
| 17 | 分享 / 保存文件选择层 | 标题与副标题、关闭、3项分享与2项保存文件行对齐；图标与文字留足间距，说明一行完整，未见被底部遮住 | [27-export.jpeg](device/27-export.jpeg), [27-export.json](device/27-export.json) | `DeepReadExportPanel.ets` standalone 分支 |
| 18 | 模板编辑键盘开启后下滚 | 外层内容确实向上滚动，导入 / 全屏与校验已在键盘上方；剩余预览 / 保存尚未出现在当前 viewport，不能把这张作为所有末尾操作可达证据 | [34-template-keyboard-bottom.jpeg](device/34-template-keyboard-bottom.jpeg), [34-template-keyboard-bottom.json](device/34-template-keyboard-bottom.json) | `DeepReadTemplateWorkbench.ets:537-544,630-647` |

本轮 [04-template-preview.jpeg](device/04-template-preview.jpeg) 为内容空白的预览，已查看但**拒绝作为健康证据**。后续第 8 步是新模板有正文的预览；[16-repeated-preview-settled.jpeg](device/16-repeated-preview-settled.jpeg) 与 [17-repeated-preview-third.jpeg](device/17-repeated-preview-third.jpeg) 当前落盘图片实际查看后均为完整正文，不能把早期空白 / 异步加载帧当作稳定失败。

本轮参考也实看 [01-ios-discovery-dark.jpg](reference/01-ios-discovery-dark.jpg)、[02-ios-settings-dark.jpg](reference/02-ios-settings-dark.jpg) 与 [03-ios-templates-dark.jpg](reference/03-ios-templates-dark.jpg)。由于两平台设置内容、模型配置与模板体系不同、手机 viewport 不同，只进行结构 / 层级参考，不声称逐像素相等。iOS 版式编排与阅读样式 selector 尚未接入是既有功能差异，不包装成新增视觉缺陷。

## 发现的用户可见问题

**U1 / 已撤回运行缺陷定论：重复预览空白未复现。** 审阅过程中 image viewer 对相同编码给出一张有正文、一张空白的展示矛盾，早期据展示判定 04 / 15 空白不能证明稳定产品失败。主 agent 已重新采用唯一 remote snapshot 路径采集首开 +3s / +11s、重开 +3s / +11s 四组；reviewer实际调用 `view_image` 查看 31 / 33 并核对其返回编码与 layout：

- [30-preview-first-3s.jpeg](device/30-preview-first-3s.jpeg)、[31-preview-first-11s.jpeg](device/31-preview-first-11s.jpeg)、[32-preview-second-3s.jpeg](device/32-preview-second-3s.jpeg)、[33-preview-second-11s.jpeg](device/33-preview-second-11s.jpeg) 四文件 SHA256 完全一致：`b1b2e190bae86f0808086219d29198a164a9b2837bf0b23001fc012496421e5e`。
- 四个本轮 UI dump 均含 `heading 示例文章标题:自定义模板预览`，bounds `[126,632][1197,906]`。不是只有 about:blank Web 容器的假阳性。
- `view_image` 对 31 / 33 返回的 `image_url` 字符串完全相等（均 255305 字符）。因此工具展示的一正常、一空白不可能证明磁盘两图不同，不能以该视觉矛盾报产品故障。

本轮首开、重开已加载内容的证据闭合；仅保留可进一步追查的生命周期源码风险，**U1 不计确认缺陷**。没有抓取 native cache / attach 事件，也不声称知道所谓缓存根因。

源码定位提示：`DeepReadTemplateWorkbench.ets:93` 的 `previewId` 为 private 普通值，`:438` 每次打开递增；`:583` 的 sheet builder 捕获该值，`:831` Web ForEach key 仅含 themeEpoch；`:415 / :421` 的 attach / pageEnd 对 captured id 严格比较。需复核重复打开时 cached sheet / Web 是否重建与重新 attach，不能将页内 `about:blank` 的 Web layout 当成正文已经成功加载。已向主 agent 发出定点检查提示，未改生产。

**U2 / P3 确证：创建阅读文件图标退化为圆圈。** 第10 / 12步导入文件前的图标实际是空圆圈。`DeepReadComposerSheet.ets:253 / :261` 使用 `Icon({ name: 'file' })`，而 `Icon.ets:108-158` 没有 file 分支，实际 fallback 为 `sys.symbol.circle`。导入行已设备证实；已选附件行同样错误为源码证据。建议仅将两个调用改为已有 `doc` 图标；不用增加图标库或广泛修改全局 Icon。

**U3 / P3 确证：发现页标题列随序号宽度微移。** 第9步第一卡序号 `01` 的 origBounds 为 `[114,919][194,1030]`，标题 x=236 px；第二卡 `02` 的序号 `[114,1221][200,1332]`，标题 x=242 px；第2–6卡也为242 px。**6 px = 1.71 vp** 的列偏移来自比例数字没有固定列宽。`DeepReadDiscoveryCards.ets:63-64` 未设 width / tabular数字；iOS `DeepReadDiscoveryView.swift:134-137` 明确 `.monospacedDigit()`。建议在现有序号 Text 固定统一列宽，保持 title / source-summary 左边界一致。当前不会影响操作，但属于用户要求的对齐细节。

## 量化检查

| 项目 | 本轮 layout 量值 | 与代码的关系 / 判断 |
|---|---|---|
| 设置卡外边距 | `[63,629][1257,1045]`：左右 63 px = **18 vp** | 与 `DeepReadDesign.gutter = 18` 一致，左右对称 |
| 设置普通行文字起点 | “版式与样式” x = 119 px：卡左 63 + 内边距 56 px = **16 vp** | 与 SettingsRow 内 padding 16 一致；模型和阅读字体行也为 119 px |
| 保存按钮 | `[63,1934][1257,2116]`：1194 × 182 px = **341.14 × 52 vp** | 与满卡宽、height 52 一致，没有底部截断 |
| 最后一组应用设置 | 卡 `[63,2281][1257,2492]`，底栏 top = 2590 px | 完整组与底栏间有 **98 px = 28 vp**，没有永久覆盖 |
| 浮动底栏 | `[188,2590][1133,2814]`：945 × 224 px = **270 × 64 vp** | 与 TabBar 固定胶囊一致；取整导致左右 188 / 187 px，可忽略 1 px 差 |
| 模板图标后标题 | 内置标题 x = 329 px，第二行同样 x = 329 px | 两个内置模板列对齐；右侧勾留独立空间 |
| 可见 viewport 裁剪例 | 顶部搜索卡 bounds bottom = 2856，origBounds bottom = 3074；底部“阅读与排版” bounds top = 464，origBounds top = 438 | 都是页面正常滚动裁剪，不能据此提交尺寸 / 截断缺陷 |
| Discovery section center | 标题 `[63,751][413,833]` center y=792；副标题 `[448,765][785,820]` center y=792.5 | 实测默认垂直居中。iOS采用 firstTextBaseline；在默认字体下为可选排版差异，无重叠 |
| Discovery 第1 / 第2标题列 | 第一 x236、第二 x242 px | 非等宽序号导致1.71vp偏移，见U3 |
| Composer 输入 / sheet标题 | 主题 `[63,672][1257,925]`；title `[273,395][1047,465]` center x660 | 主题高72.29vp取整；header正中，不因左取消而偏移 |
| Library 相邻卡距 | 第一card bottom1253、第二top1295；第二bottom1720、第三top1762 | **42px=12vp**，与页面Column space12一致 |
| Article 每个 toolbar操作 | Aa Text `[788,143][942,297]`：154px×154px | **44×44vp**；胶囊底层视觉高40vp，点击面积44vp，无相互挤占 |
| Article Web与底栏 | Web `[0,728][1320,2562]`，TabBar `[188,2590][1133,2814]` | Web和底栏间28px，正文viewport未被永久覆盖 |

## 尚未确认的代码风险与可选差异

1. **系统大字体尚未验证。** `DeepReadSettingsForm.ets:198-205` 的结果数步进行固定高 50 vp、两按钮各 44 vp、正文占余宽；当前默认字号显示完整，但更大系统字可能换行并撞上固定高。Composer 主题输入固定 72 vp、链接固定 90 vp（`DeepReadComposerSheet.ets:231-251`）；这是可滚动输入框，不能据此认定内容丢失。建议对最大系统字体采 fresh screenshot / bounds 后再决定是否改 minHeight。
2. **发现页混合字号 baseline 为可选参考差异。** 第9步和本轮 iOS01已实看，`DeepReadDiscoveryCards.ets:15-19` section header 为 center（20 / 12 vp 字），iOS `DeepReadDiscoveryView.swift:437-442` 为 firstTextBaseline。TopicCard `:62-73` 是 Top，iOS `:133-149` 为 firstTextBaseline。当前层级清楚、不相撞；若追求排版精确复刻可调整baseline，不能将此与U3可证明标题列偏移混为一谈。
3. **非 root、带 action 的通用设置标题潜在偏中。** `DeepReadSettingsVisual.ets:20-34` 左 60 vp、右 action 44 vp，标题将偏右约 8 vp。当前 standalone Library root = true 时是大标题，不受此分支影响；没有 fresh 非 root 带 action 截图，不列现行 P 缺陷。
4. **44 vp 点击面积与视觉面积需区分。** Library filter 胶囊视觉高 34 vp、外 Stack 高 44 vp (`DeepReadLibraryFilters.ets:21-38`)；Article toolbar 每个操作 44 × 44 vp (`DeepReadArticleToolbar.ets:60-96`)，不应把 20 vp 图标当作按钮点击尺寸。Discovery header 操作本身 40 × 40 vp (`DeepReadDiscoveryHeader.ets:19-28`)，若以全产品 44 vp 目标为标准则有 4 vp 差异，但当前未声明该标准，不当作 API / 运行错误。

## 当前结论

已实看上述18个 fresh 页面状态、重复预览追查与3个当前iOS参考图。没有确认新的 P0 / P1 / P2 **几何**缺陷；确认2个P3细节：文件图标fallback圆圈U2、比例序号导致标题列微移U3。预览首开 / 重开的hash、heading与编码证据闭合，稳定空白U1未复现，已撤销早期运行缺陷定论。系统最大字号、横屏 / tablet、多行超长模型名、键盘开启时所有底部操作、VoiceOver / Harmony读屏与完整FPS仍未验证，不宣称全 UI / accessibility 验收通过。已知 iOS 原生底栏收缩、卡片共享缩放、原文 / 阅读样式 / 编排选择三项仍按既有 known gap 记录，不重复作为本次新缺陷。
