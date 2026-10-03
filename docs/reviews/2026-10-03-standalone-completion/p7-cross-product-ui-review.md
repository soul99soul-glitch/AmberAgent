# P7 跨产品 UI 独立审查

本report由当前reviewer独占，生产与设备只读、不构建。分工排除Novel项目卡片（projectmeta owner）、DeepRead Board/History功能与Newsroom/随机阅读（history UI owner），不重扫已关闭P6缺陷。范围为其他阅读页、设置、Compose、资料编辑、长标题与确认弹层。**最终独立source/device scope gate：PASS；P7-UI01/02/03均closed，无已确认未修缺陷。** 初始表格为选case基线，实际验收范围与未穷尽边界以下文为准，不把所有检查提议当成待修产品bug。

## 实际字号与主题路径

- 独立深读设置→阅读与发现→SettingDeepReadPage.ReadingCard，“字号”Slider70–180%；保存deepread_font_scale。Article.readDisplayPreferences在缓存早退前读取并clamp0.7–1.8；A-/A+写同key并影响原生阅读、captured HTML与PDF。180%为深读阅读内容倍率，不等于全应用或小说UI字号倍率，验收要明确实际操控的设置。
- 原生DeepReadNativeArticleBody标题30×scale、正文/时间线/引文/来源均乘scale；正文Scroll，人物实体Flex wrap；图表传入同scale。大字下长heading/source Label Row是否挤压需实际图。杂志HTML与PDF从当前render options获取scale，不能用原生截图推断Web版与PDF均通过。
- 两独立应用设置→外观进入SettingAppearancePage；当前主题纪元重挂载分别由相关page ForEach(themeEpoch)更新令牌。资料sheet的主页面主题重挂载与IME输入保留要实屏观察；不新增字号framework或全局token审查。

## 精确可达cases与风险位置

| 实际入口 | 观察目标 / 位置 | 需要的P7实际证据 |
| --- | --- | --- |
| 小说Workspace正文→章节→右上菜单→编辑本章→查找和替换 | NovelChapterReaderPage.FindReplacePanel两输入及上一处/下一处/替换当前/替换本章全部固定40vp，源上低于phase44vp热区目标（696–718）；该panel没有独立Scroll，IME缩小85%编辑sheet时需要核正文仍有空间与footer可达。 | 同时有匹配结果的AX，逐控件热区/IME底部/编辑正文可见；先确认真实bounds，再决定局部修尺寸。 |
| 小说Workspace资料页→新建/编辑资料 | NovelMaterialEditorSheet为88%父sheet，正文150vp输入+metadata Scroll，底部44vp保存/取消；父Workspace启用KeyboardAvoidMode.RESIZE。别名、tag、customKind与错误长文在内部Scroll。 | 长名/alias+tag+键盘时两个固定footer全可达、无重叠；滚metadata底部证明真正可达，截未保存确认。 |
| 同资料编辑→取消/遮罩/返回 | cancel stopEditing→commitFields→0ms defer→比较draft与original→AlertDialog；有alive/inputToken防迟回。 | 键盘撤下后的“放弃资料编辑”确认及继续编辑/放弃真实动作；不把这条已审caller重复设计。 |
| 小说Reader长章节标题/版本正文 | CenteredTitleBar title受布局与省略约束，version/history/modal长名是否压按钮需实际图；Reader.EditSheet保存取消最少44vp，底部nav安全padding。 | 长标题+菜单、版本预览、IME修改标题、未保存确认；无需重复领域恢复或版本功能测试。 |
| 小说Workspace项目设置 | 项目润色TextArea112vp，两保存/再生成动作min48vp、左右20vp；种子与说明自然占高，Scroll TopStart。 | 长种子/长偏好IME、保存动作可滚，当前界面不擅改项目名；项目卡长名归另owner。 |
| 深读首页创建→DeepReadComposePage | 长主题、长文件名(max2行)有移除44vp、长模板名自然占高；生成52vp在父Scroll内。单帧IME导致生成按钮离屏本身不是错位。 | 主题/来源输入IME、滚到底生成完整、长选模板与文件名不挤移除/按钮。 |
| 深读Article→重新生成 | DeepReadConfirmationDialog82%居中card，文案width100%、底部Text动作padding12，非固定footer；需核实际44vp及长文/大字时卡不溢屏。 | 最终主题/180%场景JPEG+AX；180阅读倍率不作用于dialog时如实记录，不虚构弹层已按180放大。 |
| 深读Article原生与HTML / PDF | 180%题文、引文、时间线、diagram、来源链接；原生Scroll与Web各自布局。 | 分别长标题与内容、最下面来源或footer可达；PDF应为独立图，不混为普通Article。 |

