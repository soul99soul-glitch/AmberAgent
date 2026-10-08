# iOS 深读动效清单与鸿蒙覆盖（逐项编号）

2026-10-05。只读检查，未改生产代码、未构建、未操作设备。iOS 基准是当前 `DeepReadApp` 工作树；鸿蒙 **before 基线**是本轮补动效之前于 16:14–16:20 读取的工作树。多人正在修改生产代码，before 结论保持固定，after 必须在集成后单独复审回填。

## 判定方式与计数

按用户可辨认的 **31 个动效交互组**计数。同一次盖章的缩放、旋转、淡入、字幕、震动合并为一项；一个 pulse 不拆成多个数字。持续时长只记录源码明确值，SwiftUI `.spring(response:)` 是响应参数，不能把它直接声称为准确结束时长；`.snappy` 和 SF Symbols/system transitions 未指定秒数时明确记为系统默认。

- **等价**：主要触发、视觉时序、reduceMotion 与生命周期均有等价实现证据；不等于设备逐帧已验收。
- **部分**：有可定位实现，但参数、子效果、触发范围或减弱动效存在缺口。
- **缺失**：没有对应自定义动效接线；同功能的静态控件、系统自动反馈、随机阅读入口不替代该动效。
- **无法验证**：iOS 原生系统行为，在鸿蒙无同名行为不足以判断缺失；须看平台交互录像。

**before 统计：等价 0，部分 13，缺失 16，无法验证 2，共 31。** 有动效代码的13组明确列为 M01/M02/M04/M05/M08/M14/M23/M24/M25/M26/M27/M28/M30。这个基线不能支持“丰富动效绝大部分已经完整复刻”的表述；也不能把“0完整等价”误读为“没有任何动效”。未计算主观加权百分比。

普通原生 sheet/menu/spinner 的系统细节另列为 N01–N03，**不混入31组计数**，避免用通用系统控件扩大覆盖数字。两产品图标复用iOS资源不是动效覆盖项。

## 源码位置缩写

iOS 根：`/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/DeepReadApp/Sources/`。

| 缩写 | 文件 |
| --- | --- |
| ID | `Design/DeepReadDesign.swift` |
| IA | `App/DeepReadApp.swift` |
| DF | `Discovery/DeepReadDiscoveryView.swift` |
| LB | `UI/DeepReadLibraryView.swift` |
| CP | `UI/DeepReadComposerView.swift` |
| DT | `UI/DeepReadDetailView.swift` |
| NR | `UI/DeepReadNewsroomView.swift` |
| ST | `Settings/DeepReadSettingsView.swift` |
| TP | `UI/DeepReadTemplatesView.swift` |
| TG | `UI/DeepReadTemplateGeneratorView.swift` |

鸿蒙根：`harmony/entry/src/main/ets/`。以下位置用 `components/deepread/` 的组件名或 `pages/` 的页面名表示，行号对应before读取时的快照；after增行会令行号漂移，应以方法名再定位。

## iOS 减弱动效与后台读取规则

表中 **R** 为明确读取 `accessibilityReduceMotion` 并降级，**R0** 为该局部代码无明确降级分支（系统是否自动降级不能凭源码推定）。`sensoryFeedback` 没有随 reduceMotion 自动关闭，不能给鸿蒙触感helper擅自加动画减弱门禁。

**B0**：视图/系统动画，由框架管理，无显式 `scenePhase` 门禁；源码不能证明后台无计算。**B1**：SwiftUI `.task(id:)` 的等待，任务ID更换/视图离开可取消；后台并不等同视图消失。**B2**：显式可见性门禁，但无结构化后台取消；**B3**：由真实运行状态触发，有成功/错误检查。

## 31 组完整编号清单

