# AmberAgent Current Project State

Last updated: 2026-08-06

本文件只记录当前可操作事实。开始任务时仍需核对真实 Git、代码、测试和设备状态；历史过程从 Git 追溯，不在这里追加会话日记。

## Repository

- Repo: `/Users/mi/Downloads/AI/AmberAgent-iOS`
- Branch: `feat/ios-provider-parity-claude`
- Tracking: `origin/feat/ios-provider-parity-claude`；当前本地包含尚未 push 的 review fixes、小说/核心记忆闭环与文案提交，ahead 数以实时 Git 为准。
- Current committed HEAD: 以实时 `git rev-parse HEAD` 为准。2026-08-06 22:12 已把 `ab1d5fbeb` 覆盖安装到 iPhone Air（iPhone18,4，未卸载），新安装容器 `33FBA855-408D-46E1-83FD-6DD147744904/iosApp.app`，数据库 UUID 保持 `AC96CD34-4AD9-4317-A4CD-6BB64DC7FD3F`（既有 App 数据保留），启动成功；`52794d2de` 不再是装机基线。
- Worktree: 小说共创/代笔 Phase 0–3c、核心记忆闭环、相关测试与文档已提交；是否干净以实时 `git status --short` 为准。
- Git policy: 未经用户明确要求，不 commit、push、stash、reset、checkout、rebase 或清理工作区。

## Home E 版视觉落地（未提交）

目标：把定稿 E 版首页设计（home-replica.html 同源规格）落到 `ConversationsView` 与全局主题令牌，并让顶部续接位只展示真实未完成工作。

- 全局令牌：三画布——暖纸 `paperLight`、暖灰 `neutralLight`（默认）、中性白 `whiteLight`（`#F5F5F4`/`#FFFFFF`/`#EEEEED`）；`Paper.white` 已加入外观设置三卡。`fab`/`fabInk`/`focusRing`/`activeAvatarGlow` 绑定 runtime accent（可选色板生效），不再钉死琥珀金。默认仍为暖灰 × 琥珀金。用户已持久化偏好不受影响。
- 首页：搜索胶囊原位展开为玻璃搜索条（品牌/齿轮/头像让位，Esc/取消收起清空，保留提交进全文搜索）；控制卡顶部改为跨功能续接位，稳定优先级为「待处理 > 运行中 > 可恢复 > 可查看结果 > 草稿」，候选来自当前议会房间、深度阅读持久任务、小说项目、AI 生图和最新版本尚未运行的小应用；普通完成态、损坏小说、已运行的小应用版本均不出现，无候选时整行收起，只保留五入口。五入口为单墨色 Phosphor fill 语义图标，顺序为深度阅读/小说创作/模型议会/小应用/WebMount；会话列表恢复切片一体卡外框（72pt、顶/底投影、卡内左缩进 hairline；激活 `activeCard` 随 accent 浅染；空态同卡壳）；控制卡外框保留；新建为右下拇指区浮层中性玻璃胶囊「新对话」（高 42、主墨字、琥珀仅图标；`homeGlassControl`；非满幅琥珀、非圆 FAB、非假底栏）。列表 scroll 留白 + soft bottom edge。Continue 黑 CTA；账户 38；Continue 显隐 0.30s。入场级联 40ms stagger 且尊重 Reduce Motion。玻璃为搜索胶囊/展开条、齿轮与底胶囊；内容卡零描边，深度仅来自两层 `cardShadow`。
- 图标体系（review 轮重做）：新增 `iosApp/iosApp/HomePhosphorIcons.swift`——21 个 Phosphor fill 字形（路径数据与 home-replica.html 内嵌 symbol 同源；chatCircle/pushPin/imageSquare 取自 phosphor-icons/core 同名资源）+ 最小 SVG path 解析器（M/L/H/V/C/S/Q/T/A/Z，椭圆弧按 SVG 1.1 F.6.5 转三次贝塞尔）。映射修正：剑=sword（原 shield）、天平=scales（原 medal）、AI 生图=imageSquare（原 pen）、crown 关键词补「在位」；swipe/context menu 系统图标保留 SF（系统 UI 层，不在设计约束内）。
- Review 轮修复（4 只读审查代理：逻辑链/几何/渲染/taste）：①会话卡投影从「逐行切片各带阴影」改为「仅 bottom/single 行携带」——消除 72pt 行间阴影接缝，保留原生 swipeActions 的 List 结构（卡顶/侧边投影略弱为已知取舍）；②全局 `glass/glassStrong` 恢复原值（`base(\.background, 0.72/0.85)`），首页三件玻璃改用首页专用 `homeGlass*` 令牌（浅 .78→.58 / 深 .14→.08 白渐变 + 双层投影），与内页材质完全隔离；③几何：header 去掉多余 top 6（精确 42）、「会话」标题与首卡补 12pt、搜索胶囊固定 78×38、展开条 gap7/左12右6/取消 h30 横8/输入 tracking 0.13、会话 meta 接 `.monospacedDigit()`、FAB 底部改 `max(67-safeAreaInsets.bottom, 12)`；④focus 环接入展开搜索条（3px `focusRing`，聚焦时显示）；⑤搜索延迟聚焦改可取消 `Task`（收起/离场撤销，不再残留 FocusState）；⑥`loadProjects` 加 latest-wins revision（首页 onAppear 与项目列表 .task 并发时旧快照不得回写）；⑦级联入场加一次性门控（0.9s 后重建行不再重播）；⑧暖纸 `avatarActive` 修正 `#EADBCC`→`#EADCBC`；⑨节标题用独立 `section` 令牌（与 foreground2 数值同构、语义独立）。
- 第二轮补充修复（多行运行时实测发现）：bottom 行投影向上越界，在上一行底部形成 Δ≤3 暗带并压暗该处 hairline（`#E8E7E6` vs 正常 `#ECEBE9`）；给 bottom/single 行投影加 mask，裁掉行界以上晕影（左右/下方延伸保留卡侧与卡底投影；single 上方是画布，向上晕影与控制卡外投影一致，不裁）。
- 收敛轮补充修复：latest-wins 原实现虽能阻止旧项目列表/错误回写，但旧请求的 `defer` 仍会提前清除较新请求的共享 `isLoading`；收尾现在也校验同一 revision，行为回归测试用两个独立 gate 稳定复现红灯后验证修复。
- 动态续接闭环：议会投影只认可可解码且 `taskId` 匹配的归档，并把持久任务的 failed/cancelled/timedOut/interrupted 映射回可恢复态；当前议会标识恢复 Observation 跟踪，首次开会即可刷新首页。小说项目摘要持久化 `hasRunningRun`，后台脱离恢复后重新加载摘要，首页不再只依赖当前选中项目的运行快照；旧索引首次读取会做一次项目扫描并重写，以免把历史运行中项目误判为静止。
- AI 生图续接：以持久化 assistant `generate_image` Tool 消息为唯一导航真相，记录所属 conversation/message/toolCall；空输出且该会话仍在生成时显示「正在生成」，只有 Tool output 已含真实 Image part 才显示「图片已生成」。点击后以 latest-wins 选择精确会话，并用稳定 UUID + messageID + toolCallID 重新投影验证，再滚到超高 assistant 消息内的图片/加载/失败 tool part；Native driver 与正式 fallback 共用该锚点，只有滚动动画逻辑完成后才消费已查看结果。目标缺失、会话切换失败、仅有成功 JSON 但没有 Image part 时均不消费。
- 无障碍与自适应：Continue 卡、快捷入口、会话行改用 ScaledMetric；辅助功能字号下 Continue 卡纵向排布、CTA 保持 44pt、快捷入口横向滚动且标签最多两行，默认字号几何不变；旋转进度环和图片锚点尊重 Reduce Motion；续接候选新增/替换/消失时为 VoiceOver 发布状态并在消失后回焦深度阅读入口，图片续接按正在生成/已完成/失败把焦点交给具名 tool part，首页在内页期间不抢播报；会话级联只在首次入场播放。
- 数值决策记录：深色卡投影保持原型实测 `rgba(0,0,0,.58)/.76`（规格禁止取整优化，渲染观感问题实际由逐行投影叠加引起，结构修复后即为设计意图）；深色玻璃取原型 `data-mode="dark"` 实测 `.14→.08`（用户规格「10% 白」为概述，HTML 为像素实测源）；FAB 外投影 `radius 10 y 8` ≈ CSS `0 8 20` 视觉等效（SwiftUI radius 与 CSS blur 非字面同义）；功能标签 600 / Continue 标题 600 与 HTML 一致（规格 §4 的 500/640 与自身 §3 字重三档约束冲突，按 HTML 与 §3 执行）。
- 纯逻辑可测：`HomeConversationIcon.icon(forTitle:isPinned:)`、跨功能 `HomeContinueCardModel.resolve(...)` 的空态/优先级/同级时间排序/真实未完成语义、20 字形解析完整性，契约测试在 `iosApp/iosAppTests/HomeDesignContractTests.swift`。