## 审查方式与门禁

source风险仅用来挑选可达设备case，实际finding需要触发、确切JPEG/AX、文件定位与最小修复。读取新P7图后逐项比较热区、对齐、边距与安全区；普通Scroll裁切、既定长标题省略和系统原生文件面板默认尺寸不自动判缺陷。主agent负责新包构建/签名/安装/操作，此review只读图与日志、更新报告。

当前不要求重复已通过领域功能测试；若真设备找到回归，再用实际生产方法复现并交明确owner修，不加镜像实现测试。与另外两owner的范围重叠只转交证据，不独立改其源码或重复功能评审。

## P7-UI01：展开查找且键盘可见时，正文与底部保存区被压缩（P2，closed）

独立实际view_image检查`device/p7/find-replace-dark.jpeg`、`find-match-keyboard-before.jpeg`及各自AX。后者输入Rhea并有1处匹配，上一处/下一处与两替换动作均enabled。六个查找控件的真实高度均140px，即40vp，低于44vp目标。正文TextArea实际bounds y1440–1524，仅84px（24vp）；取消/保存实际bounds y1618–1757，高139px，而origBounds终点1772、高154px，底部被IME起点1757裁掉15px。前者无键盘时正文y1338–2475、footer y2569–2723，高154px正常。因而这次是具体键盘布局回归，不能仅凭无键盘图宣布footer通过。

定位`NovelChapterReaderPage.ets`的`FindReplacePanel`（约696–718）和`EditSheet`（约730–791）：85%固定Column同时放header、find面板、章节标题、layoutWeight(1)正文和footer，find面板新增的匹配预览与替换按钮直接挤占正文。六个控件改44vp单独不能闭环：它们分布在四个纵向行，进一步增加16vp占高。

交root的最小方案：仅`findOpen`分支让中间输入区成为`Scroll.layoutWeight(1)`，内容依次为查找面板、章节标题、正文、字数及错误；header和取消/保存footer留在外层。该分支正文用明确有限高度（建议沿用资料编辑的150vp），不把`layoutWeight(1)`留在Scroll无界内容里；正常未展开查找的编辑保留既有正文flex。六个查找控件40→44vp，footer保证44vp。继续使用直接读取组件状态的@Builder，不引入primitive状态传参或新的键盘策略。

修复后需实际带1处匹配与IME复验两个位置：查找区在顶部、滚至正文编辑。两帧footer应完整154px且结束在1757以内，正文实际可输入/滚动，标题与查找动作可达；另核普通编辑仍为原flex正文。当前仅提出局部方案，未修改生产源码，未关闭finding。

### Source 修复复核：PASS

独立复核当前`NovelChapterReaderPage.ets`约688–797：六个查找控件已改44vp；`EditorBody`为private @Builder直接读取`this.findOpen/editTitle/editContent/busy`，未传primitive状态快照。展开查找分支的中间`Scroll.layoutWeight(1)`包含面板、标题、明确150vp正文、字数与错误；外层header保持原位置，footer Row明确44vp。未展开分支仍为`Column.layoutWeight(1)`加正文`TextArea.layoutWeight(1)`，因此普通编辑保持弹性正文。

两个正文分支绑定同一个`editContentController`、直接onChange写同一个`editContent`，标题controller与onChange也保留；替换→编辑草稿、取消→提交输入后dirty确认、保存→提交输入后携带`editExpectedCas`调用saveChapter→reload链未被布局抽取改变。没有新增键盘策略或输入状态复制。当前source满足局部修复方案，没有新增确认缺陷；88项既有Novel UI测试由root执行PASS，本review不将该测试结果替代IME实屏。独立设备复验见下一节。

### 新包设备复验：PASS / closed

独立实际view_image检查`device/p7/find-match-keyboard-fixed-top.jpeg`与`find-match-keyboard-fixed-body.jpeg`，并逐项读对应AX：两帧同一编辑弹层、键盘均可见。顶部帧Rhea显示1/1匹配，六个查找控件实际/原始bounds高度均154px（44vp），匹配预览与左右动作无重叠；正文帧标题154px、正文`[63,851][1257,1376]`高525px（150vp），原始bounds一致，正文两段可读、字数在下方。

