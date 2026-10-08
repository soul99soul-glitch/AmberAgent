# Phase 3 独立 review

范围：新增 conversation_artifacts、ChatArtifactShelf、WidgetArtifactExport，显示设置和 ChatPage/AgentMessageRow 的本轮接线。仅写报告，没有编辑实现；相对起始快照识别增量，不改其他 WIP。

## 具体发现

1. **正文可见 widget 在产物架漏收（已修复）**。代码栅栏跨相邻两个 text parts 时，正文经 groupMessageParts 合并后显示 widget，初版 index 逐 part 解析收不到。真实 domain 探针先输出 `bodyWidgets=1 shelfWidgets=0`；实现者复用现有 groupMessageParts 后独立同一 probe 输出 `1/1`。没有另写文本合并规则，稳定 ID 使用正文组首 part index 与段起点。
2. **暗色模式 HTML 导出透明底导致独立打开不可读（已修复）**。原 widgetArtifactExport 直接复用预览 widgetDocument 的暗色 `color:#ECE8DF;background:transparent`；App 内预览有暗底，导出独立文档在默认白色页面上缺相配背景。实现者仅在导出 document head 末尾添加匹配主题的不透明页面底色 `#14110E / #EFE7D6`，独立检查 style 优先级与浅/暗色输出回归通过；预览透明、原净化/CSP和执行边界不变。

3. **长 slides 预览的来源／导出实际不可达（已修复并设备复验）**。第一轮初始截图只能证明长内容和按钮不在初始视口，不能证明滚动可达。UI agent 随后通过实际 Web 区和标题区 fling、AX 检查发现外层 Scroll 未将末尾操作移入视口。实现者仅把 WidgetPreview 的来源／导出 Row 与错误反馈移到外层 Column 固定底部，Scroll 保留标题和 Card、使用 layoutWeight(1)。独立源检保持既有 44 vp 触区、底部 20 + navBarHeight 和完整导出／来源回调；不改 Web 手势或加入滚动协调器。
4. **图表坐标标签和图例实际过小（已修复并设备复验）**。`phase3-preview-chart-light.jpeg` 中两条中文 x 轴标签和单字符 y 图例远小于“图表静态预览”说明文字；shared renderer 的 680 SVG viewBox 缩到约 320 CSS 宽后，固定 11 / 13 字号仅约 5.2 / 6.1 CSS px。这是既有 renderer 经新入口显示出的可读性缺陷。实现者仅在既有 label slot 足够时提升稀疏短标签字号到最多 24，并按原截断后最大长度／槽宽限幅；≥12 类的密集标签保持原 11。短图例按原 145 槽宽提升到最多 24，长图例保留原下限 13。独立 focused 37/37 通过，涵盖真实两组中文、24 类密集标签、长标签限幅和 SVG 转义；viewBox、坐标、数据绘制与截断逻辑不变，不扩展通用响应式框架。

5. **暗色图表轴字与图例颜色实际过暗（已修复并设备复验）**。独立查看新字号包的 `phase3-final-chart-dark.jpeg`，轴字字号已变大，但固定灰色 `#6b7280` 和蓝色 y 图例在深色卡底上仍显著暗于卡片说明，影响类别／系列辨读。定点修复仅给生成 chart text 添加两类 reserved axis／legend 标记，暗色 widgetDocument 为轴字使用 #D3D0C9、四系列 legend 对应同色系浅色 #93B4FF／#86DCA0／#FDBA74／#D8B4FE；bar／line、light 原样及普通 HTML／SVG 不改变。不使用全局 svg text 选择器。独立源码确认和 scoped 38/38 GREEN，覆盖四系列、light 无新增规则、普通 SVG 完整保留、没有全局覆盖；净化／CSP／导出路径未改。

## 调用链与产品行为检查