### Verification

- `xcodebuild -skipMacroValidation ... build`：iosApp 与 iosAppExperimentalGPL 双 scheme **BUILD SUCCEEDED**。另从受控 `iosApp/project.yml` 在临时目录运行 XcodeGen：`HomePhosphorIcons.swift` 自动进入稳定/Experimental 双 app target，`HomeDesignContractTests.swift` 自动进入 test target；被忽略的 pbxproj 只是生成物，不是交付缺口。
- 定点测试全绿：`HomeDesignContractTests`（20 字形解析、映射、调色板/accent 实测值、Continue 模型）、`NovelCreationViewModelTests`（含并发 `loadProjects` 旧请求不得清除新请求加载态）、`IOSNovelCreationWiringTests`（该用例原断言旧首页入口顺序 `小应用<小说创作<WebMount` 与 `route:` 参数风格，已按 E 版定稿顺序与 `router.navigate(to:)` 更新；设置页「核心记忆」断言保留）。
- 动态续接回归：`HomeDesignContractTests` + `IOSCouncilRunnerMechanicsTests` + `IOSNovelCreationWiringTests` + `NovelCreationViewModelTests` 合计 **205 passed / 0 failed / 0 skipped**。议会首页投影复用 runner 注入的持久任务 store，不旁路读取全局 store。iPhone 17 Pro Simulator 最新 Debug 包构建、覆盖安装并启动成功。实测未运行的小应用显示为「已生成，尚未打开」，点击进入正确 Runner；返回首页后该候选立即消失，控制卡收起为仅五入口状态。模型议会只投影当前可继续房间，不拿历史归档冒充可恢复任务。
- AI 生图续接回归：直接相关的 `HomeDesignContractTests` + `NativeTimelineScrollCoreTests` **57 passed / 0 failed / 0 skipped**；新增真实 SwiftUI Timeline 超高消息/延迟装载精确 tool-part 回放和真实 `IOSConversationStore` 跨会话持久扫描各 1 项，均单跑通过。扩大到 `ChatViewportPolicyTests`、`ChatSwiftUIStreamReplayTests`、`ChatMessageProjectionTests` 与该持久化用例共 195 项，最终源码下为 **194/195**：唯一未过的是既有 80 行长表格流式性能采样（本轮 p95 40.58–41.04ms > 40ms）；相关生图、投影、滚动和消费契约均通过，未放宽性能阈值，也未把无调用关系的波动修补到生图功能。iPhone 17 Pro Simulator Debug 包构建、覆盖安装并启动成功；无生图候选的真实首页保持仅五入口状态，控制卡无残留占位。
- 模拟器截图（iPhone 17 Pro, iOS 26.5）：三主题像素级核对——画布/卡片/激活带/头像墨色逐点匹配设计值；行高 72、卡左缘 16、FAB 右 21/底 67、节标题间距、「Amber」标题位置均实测通过。注意：本会话 view_image 渲染不可靠（曾把原型样例数据呈现为截图内容），一切以 PIL 逐像素探测为准。
- 多会话多行态运行时验证（容器种子化 4 条会话，updateAt 倒序、首条激活）：行高各 72pt；激活行通栏 `#EFE9DF` + 头像 `#E8DDC6`/`#6F5019`；idle 行 `#F6F5F3` + 头像 `#EDEBE7`/`#8F8B85`；行间 hairline 恰为 sep 合成值 `#ECEBE9`、左缩进 70/右距 16（屏上 x=86..370，与标题字框左缘 86 对齐）；行间零阴影接缝（经第二轮补充修复后 hairline 上下为纯卡色），末行下方 18pt 卡投影带与卡侧投影完整。种子化教训：会话 JSON 时间戳须为合法 ISO8601（`...T14:40:00.Z` 空小数位非法），非法时 `JsonConversationStorage` 按 index 损坏路径扫描重建——实测该容错路径按设计工作（丢弃不可解码条目并自动建新会话）。
- 首页当前会话 meta 交错（时间·条数 ↔ LLM 浓缩预览）：复用 titleModelId；FG/BG 共用 ConversationListPreviewGenerator（latest-wins）；setListPreview 拒绝已删 id；预览落盘 list-previews.json；光晕 56pt 自裁、按压 leading、meta 0.4s 淡切；Reduce Motion 不轮播。
- 当前会话头像呼吸光晕（E 版 `hm-breathe`）：`isCurrent` 头像琥珀 glow 3.4s / 延迟 1.6s，Reduce Motion 关闭；`HomeCurrentAvatarBreath` 强度契约 + 接线源码断言已纳入 `HomeDesignContractTests`。
- 首页 taste 可达性回正（2026-08-07）：右下拇指区浮层「新对话」胶囊（非圆 FAB、非顶栏、非假底栏）。Continue 黑 CTA、色带 0.16s、呼吸 glow、切片投影保留。
- Simulator 真实交互复验：搜索胶囊原位展开且 focus 环可见；输入「红酒」后实时只保留匹配会话；Esc 与「取消」均收起、清空并恢复完整列表。相邻页面抽查设置页与 Chat 页，暖灰画布/暖白分组表面层级仍清楚，首页专用玻璃没有泄漏到内页。
- Dynamic Type 实拍复验：iPhone 17 Pro 的 accessibility XXXL 下，Continue 标题/状态/主按钮完整，五入口可横向滚动且标签不压缩为单字列；恢复 Large 后卡片、72pt 会话行、FAB 与原 E 版默认几何一致。截图存于当前 Codex visualization 的 `home-resume-fix/02-max-dynamic-type.jpg` 与 `03-default-restored.jpg`。
- 未覆盖：真实 VoiceOver 开关下的播报顺序/焦点迁移、按压 0.98 回弹的触感/中间帧（自动化只能稳定取得释放后终态）、真机观感与 swipe 手感；模拟器没有可复用的真实生图记录，未支付调用真实 provider，因此「生成完成后从首页进入并滚到真实图片」仍缺一轮真实账号手工验收。「还没有会话」空态卡为生产不可达路径（`bootstrap()` 与 `deleteConversation()` 在列表为空时都自动 `newConversation()`；唯一可达空态是搜索过滤后的「没有匹配的会话」）——结构事实记录，非验证遗漏。