两帧header位置不变、取消与保存均`y1470–1624`、高154px、实际bounds与origBounds完全一致；按钮底1624与IME起点1757之间保留133px空间。左右边界63/1257与标题/正文统一，两个底部动作各宽597px、基线一致。顶部帧标题在Scroll底缘局部裁切、正文帧替换动作在Scroll顶缘局部裁切，属于连续滚动内容的正常窗口边界；相邻帧分别证明完整可达，不是固定footer裁切或错位。没有新增确认布局缺陷。P7-UI01关闭，不据此宣称其他P7设备case已通过。

## 资料编辑、键盘与未保存确认：设备 PASS

独立实际view_image与AX检查`material-edit-dark`、`material-alias-keyboard`、`material-tags-keyboard`、`material-discard-dialog`、`material-discard-restored`。无键盘时Rhea标题44vp、正文150vp，别名和标签各72vp；长别名草稿实际为`CaptainRhea, Returning-Rider-With-A-Long-Name, Courier, Guardian-Of-The-Northern-Storm-Map`，可换行。alias键盘帧输入区在Scroll下缘部分裁切，滚至metadata下一帧证明完整尾部、标签、自定义分类与三项注入选项可达；不把Scroll窗口裁切判为固定布局错误。

键盘两帧取消/保存分别`[70,1505][639,1659]`及`[681,1505][1250,1659]`，均高154px（44vp）、实际bounds等于origBounds，IME1757前留98px；左右间距、底部基线一致。三项注入选项也均154px，无重叠。取消后键盘撤下，系统“放弃资料编辑？”dialog完整居中，正文和两个动作可读；系统Button实/原140px是原生默认尺寸，未将其改造为应用自绘弹层。实际放弃后资料卡显示原`CaptainRhea、Rider、Courier`和`storm`，长别名草稿未写入资料。证据证明本次放弃路径，不声称另一个“继续编辑”动作也已设备点击。

## 深读Compose：长输入与底部布局已读；P7-UI02主题输入40vp（P2，closed）

独立实际view_image与AX检查`compose-long-title-keyboard`、`compose-keyboard-template`、`compose-full-footer`。长主题AX保留完整`P7 Native Long Title Verification: Preserving Local Sources And Four Writing Stages Across Independent Reading Applications`，单行控件展示当前可见尾部属于输入横向滚动；文本来源完整多行展示，本文证据为手工文本输入，不是文件导入。IME下模板卡可滚到，收键盘后已选“默认排版”，自定义A与杂志版式及提示正常分行，完整生成动作`[70,2422][1250,2604]`高182px（52vp），下方说明不重叠。

唯一新增确认尺寸finding：主题TextInput实际bounds与origBounds均`[70,443][1250,583]`，高140px（40vp）。`DeepReadComposePage.ets`主题输入（约135行）未指定height，落到默认40vp，低于本phase应用输入44vp目标。最小修复仅该TextInput加`.height(44)`，保留onChange/busy和现有Scroll，不扩展全局控件或另造底部栏。待最新包AX154px与长主题输入实屏后关闭。

本组IME截图未出现完整生成按钮，root实操反馈滚动曾回到focused输入；当前仅证明IME输入及模板滚动、收键盘后生成52vp完整可达，**不声称生成按钮在IME展开时可达**。缺少该状态的完整证据不自动扩展为新的滚动实现bug。

### P7-UI02最终复验：PASS / closed

独立检查最终包`compose-44-keyboard-final.jpeg/json`：明色主题、IME展开，实际主题文本`P7 Final Touch Target Check`，TextInput实/原bounds均`[70,443][1250,597]`，高154px（44vp）；文字垂直居中，左右边距与文本/URL输入一致。当前源码只为主题TextInput加height44，未改title onChange或生成输入提交链。长主题的横向滚动已在先前长主题帧检查；最新尺寸帧是普通长度文本，不冒充重跑同一长主题。P7-UI02关闭，IME生成的证据边界维持上述说明。

## P7-UI03：重新生成确认动作低于44vp（P2，closed）

独立检查旧`regenerate-longtitle-light.jpeg/json`，居中卡片宽82%，警示两行与分隔线正常，但“取消”“重新生成”的实际/原始bounds均`y1552–1698`，高146px（约41.7vp），低于本phase应用动作44vp目标。定位`components/DeepReadConfirmationDialog.ets`两个Text动作约45–68行，原来只有字体和上下12vp padding，缺少最小高度。