| 编号 / 动效交互 | iOS 触发、参数、减弱动效和后台行为 | iOS准确位置 | before鸿蒙映射与判定 |
| --- | --- | --- | --- |
| **M01 夜读台灯渐亮** | 夜读背景首次出现；delay .2s/easeOut1.6s；R 时easeIn.3s无delay；22:00–04:59；B0，无每帧timer | ID:56；DF:52；LB:57；ST:146 | **部分**。`DeepReadPaperBackground.ets:15` 已1.6s/.2s，R时.3s，但before R曲线仍EaseOut，且reduceMotion无全局写入源；背景不是持续发光动画 |
| **M02 Tab方向入场与选择触感** | 切到高序tab direction+1、低序-1，旧内容即刻透明；到达x30*dir→0、smooth duration.26/轨迹.3s；启动direction0/返回详情不重播；R禁位移；selection触感；B0 | IA:16,45；ID:140,149,154,168 | **部分**。`BoardPage.arrive():676`、`DeepReadHistoryPage.arrive():146`、`SettingDeepReadPage.arrive():180`有30px/260ms方向弹簧，`DeepReadTabBar:17`有indicator迁移；before未R gating，无明确selection触感 |
| **M03 原生Tab栏随下滚收缩** | `.tabBarMinimizeBehavior(.onScrollDown)`；系统时序/R/后台行为未在应用固定 | IA:44 | **无法验证（平台系统项）**。鸿蒙固定`DeepReadTabBar`未接scroll offset；不能把固定浮动胶囊当原生minimize已复刻，需滚动录像 |
| **M04 纸卡按压回弹** | topic/source行/library卡按压scale.975、brightness-.02；spring response.28/damping.62；R0；B0 | ID:181；DF:118,195；LB:42 | **部分**。`DeepReadDiscoveryCards:37`topic .975/180ms EaseOut，`DeepReadLibraryCard:35` .98/180ms；source行没有按压，均无brightness、spring/R |
| **M05 发现列表分批入场** | 多源区标题、topic与provider整体出现时opacity0→1、y26→0，response.55/damping.82，delay min(index,8)*.05；R取消y但仍淡入；B0 | ID:203；DF:112,121,128 | **部分**。`DeepReadDiscoveryCards:47`topic有350ms EaseOut/y26/delay封顶8；标题/provider无入场、无R分支，源码使用挂载transition而非listShown latch |
| **M06 刊头横线展开与期号更新** | 首次出现横线scaleX0→1/anchorLeading、response.7/damping.85；issue有numericText声明，但期号变更没有局部animation/withAnimation，实际数字轨迹待录像确认；R0；B0 | DF:363,374,402 | **缺失**。`DeepReadDiscoveryMasthead.build():98`横线静态宽100%，issue静态Text，无数值transition；本项计数依据为确定可触发的横线入场，不另把未证期号动画计成一项 |
| **M07 夜读/节日徽章图标弹跳** | shown变化时SFsymbol bounce/nonRepeating；系统默认时间；R0；B0 | DF:405,412 | **缺失**。`DeepReadDiscoveryMasthead.Badge():87`静态文本符号，无bounce |
| **M08 连点刊头盖章** | 1.6s内5点触发，之后每点换印文；46pt；keyframe scale2.6→.92(.18)→1(.35s)、angle8→-10(.18)、opacity0→1(.14)；R固定scale1/angle-10且淡入.25；impact heavy/.9；B0 | DF:389,401,416；ID:92–119 | **部分**。`DeepReadDiscoveryMasthead.tapHeadline():72`已5点与轮换；before仅scale1.5→1/280ms EaseOut、角度固定-10、无opacity轨迹/R/触感 |
| **M09 发现错误卡移入与退场** | error更新，moveTop+opacity，父snappy；R0；B0 | DF:37,41,49 | **缺失**。`BoardPage.DiscoveryNotices():737`条件静态卡，无transition |
| **M10 首屏热点骨架呼吸** | isRefreshing且无内容，3topic redacted；opacity .45↔1/easeInOut .9每段；R固定1；B0 | DF:101,202–207 | **缺失**。`BoardPage.DiscoveryContent():769`只有3静态placeholder/opacity.7 |
| **M11 发现空态图标wiggle** | 无热点出现一次wiggle/nonRepeating；系统默认；R0；B0 | DF:104–108 | **缺失**。`BoardPage.DiscoveryContent():780`空态静态Icon |
| **M12 topic/library→文章原生zoom** | 点击源卡、matchedTransitionSource→详情zoom；系统时序/R/后台未固定，toolbar创建不做源卡zoom | DF:91,120,196；LB:40,43 | **无法验证（平台系统项）**。鸿蒙`BoardPage.openArticle`/`DeepReadHistoryPage.reopen`使用router push；没有源卡geometry接线可证。需平台导航录像后判定可接受替代，不能凭无同名API宣称用户能力缺失 |
| **M13 摇一摇散报与幸运横幅** | 可见发现页且未创建sheet，随机pick；spread response.28/.55，360ms后response.62/.66复位；每卡x±70/y±36-18/angle±9；lucky底部move+opacity/snappy、骰子bounce、success触感；8s自动清除。R只显示横幅无scatter；B2，scatter的Task无取消token；横幅清除B1 | DF:66–76,210–229,269–293,327–345 | **缺失动效**。`BoardPage.randomRead()`有随机阅读能力，但直接打开文章；没有shake/scatter/lucky横幅/8s交互。这不是“随机阅读功能缺失” |
| **M14 阅读库筛选胶囊迁移与触感** | status选择spring response.35/damping.75，accent Capsule matchedGeometry在chip间移动；selection触感；R0；B0 | LB:78–107 | **部分**。`DeepReadHistoryPage.StandaloneLibrary():387`仅按filter设置按钮颜色/250ms EaseOut，无共享选中色胶囊迁移、选中字体变化/selection触感 |
| **M15 阅读库滚动卡淡出缩小** | scroll phase离identity时opacity.5/scale.95；R固定1；框架按滚动进度，无固定毫秒；B0 | LB:44–46 | **缺失**。`DeepReadLibraryCard`无viewport/scrollTransition接线 |
| **M16 阅读库空态符号弹跳** | status改变且空态可见，bounce/nonRepeating；系统默认；R0；B0 | LB:28–35 | **缺失**。`DeepReadHistoryPage:409`空态Icon静态 |
| **M17 活跃任务状态点呼吸** | 仅active task且非R，opacity1↔.25/easeInOut .7每段；任务结束后静止1；B0，无显式场景后台guard | LB:134–156 | **缺失**。`DeepReadLibraryCard:22`7px点静态，无active prop/phase timer |
| **M18 创建表单来源/模式/错误重排** | files变化snappy，文件行moveLeading+opacity；singleSource变化显示阅读方式区opacity/snappy；error变化snappy；R0；B0 | CP:71,91,123–125 | **缺失**。`DeepReadComposerSheet`条件文件/错误/表单目前静态；此项只记录动效，不重做业务模式缺口 |
| **M19 创建启动反馈** | launched自增，按钮SFsymbol bounce、success触感；创建成功立即dismiss并打开详情，bounce可能短到只剩反馈；系统默认时间；R0；B0 | CP:108,126,161 | **缺失**。鸿蒙创建按钮通过静态“处理中…”切换，无图标bounce或明确success反馈 |
| **M20 编辑室→已生成文章过渡** | html非空时opacity+y24入场，编辑室opacity退场，父easeOut .5s；R0；B0 | DT:48–62 | **缺失**。`DeepReadArticlePage.build():912`按结果分支直接切Web/native/article/newsroom，无文章内容transition |
| **M21 阅读状态卡入场与进度文字变化** | statuspanel moveTop+opacity；进度文字numericText与snappy更新；R0；B0 | DT:169–170,201 | **缺失**。`DeepReadArticleStatusPanel`/RecoveryPanel静态，进度label无text transition |
| **M22 文章阅读方式/样式/编排选择触感** | originalChoice、readerStyle、readerLayout变化selection反馈；无应用自定义正文crossfade；R0；B0 | DT:156–158 | **缺失明确反馈接线**。toolbar菜单/设置路由有功能，无对应selection helper；不要虚构iOS已有连续字体缩放动画 |
| **M23 完成盖章、字幕与退出** | queued/running→succeeded且无运行错误才盖，首稿计首篇/十篇/百篇；128pt通用slam（同M08参数）+full material淡入.2；caption delay.3/easeOut.4/opacity+y8；普通章1.4s/里程碑2.6s后overlay淡出.4，可点关闭；heavy haptic；R只在stamp modifier降级；B3成功核验、清除B1 | DT:64–84；NR:135–161；ID:92 | **部分**。`DeepReadArticlePage.celebratePublished():224`成功+页可见+持久化guard更明确；`DeepReadArticlePressMoment:27`已scale2.6→1/530ms、opacity.88、1400/2600ms、后台hide/active清理；缺.92压印中点/angle8→-10、字幕分段入场/overlay淡出/haptic。计时与业务guard存在，不应记完全缺失 |
| **M24 编辑室阶段切换与轨道推进** | 真progressLabel决定stage；主symbol replace/标题opacity/snappy；rail连线scaleX填充、node完成check replace，父spring response.6/damping.8；R0（只有持续光环与呼吸另有R）；B0，无本地scenePhaseguard | NR:26,38–45,73–103 | **部分**。`DeepReadNewsroomDesk.deepReadDeskStage():4`按真实stage；`build():103`阶段块opacity300ms替换，但图标无replace、rail/check/line直接切换无spring |
| **M25 编辑室主图标呼吸** | 主阶段图标SFsymbol breathe持续，R禁；周期系统默认；B0，Timeline .3s无显式场景后台guard | NR:31,38–40 | **部分**。`DeepReadNewsroomDesk:107`sin(tick*.3)*.05缩放/.3s EaseInOut，tick300ms；R/static及appBackgrounded/active/alive门禁存在，但SFsymbol的形态变换只能是平台近似，不能声称完全相同 |
| **M26 当前阶段扩散光环** | 圆环scale1→1.55/opacity1→0，easeOut1.2s，然后linear.01s复位；R不画；B0 | NR:91–95 | **部分**。`DeepReadNewsroomDesk:63`tick%4使1→1.54、opacity.48→.12，再在300ms动画中缩回；缺完全淡出后无动画复位，造成脉冲回缩；后台timer清理已接线 |
| **M27 活字逐字落盘** | tick300ms、最多10字、每字一次offsetY-28+opacity入场，spring response.32/damping.58；留6tick后循环；R全字直接就位；B0 | NR:31,108–130 | **部分**。`DeepReadNewsroomDesk.TypeTray():82`已有相同tick/10字/6tick/位置、EaseOut300ms替代spring；insertion/removal对称而iOS removal仅opacity；R全字、后台停timer已接 |
| **M28 编辑室格言轮换** | 每14tick=4.2s换，pushBottom+opacity/easeInOut.5s，2行预留并clip；R仅opacity、**仍轮换**；B0 | NR:48–57 | **部分**。`DeepReadNewsroomDesk:114`已有4.2s/clip/50高度，仅opacity500ms无push；R将整个tick停掉，格言也冻结，与iOS的“只取消位移”不同 |
| **M29 强调色色环/check与触感** | 选择色swatch，42px matchedGeometry选中环迁移，点scale.9→1，check scale+opacity，spring response.4/damping.7、selection反馈；R0；B0 | ST:292–326 | **缺失动效**。`DeepReadSettingsForm.Appearance():129`before 5色与静态ring/check存在，但无迁移/scale/transition或selection反馈 |
| **M30 保存成功反馈与短提示** | savedCount触发check.circle bounce/success触感；saved文本moveTop+opacity/snappy；3s后清除（clear写未包显式withAnimation，但父animation接saved）；R0；B1 | ST:121–153 | **部分**。`SettingDeepReadPage.saveStandaloneReading():629`已经3s清除timer并disappear清理；`DeepReadSettingsForm:251`notice静态插入、button纯文字，无bounce/haptic，不能误报3s未实现 |
| **M31 模板选择反馈** | layout/style切换snappy；选中check scale+opacity，layout/生成template图标bounce，templateId变化snappy；layout/style/template selection触感；R0；B0 | TP:16,25,31,44,53,76,82,101,125–128 | **缺失动效**。`DeepReadTemplateSettingsPage.StandaloneTemplates():231`before check静态，select无snappy/transition/haptic。此项不借动效审查重复列模板业务能力差距 |