### Known Issues（非本轮改动引入）

- `iosApp/**/*.xcodeproj/` 在 .gitignore 中：拉取 5bcf860ea 后本机 xcodeproj 缺 `NovelGhostwritePipeline.swift`、`NovelChapterPlanAcceptanceLifecycle.swift` 的 target 引用，app target 无法编译；已在本机 project.pbxproj 补齐（本机修改，不会入 git）。其他机器拉取后需同样处理。`ab1d5fbeb` 新增的 `HomePhosphorIcons.swift` 同样缺引用（编译报 `cannot find type 'HomePhosphor'`），已于 2026-08-06 按同一模式补进本机 project.pbxproj（iosApp 与 iosAppExperimentalGPL 两个 app target）；pbxproj 被 gitignore，不入 git。
- 同一推送带入的 `NovelCollaborationModeTests.swift`、`IOSMemoryRecallPolicyTests.swift` 与当前 Shared 框架/代码不兼容（MemoryRecord 签名、AgentRuntimeSetting 等），加入 target 即编译失败；保持未加入 target，待该 slice owner 修复后再纳入。
- `IOSSettingsWiringTests.testBackgroundToolEnginePublishesLiveActivityStagesAtExecutionBoundaries` 在基线上即失败：`15a2607a8` 在 run block 内重新引入 `await self.publishRunningPresentation(`，违反既有 wiring 契约；与本轮首页改动无关，待 owner 裁决。
- `ChatSwiftUIStreamReplayTests.testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` 在当前 Simulator 采样不稳定：同一工作区本轮既有通过，也出现 p95 40.823–44.215ms（门槛 40ms）和 SIGKILL；未放宽阈值，需在安静设备/真机重新建立性能证据。
- 首页视觉真机验收仍缺（多数据多行态已在模拟器完成像素级验证，见 Verification；真机观感与 swipe 手感待确认）。