复核当前source修法：只在两动作原padding之后增加`constraintSize({ minHeight: 44 })`，原onDismiss/onConfirm回调、layoutWeight、警示文字与card间距保留。最终`regenerate-44-final.jpeg/json`两动作实/原bounds均`y1548–1702`，高154px（44vp）；各宽463px，中间分隔线与基线对齐，标题、两行警示和动作无重叠，卡片完整居中。阅读180%大字在遮罩后可见，但该弹层使用原UI字号，未声称弹层也放大180%。P7-UI03关闭。

## 180%阅读、明暗主题、长文与PDF：本次设备样本 PASS

独立逐张view_image原生`native-180-light-top/body/timeline/bottom`及对应AX：长英文主题自然分行，中文长正文/分析保持左右阅读边距，没有文字相互覆盖或横向越界，固定header/完成操作栏位置稳定；图解末尾三节点与两条连线说明完整可滚。`native-180-light-timeline`文件名下实际显示的是作者分析段，不能据此声称原生时间线组件已验；顶图还在98%任务状态，后续三图显示完成，这里只记录实际可见状态。Scroll顶/底的单行截断是阅读窗口边缘，不认定为正文丢失。

独立查看`html-180-dark-top/body`、`html-180-light-top/body`：自定义P6 Captured Template A长英文标题、引文、实体标签、时间线和要点卡在180%下正常换行，明暗两主题字色与背景可读，HTML内容没有压入固定header。暗色body看到核心要点/分析，明色body看到实体标签与真实时间线；没有把两帧截在不同段落当成渲染差异bug。

独立查看`pdf-180-light-preview`、`pdf-180-saved-newname`、`pdf-180-native-share-ready`：A4导出预览显示同篇标题与引文、顶部关闭/返回菜单AX高154px（44vp），底部保存/分享各168px（48vp）、实/原bounds一致且左右对齐；保存后出现“PDF文件已保存”，真实系统ShareKit显示PDF缩略图、文件名与469.53KB。该组证明180%选项的导出预览和真实系统保存/分享交接，不证明逐页分页印刷质量，也没有实际发送给第三方。

## 小说明色长名称、项目控制与计划建议：本次设备样本 PASS

独立查看`novel-light-projects`、`novel-light-long-header`、`plan-light-entry/actions/generated`、`novel-control-light/bottom`：项目列表浅色下标题与图标/右箭头无冲突；Workspace项目名和分支长名有明确省略，左右返回/操作按钮保持独立位置。长章/讨论正文（root fixture为7340字）自然换行，作者与正文起始分组清楚，底部输入与写整章操作不重叠；本review核视觉样本，不重新证明fixture每一个字符均被显示。

项目控制顶部模式/模型/计划说明与底部八项导航、保存完整可滚，长副标题正常分行，当前不可用模型有实际禁用视觉。计划页六字段间距一致，保存草稿/确认计划、一键拟计划与后续走向保存/清除行保持对齐；实际生成建议后可见POV `Rhea has the storm map.`与保留的后续两项走向，按钮未被旧状态卡遮住。生成帧底部走向动作在Scroll边缘略裁切，前一actions帧已证明完整可达，不提出重复布局修复。

## 最终结论与证据边界

本review负责的确认finding三项均有生产source最小修复和实际新包JPEG/AX关闭证据；本次补充阅读/资料/长文/主题/弹层/导出样本未发现新增明确UI缺陷，因此独立P7 scope gate PASS。生产、构建、签名、安装和设备操作均由root负责，本review只读取源码/现有测试证据/JPEG/AX并更新独占报告。项目两任务卡与Newsroom/随机阅读功能门禁由对应owner另行出结论，本报告不替代其职责。

未穷尽：所有页面与主题/字段长度的组合、损坏恢复fixture、极长导入文件名、多种系统字号倍率、所有图表及原生时间线组件、PDF逐页印刷、资料确认“继续编辑”实际点击。本次180%为深读阅读倍率，小说UI没有同名全应用180%设置；Compose生成只证明收键盘后完整52vp可达，未声称IME展开时可达。这些是未触发/未测范围，不是已确认未修产品缺陷，也不扩大为额外兜底设计任务。
