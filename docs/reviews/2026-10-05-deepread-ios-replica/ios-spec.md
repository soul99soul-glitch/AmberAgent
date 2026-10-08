# iOS 独立深读复刻规格（当前工作树）

采样日期：2026-10-05。只读检查源根 `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp` 及其实际引用的 `iosApp/iosApp/DeepReadCore`。本规格编写者没有构建、启动或修改 iOS 应用。以下是源码可证规格，不能代替截图、真机触感与系统原生控件尺寸的视觉验收。

iOS 仓 HEAD 为 `4782e5a04e5700c34c7bf1016dcb8f047e66e6e4`，但独立 `DeepReadApp/` 及多个 `DeepReadCore` 文件在当前工作树为未追踪文件，EditorialRenderer 等文件也有 WIP。故复刻基准是这次读取的当前工作树，不能描述为该 commit 已提交的产品。

## 1. 导航结构

- **底部 3 个 Tab**：`发现/newspaper` → `阅读库/books.vertical` → `设置/gearshape`，顺序固定，每个 Tab 有独立 `NavigationStack`。
- **没有独立创建 Tab**。发现导航栏右侧有热点来源设置与创建阅读按钮；阅读库右侧有创建按钮。创建使用 `NavigationStack` 内的模态 sheet，完成后退出 sheet 并在原 Tab push 文章详细页。
- **没有独立编辑室 Tab**。编辑室是详细页的生成等待界面：仅当 queued/running 且文章 HTML 尚未生成时显示。若已有稿件，则优先显示已有 HTML 与实时状态，后台重试不抹掉正文。
- 设置 → 阅读模板 → AI 阅读模板；自定义 HTML 编辑器、模板预览均为 sheet。
- 全局 tint 随强调色变化；Tab 选中触发 selection haptic；iOS Tab 栏采用 `.tabBarMinimizeBehavior(.onScrollDown)`。
- Tab 切换旧内容立即 opacity=0（纸背景保留），到达内容从 `30 * direction` 水平位移弹簧归零，约 0.3 秒；不做缩放或纵向移动。首次启动 direction=0 不动画，返回文章也不是新到达。

证据：`Sources/App/DeepReadApp.swift`、`Sources/Design/DeepReadDesign.swift`。

## 2. 基础视觉参数

### 2.1 颜色

| token | 浅色 | 深色 | 用途 |
| --- | --- | --- | --- |
| paper | `#FBF7F1` | `#201C19` | 全屏暖纸背景 |
| card | `#FFFDF9` | `#2A2521` | 列表与表单纸卡 |
| ink | `#2A2320` | `#F1E9DF` | 主要文字 |
| muted | `#6E6254` | `#B9AA98` | 次要文字 |
| rule | `#E4D9CB` | `#3D3530` | 壳层细分隔与卡边 |
| done | `#3F6A4A` | `#8DBB98` | 完成 |
| warn | `#8F5A10` | `#E2A75A` | 活跃任务或不完整结果 |
| danger | `#A8261F` | `#FF7B6E` | 错误 |

5 个强调色色卡：朱砂 `#C8402F/#E67261`（默认），黛蓝 `#2F5D8A/#7FA8D6`，松绿 `#2F6B57/#7DBFA4`，藤紫 `#6B4C9A/#B39DDB`，墨黑 `#2A2320/#F1E9DF`。强调色即时应用并持久化；正文强调色同步。

### 2.2 尺寸与字体

- 列表页横边距 18 pt、底部 28 pt、内容 maxWidth 760 pt，宽屏居中。
- 通用 card：radius 18，边框 0.6，阴影 ink opacity 0.06/radius 10/y4。
- 点击按压：scale 0.975、brightness -0.02，spring response0.28/damping0.62。
- `headline` 17 pt、`subheadline` 15、`callout` 16、`footnote` 13、`caption` 12、`caption2` 11、`title3` 20、`title2` 22 是 iOS 默认动态字号类别的常规值；源码使用动态字号而非固定值，系统/辅助功能实际尺寸须截图确认。
- 壳层标题用 `.system(..., design:.serif)`，正文通常系统默认；HTML 正文明确使用应用内 Noto Serif SC（`AmberDeepReadSerif`），代码 JetBrains Mono，切换系统字体时使用 sans stack。壳层不能误用 HTML 字体声明作为已证字形。
- 通用列表入场：opacity0→1、y26→0，spring0.55/damping0.82，延迟 `min(index,8)*0.05`，长列表后续行不继续加长延迟。
- 文字按钮通过布局外扩充到至少 44 pt 点击区域，外扩不改变可见排版。