## iOS MiniApp 与 Android 对齐（未提交）

- 截图中的「Amber 小应用示例 + 状态/源码/版本 + 300pt 预览」来自 iOS MVP：生产仓库默认 seed 了固定样例，Runner 又把开发管理面板当成运行首页。现在生产默认不 seed；升级时只删除字段、版本、授权、运行次数、审计和本地数据都完全未变化的旧样例，任何已使用/编辑样例均保留。
- Runner 默认直接显示沉浸式 WebView；返回、标题/版本和右上角管理按钮是唯一常驻 chrome，源码、权限、版本恢复、审计和 bridge 日志收进 large sheet。源码加载、保存版本、恢复版本、外链图片授权和运行策略变化都会刷新实际 WebView，不再只改 SwiftUI 状态。
- 模型协议补齐 MiniApp V3 全能力、自检和长输出约束；截断后的紧邻「继续」会从 `{` 重生完整 JSON，跨过无关用户轮次不会误恢复旧请求。解析、校验、版本冲突或仓储失败会写入可见错误，并把前台/后台 run、Watch、Live Activity 收口为 failed，而不是假 completed。
- iOS bridge 已补 `window.fetch`、`externalImages`、`launch`、加速度计/陀螺仪、一次定位、剪贴板读取、WebView debug 和源码开关；敏感系统能力逐次确认，launch 全局限频，Runtime/WebView 关闭会取消请求、事件、传感器和系统 continuation。`host.sendToConversation` 已真实写入当前 Chat composer；`host.createArtifact` 继续落 Workspace。
- Review 收口：MiniApp 意图会抑制同轮 Generative UI planner，空响应与解析失败均按 failed 终态收口；后台 Workspace 同步失败只按 message id 替换目标卡片，不再拿旧全量快照覆盖并发消息。Repository 普通 mutation 在原子写失败时恢复最近 committed state，失败授权或 shared data 不再泄漏到内存并被后续写入带盘。
- 进程强杀闭环：Chat 生成/修订会把 app/version 与轻量 pending undo 一次原子写入同一 `miniapps.json`；会话正文落盘后前后台路径都先 commit pending，再同步 Workspace。若进程死在两次文件写之间，冷启动会在开放会话入口前扫描持久聊天卡，并用 `appId + version + htmlHash` 精确决议：卡片已落盘则保留 app，未落盘则 CAS 回滚；旧版本卡片不能替新版背书，后续 rename/run/version 改动不会被旧事务覆盖，30 版裁剪时被移出的版本也可恢复。没有引入独立数据库或第二套后台状态机。
- KMP conversation 的 `{id}.json` 是 canonical、`index.json` 是派生缓存；索引刷新失败不再把已原子提交的会话正文误报为 save failure，避免 iOS 随后错误回滚 MiniApp 并留下悬空聊天卡。列表读取仍会从会话文件扫描并机会性修复索引。
- 权限生命周期：首次授权弹窗因离页/重建取消时不再持久化为 DENY；确认返回后复核 app/version/permission/policy/grant。设置或授权变化会重建 runtime 并关闭旧 EventBus/Sensor/请求；EventBus unsubscribe 在撤权后仍可清理，订阅/发布加 Android 对齐的上限。AI 每次调用确认并按 app/day 限 50 次。
- 外链图片不再开放 WebView 直连 `https:`；只允许 `amber-miniapp-image:` 受控代理，复用公网 DNS/私网与重定向防护、HTTPS、image MIME 和 2MB 上限。`host.getTheme` 返回当前 Amber 深浅主题，WebView/错误页透明适配宿主。
- UI review：列表、Runner、管理 sheet、设置页已在 iPhone 17 Pro Simulator 实拍；返回语义、Toggle/源码/权限菜单 VoiceOver 标签、44pt 更多/源码动作/版本恢复、语义字体、窄宽 metadata 回流、设置 divider 对齐、状态色对比、可见 toast/announcement、明确 loading 态均已修。MiniApp 生成协议新增 44×44、320px reflow、lang/label/focus、深色与 Reduce Motion 契约；旧截图所示源码+预览同页已不再是当前结构。
- 为兼容已保存 iOS MiniApp，`Amber.search` 同时支持旧数组用法与 `.items`，EventBus/Sensor 回调同时保留 payload 和旧 envelope 字段。
- 定点门禁：`IOSMiniAppBridgeRuntimeTests`、`IOSMiniAppOutputParserTests`、`IOSMiniAppChatMessageFactoryTests`、`IOSMiniAppRepositoryTests`、`IOSConversationStoreTests`、`IOSParityRedLightTests` 受影响集合合计 **155 passed / 0 failed / 0 skipped**；新增覆盖创建/修订强杀恢复、精确卡片对账、旧卡拒绝、30 版裁剪恢复、冷启动端到端扫描，以及前后台 commit 顺序。`JsonConversationStorageTest` 全类 JVM 门禁 **BUILD SUCCESSFUL**，含 canonical 会话写成功而派生索引写失败的回归；`git diff --check` 通过。iOS 最终门禁需排除当前工作区中范围外且 API 已漂移的 `HomeDesignContractTests.swift`、`NativeTimelineScrollCoreTests.swift`，两者未修改。截图证据保存在当前 Codex visualization 的 `miniapp-audit/`。仍缺真实 CoreLocation/CoreMotion/剪贴板与真实 provider 的设备端闭环；磁盘上仍是两个独立文件，但强杀中间态已有可恢复协议，不再依赖进程内 closure。

## iOS Skill / MCP Chat 对齐

