# iOS 深读动效 after 增量源码复核

状态：**已完成31组源码复核，含Discovery最后一轮、Library小补与主agent随后增加的Board初帧timer**。2026-10-05，首次 after 取样16:48（Asia/Shanghai）。只读源码，不构建、不操作设备、不编辑生产文件。

固定沿用 [motion-ios-inventory.md](motion-ios-inventory.md) 的 M01–M31；每次盖章、pulse、按压按同一交互组计数，不加细分组改变分母。before 清单保持原样，不因 after 接线改写历史结果。

## 分类规则

- **S：语义/主要参数已接齐**：真实 trigger、关键幅度/明确时序、对应 reduceMotion 与回收路径有可定位代码。平台曲线引擎或触感波形并不要求同名 API；这不是逐帧或真机等价声明。
- **P：平台近似/局部缺口**：有明确实现，可作为对应动效，但 SF Symbols 专属效果、滚动 phase、numericText 等由平台近似，或一组中的局部触发尚未接入。逐行说清差异，不用“部分”掩盖未做项。
- **U：未实现/原生未接**：对应动效或特定业务选择的触发不存在；iOS 原生行为另明确写“未接”，不冒充系统已提供。
- **设备待验**是独立维度，所有 S/P 源码结论均不自动变为“设备已证明”。本 agent 不将主agent视频或截图直接认定为已逐项通过。

**API边界**：`DeepReadMotionPreferences` 只在 API≥23 且 capability 可用时读取/订阅系统减弱动画；API12使用普通动效。selection/success/impact 的10/35/55ms touch vibration是平台触感近似，实际力度与时序需真机。此限制不会把已经有完整 trigger 的M组都误降为“0覆盖”。

## after组数与证据边界

| 分类 | 数量 | 固定编号 |
| --- | ---: | --- |
| S：语义/主要参数已接齐 | 14 | M01、M02、M06、M08、M09、M10、M14、M17、M20、M23、M26、M27、M28、M29 |
| P：平台近似/局部缺口 | 14 | M04、M05、M07、M11、M13、M15、M16、M18、M19、M21、M24、M25、M30、M31 |
| U：未实现/原生未接 | 3 | M03、M12、M22 |
| 合计 | 31 | M01–M31，各一项，无新增分母 |

可以说**多数交互组已经有真实动效接线**；不能说“绝大部分与iOS逐帧完全相同”。before按其既有严格等价规则为0等价/13部分/16缺失/2原生无法验证，after按上面公开规则分类，不能将这两种“完整”含义混为同一度量。最初两项原生待验经源码复核明确没有实现该产品过渡，after记U，不改before历史表。

本报告只证明源码callsite与参数。本轮构建、签名、安装、测试结果由主agent独立汇报；不纳入31组动效的设备通过数。普通系统sheet/menu/spinner仍沿before N01–N03另列，两产品PNG图标资源也不属于动效计数。

## 固定31组逐项 after 表

鸿蒙相对源根为 `harmony/entry/src/main/ets/`，组件路径默认 `components/deepread/<表内名称>.ets`，页面为 `pages/<名称>.ets`。几个struct别名的文件映射：`DeepReadDiscoveryMasthead`在`DeepReadDiscoveryHeader.ets`；`DeepReadDiscoveryPlaceholder`/`DeepReadDiscoveryNotice`在`DeepReadDiscoveryMotion.ets`；`SettingsForm`是`DeepReadSettingsForm.ets`。iOS trigger 与源码精确位置沿用before清单，未更换参考产品或复刻目标。

