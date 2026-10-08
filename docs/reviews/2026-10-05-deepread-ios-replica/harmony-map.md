# 鸿蒙独立深读 UI 集成地图（只读查证）

日期：2026-10-05。源根：`/Users/arquiel/Downloads/AI/amber-harmony-preview`。查证 HEAD：`b2d054bd7`。工作树有大量此前已存在的 tracked / untracked 变更；本次仅新增本报告，没有改源码、构建、签名或操作设备。已阅读 `.agents/skills/harmony-arkts/SKILL.md`。

## 1. 产品根与实际入口

| 对象 | 当前事实与位置 |
|---|---|
| 可编辑 UI 源 | `harmony/entry/src/main/ets/`，并非另一个独立 DeepRead entry 源目录 |
| 共享领域模块 | `harmony/deepread/src/main/ets/`，包名 `@amber/deepread-domain`；另共享 `harmony/chat` 的 providers / 搜索偏好 / AI 客户端 |
| 生成后的独立工程 | `.harmony-standalone/deepread/`，实际存在，AppScope 已是独立 bundle；它是构建快照，不是手动改 UI 的源根 |
| 独立 bundle | `app.amber.deepread.reader`，见 `scripts/harmony-standalone/prepare.mjs:10`、`ProductIdentity.ets:14` |
| 宿主 bundle | `app.amber.deepread` 会被 `productKindForBundle()` 识别为 `agent`，不是独立深读；skill 中旧安装示例不能直接用于本产品 |
| 根页 | `ProductIdentity.ets:24` 的 `productRootPage()` 返回 `pages/BoardPage` |
| 启动选择 | `entryability/EntryAbility.ets:306`：`windowStage.loadContent(productRootPage(), ...)` |
| 页面注册 | `resources/base/profile/main_pages.json` 同时注册 Board、Article、History、Compose、Sources 和各设置页 |

`prepare.mjs` 从当前 `harmony/` 复制 AppScope / entry / chat / deepread / native / hvigor 等源码到生成根，改 bundle、图标、产品资源和权限，并去掉源工程 AGC signingConfigs。生成目录保留 build 缓存、ohpm 依赖和本地签名材料；不能依靠源 `build-profile.json5` 自动替独立包签 AGC。

当前独立深读没有持久的三页底部导航：Board 顶栏加号→Compose，历史按钮→History，齿轮→StandaloneSettings；它们是 `router.pushUrl` 的独立 @Entry 页面。复刻 iOS 导航若需要新 host，应明确嵌入组件与路由页的生命周期，不能把现有 @Entry 页面直接当可复用组件猜着装入 TabContent。

### 推荐的最小 3 tabs 落地

保持现有三张 @Entry 页分别作为发现 / 阅读库 / 设置 tab 的根：`BoardPage` / `DeepReadHistoryPage` / `StandaloneSettingsPage`。由主 agent 提供共用底栏，各页传固定 selected id。只在独立 deepread 产品的这三页显示；不创建第四个“创建”tab。

- tab 切换用 `router.replaceUrl()`，同 tab 点击无操作，Promise 期间禁止重复切换。这样 tab 点击不增长返回栈，避免发现→库→设置后反复返回旧 tab。
- 创建入口仍在发现 Hero 和库顶栏；Article / Sources / Create / 子设置 / 模板页保留既有 push/replace 语义，不显示 tab bar。Article back 返回刚才的根 tab。
- 当前 selected 直接由每张 page 的固定常量驱动，避免另加 AppStorage activeTab 与 router 状态互相覆盖；若主 agent已有 activeTab，则各根页 onPageShow 以真实 route 校准。
- 三张根页 `onPageShow()` 都调用 `markWantRoutingReady(this.getUIContext().getRouter())`。现有仅 Board 调用；Board 被 replace 销毁后，不应继续让通知通过它的旧 UIContext Router 跳转。通知 push Article，back 回当前 tab。
- `productRootPage()` 冷启动继续指向 Board，`main_pages.json` 已注册三页，无需新增 root route。宿主的 Board / History 仍保留原返回栏和 router 行为，用 `getProductKind()` 隔离。
- 不将 @Entry struct 嵌套。三根状态会随 replace 销毁并从既有 repository / storage / scheduler 重读；现有生命周期取消和观察守卫正适配这条路径。

## 2. 页面与业务接线