## 原生系统与默认动画（另列，不放大动效覆盖）

| 项目 | iOS位置与真实含义 | 鸿蒙可证范围 |
| --- | --- | --- |
| **N01 模态sheet/导航push/系统menu** | DF:85,90；LB:65；DT:130；TP:130,203；TG:51。系统默认呈现/关闭、玻璃menu响应；未在应用指定动画时长 | Harmony bindSheet、router、bindMenu有原生动画，但不能仅源码标为iOS像素/手感等价；需要录像。M03/M12单独保留原生缩小与zoom的待验收门槛 |
| **N02 默认加载旋转** | CP:58、DT:116,167、TG:25 等`ProgressView`。不是产品自定义动画 | 鸿蒙LoadingProgress/忙状态需核实际绑定；静态“处理中…”不算同等loading动效；不拿各spinner重复计数 |
| **N03 表单Toggle/Slider/Picker按压** | 原生控件默认响应，源码未限定timing；glassProminent按钮平台按压由系统负责 | 原生Toggle/Slider/Menu/Button的系统效果只可称平台对应控件，不能编造自定义等价时长 |

## 未实际消费的动画声明

`iosApp/iosApp/IOSDeepReadEditorialRenderer.swift:470` 声明 `@keyframes deepReadPulse`，但该renderer与DeepReadCore没有任何`animation:deepReadPulse`消费。它不属于当前实际iOS动效，不计入缺失，也不应为它额外实现正文pulse。