### 2.3 纸背景与夜读

- 背景忽略安全区。确定性散点纸粒子：面积/260 个、1.1×1.1、ink alpha最多0.05。
- 夜读时间是当地 22:00–04:59；发现、阅读库、设置开启台灯光晕。光色 RGB(1,.78,.45)，中心(.82,0)、半径10→520，深色 alpha.26/plusLighter、浅色 alpha.2/normal；入场 easeOut1.6s、delay.2。减弱动效时 easeIn.3s。
- 纸粒子和灯光都不可截获点击。

证据：`Sources/Design/DeepReadDesign.swift`、`iosApp/iosApp/DeepReadCore/DeepReadAppearance.swift`。

## 3. 发现

**布局**：ScrollView → LazyVStack(alignment leading, spacing10) → 刊头 → 顶部错误卡（可选）→ 多来源热点区 → 各单来源榜单卡。导航标题“深度阅读”为 inline，右侧依次筛选线图标/创建方形笔图标。

**刊头**：leading VStack spacing10、top4；最上方 ink 横线2.5高；其下日期行：`No.%03d`（任务数+1，等宽数字）与中文“月日 星期”之间 gap8，caption medium/muted。右侧徽章 gap6：夜读、当日节日。窄屏或大字号日期与徽章换为上下排列 gap6，不压缩遮挡。刊头主句“把热点读深一点”：28 pt semibold serif，允许辅助功能放大，上限 accessibility2。

**节日/夜读徽章**：caption semibold、文字 ink/图标专属 tint、padding横8纵3、胶囊背景 tint alpha.12，图标与文字 gap4。2026-10-05 命中国庆1–7日徽章“国庆 · 慢慢读”。

**多来源区标题**：top10；HStack firstBaseline gap10；“多来源热点”title3 semibold serif，右侧“多个榜单都在讨论”caption muted，再延伸 rule0.8 横线。

**多来源热点卡**：HStack firstBaseline gap12、padding14。左侧排名 `%02d`，22 light serif、monospaced/accent；中间 VStack spacing6：title headline serif/ink/最多3行，来源摘要 caption muted/最多2行；右侧 up-right arrow footnote semibold/muted。无额外大图、标签堆叠或夸张卡间距。

**单榜卡**：VStack spacing0、padding14。卡头榜名 headline serif/ink；右侧 stale 时“上次内容”caption2/muted；卡头底间距8。provider错误或旧内容说明 caption muted、底间距6。条目之间分隔线0.6；行 HStack firstBaseline gap10、vertical12，数字 callout serif（前三 bold/accent，其他 regular/muted），minWidth26/单行/minScale .7；右侧标题使用系统默认正文/ink。整个榜单共用一张 card。

**状态**：首次加载且无内容显示3张骨架topicCard，opacity .45↔1、easeInOut .9；空内容显示原生空态“暂无热点”，说明随已启用源为空而变化；刷新失败可保留旧榜单并明确写“暂时无法更新，显示上次取得的内容。”；Wi-Fi only 当前不满足时有明确提示。支持下拉刷新，前台与配置变更触发刷新。

**点击/长按**：普通点击自动选择文章精读或多源综述；文章类来源优先精读，讨论类话题综述。长按菜单在可精读时有“精读原文 / 只读原文”，始终有“多源综述”。进入详情有源卡 zoom 过渡。

**彩蛋**：刊头1.6s内5次点按盖46pt章（之后每点换印文：深读/慢读/求真/存疑/博观/约取）。摇一摇卡片散开后复位，底部出现8s幸运文章玻璃横幅，可“读这篇”或关闭。减弱动效时不散开。摇一摇只在发现页可见且未打开创建 sheet 时触发。

证据：`Sources/Discovery/DeepReadDiscoveryView.swift`（457行）。

## 4. 阅读库