| 页面 / 组件 | 当前布局职责 | 当前服务 / 数据链 |
|---|---|---|
| `pages/BoardPage.ets`（896 行） | 深读发现根；Studio 顶栏、Hero、综合热点、逐源榜单、活动任务卡、随机阅读、条目操作 sheet | `getAppContainer().storage` 读热榜/关注/Wi-Fi/翻译配置 → `createNewsNowProvider(http).fetchAllSources()` → `HotListState` 投影 / `aggregateHotTopics()` 聚合 → 缓存；`getDeepReadScheduler().observeActiveRuns()` 订阅活动任务 |
| `components/DeepReadNewsroomCard.ets`（55 行） | Board 的在途任务卡 | 接收真实 `DeepReadActiveRun`；点按调用 Board.openRun → Article |
| `pages/DeepReadComposePage.ets`（183 行） | 主题、原文、URL、文件、每篇模板选择、生成按钮 | `sourceInputs(text, urls)` + `importDeepReadSource(uri)` → `makeEmptyDeepReadOutput()` → `captureDeepReadTemplate()` 保存快照 → `getRepository().upsert(entry)` → `router.replaceUrl(Article, { topicId, title })`；先落所有作者输入，再转交任务执行 |
| `pages/DeepReadArticlePage.ets`（980 行） | 阅读控制器：首用确认、真实进度、继续/取消/重试/重生成、字号、来源和导出入口、原生/HTML 阅读分支 | `loadArticle()` 读 RDB 缓存 / display prefs / 首用确认 → `getDeepReadScheduler()` → 订阅 RDB 输出及真实运行状态 → `scheduler.run()` / `runSection()`；离页取消订阅并拒绝迟到 UI 更新，任务属于共享 scheduler |
| `components/DeepReadNativeArticleBody.ets`（250 行） | 原生文章正文：标题、摘要、图片、时间轴、核心点、多方观点、引语、延伸阅读、来源、分析/图表 | 仅接收 title/output/fontScale/fontSerif/onOpenLink；没有调度或持久化，适合忠实替换呈现 |
| `components/DeepReadDiagram.ets` | 正文 diagram 显示 | 消费 output diagram；页面/renderer 传数据 |
| `components/DeepReadExportPanel.ets`（373 行） | 已保存文章 TXT / Markdown / PDF 保存与分享、PDF 预览 | 每次打开读取一个持久化 snapshot → `DeepReadExportFiles.ets`；PDF 使用捕获的模板 snapshot；未完整文章明确限制 PDF，API 能力 gate 保留 |
| `pages/DeepReadSourcesPage.ets`（136 行） | 已保存输入/搜索/网页/文件来源，采集状态、正文展开、浏览器打开 | `getRepository().observe(topicId)` + 初次 `get()`；版本门确保新 observation 赢过迟到初读；`startAbility(viewData)` 仅打开有效 HTTP(S) URL |
| `pages/DeepReadHistoryPage.ets`（266 行） | 阅读库；标题/正文搜索、状态筛选、创建、恢复文章；同时显示已存条目与未持久化活动任务 | `observeDeepReadLibrary(repository, scheduler)` → `queryDeepReadLibraryRows(snapshot, query, filter)`；`listHistory(0)` 是显式刷新后备；打开 Article 带 `fromHistory: 'true'` |
| `pages/StandaloneSettingsPage.ets`（48 行） | 独立设置目录页 | 入口到 SettingDeepRead、TemplateSettings、ChatProviderSettings、ChatSearchSettings、Appearance，只有路由，没有独立存储 |
| `pages/SettingDeepReadPage.ets`（1085 行） | 深读模型选择、发现/热榜设置、阅读字体字号、搜索入口；agent 产品另有议会模型库 | `DeepReadModelSelection.ets` 读写跟随/固定 TaskModelReference → AppContainer 的 provider/model 解析；读取同一 Preferences Storage；模板使用 `DeepReadTemplate.ets` |
| `pages/DeepReadDiscoverySettingsPage.ets`（214 行） | 五组 catalog 来源开关、关注词/排序模式、缓存间隔、仅 Wi-Fi、标题翻译 | `DEEPREAD_DISCOVERY_CATEGORIES/SOURCES` → Storage 的 `source_<id>`、`deepread_focus_*`、`deepread_hotlist_*`；与 Board 精确共享键 |
| `pages/DeepReadSearchSettingsPage.ets`（115 行） | 已配置搜索首选、Google 补充开关、管理搜索服务入口 | `loadSearchPrefs` / `updateSearchPrefs(getChatKvStore())`；不能改写无调用作用的旧 `active_search_source` 代替这条链 |
| `pages/DeepReadTemplateSettingsPage.ets`（110 行） | 内置模板 / 自定义模板选择，进入编辑、新建 | `loadCustomTemplates(storage)`；`deepread_template_id`；编辑走 Workbench route param templateId |
| `pages/DeepReadTemplateWorkbenchPage.ets`（557 行） | 新建/编辑、HTML 导入、AI 生成、Web 预览、保存、删除确认 | `load/saveCustomTemplates`；`getDeepReadTextRuntime()` 捕获同一深读任务模型 → `aiClient.generateText()`；保存模板和选用 ID；AI 填编辑器后仍需作者预览/保存 |
| `platform_impl/DeepReadTemplate.ets`（355 行） | HTML 验证、内置/自定义库、捕获、解析和渲染 | article / compose / export 都使用同一 captured snapshot；全局模板变更或删除不得改变已生成文章与 PDF |

