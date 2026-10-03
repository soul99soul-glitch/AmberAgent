# P6 独立设备 / UI review

本报告独占，生产与设备只读。范围为独立深读首页/配置、模板、创建、阅读库与正文、来源及系统导出。**P6 source 与本报告范围 product/device gate PASS。三项精准UI finding已由最终新包JPEG/AX关闭，无剩余确认缺陷。** 不使用P5图片替代P6验收，Google实际网络超时明确保留为证据边界。

## 已独立目测及AX检查

| 分项 | 真实P6证据与观察 | 当前结论 |
| --- | --- | --- |
| 首页与独立配置 | deepread-home与deepread-settings：创建、阅读库、设置、阅读与发现/模板/服务商/搜索/外观入口可读，长热榜标题自然换行，无行间重叠；设置页帮助明确文章来源配置保存本应用。 | 当前帧布局通过 |
| 模板编辑/预览/保存 | template-editor、template-preview、template-saved：HTML长输入换行，操作行完整；预览示例文章可滚、关闭入口独立，保存后P6 Captured Template A真实出现在列表与编辑器并有“已保存并用于新文章”。没有从样例预览推断生成请求已冻结模板。 | 设备已观察路径通过 |
| 创建版式 | compose-template-selected：选中自定义Template A，说明明确开始时保存版式；生成按钮AX[70,2422][1250,2604]高182px=52vp，离手势区留白、长来源说明完整。上方输入单帧在Scroll外不是错误隐藏控件，不以零bounds离屏AX判断错位。 | 当前帧布局通过 |
| 运行与完成 | library-live真实新文章生成中、旧条目失败；structured-complete真实完成状态、本篇版式Template A及完整可滚渲染。资料/导出AX各154px=44vp，保存到工作区/重新生成也154px；标题与按钮未相互挤压。模型无工具完成说明来自root真实运行与请求记录，本图只证明实际完成结果显示。 | 完成显示通过；阅读库占位颜色待修 |
| 来源与系统浏览器 | article-sources显示17条来源，粘贴原文与搜索来源区分、字符数/40k截断明确；长英文来源名与URL换行，浏览器动作AX[126,1400][1194,1554]高44vp；source-browser真实打开oracle.com系统浏览器。后者本帧仍加载，不宣称外部网页已完整加载或研究结论可靠。 | 入口与当前布局通过 |
| TXT保存与分享 | export-panel所有动作纵向间隔正常，TXT动作168px=48vp、关闭154px；txt-saved实际“TXT文件已保存”；txt-share真实ShareKit显示.txt及1.32KB，关闭/系统操作完整。 | 保存完成反馈/真实文件面板通过 |
| PDF预览/保存/分享 | pdf-preview真实A4导出预览、Template A文章可滚，返回菜单154px，保存/分享各168px；pdf-saved真实“PDF文件已保存”；pdf-share真实.pdf、468.57KB及缩略图。没有底部动作重叠正文或彼此挤压。 | 保存完成反馈/真实文件面板通过 |

屏幕1320×2856，本机3.5px/vp。只把实际点击组件的AX热区作为44/48vp证据，不把Btn子Text的字体bounds当热区。系统分享文件名省略号是原生长名行为，不误报本应用丢失名字。TXT/PDF未发送给外部接收人，未设备读回保存文件全字节；共享面板大小与缩略图也不代替完整PDF分页字节验证。

## P6-UI01：阅读库深色搜索占位文字不可读

**P2，source与最终设备已关闭。** library-live.jpeg的“搜索标题与正文”呈近黑字深底，明显弱于右侧刷新及其它文案，影响发现标题/正文搜索能力。AX TextInput[56,402][1039,556] enabled=true，非禁用态。

定位DeepReadHistoryPage.ets:182–184，只声明fontColor(INK)/backgroundColor(SURFACE)，未设置placeholderColor，实际默认占位色未跟随页面深色。最小修复只加当前次级文字token的placeholderColor；保持44vp、现查询与筛选逻辑。修后需要同场景新包截图关闭，不以源颜色声明直接验收。

独立复核当前生产只增加.placeholderColor(INK3)，44vp及onChange保持原样；source修复足够，等待最终新包图。

## P6-UI02：服务商帮助文案仍描述宿主

**P3，source与最终设备已关闭；不是布局问题。** providers.jpeg独立深读页面仍说“聊天和小说共用这里……深度阅读可跟随聊天或从模型库单独选择”，容易误解独立应用配置及阅读模型入口。定位ChatProviderSettingsPage.ets:372固定FooterNote，未按ProductKind区分，真实独立模型路由已指向“阅读模型设置”。

最小修复按现getProductKind仅调整独立产品说明，宿主说明保持；不增加配置模式或新路由。这一项属于当前产品文字与实际行为不一致，不把它扩展成配置隔离失效。

独立复核当前三分支：deepread说明配置本应用并去阅读模型设置；novel说明本应用/项目创作模型/任务独选；agent保持既有宿主说明。新getProductKind import与当前实际配置路由一致，没有provider存储或模型消费者变化。source通过，等待新图。

## P6-UI03：搜索设置页在真实ArkUI构建时崩溃