- 标题“阅读库”，使用原生大标题导航（未设 inline）；顶部原生搜索，placeholder“搜索标题与正文”，同时匹配标题与 resultMarkdown。
- ScrollView + LazyVStack spacing12。首项为横向筛选 chips：全部、待生成、生成中、已完成、失败、不可用；chip gap8、horizontal14/vertical7、额外vertical6得到44pt点击高度；选中 accent 实心、字体subheadline semibold/paper，未选rule描边、regular/ink。选中色胶囊通过匹配几何 spring .35/.75 移动；切状态 selection haptic。
- 文章card：padding16，VStack spacing10。首行左状态chip，右中文年月日 caption muted；标题 title3 medium serif/ink；sourceSummary caption muted最多2行；运行错误 danger caption最多2行。
- 状态chip：gap6、圆点7、caption semibold、padding横9纵4、capsule底色tint alpha.11。queued/running=warn、failed/unsupported=danger、succeeded=done（有missingSections则warn）。活跃圆点opacity1↔.25，easeInOut .7；部分成功文案“已完成 · 部分章节待补全”。
- 滚动过渡在离开中心阶段 scale.95/opacity.5；减弱动效不改变。点文章 zoom push 到详情。
- 首次无文章空态“你的阅读库”，说明“从一个主题、一段文字或几个来源开始。”top40；有筛选/查询但无匹配则“没有匹配的文章”。
- toolbar右侧创建方形笔，打开创建 sheet。没有永久悬浮创建区或独立创建Tab。

证据：`Sources/UI/DeepReadLibraryView.swift`。

## 5. 创建阅读（sheet）

原生分组Form（纸背景、各行card）+ inline“创建阅读” + 导航栏“取消”。Section顺序固定：

1. **阅读主题**：多行TextField“想深入了解什么？”title3 serif。综述提示自动多角度搜索；单一原文精读提示可以不填，使用原文标题。
2. **补充来源**：粘贴文本可选（4–10行）；网页链接每行一个可选（2–5行、URL键盘、不自动大小写/纠错）；导入文件按钮；读文件进度；文件列表（最多2行title/图标、长文截断说明、右侧移除）。文件行入场moveLeading+opacity。
3. **阅读方式**仅输入来源总数=1时出现：segmented“精读 / 只读原文 / 多源综述”，解释文案随选择变化。精读以原文为正文，提供导读/模板模块/段落批注；原文模式只抓取排版、无模型调用、稍后可生成精读；综述继续搜索。
4. **版式**仅不走原文/精读时出现，Picker“生成模板”，包含8内置选项+自定义模板。
5. 错误区（有错误才出现），danger可选择文字。
6. 主按钮玻璃突出“开始深度阅读/sparkles”，只读原文为“打开原文/doc.plaintext”。maxWidth infinite、vertical6；不必补标题的精读可空标题，其余标题为空或读文件中禁用。

创建成功退出sheet并push详情。网页地址必须完整http/https，有错误明示保留输入。多文件导入、来源移除、长文件截断说明不能在复刻时失去。

证据：`Sources/UI/DeepReadComposerView.swift`。

## 6. 文章详细与来源

- inline导航“深度阅读”，主体VStack spacing0 → 条件状态panel → 条件错误 → HTML文章/编辑室/未生成空态。成功无警告文章不常驻状态卡。
- **状态panel**仅非成功、有missingSections、持久化错误、运行错误或搜索抓取失败时显示：VStack spacing8、padding16、glass radius20、maxWidth760、左右16、top6。第一行实时状态subheadline medium，活跃时左spinner/右“取消”；失败、不支持、不完整、抓取失败或运行失败显示“重试”，有缺章显示“补全章节”。明示failure、持久化错误、搜索缺失数与待补全章节，不能被装饰动效覆盖。
- 有AI guide精读时导航中心segmented“精读 / 原文”。无guide时默认原文；已完成原文可点击“生成精读”。
- 导航右侧按数据类型显示“版式与样式/textformat”菜单、来源/link、分享方框箭头。普通内置杂志可选4版式与8样式；精读及template-synthesis保持原结构，仅切样式；custom HTML自己的设计不被内置style强加。
- 分享菜单文本/Markdown/PDF；导出中spinner且禁用；无resultMarkdown不可分享。PDF隐藏远程图像、展开脚注；Markdown/文本按最终结果导出。
- HTML出现easeOut .5，opacity+offsetY24入场。已有稿件运行重试时仍显示文章，只更新顶部状态。
- **来源sheet**：NavigationStack + List，标题“原始来源”，完成关闭。每来源一Section：headline标题、caption类型、有效URL Link（footnote可选）、抓取状态caption、抓取失败danger footnote、“查看采集内容”DisclosureGroup（footnote可选文本）。
- WebView透明且非持久化，无JavaScript。HTTP/S外链调用系统浏览器；失败/进程终止有用户可见错误。