### Board 的真实路由参数

`showTopicActions()` 捕获原始 HotItem + seedUrls + inputSources；`openArticle()` 使用 `discoveryArticleParams()` 构建 topicId / title / seeds / 来源等参数。随机阅读也通过同一 `openArticle()`，不是占位跳转。必须保留无 URL 热点的文字来源，不能只保留 URL 字段。

Board 页面可见时初始化、回页加载配置；离开时 abort 当前刷新、增加 token、解除活动任务订阅。复刻过程中可以换视觉和 sheet 样式，但这些可见性守卫、abort、缓存保存和实际 source 错误显示要原样保留。

### Article 调度与保存链（避免误改旧分支）

`di/AppContainer.ets:256 getDeepReadScheduler()` → `DeepReadRunRepositoryAdapter.hydrate()` → `createScheduler()`。

每个实际 run 由 `createRunManager()`：

1. 加载当前 topic 的输出。
2. `resolveDeepReadRuntimeCore()`（在 AppContainer:1802）解析当前 TaskModelReference / providers 与运行参数。
3. 捕获传入或已存 `DeepReadTemplateSnapshot`；只有没有 snapshot 才查当前模板配置。
4. `SearchRegistry` 懒加载聊天搜索偏好，并接入该 topic 的 `searchDeepReadGoogle()`；prefetcher 采集来源。
5. 读 DeepRead playbook；`createChatDeepReadAiClient()` 使用捕获 provider runtime。
6. `run_manager.ts` 进行来源/计划/分段写作；`scheduler.ts` 独立持有任务、abort 和活动状态。
7. `DeepReadRunRepositoryAdapter` 提交完整 RDB 输出；完整提交后 `getDeepReadArtifactStore().save(topicId)` 写 Workspace 产物。
8. `DeepReadGenerationActivity.ets` 上报真实请求/流式 snapshot → background keepalive / notification；notification Want 用产品身份，回 Article。

`DeepReadRunConfig.ets` 是旧 key 配置快照工具；当前 scheduler 主路径已改用 `resolveDeepReadRuntimeCore()`，UI 复刻不能把它当主运行配置重新接回。

Article 上层更换 chrome / progress / recovery UI 时，保留 `runNow()`、`continueGeneration()`、`retryFirstFailure()`、取消行为、完整旧稿保留、first-use confirmation、原生/HTML fallback、Web session / theme epoch 门控、Workspace retry 和导出 snapshot。

## 3. 共享边界与避免 God 文件膨胀