目标：对齐 Android Chat 创建本机 Skill / 连接 MCP 的最小闭环，不过度设计。

- 启动 seed：`IOSBuiltinSkills.installIfMissing` 写入并启用 `skill-creator`、`会议准备`、`监控文档`（内容嵌入，不依赖 Bundle 资源目录）。
- Chat 工具：`skills_list` / `use_skill` / `skill_validate` / `skill_import` / `skill_enable` / `skill_disable`，以及 `mcp_list` / `mcp_test` / `mcp_import_from_skill`（另保留既有 `mcp_call`）。声明在 KMP `Tool.kt`，执行在 `IOSSkillMcpToolService`。
- 写工具门控：用户说创建/连接/做一个 skill 或 MCP 时也会声明 `workspace_file_write`，不再要求必须出现 `/workspace` 字样。
- 创建路径：`use_skill(skill-creator)` → `workspace_file_write` → `skill_import`；若有 `mcp.json` 再 `mcp_import_from_skill` → `mcp_test` / `mcp_call`。
- Review 精准修复：skill enable/list/use 统一以目录名为键；单文件 `SKILL.md` import 顺带 sibling `mcp.json`；`mcp_import_from_skill` 跳过已存在同名 server；前台 `maxToolResumeCount` 4→6（与后台一致）。
- 定点测试：`IOSSkillMcpToolsTests` + `IOSSkillFileStoreTests` + `ChatViewModelGenerationParamsTests` **22 passed / 0 failed**。`project.yml` 排除已知 API 漂移的 `IOSMemoryRecallPolicyTests` / `NovelCollaborationModeTests`。
- 未覆盖：真机对话闭环、zip skill 导入、按 MCP 工具展开为 `mcp__*` 声明。

## Current Product Truth

- 当前工作主线是原生 iOS + KMP 共享能力。`app/` 是 Android 应用，不代表 iOS 运行时。
- 默认且唯一生产 Chat 列表路径是 `NativeChatTimelineView`。`ChatSwiftUIMessageList` 只在 `CHAT_PERF_REPLAY` 下保留，UICollectionView 路径只作非默认回归。
- 小说创作已经具备创作 / 正文 / 设定三入口、独立创作与剧情同步模型、Quick Start、资料建议、收录、编辑、剧情同步、分支/Fork、整章润色、导入导出和中断恢复。小说项目文档是领域权威，普通 Chat/Memory/Workspace 不是小说存储。
- 共创模式现已与规格对齐：`needsSync` 时仍可讨论，但正式正文生成、整章重写、润色和对应 retry 均失败闭锁。共创 / 代笔双模式计划见 `docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`（Active；Phase 0–3c 完成，下一刀真机验收）。
- Chat、小说和模型议会在页面退出后由 App 级 owner 继续持有运行；iOS 本地后台仍受系统调度约束，不等于无限后台。
- 只有官方 OpenAI Responses API 的小说正文、重新生成和单章润色已接入服务端 background response + cursor 恢复。Quick Start、讨论工具循环、Chat、模型议会及其他 provider 仍是本地 best-effort。
- Live Activity、锁屏卡和 continued-processing task 按 `runId` 独立管理；旧 run 的完成、取消、深链和系统移除回调不得作用于新 run。

## Novel Collaboration Mode Phase 0 / 1 / 2

- Phase 0：`canStart(.prose)`、`NovelGenerationReducer`、injection planner 与 prose retry 投影均要求分支 `synchronized`；同步横幅与写正文占位对齐；精确重试同步 composer mode/granularity。
- Phase 1：项目 `collaborationMode`；分支级 `NovelChapterPlanRecord`（草稿/确认 + digest）；顶栏「项目控制」面板可切共创/代笔（切代笔做策划包就绪检查，不强制本章合同）；确认合同注入整章 prose；代笔写整章无确认合同则 UI/`canStart`/reducer/retry 挡；收录仍人手。
- Phase 2（验收深度 B）：候选/run 绑定 `chapterPlanDigest`；收录 digest 不匹配则拒；`systemAutoCollect` 来源；结构化 `chapterPlanAcceptance`（stateSync 模型）；SessionViewModel 单章 pipeline（写→验收→自动收录→同步→暂停）；面板开始/暂停/继续；进行中硬切共创挡。
- Phase 2 review 修复：复用候选只认 `progress.candidateID`；收录成功即清合同；继续按钮严格按 `canStart`；代笔中禁用合同编辑与模式 Picker；合同错误提示就近显示；清除需确认；合同字段常驻标签。
- Review 修复：协作模式/清除合同的历史账本校验不再断言当前态；`canStart` 按 run 粒度判断；面板 sheet 改为 large、合同按钮 44pt、IME commit、Picker 本地回滚。
- 完成计划后的生产 review 修复：代笔开始时重新校验策划包、本章合同与当前主分支；项目级模型 Picker 等待持久化成功后再关闭，失败留在原位并提示；小说 composer 输入命中区不低于 44pt；元信息切换遵循 Reduce Motion。
- Watch `WKCompanionAppBundleIdentifier` 改为字面量 `app.amber.ios`；`IOSMiniAppBridgeRuntimeTests` 的 `async let` 断言改为先 await 再 XCTAssert。

### Phase 3a — 跨章防复读（薄回执）