| 组 | after分类 | 当前代码与关键参数 | 尚存差异 / 设备门槛 |
| --- | --- | --- | --- |
| **M01 台灯渐亮** | **S** | `DeepReadPaperBackground:17–35`首次挂载后16ms启动；普通1600ms/delay200/EaseOut，R300ms/EaseIn；组件离开清理初帧timer | 16ms等待用于初帧挂载，不改变用户语义。浅/暗光晕叠加及夜读首次亮起待设备验证 |
| **M02 Tab方向/选中反馈** | **S** | `DeepReadTabBar:22–64`前后序direction、activeTab先写隐藏旧内容，16ms后indicator260ms spring；切换成功调用selection。`DeepReadHistoryPage.arrive():156–173`、`SettingDeepReadPage.arrive():202–220`与`BoardPage.arrive():697–714`均 x30→0，16ms初帧、260ms弹簧；R/后台无位移，离页清timer；首次出现限定；build按activeTab opacity | router替换与SwiftUI保留Tab的实现不同；三根页16ms初帧路径现均已补齐。方向、旧文隐藏、启动/返回条件已接，真实首次位移与无文本叠印/回跳仍须连续帧确认 |
| **M03 原生Tab下滚收缩** | **U：原生未接** | 当前`DeepReadTabBar`仍固定270×64，没有按scroll offset缩小/恢复 | 不能称“已复刻iOS .onScrollDown”；记录为未接原生交互，平台滚动录像待验，不用形似胶囊替代 |
| **M04 纸卡按压回弹** | **P：局部参数近似** | 库`DeepReadLibraryCard:72–79` .975/brightness.98/springMotion(.28,.62)/R静止；`DeepReadDiscoveryCards.press():52`和`pressRow():119`所有topic/source行.975/280ms spring、active/后台门控 | 库brightness=1正常/.98按压对应iOS-.02；Discovery用card→paper背景变暗替代整卡brightness，curve240/18也为平台近似。核心按压范围完整，不再只有topic |
| **M05 发现列表分批入场** | **P：R时序近似** | `DeepReadDiscoveryMotion:24–38`初帧16ms、opacity0/y26→1/0、普通550ms弹簧、delay min(index,8)*50；`DeepReadDiscoveryCards:9–25`header index0且不参与scatter，`:85`topic index+1；`BoardPage:831`provider index=topics.length+index+1；恢复scroll不重播，后台/离页清理；R仍250ms淡入/y0 | header、provider索引与R淡入此前缺口均已补。R路径取消stagger/使用250ms EaseOut，iOS仅去y而仍使用.55响应弹簧与min(index,8)*50ms delay；这一具体时序差保留，普通平台物理曲线也非逐帧声明 |
| **M06 刊头横线/期号** | **S** | `DeepReadDiscoveryMasthead:74–88`真正ruleScale0→1、16ms后700ms左端弹簧展开、后台/R/离开settle | iOS期号只有numericText声明、无局部animation，未证数字滚动不另当必补效果。实际横线轨迹待设备验证 |
| **M07 节日/夜读徽章bounce** | **P：SFsymbol bounce近似** | `DeepReadDiscoveryHeader.Badge():174–180`图形真正消费`DeepReadDiscoverySymbol`；`DeepReadDiscoverySymbol:21–43`首次显示 y-3/scale1.12，16ms后420ms弹簧归0/1，finite一次；`playEntrance`防恢复重播，R静止、active/后台/离开/cycle清理 | 原来静态徽章已补。SFsymbol内部bounce由整体☾/⚑ glyph位移/缩放替代；文字label不弹跳，触发范围对应，具体系统形态是平台近似 |
| **M08 连点刊头盖章** | **S** | `DeepReadDiscoveryHeader.tapHeadline():132–168`1600ms内5点/之后换印文；16ms首帧后scale2.6→.92/angle8→-10/180ms，再350ms回1；独立140ms opacity0→.88、impact；`:209`浅色MULTIPLY/暗色NONE；Rscale1/angle-10/250ms淡入、周期与后台清理 | opacity与压印分轨、浅色纸面混合已补。0.88对应iOS InkStamp整体.88×动画opacity1；平台弹簧/Cubic曲线形状需设备对照，不因API名字不同否认主参数已齐 |
| **M09 发现错误卡入场** | **S** | `DeepReadDiscoveryNotice:117–155`真实mount初帧-18/opacity0，16ms后280ms→0/1；R取消位移/150ms，transition移除淡出；Board实际三类Notice调用 | iOS .snappy未固定毫秒；本平台把时序明确为280ms。错误新增/消失与背景清理待连续帧 |
| **M10 热点骨架呼吸** | **S** | `DeepReadDiscoveryPlaceholder:80–103`900ms每段.45↔1/EaseInOut，首屏loading才mount；R常亮；active/后台/离开清timer，Board实际3个placeholder调用 | 首段先常亮900ms再渐低，iOSphase起始可能较早淡；主要幅度/周期/触发齐，设备确认首帧即可 |
| **M11 发现空态wiggle** | **P：SFsymbol wiggle近似** | `BoardPage:812`真实空态调用`DeepReadDiscoveryEmptyGlyph`；`DeepReadDiscoverySymbol:22–43,67–77`首次-8°，16ms后140ms到6°再260ms归0°，一次有限wiggle；R静止/恢复不重播/active与后台清理 | 原来静态空态已补；SFsymbol wiggle用整本notebook Icon旋转替代，内部symbol形态/系统轨迹需平台近似说明与连续帧验证 |
| **M12 进入文章源卡zoom** | **U：原生未接** | `BoardPage.openArticle` / `DeepReadHistoryPage.reopen`仍router push，没有sourceID/geometry与文章目的页共享过渡 | 默认router导航不是iOS matched source zoom。平台默认导航可用，但此项原生源卡zoom尚未接，需明确保留 |
| **M13 摇一摇/幸运横幅** | **P：SF骰子/曲线近似** | `DeepReadDiscoveryShake:57–88,117–158`真实accel50ms采样/两峰检测，显式随机也走same shuffle；scatter360ms→回位+pick、success、8000ms清除；`DeepReadDiscoveryMotion:59–67`x±70/y±36-18/angle±9、280/620ms；`DeepReadDiscoveryShake:180–210`22pxShapes骰子16ms后420ms springbounce，横幅y28+opacity280ms/读这篇复用来源与文章路由；R无scatter，后台/离开/弹层active清timer/解绑sensor | iOS原生shake事件与SFdice bounce由平台sensor/Shapes弹跳替代，scatter spring参数也平台近似；视觉/方向/延时/命中真实候选都已接。模拟器不能证明物理摇动与触感，待真机；无重复计数 |
| **M14 库筛选胶囊/反馈** | **S** | `DeepReadLibraryFilters:14–36`真实filter变化selection；350ms springMotion(.35,.75)，选中色块geometryTransition，selected文本600/其他400、R0ms | 共享geometryTransition有代码；视觉是否连续移动而非跳帧仍须设备连续帧 |
| **M15 库卡滚动淡缩** | **P：滚动phase近似** | `DeepReadLibraryCard:44,72–77`可见比率<.999时scale.95/opacity.5，回完整视口归1；onVisibleAreaChange([0,1])、R固定1 | iOS以scrollTransition phase连续变化；这里按可见阈值二态切换、spring280ms，触发和目标值对应但不等同连续phase轨迹 |
| **M16 库空态bounce** | **P：SFsymbol bounce近似** | `DeepReadHistoryPage.selectLibraryFilter():194–216`真实filter改变且已加载/空态才一次bounce；16ms首帧、100ms scale1→1.15+180ms弹簧回1，iterations1；`:461`空态book消费scale；R/后台/离页/cycle清理 | 空态触发已补，不再以M14替代。SFsymbol内部bounce换为整体Icon缩放；快速连续筛选、空态变有结果与动态R切换仍须连续帧验证 |
| **M17 活跃状态点呼吸** | **S** | `DeepReadLibraryCard:23–47,59`statusKind running且alive/active/inViewport/非R/非后台，700ms EaseInOut，opacity1↔.25；失活/离开/后台清理；History传真实pageVisible/activeTab | before缺口已补。是否首帧/最后归1与任务结束时立即停，待连续帧；不依赖固定时间推进业务status |
| **M18 创建来源/模式/错误重排** | **P：局部缺口** | `DeepReadComposerSheet:106–119,273–275,305–308`文件增删350ms spring、x-24+opacity；错误-8y+opacity；R静止 | 文件与错误已接；iOS的singleSource阅读模式区未在当前Composer出现，其模式区opacity重排不能算已覆盖；文件offset不同于iOS moveLeading默认容器边界，属于平台近似 |
| **M19 创建成功反馈** | **P：SFsymbol bounce近似** | `DeepReadComposerSheet:157–162`真实upsert成功后success haptic、写pendingTopicId；`:310`按钮success绑定pendingTopicId非空；`DeepReadSettingsVisual.ets:148–180`success watch触发scale1→1.25，150ms后弹簧归1，R/后台/离开清理 | 原先未传success缺口已补；SFsymbol bounce换为整体Icon弹簧。保留立即调用onCreated，不加人为dismiss延迟；与iOS一样实际可见持续时间须设备验证，源码不保证用户一定看到完整弹跳 |
| **M20 编辑室→文章过渡** | **S** | `DeepReadArticlePage.readingTransition():730`opacity+y24，500ms EaseOut、R去位移；Web`:841`、native`:939`已真正消费，编辑室`:947`500ms opacity退场；active前台门禁 | 同一Transition同时作用于插入/删除；iOS正文remove通常只受当前conditional状态，回退删除细节待录像，不影响当前成功呈现语义 |
| **M21 状态卡/进度文字** | **P：numericText替代** | `DeepReadArticleStatusPanel:35–40,48–54,98`top-16+opacity300ms，label identity ForEach ±4y+opacity短过渡；R仅opacity；Article`:807`传editorialActive，后台0ms | 状态panel入场已接；numericText换为稳定文字淡移，不是滚动数字。label截断clip22高，需实屏确认不叠字 |
| **M22 原文/样式/编排选择触感** | **U** | `DeepReadArticleToolbar.StyleMenu():25`只有模板路由/字号与文章操作；无originalChoice/readerStyle/readerLayout对应selection触发 | 模板选择触感在M31计数，不能填成文章三类选择反馈已接。这里只记录触发缺失，不展开旧业务能力审计 |
| **M23 完成盖章/字幕/退出** | **S** | `DeepReadArticlePressMoment:61–108`10/180/350ms scale2.6→.92→1、angle8→-10，opacity延10/140ms（R250ms）；字幕delay300/400ms、y8；overlay200ms入/400ms退；1400/2600ms停留+点击关闭；impact。cycle/run/成功持久化guard沿用Article | R切换可立即settle，后台/active/离开清timer。SF画章与原生material是对应绘制；实际完成稿触发、重试保留旧稿不误盖章，仍需设备/运行证据 |
| **M24 阶段与轨道推进** | **P：SFsymbol replace近似** | `DeepReadNewsroomDesk:113–146,175–191`真实stage驱动，line600ms左端填充/节点color动画，check/Icon identity替换，主标题500ms opacity，icon scale.8入+opacity200退 | SF Symbols replace用Icon scale/fade替代；阶段语义与完成轨道已齐，图标形态与系统snappy轨迹是平台近似 |
| **M25 主阶段图标呼吸** | **P：SFsymbol breathe近似** | `DeepReadNewsroomDesk:179–181`sin(tick*.3)*.05 scale，300ms EaseInOut；非R/active/前台才运动 | iOS breathe具体symbol形态不等于整体缩放；已有可辨认的呼吸对应，不能降为完全缺失，也不说系统形态完全等价 |
| **M26 当前节点扩散环** | **S** | `DeepReadNewsroomDesk.pulse():69–89`10ms复位、1200ms scale1→1.55/opacity.5→0、1210ms后重播；R/后台/active/alive guard与owner清理 | opacity.5对应iOS stroke accent alpha.5×opacity1，不是强度丢失。已修正before可见缩回；需连续帧确认归零后复位不可见 |
| **M27 活字落盘** | **S** | `DeepReadNewsroomDesk:57–60,97–103,155–165`300ms tick/前10字/留6tick；y-28+opacity、320ms物理弹簧；removal仅opacity；R全部排好；前后台与owner清理 | springCurve参数386/23是平台物理参数，不能用API名字不同判零覆盖；视觉时序可对照，目标值与用户语义齐 |
| **M28 格言轮换** | **S** | `DeepReadNewsroomDesk:57–60,105–110,194–200`每14tick=4200ms，500ms push上移/opacity，clip50高两行；R降为4200ms tick/只淡入，仍换格言；后台/离开停止 | before冻结R格言与缺push已补；恢复后台可能暂停时间再续，iOSTimeline基于clock更新，两平台续播相位差应作为生命周期选择而非生成进度错误 |
| **M29 强调色环/check/反馈** | **S** | `DeepReadSettingsForm.chooseAccent():64–75`保存成功后selection，400ms springMotion(.4,.7)写真实accent；`:140–160`42环geometryTransition、check scale.5+opacity、色点.9→1；R静止 | `setAccentColor`会bumpThemeEpoch并重挂载父页，代码已接齐但色环跨重挂载是否仍连续需设备确认，不能仅有geometry声明就称录像已通过 |
| **M30 保存反馈/短提示** | **P：SFsymbol bounce近似** | `SettingDeepReadPage:744–759`真实保存成功haptic、300ms提示入/退、3000ms清除；`SettingsForm:270`success prop/checkCircle；`DeepReadSettingsVisual.ets:148–180`scale1→1.25→1/150ms后回弹+生命周期清理 | 主语义、计时、提示移动都已接；SFsymbol bounce用普通Icon spring scale替代，150ms是二段间隔，不冒充iOS系统总时长 |
| **M31 模板选择反馈** | **P：图标近似/触发范围** | `DeepReadTemplateSettingsPage.applySelection():114–135`持久化成功且changed/页可见才selection；图标100ms scale1.15/y-3→180ms归1；选中check scale.65+opacity220ms，reduce/后台/生命周期清理 | 当前可见内置与custom模板选择已接；iOS同时含layout/style的行当前没有对应触发。已有template反馈可评为平台近似，不应声称所有iOS模板管理交互已齐 |