- `BoardPage.ets` 896 行、`DeepReadArticlePage.ets` 980 行，已接近项目 1000 行目标：不要把 iOS 的底栏、Hero、状态卡和 action menu 全堆入原页面。提取局部呈现组件，page 保留状态和调用；先机械提取后验证，再调整视觉。
- `SettingDeepReadPage.ets` 1085 行：更改独立产品布局时提取独立模型/阅读设置呈现区域，不能顺手删除 host 的旧议会模型库或配置逻辑。已有 method-extraction 行为测试会依赖方法名，提取时同步改 fixture 的实际加载位置。
- `AppContainer.ets` 1989 行为共享 God 文件，但本任务 UI 复刻不需要改其装配与 service；不要借此任务进行容器重构。
- `design/tokens.ets`、`fonts.ets`、`PageTemplate.ets`、`components/studio/*`、provider / search / appearance 设置都被小说和宿主共享。针对 iOS 深读的字体、颜色、间距和动效优先放 `components/deepread/*` / 专属 presentation tokens，而不是整体改共享 token。
- 独立 UI 分支用 `getProductKind() === 'deepread'` 与宿主行为隔离；必要的新根 route 由一个集成者拥有。ArkTS 保持 V1 装饰器，themeEpoch ForEach 真正消费，@BuilderParam 的 lambda 只调用已声明 @Builder，组件构造不放裸普通 lambda。

## 4. 可供 3 个 worker 并行的互斥文件所有权

共同前置：主 agent 先固定 iOS 参考和深读专属基础样式接口。主 agent 已计划 `design/DeepReadDesign.ets` 的 D getter（paper/card/ink/muted/rule/accent/done/warn/danger，serif/sans/gutter/radius），只对独立深读提供配色。以下分工以实际源码职责为准；任何跨区变更发消息给所有者，由其落地。每名 worker 必须知道工作树有其他 worker 和既有 WIP，不能 reset、stash、清理或撤销他人的修改。

| Worker | 唯一修改所有权 | 可新增局部组件 | 必须保留的业务 |
|---|---|---|---|
| W1 发现与根导航 | `pages/BoardPage.ets`、`components/DeepReadNewsroomCard.ets`、`pages/DeepReadDiscoverySettingsPage.ets`；如决定新独立 host，由 W1 拥有新 host 文件 | `components/deepread/DeepReadDiscovery*.ets`、`DeepReadHotspot*.ets`、`DeepReadNavigation*.ets` | 抓取/缓存/翻译/Wi-Fi、焦点投影、真实任务订阅、种子与输入来源 route 参数、sheet 动作 |
| W2 创建与阅读 | `pages/DeepReadComposePage.ets`、`pages/DeepReadArticlePage.ets`、`pages/DeepReadSourcesPage.ets`、`components/DeepReadNativeArticleBody.ets`、`DeepReadExportPanel.ets`、`DeepReadConfirmationDialog.ets`、`DeepReadDiagram.ets` | `components/deepread/DeepReadArticle*.ets`、`DeepReadCompose*.ets`、`DeepReadSource*.ets` | 创建先落资料/模板 snapshot、runtime 调度、真实 generation/recovery/cancel、Web 所有权、导出完整性 / API gate / snapshot |
| W3 库与设置/模板 | `pages/DeepReadHistoryPage.ets`、`pages/StandaloneSettingsPage.ets`、`pages/SettingDeepReadPage.ets`、`pages/DeepReadSearchSettingsPage.ets`、`pages/DeepReadTemplateSettingsPage.ets`、`pages/DeepReadTemplateWorkbenchPage.ets` | `components/deepread/DeepReadLibrary*.ets`、`DeepReadSettings*.ets`、`DeepReadTemplate*.ets` | merged library 状态、provider/model 精确选择与写入、已有搜索偏好、模板库/AI/预览/确认删除 |

主 agent 保留 `platform_impl/ProductIdentity.ets`、`entryability/EntryAbility.ets`、`main_pages.json`、`design/*`、`PageTemplate.ets`、`di/AppContainer.ets`、standalone 脚本及所有 `harmony/deepread` / `harmony/chat` service 模块所有权。若 W1 需要根 route 更新，给主 agent 新 route 名，由主 agent 注册与更新 root / 深链 ready 并做产品隔离回归。

共享专属 style 文件必须由主 agent 先落，避免 3 个 worker 同改一份 foundation。尽量对现有小组件复刻视觉，不引入新状态框架或 coordinator。

## 5. 现有测试与执行入口

本报告未执行测试；下面是实际脚本/fixture 的命令。很多 CJS 测试从真实 page 提取控制器方法执行，能证实业务/生命周期，不能证明像素、点击区和滚动体验。

深读领域与 Entry 集成回归：