- Snapshot 携带有界 `recentWrittenHighlights`；收录后的 `finalizeCollection` / 手动同步 finalize 用新事件 summary 合并截断；旧文档缺字段 decode 为空。
- 整章 prose 注入 `RECENT WRITTEN BEATS`（计入 required 预算预检）；续写不注入。
- 验收 prompt `novel.chapter-plan-acceptance.v2`：必填 `obviousRepetition`；decoder 兼容 schemaVersion 1。
- Pipeline：合同通过但 `obviousRepetition` 非空 → 暂停不自动收录，继续时重写不复用同稿；收录后按 binding 清合同，失败则停；完成后 detail 提示已记入要点条数。
- Review 修复：失败 detail 用红色 Label；代笔中合同字段禁用；workspace 清合同后字段回填；代笔按钮补 contentShape。

### Phase 3b — 连续性软门

- 项目偏好 `pauseGhostwriteOnBlockingContinuity`（默认 true；旧文档缺字段 decode 为 true）；面板 Toggle「连续性出现「严重」问题时暂停」。
- 代笔：合同验收通过且无复读后、自动收录前调用 `auditContinuityIncludingCandidate`（已有正文 + 候选下一章）。
- 仅 `blocking`（界面「严重」）暂停不收录；`failedChunkCount > 0` 亦暂停（审计未结论不得放行）；`major`/`minor` 不挡。
- 继续：同 binding 保留 `autoCollectedCandidateIDs` 与可复用候选；复读/严重连续性强制重写；已 collected 同 digest 不再写第二遍。
- Review 修复：`ghostwriteProgressStorage` 可观察；owned-run 取消；blocker 文案与 Toggle 错误就近显示；`.cancelled` 非红错。

### Phase 3c — 审稿模型 / 下一弧 / 代笔看板（薄可交付）

- `NovelModelRole.review`：项目 `reviewModelPolicy` + App 默认偏好；设置页与项目设置暴露「审稿模型」；合同验收与连续性审计走 `.review`。
- 分支级 `NovelUpcomingArcRecord`（最多 8 条）；整章 prose 注入 `UPCOMING ARC`；面板「下一弧」保存/清除；续写不注入。
- 面板拆「代笔看板」（只读回执）与「代笔推进」（Toggle/按钮）；审稿显示走 effectivePolicy + displayName；步骤回执用短码，暂停原因只在 detail。

### Verification

- `NovelCollaborationModeTests` **22/22 PASSED**（含下一弧 upsert/clear、整章注入、看板步骤回执）。
- `NovelProjectConfigurationTests` 审稿模型独立持久化 + 偏好三角色、相关 wiring 两项 **PASSED**。
- `NovelPromptCatalogTests/testCatalogSnapshot` 已随 acceptance v2 更新哈希。
- 完成后 review 定点与配置/协作回归 **158/158 PASSED**；Session、reducer、注入、连续性、生命周期、恢复、文档与 prompt 扩大回归 **296/296 PASSED**，两组共 **454 passed / 0 failed**。
- 最新 Simulator Debug 包已重新安装并成功启动；当前自动化无法稳定进入小说深层页面，且随后 CoreSimulatorService 连接失效，因此这里不把启动或静态读码当成深层视觉验收。
- 文案提交 `52794d2de` 的 3 项受影响定点测试 **3/3 PASSED**；已用 Team `89QRFX9548` 完成 iPhone Air（iOS 27.0）Debug arm64 增量构建与严格签名校验。2026-08-06 覆盖安装并成功启动 `app.amber.ios`，安装容器 `85B13522-2E02-41F4-9CD0-3EEE65C4B6CC/iosApp.app`；数据库 UUID 保持 `AC96CD34-4AD9-4317-A4CD-6BB64DC7FD3F`，未先卸载，现有 App 数据应保留。
- 真机：代笔单章闭环、审稿模型生效、下一弧注入、看板回执 — 仍待设备验收。
- 下一刀：真机 Phase 2/3a/3b/3c 验收。

## Current Review Fixes

- MiniApp 生成/修订现在以目标 app 的状态切片记录本次 mutation；前台或后台 conversation 保存失败时，只在该 app 未被后续修改的前提下恢复原记录与版本，不影响其他小应用。
- Workspace artifact 改为 conversation 首次保存成功后再同步；同步失败会保留可运行的 MiniApp 与已落盘聊天卡片，并在当前消息中显示失败原因后尝试补存提示。
- MiniApp 卡片导出缺失记录或临时文件写入失败时显示 alert；操作按钮保持原 bordered 视觉，提供 44pt 命中区，并在横向空间不足时切换纵向布局。
- SVG“保存”胶囊保持 28pt 视觉，只把交互命中形状扩大到 44pt。
- iOS 核心记忆在 `AppShell` 首次渲染前同步加载；损坏文件会保留原件并停止写入，缺失文件清空内存态，所有新增/编辑/删除在原子写失败时回滚完整快照并返回失败。
- Chat 召回统一使用运行时 `maxItems` / `maxPromptChars`、scope、归档/过期与相关性策略；实际进入 provider 请求的记录会只更新 `lastUsedAt` 并持久化，不再把编辑时间误当使用时间。
- 模型写入审批会区分保存、编辑、删除；删除展示目标正文并使用 destructive 语义，被拒绝或 scope 禁用的正文不落审批历史。记忆页补齐可观察刷新、陈旧编辑保护、错误反馈、审批历史清除和 44pt 交互区域。
- 核心记忆专用测试 **14 passed / 0 failed**，工具写入/审批链路定点 **5 passed / 0 failed**；iPhone 17 Pro Simulator 已实屏核对记忆主页首屏，审批卡真实触发态与真机 Dynamic Type 仍待设备验收。

## Generative UI Current State

目标是在 iOS 原生 timeline 中复用现有流式 `show-widget` 解析与安全 SVG 渲染，补齐 Android 已有的视觉意图路由、模型提示和终态兜底，不新增第二套渲染器或滚动 owner。