证据：`Sources/UI/DeepReadDetailView.swift`、`DeepReadArticleWebView.swift`、`DeepReadArticleRenderer.swift`。

## 7. 编辑室（生成等待界面）与完成时刻

- 在详情剩余可用空间水平/垂直居中；小屏、横屏或大字号容纳不了时改ScrollView；padding24、maxWidth520。
- 主VStack spacing28：4节点stage rail → 主图标/标题（spacing10）→ 活字标题盘 → 轮换桌面格言 → footnote“可以离开此页面，稍后在阅读库查看进度。”。
- 节点34圆、stroke1.5；节点间line2/top16（对齐节点中线）；节点下caption2 gap6。完成节点accent实心白check，当前节点accent描边/图标，未来节点rule描边/muted图标；当前外圈2描边每1.2s由1倍扩至1.55且淡出。
- 阶段为采访/magnifyingglass、整理/doc.on.doc、撰稿/pencil.and.scribble、付印/seal。主图标34 medium accent/breathe；标题title2 semibold serif：“记者正在多方采访 / 编辑正在整理素材 / 主笔正在撰写文章 / 即将付印”。阶段切换snappy + 图标replace、标题opacity；rail spring .6/.8。
- **真实进度映射**：progressLabel含“抓取”→整理；含“保存”或以“扩展阅读”结尾→付印；前缀“正在生成”→撰稿；其余采访。不能按动画时间自动演进stage。
- 活字：取主题前10个非空白非标点字；HStack gap5、每字maxWidth28/height32、corner5、空槽rule虚线[3,3]，填入ink底/paper字17bold serif；每.3s落1字，留6tick后重新循环，offsetY-28入场，spring .32/.58。减弱动效直接全部排好。
- 格言每4.2s切换，callout serif italic/muted、预留2行高度，pushBottom+opacity .5s；clipped防旧新两行叠出，减弱动效仅opacity。
- **完成印章**仅当前用户看着的queued/running→succeeded且无运行错误时展示：全屏ultraThinMaterial，128章，caption headline serif；重试失败留下旧稿不可盖新成功章。首篇/十篇/百篇只在首稿成立；一般“付印”1.4s，里程碑2.6s，可点击提前关闭。
- 通用印章：外线size*.07/圆角*.14，内线*.02/圆角*.08/内缩*.1，竖排两个字*.3heavy serif、opacity.88；浅色multiply、深色normal。动画scale2.6→.92(.18s)→1(.35s)、angle8→-10、opacity0→1(.14s)，减弱动效不大幅缩放。

证据：`Sources/UI/DeepReadNewsroomView.swift`、`iosApp/iosApp/DeepReadCore/DeepReadNewsroom.swift`、`Sources/Design/DeepReadDesign.swift`。

## 8. 设置与子页

设置是原生分组Form、纸背景、card行，导航标题“设置”（大标题）。节顺序：

1. **外观**：5色一行，色点32、选中环42/stroke2，点击槽44、下caption2 gap6，每色平分可用宽；未选点scale.9、选中check。spring .4/.7迁移选中环。“版式与样式”push阅读模板，右值“经典 · 经典杂志”。即时生效。
2. 凭据错误（有错误才出现）：danger错误、“重新读取凭据”。
3. **文章生成**：当前模型picker→每模型导航行（name+caption模型ID）→添加模型服务menu（OpenAI兼容、Claude）。支持删除。说明API凭据存应用Keychain。
4. **多来源搜索**：首选服务picker→各服务导航行（name+启用/停用caption）→添加服务menu→结果数Stepper1...50。
5. **免费聚合与正文读取**：DuckDuckGo、Bing、Jina搜索与正文补充、Wikipedia、Hacker News、Google网页补充6个Toggle。
6. **阅读与排版**：字体serif/system；字号显示百分比+slider0.7...1.8/step.05；生成模板picker；管理与创建模板导航。
7. 玻璃突出“保存并应用/checkmark.circle”，成功显示“已保存，下一次生成将使用此配置。”3s后消失、success haptic。外观即时持久化；生成/搜索/字体等配置需要保存，不能混为全都即时。