```bash
npm --prefix harmony/deepread test
node harmony/chat/node_modules/tsx/dist/cli.mjs --tsconfig harmony/chat/tsconfig.json --test --test-concurrency=4 harmony/entry/src/test/deepread_*.test.cjs harmony/entry/src/test/product_identity.test.cjs harmony/entry/src/test/standalone_entry_lifecycle.test.cjs
node --test scripts/harmony-standalone/prepare.test.mjs
```

相关真实测试文件：

- 发现：Entry `deepread_board_ui.test.cjs`、`deepread_newsroom_card.test.cjs`；domain `deepread_discovery_settings.test.cjs`、`deepread_hotlist_toggle.test.cjs`、`hotlist_aggregation.test.cjs`、`hotlist_provider.test.cjs`、`hotlist_rss.test.cjs`、`hotlist_translator.test.cjs`。
- 创建/来源：domain `deepread_compose_capture_ui.test.cjs`、`deepread_sources_ui.test.cjs`、`input_sources.test.ts`、`input_sources_run.test.ts`。
- 阅读/导出：domain `deepread_reader.test.cjs`、`deepread_template_library.test.cjs`、`deepread_export_panel.test.cjs`、`deepread_export_files.test.cjs`、`deepread_artifact_store.test.cjs`、`deepread_entry_p1.test.cjs`；Entry `deepread_run_container.test.cjs`、`deepread_google_host.test.cjs`。
- 库/恢复：Entry `deepread_history_ui.test.cjs`；domain `deepread_library.test.ts`、`deepread_history.test.cjs`、`deepread_continue_support.test.cjs`。
- 模型/搜索/模板：Entry `deepread_search_selection_ui.test.cjs`、`deepread_template_ui.test.cjs`；domain `deepread_model_selection.test.cjs`、`deepread_structured_pipeline.test.ts`。
- 根导航/通知/生命周期：Entry `product_identity.test.cjs`、`standalone_entry_lifecycle.test.cjs`、`deepread_editorial_motion.test.cjs`、`studio_motion_lifecycle.test.cjs`；domain `deepread_want_route.test.cjs`、`deepread_activity_navigation.test.cjs`、`deepread_generation_activity.test.cjs`、`deepread_keepalive_native_contract.test.cjs`。

全仓聚合入口实际是 `npm run test:harmony` → `scripts/test-harmony.mjs`，会纳入 chat / deepread / entry / harmony/tests 和 prepare/lint 脚本测试。这里只要 UI 范围相关测试通过即可，是否全量由集成者依据改动决定。

## 6. 最小独立深读 lint / build / sign 命令

已经存在专门 build wrapper，不需要手动先 prepare 再设环境变量：

```bash
# 在 repo 根执行，targets 使用实际改动文件列表
harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/pages/BoardPage.ets

# 构建独立深读：prepare 当前源码 → 产品根 ohpm install --all → hvigor assembleApp
JAVA_HOME=/Applications/DevEco-Studio.app/Contents/jbr/Contents/Home node scripts/harmony-standalone/build.mjs --product deepread

# 同一最小命令加离线模拟器签名
JAVA_HOME=/Applications/DevEco-Studio.app/Contents/jbr/Contents/Home node scripts/harmony-standalone/build.mjs --product deepread --sign
```

`JAVA_HOME` 路径本次只读检查确实存在。wrapper 内 build 使用 `AMBER_HARMONY_DIR=.harmony-standalone/deepread`；签名执行生成根下的脚本，因此它的 AppScope bundle 读取也是 reader 产品。

离线 signed HAP：`.harmony-standalone/deepread/entry/build/default/outputs/default/signed/entry-default-signed.hap`。未签名 HAP 为同目录上一级的 `entry-default-unsigned.hap`。脚本离线 CA 仅用于模拟器；零售设备必须用 reader bundle 有效 AGC 调试签名和已有安装签名身份。报告未核验本机当前 AGC 材料和设备签名，不应据旧 artifacts 宣称本轮真机可装。

后续模拟器 start bundle 应是 `app.amber.deepread.reader`。因为 UI / 主题改动，skill 要求 scoped lint → 独立构建 → 签名 → 模拟器安装/冷启 → 实际截图目测，至少浅色/深色、创建、库、发现、正文/生成中、设置/模板关键状态；此轮只读地图不执行这些操作。

## 7. 追加：Composer 阅读模式与三组模板控件的实际差距