**P1，source与最终设备已关闭。** root实际进入pages/DeepReadSearchSettingsPage后退到HOME；独立读`/tmp/amber-p6-search-page-hilog.log:5307–5309`，真实TypeError为class constructor cannot called without new，content栈指该页编译ts306:29，随后AppKit记录app退出。既有业务actual tests只执行refresh/saveChoice，不执行ArkUI编译组件树，因此其通过不排除这个崩溃。

根因原CardGroup content普通lambda直接调用CardToggleRow/CardRow组件。独立核当前已提取@Builder GoogleCard/ManageCard，lambda仅调用this.GoogleCard()/this.ManageCard()，与既有StandaloneSettingsPage.ReadingRows/ServiceRows模式一致；实际checked/onToggle/route、enabled条件与布局参数保持。最小改动只修ArkUI组件调用语义，没有变动保存逻辑。最终新包必须实际从入口进入、操作Google开关并返回再进入，不能凭loader或源修复关闭crash。

## 冷读与捕获模板补证据

独立view_image目测`template-global-changed`当前默认已改“杂志·立场”，P6 Captured Template A尚在自定义列表；`article-cold-template-A`冷开原文章仍显示本篇版式Template A、完成状态与正文；`library-cold-complete`对应文章已完成、旧失败条目仍独立。三图共同支持更改全局默认不改原文章保存版式及完成状态；不把root简称“B”误写为设备实际存在的模板名。早轮冷图仍有UI01旧占位颜色，不能用于修后颜色gate。

本次独立再次执行GoogleHost7、SearchSelectionUI3、DI3，共**13/13通过**。它们不执行ArkUI组件树，不冒充UI03设备已修。未发现上述三个精准源修复之外的新确认问题，整体gate等待root统一构建签名安装后的最终补图。

## 最终新包独立实屏收口：P6 PASS

最终构建签名证据`/tmp/amber-p6-device-final-build.log`，root两产品构建/签名并安装独立深读包；本review读取成功记录、独立目测最终JPEG与AX，不冒充独立重新构建或操作设备。

- **UI01关闭**：library-placeholder-fixed实际占位文字为可读次级色，搜索AX仍[56,402][1039,556]、44vp且enabled；没有扩大控件或改变搜索逻辑。新Local Reader与原P6文章均完成，旧失败条目独立保留。
- **UI02关闭**：providers-copy-fixed实际仅说明“本应用的服务商与模型”“到阅读模型设置”，两行自然换行，未保留错误宿主/跨产品语境，入口与配置状态一致。
- **UI03关闭**：search-settings-fixed真实进入深读搜索页，首选列表、Google Toggle、管理入口完整渲染，未回HOME；未启用service明确标注，不伪装成可用认证。Google长标题与描述不碰toggle，卡片/按钮/帮助无重叠；root后续真实启用Google并启动Article继续生成，证明已越过原首次组件树崩溃。源码save/load逻辑由实际prefs回归补证，截图不替代该验证。
- **发现配置通过**：独立目测discovery-settings、discovery-source-categories、discovery-tech-finance、discovery-finance-life，20目录实际分AI英文/社交/科技数码/财经/体育；长HuggingFace标签与开关不重叠，关注文本/筛选、刷新间隔、仅Wi-Fi、中文标题各自清晰，长说明及页底留白正常。中间滚动帧上端部分条目出屏是正常Scroll，不报错误裁切；图片不证明20外部endpoint全部在线。
- **native Google证据准确收口**：google-host-progress实际Article完成并展示本篇“杂志·立场”，隐藏host未撑开正文。google-only-sources只有粘贴48字符与free_aggregate失败记录，明确`prefetch deadline exceeded`，没有Google结果成功收录证据。独立读`/tmp/amber-p6-google-host-hilog.log:6494/6608/6870`，本应用PID9531创建/活动nwebId1/WebId1；结合冻结Google私有host源码、实际7条owner/DOM回归与真实触发链，证明Web组件挂载与链路接入。日志挂载不等于Google网站成功返回，超时也不冒充搜索成功。

最终结论：本阶段新功能入口、配置保存、生成与无工具structured路径、捕获版式冷读、来源查看/跳转、TXT/PDF文件保存及真实分享面板、UI三项修复均有上述分层证据，**P6本报告范围产品门禁通过**，无剩余确认缺陷。Google外部结果可用性本次未验成成功，云模型质量、外部接收人送达、设备读回全部导出字节及所有发现源在线率仍未测；已准确记录，不制造兜底设计或静默降级。不推进P7、不修改生产或设备。

## 本轮实际源码 / DI补验

此前独立native-search报告已关闭异步eval owner迟回及私有WebSession误发Chat全局状态，GoogleHost7/7、SearchSelectionUI3/3已独立通过。当前源码仍保留这两项窄修复。

本轮独立执行deepread_run_container.test.cjs **3/3通过**，loader执行实际AppContainer getDeepReadScheduler工厂：无tool模型为structured、真实topic闭包分别给nativeGoogle且新run独立prefetcher；显式与续读captured template不读取无效默认；fresh force不误用旧cache而捕获当前默认。读取当前源码options.force分支与测试一致，未仅照转root日志。

源层/fixture不证明Google外部网站实机可用或云模型质量。本报告不修改native搜索报告的设备门禁，等root后续搜索设置/实际页面与冷读图到达后再精确补结论。