模型编辑器：名称、协议、启用、服务地址、模型ID、API Key；OpenAI切Responses时隐藏Chat Completions路径，Claude有Prompt Caching。返回父页后保存；独立应用无OAuth登录。

搜索编辑器：服务名、启用、API Key（Jina可选；免费聚合/SearXNG不显示API Key）；不执行服务显示warn说明。SearXNG有地址/搜索引擎/语言/用户名/密码；Tavily/LinkUp/AmberAgent有深度；Perplexity token限制；Jina搜索/正文地址；博查摘要Toggle；Grok模型/地址/系统提示词。不能因视觉统一隐藏必要配置。

热点来源设置（发现push）：刷新间隔15/30/60/120；Wi-Fi only；英译中；关注关键词与全部/关注优先/只看关注；各分类内来源Toggle。原生Form、paper/card、标题“热点来源”。

证据：`Sources/Settings/DeepReadSettingsView.swift`、`DeepReadConfigurations.swift`、`Sources/Discovery/DeepReadDiscoverySettingsView.swift`。

## 9. 版式、样式与模板

阅读模板管理为List，paper/card，标题“阅读模板”；顺序为4版式、8样式、8生成模板、自定义模板。

- **4版式**：经典、速读、时间线、观点对照。row HStack gap14；图标title3/accent，预览48×60（paper、radius8、rule边框）；名称系统body；详情caption/muted、VStack gap4；右侧选中check。版式控制内容顺序，不能只换标签。
- 经典顺序按structured standard；速读=核心脉络、分析、待核实、图示、时间线、延伸阅读、来源；时间线=时间线、图示、脉络、分析、待核实、延伸、来源；观点对照=分析、待核实、脉络、时间线、图示、延伸、来源。
- **8样式**：经典杂志、书卷、简报、手账、报纸、极简、学术、彩页。预览48×60/radius8，在实际style背景上画“文”17pt、accent短横18×3、正文模拟横线30×2/24×2；预览不是通用假图片。
- 样式语义：书卷居中衬线/首字下沉/段首缩进；简报sans标题/编号；手账点阵18网格/高亮/胶带标签；报纸双线头与粗标题/宽屏640以上双栏；极简白纸sans/去横线；学术摘要框/章节编号/来源编号；彩页accent反白大报头/卡片脉络/反白引言。
- **8生成模板**（顺序）：自动、默认杂志、斜切图文、速览简报、问答解读、观点交锋、时间线报道、评测汇总。生成模板图标预览48×48。内置点击保存为默认；这改变生成结构，与读者style/layout是不同设置。
- **自定义模板**：名称+caption描述+默认check；点行编辑，长按用于新文章/删除，删除确认说明历史文章回默认版式；“新建HTML模板”sheet；“用AI生成模板”push。
- **编辑模板**：inline“编辑模板”+取消；名称、描述；HTML/CSS TextEditor caption monospaced/minHeight300；占位符说明；校验模板/校验结果；预览；错误；保存并用于新文章。变更后清除旧校验状态，编辑未保存时阻止手势关闭（取消仍可明确退出）。预览sheet标题“模板预览”、完成关闭、HTML WebView。
- **AI模板**：Form标题“AI阅读模板”；name默认“我的阅读模板”、要求多行5–12；生成草稿按钮，空要求/生成中禁用；ProgressView“正在生成并校验模板…”；说明使用当前模型；校验通过后sheet到编辑器，错误保留显示。

证据：`Sources/UI/DeepReadTemplatesView.swift`、`DeepReadTemplateGeneratorView.swift`、`iosApp/iosApp/DeepReadCore/DeepReadAppearance.swift`、`DeepReadSynthesisTemplates.swift`。

## 10. HTML 正文关键尺寸

**默认杂志**：body0 margin/padding；article底12；每section左右22、top28；h1=32/weight500/line1.13/margin12 0 16；h2=18/650/1.34/bottom6；body p=15/line1.68/bottom13；section/kicker标签10 sans/letterSpacing .18em/muted；引用18/1.48/左线3+padding12；带图片模板hero图265高/cover，斜切覆盖106高、top-54、polygon(0 24%,100%0,100%100%,0 100%)、padding46 22 0。默认杂志隐藏所有figure，斜切图文才展示配图。