本节只读当前 iOS / Harmony 源码，不写实现，不 build。iOS 根为 `/Users/arquiel/Downloads/AI/amberagent-ios/iosApp`；下文 iOS 路径均相对此根。

### 7.1 iOS 控件不是同义装饰

| 控件 | iOS 当前行为 / 明确证据 | Harmony 当前等价范围 |
|---|---|---|
| 精读 | `DeepReadApp/Sources/UI/DeepReadComposerView.swift:33-39,76-100,158`：恰好一个链接/文件/粘贴文本时显示三段选择，精读把 `primaryIndex=0` 交给 runtime；主题可空，取原文标题；隐藏多源生成模板 | 无精读 mode / primary 字段，无导读与段落批注数据。当前所有 create 均进入综述 pipeline |
| 只读原文 | 同文件:78,87,105,159：只抓取排版原文，不调用模型；Runtime:188-198 允许无模型，:313-322 持久化 unannotated 原文立即结束；DetailView:121-126 可再生成精读 | 有已保存来源正文展开和打开浏览器，但没有独立无模型阅读任务；Article 空生成正文不认 inputSources 是文章 |
| 多源综述 | Composer:79,88：该单源当研究来源之一，继续搜索；多个用户输入或只有主题时自然进入综述；Runtime:201-224 搜索/抓取和合并，然后经典或指定模板生成 | 现有 `run_manager.createRunContext()` + `source_prefetcher.collect()` + 四 stage writer 已等价具备此类用户能力，单源也会补搜索 |
| 版式编排（4） | `DeepReadCore/DeepReadAppearance.swift:217-280`：classic / brief / timeline / debate，即经典、速读、时间线、观点对照；改变同一篇经典结构文章章节顺序与局部 CSS | NativeBody 和 `DeepReadTemplate.chapters()` 当前固定章节次序，无用户可选 layout |
| 阅读样式（8） | Appearance:46-215：classic / scroll / briefing / journal / broadsheet / minimal / academic / colorPage；纸/字/颜色/CSS 全局即刻改变，custom HTML 不受这层样式影响 | 字体(default/serif)、字号、全局 theme 已有；没有上述 8 种正文 style |
| 生成模板 | `DeepReadSynthesisTemplates.swift:6-23`：auto、两个经典杂志选项、brief / qa / debate / timeline / review；各自产生不同内容字段，auto 先模型选择；Runtime:228-257 真实生成并存 `shape=template_synthesis` | `DeepReadTemplate.ets:14-17` 只有 none / editorial / custom；仅渲染模板 snapshot，生成仍走同样四 stage，不能把 style 或 chapter order 叫生成模板 |

iOS 同名“版式”有三种职责，代码的消费者证明它们不能合并到一个选中 ID：

- `DeepReadTemplatesView.swift:12-91` 分别写 `appearance.readerLayout`、`appearance.readerStyle`、`settings.templateId`。
- Appearance:284-316 将 layout/style 即时写入 `UserDefaults` 的 `deepread.appearance.readerLayout`、`deepread.appearance.readerStyle`。
- `DeepReadArticleRenderer.swift:9-33` 先按 `close_reading` / `template_synthesis` / custom HTML 选择正文 renderer；只有经典结构渲染走 `sectionOrder: layout.order`，所有内置 style 走 canvas + CSS。因此 layout 对精读 / 专用 synthesis 不应假装重排不存在的经典章节。
- iOS Renderer:34-57 还区分默认杂志纯文字与 editorial 插图版；Harmony 当前 native 默认也显示图片，视觉对齐需要明确默认杂志的展示策略。

### 7.2 精读的数据与运行不是已有四 stage 的重命名

iOS `DeepReadCore/DeepReadCloseReading.swift:7-141` 的 `DeepReadCloseReading`：

```text
shape = close_reading
title, originalTitle, url, site, genre, guide
bodyStart, bodyEnd, heroImageURL
paragraphs[] { id, kind: text|heading|image, text }
notes[] { paragraph, kind: key|context|verify|data|add|differ, title, body, sources? }
modules? { verdict/pros/cons/specs, fact/timeline/parties/uncertain, argument, points }
others?[] { id, title, url, site, stance: agree|add|differ, summary }
comparison?.rows[] { aspect, primary, cells by report id, conflict }
```