## 最终源码复核结论

已由缺失或早期简化变成真实接线的重点包括：发现列表与section header分批入场、徽章/空态符号、三段盖章、骨架/错误卡与摇一摇幸运横幅；库筛选选中色块、活跃点、滚动淡缩与空态弹跳；创建文件与错误的过渡与成功反馈；正文500ms入场；状态卡与稳定label短过渡；完成章三段keyframe/字幕/退出；编辑室轨道、完全淡出光环、非对称活字入退、减弱动效下保留格言；强调色环/check与保存反馈；模板check/图标/触感。

这张表将源码覆盖与设备证明分开，不以“完全等价必须用同名API”的标准把平台对应实现判为零。M03/M12的iOS原生minimize/source zoom当前确实未接；M16/M19最新触发已补并复核，M22和M18/M31局部触发边界保持明确。

## 留给设备验证的重点

- M02发现页首次方向是否真实播放、旧内容是否立即隐藏；M05所有header/topic/provider的分批淡入与减弱动效时序；M14/M29跨theme重挂载的共享色块/色环是否连续。
- M13真机物理摇动、sensor在后台/弹层打开后是否停止；selection/success/impact真实触感；模拟器不能证明这些。
- M19成功后立即导航的按钮反馈实际可见程度；M20/M21正文与状态label入场是否叠字；M23真实首次成功/重试/失败不误盖章。
- M10/M17/M26脉冲复位、M27活字仅入场弹跳、M28后台与R状态下格言的正确轮换；截图不能证明周期与入退轨迹。
- M03原生tab收缩、M12源卡zoom与M22三个不存在的选择trigger保留U；设备默认导航不能将其判为已接。所有其余源码分类保持“设备待验”，主agent可另按连续帧/截图/运行证据填验收结果。

最终表已核固定31编号连续、无重复、每项只有一个S/P/U分类。不编辑before基线，不造泛泛百分比。