`DeepReadTemplateGeneratorView`、HTML编辑器与预览没有自定义`withAnimation/transition/symbolEffect`；仅原生progress/sheet/导航。不能为了凑清单声称AI模板存在打字机等动效。纸粒子为静态纹理，也不是持续抖动动画。

期号numericText仅有modifier，缺局部动画transaction（M06）；不能把声明直接说成每次期号变化一定滚动。创建按钮launched的symbol bounce虽有真实trigger，但立即dismiss（M19），实际可见程度需录像而非源码保证。

## 本轮已新增的基础接线（after局部复验，不能改写before）

读到当前 `platform_impl/DeepReadMotionPreferences.ets` 后确认：

- `EntryAbility.ets:216`在deepread能力创建start、`:392`前台refresh、`:417`destroy stop。
- `supported():14`同时要求 `deviceInfo.sdkApiVersion >= 23` 与 `canIUse('SystemCapability.BarrierFree.Accessibility.Core')`。
- `start():19`初始化AppStorage `reduceMotion=false`，支持时读取 `accessibility.isAnimationReduceEnabledSync()` 并订阅 `onAnimationReduceStateChange`；stop解除订阅。
- **API 23以上已建立公开系统设置桥；API12没有该公开能力，保持普通动效。** 这不能宣称全API12减弱动效等价，也不是要求用户手动改AppStorage。
- root本轮新增 `design/DeepReadHaptics.ets`：selection/success/impact用10/35/55ms平台touch vibration近似。reduceMotion不关闭sensoryFeedback是正确对应；helper存在仍需确认各M组callsite实际接线，硬件触感另须真机。