Runtime:281-380：取原文完整正文 → 分段/编号 → 只读分支直接结束；精读分支搜索别家报道 → 调模型生成 genre 对应 modules、导读与指向已存在段落的 notes → 另调模型比较别家报道，合入 add/differ notes / comparison。导读失败保留 readable original，并标 missingSection。`annotate()` (:127-134) 去掉 original_only metadata 后重试，原文保留显示。

Harmony `domain/models.ts:113-151` 的 Output 只有 summary / entities / timeline / corePoints / analysis / links / visuals / sectionStates，以及输入与渲染模板 snapshot，没有 paragraph IDs、guide、notes、原文 shape、别家对照表。`section_writer_tools.ts:3` 的七个工具写 overview / narrative / analysis / extended_reading / visuals / diagram / finish；能写综述和阵营观点，但没有锚定原文段落/批注消费者。因此不能通过换工具提示词名称兑现 iOS 精读。

### 7.3 已有原文/单源能力可以复用什么

- **已落原文**：`domain/input_sources.ts:9-22` 的 `DeepReadInputSource` 已保存 id / kind / title / url / content / status / error / truncated；手动完整粘贴另在 Output.inputText；单源是可确定的作者输入，不需要新数据库表。
- **网页抓取**：`source_prefetcher.ts:168-245` 的闭包 `fetchSource()` 能直抓 seed，HTTP/私网 gate/abort/Jina 已在；但 `collect()` (:224-265) 总会同时调用 snapshot.providers 多角度搜索，只有传入空 provider registry 才不搜索。现有公开接口没有 collectPrimary()/seed-only 选项。
- **抓取正文不完全保真**：当前 `parseJinaReaderBody()` (:88-104) 去图片、去 Markdown 链接且丢 header Title；`fetchSource()` 将 title 设为 caller fallbackTitle。`reader_extractor.ts:43-79` 直接抓取会去标签、过滤短于 18 字符的行、去重，输出 plain text；精读原文需要保留 heading/image 和短句，不能在报告中说这一 parser 原样等价 iOS 原文。
- **已有纯转换**：`sourceInputs()`、`mergeCollectedSources()`、`generationSources()` 能处理真实单源文件/文本。只读原文的 text/file 无需调用网络或模型；URL 需要一个明确 seed-only collector。
- **原文浏览 UI**：`DeepReadSourcesPage` 可以展开 saved content 和发浏览器 Want；等价的是“查看来源”和“打开网页”，不是 iOS 原文作为可保存阅读任务的无模型模式。
- **已有模型入口**：`getDeepReadTextRuntime()` → captured provider + `AiClient.generateText()` 能做额外严格 JSON 调用；不需要引入第二个 AI SDK、另一套 provider 设置或新的任务系统。

### 7.4 只复制 visible controls 时必需的最小接线

以下为可选最小实现边界，不是已经实现的结论。

**A. 四 layout + 八 style：最窄，只有阅读呈现与 Preferences。**

1. 一个小 `DeepReadReadingAppearance` 定义 layout/style 枚举、light/dark palette、section order / CSS（或局部 ArkUI 对应样式）。持久化独立 key，读取默认 classic。
2. TemplateSettings 的点击写偏好并同步 AppStorage + V1 @StorageProp。文章页返回时重读，或在页面树真实消费该偏好；现有 Article.onPageShow 只改 pageVisible，loadDisplaySettings 只在初次 load，故不能只写 storage 等待不存在的 consumer。
3. NativeBody 接受 layout/style props 并用 section order、调字体/色板；HTML 内置 renderer 加 layout/style opts；custom snapshot 原样走 custom renderer，避免全局 style 静默改变作者 HTML。
4. PDF/export 用同一呈现参数：`DeepReadExportPanel.openPdfPreview()` 和 `renderCapturedDeepReadTemplateHtml()` 要跟正文一致。
5. 不需要 Output 字段、run_manager、scheduler 或 RDB schema 修改；它们是全局即时阅读外观，不能作为 per-article generation snapshot。

**B. 只读原文：小但跨持久化/完成判定，不能仅换按钮。**