本轮新增：完成态且安全净化后的 SVG 卡片右上角提供 44pt“保存 SVG”命中区；通过系统 Files 导出从净化 HTML 中提取的 `.svg`；不导出 raw `widgetCode`、slides 或 full_html 封面预览；导出失败显示 alert。相关契约测试覆盖净化提取、完成态/安全门控和安全文件名。

- `GenerativeUiPlanner` 已从 Android app 下移到 `core:ai:generation:api`，并与共享 show-widget prompt/protocol 一起导出给 Swift；Android 保持原 FQN 调用。
- iOS 请求入口按真实用户意图注入共享 prompt：直接流程图/架构图/PPT 请求不暴露无关工具；图片生成和需要搜索、文件、skill、subagent 的请求保留工具路径。
- 直接 SVG/PPT 请求的流终态必须含完整可解析的 show-widget；缺失时清掉坏的可见 assistant 草稿，关闭工具与 reasoning 后最多重试一次。第二次仍失败就保留真实模型结果，不伪造本地 SVG，也不循环重试。
- 需要搜索、文件或其他工具的画图请求仍保留工具链，但最终 assistant 同样必须交付图；工具执行前后的 background handoff 会重新注入同一视觉协议 prompt。
- 这份要求和“是否已重试”随 iOS 前后台 handoff 持久化；后台补绘开始前原子写入补绘请求和 attempted checkpoint，输出上限截断但没有未闭合工具调用时仍可补绘一次。
- foreground terminal 以 `runId` 校验所有权，旧 run 的异步保存/Live Activity 回调不能清掉新 run。后台 expiration 在异步 finalize 前即暴露 terminal owner，save continuation 不再漏掉已抢占的终态。
- full_html 只通过专用 validator 成为完成卡片；共享 `amberagent.local/full-html` runtime URL 会改写到本地资源，非法 deck 不再靠静态封面伪装成功。
- `[ROUTE:image|svg|diagram|slides]` 仍参与路由，但原生用户气泡不再显示该元数据。partial/complete 卡片 identity 保持稳定；卡片初始/最小高度为 96pt，操作按钮 44pt 且窄屏纵向排列，WebView 流更新按一帧合并。结构化折线不再越过标签，单节点 flow 居中。

### Verification