- 默认开启的 DisplaySetting 字段在旧配置缺字段时保持开启，保存/读回为 false 时保持关闭；SettingDisplayPage 的明确标签说明正文组件和产物架同时受开关控制。ChatPage 读取同一配置传给 AgentMessageRow，并将同一字段传给 artifact index。
- 正文 widget 从当前 selected assistant 消息派生，不扫 user/reasoning/tool output；仅闭合栅栏、解析成功且 complete 的 HTML/图表/幻灯片收录。原文本是持久化来源，无第二个 artifact 注册表。
- 消息/节点/会话来源精确保存；分支切换后不继续返回旧 message 的来源。相同 image URL、miniapp identity 去重，document 版本不去重，重复文件名对比入口仍沿原读取逻辑。
- ChatPage 打开 shelf 时读完整历史，受首屏分页限制时读取 repository；异步返回后检查 pageAlive/pageVisible/conversation ID，再取同会话 run 最新快照。来源按钮再次读 repository 和 run snapshot，验证 exact selected message，再用原 rows/定位机制跳转，不靠标题匹配来源。
- Widget preview 复用既有 GenerativeWidgetCard 的 JS-disabled、本地 Web、净化和 CSP；导出经过同 widgetDocument 净化，再复用原 DocumentViewPicker 选择 URI、通用写文件方法。底层 saveMarkdownFile 并不限定 `.md`，调用传入的 `.html` 名称有效；用户取消选择不写文件。
- 导出按钮防重复点击；异步错误只回填仍存活且 selected ID 一致的组件，销毁后的 UI 不回填。widget action 沿原 prompt 填入输入框行为，未增自动执行工具入口。

## UI 源码检查及实际验收边界

- 新列表保留 20 左右内边距，底部 20 + navBarHeight；widget 长标题最多两行、预览标题最多三行；操作区域最小 44，图片 Flex 换行、文档/小应用长名称省略。独立 sheet host 保持原生命周期边界。
- 返回、关闭与短预览的来源／导出具备可见触区；长 slides 的操作可达性问题见发现 3。空态覆盖全部四类产物，文档预览/对比与小应用运行在关闭 shelf 后走原入口。
- 暗色独立导出问题记录在发现 2。设备实际错位/边距/暗色截图由主 agent 验证，不能以以上源码检查代替像素证据。

## 独立验证

- 首轮 artifacts/widget/display 11/14：3 处失败来自测试使用错误 makeConversation 签名，已由实现者修 fixture，与实现缺陷区分。
- 跨 part 正文/shelf probe RED `1/0` → GREEN `1/1`。
- 修复后 artifacts/widget/display/grouping 加实际 SyncCryptoEntry/settingsbackup/syncsnapshot：54/54 GREEN。
- 后续字号修复的 artifacts/widget/display/grouping/chart-labels：37/37 GREEN；暗色 scoped CSS 修复后同组合 38/38 GREEN。

## 装机验收前发现的窄恢复修复

主 agent 为真实 UI 测试数据走现有备份恢复入口时，设备出现 passwordless restore 的 `length undefined`。仅评审其 `SyncCryptoPort.ets` 增量：空 passphrase 显式作为零字节，非空仍调用原 TextEncoder.encodeInto。真实 Entry Port 代码配合官方 SDK 空字符串返回 undefined 的 stub 验证：空/非空 UTF-8 KDF 均与独立 SHA256 字节计算一致（2/2）；原盐、迭代限制、SHA256-ITERATED 和 AES-GCM 未改。SettingBackupPage 相对开始时快照无增量，临时诊断日志没有进入交付。此修复精准命中已观察失败，不扩大为空字符串全仓检查或新增兜底。

## 第一轮设备截图独立目测

已读取 `/tmp/amber-followup-ui/phase3-shelf-light.jpeg`、`phase3-preview-html-light.jpeg`、`phase3-preview-chart-light.jpeg`、`phase3-preview-slides-light.jpeg` 及对应 JSON bounds，没有操作共享设备。

- 浅色产物架三张卡片的左侧标题、类型行与右侧预览/来源列对齐，卡片间距一致，没有文字重叠或超出左右边距；顶栏关闭和底部系统手势区分离。
- HTML 预览正文与图表两组柱状图均实际显示，顶栏返回/关闭可见；来源与导出按钮在实际 bounds 中可见且分离（HTML 两按钮 y=1522..1676，chart y=1786..1940）。所有对应触区高度为 154 像素，与组件最小 44 vp 一致。
- 幻灯片初始截图可见第一页和第二页正文，但不能证明末尾来源／导出可达；实际手势验收随后发现不可达，见发现 3，已按真实操作证据更正。
- 本轮截图没有识别到新的具体错位、对齐或间距缺陷；图表字号的实际可读性问题见发现 4。深色、开关关闭与文件导出实际操作仍由主 agent 后续验收，不以浅色截图代替。