before仅3组件读reduceMotion、无写入源的缺口已被上述新bridge修正。其它after修改正在并行集成，完整31项after判定留待最终源码复审；不把“已新增helper/正在施工”记为各页面完成。

## 生命周期审查结论与后续最短复验

1. 鸿蒙before编辑室timer在`alive && active && !appBackgrounded && !reduceMotion`才运行；disappear/后台停止，业务stage仍由scheduler决定。这是可证安全边界，补pulse不应破坏它。R时如果要保留iOS格言轮换，可降频4.2s，勿继续为静止活字/环每300ms重绘。
2. 完成章before已有cycle/request/runStart去重、页面可见与后台guard，保存完成后才展示。补keyframe/字幕应保持成功/旧稿保留条件，不能按动画timer宣布完成。
3. iOS本身多处没有显式scenePhase/R gate，不能把“复刻iOS”解释为鸿蒙必须去掉已经实现的后台暂停。
4. after先按固定M01–M31检查真实callsite、curve/duration、R和后台清理，再用连续帧验证至少Tab方向、按压、accent环、skeleton、stage、pulse、type、notes、完成章、保存提示。单张截图/静态字符串test不能证明轨迹、时序和手感。
5. 本文不报告构建、装机、真机触感或provider成功；这些由主agent最终独立证据负责。