1. Output 最少加可选 `readingMode: synthesis|original|closeReading`、`primarySourceId`；缺省为旧 synthesis。原文 body 可先复用 `inputSources` 的 primary content + 完整 inputText，精读最终需要 `closeReading` payload 保存段落。
2. Composer singleSource 判定只看作者原始 text/URL/files，单源非 synthesis 允许空 title，并持久化 primary ID。禁止采集后来源数量反过来改变用户选择。空 title 先用作者正文首行/文件名/URL host，URL 抓取成功再用实际原文标题更新 entry。
3. original 分支必须在 AppContainer `createRunManager()` 的 `resolveDeepReadRuntimeCore()` 之前识别，或不进入要求模型的 run_manager；当前配置解析总先于 `run()`，不改这一消费者，无模型 original 仍会报错。
4. 暴露现有 seed 抓取闭包的一个窄方法/选项，返回真实 title + 保留结构的正文；text/file 直接复用已存 primary。original 完成后保存 COMPLETE，绝不调用 generateArticlePlan 或四 stage writer。
5. `helpers.isComplete()` 当前必须四 stage READY；`hasDisplayableDeepReadOutput()` 当前不认 inputSources 正文。给 original/close payload 定义自己的有效完成和可显示判断，不能伪造四个 READY。库、恢复、Workspace artifact、ExportPanel 会自动消费这些 helper，但仍要验证原文导出内容真实存在。
6. `domain/export.ts` 加原文分支，正文 NativeBody/HTML renderer 加原文分支。仅把原文塞 summary 会丢标题/段落/后续 annotation 语义，且看起来像生成概览。
7. RDB `output_json` 已 JSON.stringify，添加 optional fields 不需 SQL migration；但 `RdbRepository.ets:132-164 outputWithSectionState()` 是显式逐字段复制，必须保留新增字段。否则 error/retry 更新会静默丢 readingMode / primary / payload。

**C. 精读：窄新模块，不能用 generic synthesis 冒充。**

1. 在 B 的 original 数据/collector 基础上增加上列 `closeReading`（paragraphs、guide、notes、genre modules）；所有原文保存 independent 于模型结果。
2. 分段器、有限 JSON prompt/parser 放小纯 domain 文件；验证 note 的 paragraph 存在，comparison 的 source IDs 存在。
3. 模型生成导读/批注失败保存原文并标 partial；retry 只补 missing，不能把旧 readable original 清空。真实 cancel/token/旧稿保留继续利用现有 scheduler admission。
4. 原文/精读 segmented toggle、段落锚定 notes、genre modules、别家说法/对照表由独立正文 renderer 消费。若暂仅实现 guide + notes，应明示缺少 iOS 别家比较/modules，不能标“完全复刻”。

**D. 六种生成模板：数据生成能力扩展，layout 不足以替代。**

1. 保留当前 templateSnapshot 指向呈现/HTML 所有权；另加可选 `generationTemplateId`，及 globals 默认 key。原因：当前 `captureDeepReadTemplate()` 只认 none/editorial/custom，直接写 deepread_qa 会报“所选模板已不存在”。
2. 真实 syntheses 与原 stage 输出是不同 shape：新增 `templateArticle` optional payload 可直接按 iOS `DeepReadTemplateArticle` 的 brief/qa/debate/timeline/review 存数据（sources 编号、引用对应和模板判别）。缺省无此 payload 仍走经典四 stage。
3. `createRunContext()` 后复用已 collected sources；指定 concrete template 调 `aiClient.generateText()` 返回明确结构，auto 先选择 concrete 或 classic，不能把 auto 默认映射 classic 并显示已自动选择。
4. 这条专用分支可在 `run_manager.run()` 小分派到纯 `synthesis_templates` 模块；不增加第四个任务 manager 或数据库；保存/取消/活动状态仍走现有 scheduler/repository。
5. `isComplete/hasDisplayable`、原生/HTML正文、TXT/Markdown/PDF/Workspace 和 RdbRepository 显式复制字段都要消费/保留 shape。将 QA answers 或 review score/spec 硬压 corePoints 会丢引用关系和结构，是能力差异而非忠实接线。

### 7.5 本轮实现选择建议

读者 layout/style 能独立在现有 UI 工作内完整兑现，最小影响不触碰 task domain。原文 mode 能复用已有输入保存与 seed 抓取，但确实需模型准入前分支和状态/导出消费者。精读、专用生成模板是已证实缺的 domain 能力，应由一个所有者集中做数据/运行接线，再由 W2/W3 消费；不能三名 UI worker 各加一套 mode/template 语义。现有综述 pipeline、provider 设置、RDB 与取消机制保留，不为此引入新架构。