**精读**：section左右22、top28；h1=28/600/line1.25/bottom8；p=15/1.75；kicker10/accent/top22/bottom10；原标题13/italic/muted；byline12/gap8/底线/paddingBottom14；导读surface背景/radius14/padding14 16/top18；段落grid左编号22+正文、gap4 8、bottom16；段落号10、段落批注summary12/line1.55、折叠正文13/1.7。脚注用HTML details原地展开，无JavaScript。760宽以上批注独立右栏270。原文模式去编号/批注/AI块保留原段落和图片。

经典reader画布与壳layer card有意不同：浅色surface `#F2EADE`、border `#DBCEBC`；深色surface `#2A2520`、border `#54493D`。其他8style的全部canvas/CSS以 `DeepReadAppearance.swift` 为精确源，应复用现有等价实现，不能把所有样式替换为同一暖纸底。

字体缩放统一 clamp0.7...1.8；用户选system时正文sans；article强调色与全局accent联动。

证据：`Sources/UI/DeepReadArticleRenderer.swift`、`iosApp/iosApp/IOSDeepReadEditorialRenderer.swift`、`iosApp/iosApp/DeepReadCore/DeepReadCloseReadingRenderer.swift`。

## 11. 鸿蒙复刻时不可丢失的用户能力

- 发现来源/刷新/关注设置、旧内容保留、多来源与单榜点击、可选精读/原文/综述。
- 创建来自主题/文本/多链接/多文件，来源移除、单源模式切换、按任务模板覆盖默认。
- 阅读库完整状态筛选、标题与全文搜索、返回原Tab/原列表状态。
- 生成可离开页面继续、当前真实阶段、取消/重试/补全、失败保留已有稿件、存储失败可见。
- 完整正文、精读/原文切换、之后生成精读、来源采集详情与错误、外部链接、文本/Markdown/PDF导出。
- 5强调色、4阅读编排、8样式、8内置生成模板、自定义HTML校验/编辑/预览/选择/删除、AI生成模板。
- 模型与搜索配置全部既有可执行能力与必要字段、持久化与应用；视觉复刻不应把Harmony端已接入能力削掉。
- 深浅主题、大字号、窄屏/横屏、44点击区域、减弱动效、进入文章与退出sheet的正确导航。

## 12. 验收边界

1. 必须按“发现/阅读库/设置”3Tab与创建sheet核对导航。用户要求的“完全按照iOS”不能保留鸿蒙独立创建Tab并仅改配色。
2. 使用当前iOS同场景截图核对刊头、单榜密度、阅读库卡、创建Form、设置Form、文章导航与等待编辑室。源码未提供系统TabBar/NavigationBar/Form准确像素坐标，原生系统外观无法仅由这些常量证明。
3. 纸背景是连贯页面背景，成功正文没有多余常驻状态卡；真实失败/部分结果不能为画面干净而隐藏。
4. 区分源码/构建/模拟器视觉/真机触感/真实provider五类证据；本规格只提供源码证据。

## 13. 初始参考截图与源码不一致（已识别）

主agent于本轮保存的 `reference/ios-discovery-light.jpg` 和 `reference/ios-library-light.jpg` 均为368×800图像。只读查看后发现旧已安装binary的发现页有当前源码没有的hero说明和红色“＋ 创建阅读”按钮，顶部只有热点筛选图标，日期含年份，第一张热点卡显示5行标题。

当前源码则仅有导航栏创建入口、日期没有`.year()`、topic title明确`.lineLimit(3)`，也没有hero说明。因此初始截图不是当前源码完整视觉证明。主agent已决定重新编译当前iOS源码，以重新编译后的画面为视觉验收基准。不能为复刻旧binary向Harmony加来源代码没有的hero文案/常驻按钮，或擅自取消3行标题上限。

阅读库初始截图确证原生大标题、标题/正文搜索框、横向chips、3Tab浮动胶囊栏；其内容区域空白不能证明当前源码没有空态，当前源码明确包含“你的阅读库/没有匹配的文章”ContentUnavailableView。正式验收需使用重新编译的同状态截图。