- “保存 SVG”导出 helper 的 Python 镜像契约已核对：只提取净化 SVG，partial/unsafe/非 SVG 均被门控；`git diff --check` 通过。
- 本机定点 `xcodebuild -only-testing:iosAppTests/IOSGenerativeWidgetParserTests/testSVGExport*` 未能编译：沙箱无法写 `~/.cache/clang/ModuleCache` 与 SwiftPM ManifestLoading 诊断文件，且 CoreSimulatorService 不可用；这不是本次按钮逻辑失败。
- `GenerativeUiPlannerTest` 与 `IosChatBackgroundPayloadJsonBridgeTest` 的 JVM 定点测试均 **BUILD SUCCESSFUL**。
- SVG/parser、full_html runtime、前台 stale-run 和后台 expiration 定点：**22 passed / 0 failed / 0 skipped**；完整 `ChatViewModelSelectedFileContextTests`：**63 passed / 0 failed / 0 skipped**。
- 扩大到 `ChatSwiftUIStreamReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、`IOSParityRedLightTests`：**170 passed / 1 failed / 0 skipped**。唯一失败是范围外的 24KB 纯文本 pacing 契约冲突：当前实现允许单拍 36 字，测试要求绝大多数更新不超过 24 字；隔离复跑仍失败。该用例不经过 widget parser/card，也未放宽阈值。
- Android app 的定点 `GenerativeUiPlannerTest` / `GenerationPromptsTest` 被当前工作区中范围外的 Model Council 缺失符号阻断在 app 编译阶段；共享 planner 自身的 JVM 测试已通过。

### Remaining Acceptance

- 真机使用至少一个 OpenAI/Claude-compatible provider 发起“画流程图/架构图”请求，确认 SVG 在同一条 assistant timeline card 中随流逐步出现，结束后无需重试或至多自动重试一次；完成后点右上角“保存 SVG”，确认 Files 导出可用、文件名安全、失败有提示。
- 再验证图片请求仍调用 `generate_image`、需要外部上下文的视觉请求仍先完成工具链、PPT 最终落为完整 `full_html` deck。
- Simulator 性能探针不能替代 ProMotion 真机的卡片高度增长和滚动手感验收。

## Novel Streaming Current State

目标是消除小说长文流式生成中数次大幅高度跳变及终态最后一拍闪烁，不新增滚动 owner 或几何补偿。

- Chat/小说共享 pacer 的单拍上限从 64 收紧到 36 个字符，仍沿用 48ms 节拍。
- 小说 completed、interrupted、failed、persistence-blocked 先按现有节拍排空可见正文，再切 durable terminal；排空期间关闭 Stop，并继续用既有 busy gate 阻止下一次 mutation。
- 终态规范化从当前可见正文前缀继续 pacing，避免模型误包 Markdown 围栏时整章一次替换。
- 小说 Native Timeline 复用 Chat 已验证的 UIKit 手势判定，程序化 `.interacting` 不再被误判成用户上滑。
- cancel 或 binding 失效会撤销排空标记；persistence-blocked 排空后仍回到原有重试入口。

### Verification

- 相关门禁：`NovelSessionViewModelTests`、`NovelSessionReplayTests`、`NativeTimelineScrollCoreTests`、`ChatViewportPolicyTests`、4 条 SwiftUI 长文/终态回放及共享 pacing，共 **206 passed / 0 failed / 0 skipped**。
- 扩大到完整 Chat SwiftUI：**223 passed / 1 failed**。唯一失败是独立完整 Markdown 表格 display-link probe 的 simulator 时序阈值（p95 约 40.6ms 对 40ms）；隔离复跑有过有败，未放宽阈值，也没有据此修改产品路径。
- 最新产品包已用 Team `89QRFX9548` 完成 iPhone Air Debug arm64 构建，`codesign --verify --deep --strict` 通过。
- 含「保存 SVG」的主包已于 2026-08-05 19:41 覆盖安装并启动 `app.amber.ios`；安装容器 `0693C392-DCFC-4B56-8DE1-EE37945B4DFF/iosApp.app`；主 Debug dylib SHA-256：`e4062b24644f92abc199f50dd84562ec349d868c11113c45df498eb0ba51a8a6`（二进制含「保存 SVG」）。覆盖安装未卸载，应保留既有数据。Files 导出与 SVG 卡按钮可见性仍待手测。

### Remaining Acceptance

- 在真机用真实 provider 生成长章节，观察中途高度增长、终态切换和轻拖上滑期间是否仍闪烁或被拉回底部。
- 真机 ProMotion、rubber-band、键盘安全区和后台系统到期行为不能由 Simulator/单测替代。
- 该 slice 已随 `61c3b4e46` commit/push；本页顶部列出的 `58b473837` review fixes 已构建并覆盖安装到真机，但长文手感仍需真实 provider 操作验收。

## Recently Landed Baseline

- `da71c8597`：修复 Quick Start 宽容解析边界、失败草稿污染、上下文人物建议和小说流式滚动；受影响小说回归 **439 passed**。
- `086e57525`：补齐长时间流式任务的本地后台所有权和官方 OpenAI 小说生成的服务端恢复路径。
- `5a767e480`：完善小说流式生成、Ask User、资料与设置交互。
- `db7371fcb`：修复交互终态串线、中文输入最后一拍和小说编辑内容失真。

## Current Priorities

1. 先完成 Generative UI 的真机真实 provider 验收，重点看同卡片渐进 SVG、一次兜底边界、右上角“保存 SVG”导出、图片工具路由和 full_html deck 完整性。
2. 完成当前流式实现的真机长文手感验收；若仍跳变，记录发生阶段、是否触摸屏幕、是否终态以及可见内容变化，再沿现有 owner 定位。
3. 只在真实复现支持时继续调整 pacer、终态排空或 Native Timeline 手势判定，不加第二套状态机或 offset 补偿。
4. 后台能力下一步优先补真实 OpenAI 账号下的 expiration / 强杀 / 冷启动恢复证据；其他 provider 不伪装成服务端 durable job。
5. 小说共创 / 代笔：Phase 0–3c 已落地；下一刀真机验收（单章闭环、审稿模型、下一弧注入、看板回执）。
6. Android 小说复刻属于 Android 主仓；本仓的 `NOVEL_CREATION_ANDROID_IMPLEMENTATION_PLAN.md` 仅是跨仓草案。

## Known Risks

- 当前产品改动已提交；后续仍需用实时 `git status` 复核是否有新的并发工作区改动。
- “保存 SVG”契约 helper 已覆盖，但 Files picker / 真机导出体验与完整 `IOSGenerativeWidgetParserTests` 编译运行仍缺本机沙箱外证据。
- Generative UI 的终态契约已经自动化验证，但模型是否能在真实 provider 的 token/window 限制内稳定输出完整 SVG/full_html 仍需真机与真实账号证据。
- 当前 Chat pacer 上限 36 与 24KB 长文门禁的 24 字要求冲突；这是与 widget 无关的既有问题，需决定恢复 24、调整发布策略或同步契约。
- Android app 回归当前受范围外 Model Council 编译缺口阻断；不能把共享 planner 测试通过等同于 Android app 全门禁通过。
- 长表格 display-link 性能探针在 Simulator 40ms 边界附近波动，尚无足够证据修改 renderer identity、发布 owner 或测试阈值。
- iOS continued-processing 由系统决定调度与终止；用户从 App Switcher 强制结束后，本地 SSE 无法继续。
- 服务端恢复在首个 `response.created` cursor 落盘前遭进程终止时没有 response ID，无法恢复；还受服务端保留窗口和账户数据策略约束。
- 历史消息行内公式 `mathInline` 仍缺已接受的渲染设计；不要用字符串替换或额外 layout 分支硬补。
- 后台到期已覆盖单轮累计 partial，但仍缺“完成至少一轮 assistant/tool 后，下一轮 expiration”完整保留既有 suffix/tool output 的端到端契约。
- 小说真实 provider 全流程、长文手势、后台到期、Files picker 和最终视觉仍需设备证据；构建、安装和单测通过不等于这些体验已验收。

## Canonical References

先看 [`README.md`](README.md) 的主题地图。当前常用权威入口：

- 工程规则：`AGENTS.md`、`iosApp/AGENTS.md`
- 小说局部规则：`iosApp/iosApp/NovelCreation/AGENTS.md`
- 小说产品与领域：`docs/NOVEL_CREATION_SPEC.md`、`CONTEXT.md`
- 小说所有权 ADR：`docs/adr/0007-novel-creation-owns-project-state.md`
- 小说实现基线：`docs/NOVEL_CREATION_IMPLEMENTATION_PLAN.md`
- 共创 / 代笔计划：`docs/NOVEL_COCREATION_GHOSTWRITE_PLAN.md`
- Live Activity 视觉：`docs/ACTIVITY_ISLAND_REDESIGN.md`

## Update Contract

仅在分支/工作区、当前产品事实、最近验证、优先级、已知阻塞或权威入口变化时原地更新。删除已失效内容，保持可在几分钟内读完；不要恢复按日期无限追加的日志结构。