第二轮独立读取校正后的实际画面（不凭文件名或 Row checked 值推断状态）：`phase3-shelf-dark-verified.jpeg` 实际为深色三卡产物架，HTML／图表／幻灯片标题、类型、右侧预览及来源均可见，边距和列对齐一致；`phase3-widget-setting-off.jpeg` 实际显示生成式组件灰色左侧关闭状态。随后 `phase3-body-disabled.jpeg` 的同一会话正文显示三个原始 show-widget Markdown 代码块，`phase3-shelf-disabled.jpeg` 显示空产物架，证明实际关闭行为同时作用于正文和收录。`phase3-theme-restored-light-verified.jpeg` 实际显示“浅色”选中并恢复浅色界面。没有识别到上述实际画面的新错位／对齐／间距缺陷。新包长 slides 固定 footer 与图表字号的复验见下一段。

新 three-fix 包独立截图复验：`phase3-final-slides-light.jpeg` 的两页内容和固定底部来源／导出同时可见，操作与系统手势区分离；对应 JSON 的来源 `[70,2632][323,2786]`、导出 `[365,2632][660,2786]` 均为 bounds == origBounds，完整 154 像素触区。UI agent 实际点击导出打开 DocumentViewPicker、取消返回，以及点击来源关闭 sheet 返回原会话，已补齐发现 3 的操作证据。`phase3-final-chart-light.jpeg` 的两组中文 x 轴标签和 y 图例已明显可读，未出现与坐标轴／图例重叠，发现 4 闭环。该 chart-light 图没有展示底部操作，且 JSON 已是返回后的正文树，故仅用它证明图表字体现象，不用不同时刻素材证明 chart footer。最新 scoped 字色包实际截图复验见下文。

额外最终画面核对：`phase3-final-slides-dark.jpeg` 实际保持可读正文和固定底部来源／导出；`phase3-final-widgets-restored-on.jpeg` 显示生成式组件橙色右侧 ON，`phase3-final-light-restored.jpeg` 显示浅色选中。针对 UI agent 的空架 header 疑虑，独立读取当前 `phase3-final-empty-shelf-settled.jpeg`，右上角橙色“关闭”两字实际清楚可见，与产物架标题同一行，没有缺字／遮挡；结合完整 AX 和真实关闭点击成功，不成立为实现缺陷。

最后 scoped 字色包（主 agent build4／sign4／实际安装通过）独立读取 `phase3-final-color-chart-light.jpeg` 与 `phase3-final-color-chart-dark.jpeg`：浅色两组标签保持原灰、y 图例保持原蓝；暗色两组标签实际变为清楚的浅色、y 图例为同色系浅蓝。两种主题的 24 字号与坐标轴／图例间距正常，没有文字碰撞，柱色未改变，固定来源／导出均实际可见且与手势区分离。发现 5 的最后实际视觉 gate 已通过。

额外复核来源按钮窄修复：ChatPage shelf 的 onSource 先关闭 sheet，再调用原异步 openArtifactSource；失败继续沿既有 errorMsg 显示，因此“来源已不存在／不在当前分支”等错误不被 sheet 遮住。来源身份校验、定位和失败逻辑未变，没有新增兜底。

状态：本 phase 五处具体发现均已精准修复，并完成独立源码／focused／对应实际设备画面复验；额外恢复及来源错误可见性窄修复通过。默认开关及关闭／恢复行为、深浅色产物架／预览、来源和长 slides 导出操作已有对应证据，最后暗色 chart 字色复验通过。Phase 3 review 范围没有未处理的逻辑／调用链／已观察 UI 缺陷；实际导出文件内容证据另由主 agent 记录，不将截图等同于文件字节验收。
