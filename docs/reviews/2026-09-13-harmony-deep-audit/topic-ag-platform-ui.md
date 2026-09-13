# Topic A/G — 平台生命周期、路由页入口/保存链路、V1 响应式、权限/资源释放

分支 harmony-preview；HEAD bdce551dd373f3d7e4c393b194997cae19289c40；工作树只读。
环境：无 hdc 设备；**且当前 build 硬失败（`entry:default@CompileArkTS` FAIL），即便有设备也无法产出 HAP 做 UI 截图**——所以本报告所有「UI 实际表现」一律 pending，平台语义结论只用官方文档/SDK 证据。
工具：`harmony/scripts/devecocli.sh` 本机 `MODULE_NOT_FOUND`（/opt/homebrew/bin/devecocli 缺失），已改用本地 SDK d.ts + 官方 openharmony docs 源，未改环境。

状态图例：`已读`=完整读取函数与调用上下文；`追踪`=跨模块调用链已核；`定位`=仅 grep；`隔离`=可离线合成验证；`待设备`=需模拟器截图目测。

---

## 1. 覆盖表

### 1.1 平台生命周期（已读 + 追踪）
| 实现 | 覆盖 | 结论要点 |
|---|---|---|
| `harmony/entry/src/main/ets/entryability/EntryAbility.ets` | 已读 1-227 | 冷启动/Want/前后台/回前台/窗口销毁全覆盖；见 AG-2/AG-7 |
| `harmony/entry/src/main/ets/di/AppContainer.ets` | 已读 1-1456 | 单例装配 + 失败不缓存 rejected；fallback 见 AG-3 |
| `harmony/entry/src/main/ets/platform_impl/ChatRdbRepository.ets` | 已读 140-350 | RDB→memory 回退；见 AG-3 |
| `harmony/entry/src/main/ets/platform_impl/PreferencesStorage.ets` / `ChatKvStore.ets` | 已读 | AppStorage.abilityContext 取用正确（非 globalThis） |
| `harmony/entry/src/main/ets/platform_impl/PermissionStatusPortImpl.ets` | 已读 1-57 | 见 AG-15 |
| `harmony/entry/src/main/ets/platform_impl/WebMountSession.ets` | 已读 1-240 | 见 AG-5（独立复核主代理 F 结论） |
| `harmony/entry/src/main/ets/works/NovelPolishWorkAbility.ets` | 已读 1-60 | 无头 workScheduler；参数校验存在 |
| `harmony/entry/src/main/ets/design/tokens.ets` | 已读 1-201 | 见 AG-1 |
| `harmony/entry/src/main/ets/design/fonts.ets` | 已读 | onCreate+onWindowStageCreate 双注册（幂等） |
| `harmony/entry/src/main/module.json5` | 已读 | 权限声明；见 AG-15 |

### 1.2 页面区域覆盖（75 个 page 文件 / 74 条 main_pages）
| 区域 | 页面 | 覆盖 |
|---|---|---|
| 根/首页 | `ChatListPage`(首页 loadContent 目标)、`Index`(未注册) | 已读、追踪深链 |
| 设置枢纽 | `SettingPage`、`SettingAppearancePage`、`SettingDisplayPage`、`SettingExperimentalPage`、`SettingSystemAccessPage`、`SettingAboutPage` | 已读/已读/已读/已读/已读/定位 |
| 助手 | `ChatAssistantsPage`、`ChatAssistantExtensionsPage`、`ChatAssistantLocalToolsPage`、`ChatAssistantMcpPage`、`AssistantListPage`、`AssistantDetailPage`、`AssistantBasicPage`、`AssistantPromptPage`、`AssistantRequestPage`、`AssistantExtensionsPage`、`AssistantMemoryPage`、`AssistantImporter`、`AssistantPropertyEditor` | 已读入口+保存链；死链见 AG-4 |
| Provider/模型 | `ChatProviderSettingsPage`、`ChatProviderDetailPage`、`ChatModelPickerPage`、`ChatModelParamsPage`、`ChatTaskModelsPage`、`ChatTaskModelPickerPage`、`SettingModelPage`(壳) | 已读保存链；见 AG-9/AG-10 |
| 搜索 | `ChatSearchSettingsPage`、`SettingSearchListCards`(壳)、`SettingSearchServiceEditorSheet`(壳)、`SearchPage` | 已读 applyPatch/refresh；壳见 AG-4 |
| 记忆 | `SettingAgentMemoryPageFull`、`MemoryPage`、`AssistantMemoryPage`、`MemoryStore`(platform_impl) | 已读；`MemoryPage` 死链见 AG-4 |
| MiniApp | `MiniAppListPage`、`MiniAppSettingsPage`、`MiniAppRunnerPage`、`MiniAppPlatform` | 已读生命周期/保存串行 |
| 占位重定向 | 12 个兼容壳（见 AG-4.2） | 全部已读 |
| 深度阅读/小说/议会 | `BoardPage`/`DeepReadArticlePage`/`DeepReadHistoryPage`/`SettingDeepReadPage`/`NovelProjectsPage`/`NovelWorkspacePage`/`CouncilPage`/`CouncilRunPage` | 参数链已读；域内逻辑归其他 owner |

### 1.3 未逐行穷读（登记，非伪完成）
- `ChatSearchSettingsPage.ets` 811 行：读到 applyPatch/编辑器草稿（1-300），字段映射尾部（300-811）为定位；
- `MiniAppRunnerPage.ets` 623 行：生命周期/Web 装配（108-560）已读，桥接消息分支为定位；
- 纯展示/组件（`components/**` 162 文件）：按「引 tokens 是否订阅 darkMode」批量核验（AG-1），未逐文件读渲染树；
- 域 HAR（`chat/src`、`deepread/src`）：仅在调用链需要处读，业务正确性归 B/C/E。

---

## 2. 候选（含确认/降级/假阳性）

### AG-1 — 主题令牌 `export let` 不可观察 + 页面缺 `darkMode` 依赖：切深色/换 accent 后部分页面不刷新
- **级别** P1（可见功能缺陷）；**置信度** 机制高 / 用户可见面中（无设备，UI 表现 = 待设备）
- **临时 ID** AG-1
- **位置**
  - `design/tokens.ets:13-40, 53, 59-61, 81-112, 115-120, 199-200`（`export let` 色板 + `applyTheme`/`setAccentColor` 整体赋值）
  - `pages/SettingAppearancePage.ets:37-45`（`setTheme` 调 `applyTheme`）、`:47-56`（`setAccent` 调 `setAccentColor`）
  - `entryability/EntryAbility.ets:185,192,204`（启动/系统主题变更路径）
  - `pages/SettingPage.ets:21`（声明 `@StorageProp('darkMode')` 但全文件无 `this.darkMode` 读取）
  - `components/TopModelMenu.ets:51`、`components/ChatInput.ets:26`、`components/AgentMessageRow.ets:62`、`components/ChatMessageActions.ets:12`（同：只声明不读）
  - `pages/SettingAppearancePage.ets:112` 与 `pages/SettingDisplayPage.ets:174` 的 `opacity(... ? 1 : 1)` 是作者手写的「造依赖」补丁，反证作者已知「不读就不刷新」
- **调用链**：`SettingAppearancePage.setTheme` → `applyTheme(dark)`（改模块级 `let`）→ `AppStorage.setOrCreate('darkMode', …)`；或 `EntryAbility.onConfigurationUpdate`（系统切深浅，无页面跳转）→ 同上。改的是模块级变量，不是任何组件的 `@State/@Prop/@StorageProp`。
- **根因**：官方文档明确「对嵌套/模块侧的可变绑定赋值不在观察范围；状态变量改变时只查询依赖该变量的组件」（`openharmony/docs` `ui/state-management/arkts-state.md:115-125`、`### 框架行为`）。令牌是 `export let`，其赋值不进入 ArkUI 观察体系；只有 build 中**读取了** `darkMode`/`accentColor` 这类 `@StorageProp` 的组件才会重建，重建时才重新读 `tokens` 得到新色。
- **现有保护为何不成立**：
  - `tokens.ets:8` 注释与 `SettingPage.ets:20` 注释声称「切换当前页经 replaceUrl 重建」「借 darkMode 读取触发根页重建」——但 `SettingAppearancePage` **没有任何 `replaceUrl`**（全仓 `replaceUrl` 调用点见 AG-4.2/清单，无一用于主题切换），`SettingPage` **没有任何 `this.darkMode` 读取**，注释承诺的机制不存在。
  - 57 个文件订阅了 `accentColor`（换 accent 时这些页会重建，且 `setAccentColor` 已重算 `ACCENT_LIGHT/SIGNAL/ACCENT_INK`，故 **accent 切换基本可用**）；但只有 13 个文件订阅 `darkMode`，切深浅时其余 162 个引用 tokens 的文件无依赖 → 不重建 → 保持旧色。
- **复现/静态证明**（可离线）：`python3` 扫描 `entry/src/main/ets`：import tokens 且无 `darkMode` 订阅的文件 = **162 个**；其中 `this.darkMode` 真被 build 读取的更少（`rg "this\.darkMode"` 仅 7 个文件命中）。
- **实际验证**：静态证明已完成（工具/命令见 §1 环境说明）；**UI 目测不可执行**——build 在 `CompileArkTS` 阶段 FAIL（AG-6），无法产出 HAP，故「切深色后具体哪几屏残留浅色」= 待设备验证。
- **用户影响/前提**：①系统在应用前台自动切深浅（`onConfigurationUpdate`）且当前栈顶是未订阅页（如 `ChatProviderDetailPage`）→ 该页保持浅色；②在 `SettingAppearancePage` 手动切深浅后返回，栈内未订阅页残留。前提：页面已 build 过且未重建。
- **最小修复**：二选一。(a) 把主题令牌改为 `AppStorage` 键 + 组件 `@StorageProp` 消费（skill 铁律 1），根治；(b) 低成本兜底：`setTheme`/`onConfigurationUpdate` 后由根页 `router.replaceUrl` 重建当前页（与注释承诺对齐），并给关键页补真正的 `this.darkMode` 读取。推荐 (a)。
- **回归测试**：ArkUI 无单测通道；建议交接时补「模拟器切深色逐页截图」清单（依赖先修 AG-6）。静态门禁可加一条 lint：import tokens 且 build 内含 `@StorageProp` 未读取 → 告警。
- **历史关系**：`docs/plans/2026-08-25-harmony-parity-backlog.md:62` V7「主题生命周期：启动浅色闪帧、无系统跟随模式」——启动闪帧与「跟随系统」已修（`EntryAbility` 先解析 KV 再 `loadContent`），但**换肤后的存量页刷新**未闭环，本轮在现实现上确认为独立缺陷。

### AG-2 — 应用在前台且栈顶非 ChatListPage 时，通知/分享 Want 深链被静默丢弃
- **级别** P2；**置信度** 高（静态可证）
- **位置**：`entryability/EntryAbility.ets:84-87`（`onNewWant` → `routeFromWant`）、`:89-116`（写 `AppStorage.wantTopicId/wantTitle`）；唯一消费者 `pages/ChatListPage.ets:85-111`（`consumeDeepLink`，在 `aboutToAppear:81` 与 `onPageShow:68`）。
- **调用链**：`NotificationNotifier.buildArticleWantAgent`(35-62) 用 `START_ABILITY` 拉起 `EntryAbility` → 已在前台 → 系统走 `onNewWant` → 只写 AppStorage，**不导航**。若当前栈顶是 `ChatPage`（`ChatListPage → ChatPage`），`ChatListPage.onPageShow` 不会触发，且 `ChatPage` 全文无 `wantTopicId` 消费（`rg wantTopicId` 仅命中 EntryAbility/ChatListPage）。
- **根因**：深链设计为「根页消费」，但 `onNewWant` 未把根页带到栈顶，也没有全栈消费者。
- **现有保护为何不成立**：`ChatListPage.ets:67` 注释只覆盖「应用已在前台时第二条通知不再触发 aboutToAppear」这一子情形（此时恰好在根页，`onPageShow` 兜住）；对「前台但不在根页」无覆盖。
- **复现**：静态：从任意会话内（ChatPage）点 DeepRead 完成通知 → 期望打开文章，实际无任何 UI 变化，`wantTopicId` 一直留在 AppStorage 直到用户手动返回根页。
- **修复**：`onNewWant` 检测根页是否在栈顶，否则 `router.pushUrl({url:'pages/DeepReadArticlePage'…})` 或先 `pushUrl('pages/ChatListPage')`；或让 `ChatPage`/通用页也消费 `want*`。
- **回归**：模拟器「栈内非根页收通知」用例（待设备）。
- **历史**：无对应历史条目，属本轮新确认。

### AG-3 — RDB 初始化失败静默退化为内存库，且 `chatStorageDegraded` 标志零消费者 → 用户无警告、重启丢全部会话
- **级别** P1（数据丢失，静默）；**置信度** 高
- **位置**：`platform_impl/ChatRdbRepository.ets:149-186`（`initializeStore`，失败 `AppStorage.setOrCreate('chatStorageDegraded', true)` + `hilog.warn`，`return null`）、`:188-198`（`ensureStore`）、`:283-300`（`save` 落 memory）、`:302-311`（`getById` 读 memory）。全仓 `rg chatStorageDegraded` **只有这两处写入，无任何读取**。
- **根因**：设计注（文件头 5-7 行）承诺「非静默」，但唯一非静默手段是 hilog（面向开发者）；面向用户的降级提示不存在。memory 库进程内有效，重启即空。
- **现有保护为何不成立**：`chatStorageDegraded` 本可驱动一个横幅，但无消费者；`getChatRepository()`（`AppContainer.ets:836-843`）也只在注释里提「失败内部回退 memory」，不向上抛。
- **复现**：静态即可证；真实触发需设备制造 `getRdbStore` 失败（磁盘满/迁移异常）→ 待设备。
- **附带缺陷**：`ensureStore` 在 `attempt.finally` 中把 `initPromise` 置 null，而失败时返回的是 `null`（不 reject）→ **下一次任意操作都会重试 `getRdbStore`+建表**，即 RDB 永久损坏时每个 `list/getById/save` 都重复初始化（性能/日志噪声）。
- **最小修复**：`ChatListPage`/`ChatPage` 读 `@StorageProp('chatStorageDegraded')` 显示持久化降级横幅；`ensureStore` 用「一次性失败标记 + 明确重试入口」替代每操作重试。
- **回归**：注入 `relationalStore.getRdbStore` reject 的探针（隔离），断言 UI 标志存在（现状会失败）。
- **历史**：coverage-initial 标注存储归 E；本项只从「平台 fallback + 用户可见性」角度的 A/G 视角确认，不与 E 重复。

### AG-4 — 路由可达性：74 条注册页 vs 75 个页面文件；成片页面/整棵助手编辑子树不可达
- **级别** P2（覆盖表完整性 + 死代码/能力不可达）；**置信度** 高
- **位置/证据**：`main_pages.json` = **74 条**（非 coverage-initial 所述 75）；`pages/` 下 **75 个** `.ets`；差集 = `Index.ets`（其头部注释 `1-3` 自陈「已从 main_pages 移除；无路由入口」）。
  - 无任何 `url: 'pages/X'` 引用（grep 全仓，排除自身）的**已注册**页共 22 个，其中真正的孤儿文件：
    `MemoryPage`、`HotListSourceSettingsPage`、`PromptTemplatePage`、`AssistantImporter`、`AssistantPropertyEditor`、`DeepReadTemplateWorkbenchPage`、`AssistantListPage`，
    以及**因 `AssistantListPage` 不可达而整棵失效**的 `AssistantDetailPage`(仅被 `AssistantListPage:93/104` 引用) → `AssistantBasicPage`/`AssistantPromptPage`/`AssistantRequestPage`/`AssistantExtensionsPage`/`AssistantMemoryPage`（仅被 `AssistantDetailPage:29-33` 的 tab 表引用）。
  - 12 个重定向壳中**无任何调用方**的：`SettingAgentExecutionPageFull`、`SettingAgentExtensionsPageFull`、`SettingAgentPermissionsPageFull`、`SettingExperimentalModelCouncilPageFull`、`SettingExperimentalSubAgentPageFull`、`SettingMcpPageFull`、`SettingModelPage`、`SettingSearchListCards`、`SettingSearchServiceEditorSheet`、`SkillDetailPage`、`DeepReadTemplateWorkbenchPage`（仅 `DeepReadTemplateSettingsPage` 被 `SettingExperimentalPage:37` 引用）。
- **影响**：助手编辑实际走 `ProfilePage:510 → ChatAssistantsPage → ChatAssistantExtensionsPage/…`（活链）；`Assistant*` 整族与 `MemoryPage` 是死代码——审计/维护成本 + coverage 表若按「页面文件」计数会虚高。`SettingPage:77` 的「核心记忆」指向 `SettingAgentMemoryPageFull`（活），故 `MemoryPage` 并非能力缺口。
- **修复**：死页登记后删除或补入口；`DeepReadTemplateWorkbenchPage` 的「工作台」能力当前无路由（重定向到 `SettingDeepReadPage`，后者只有模板选择 `SettingDeepReadPage:321-331`），若属能力缺口需单列。
- **验证**：静态脚本可复现（§3 命令）。

### AG-5 — WebMount：`markHostMounted()` 定义后全仓零调用 → `wm_open` 恒 5s 后抛「host not mounted」（独立复核主代理 F 结论）
- **级别** P1（整个 WebMount 工具链不可用）；**置信度** 高（静态可证 + 官方行为一致）
- **位置**：`platform_impl/WebMountSession.ets:36`（`private hostMounted=false`）、`:109-111`（`markHostMounted` 定义）、`:139-154`（`open()`：`hostMounted` 为假才 `onMountNeeded()` + 轮询 50×100ms，超时 `throw new Error('WebMount host not mounted')`）、`:220-240`（`WebMountHost`，`@Component`，`aboutToAppear` 只 `attachUiContext`，`build` 内 `Web(.., controller)` + `.id(WEB_HOST_ID)` + `onPageEnd`，**从不调用 `markHostMounted`**）
- **调用链**：`ChatPage.aboutToAppear:875-876` `bindHooks({onMountNeeded: () => webmountHostVisible = true})`；`ChatPage.build:4456` 无条件渲染 `WebMountHost({visible: this.webmountHostVisible})`（组件实例常驻；`visible` 只控制内层 `Web` 是否创建）。
- **独立复核结论（确认，非假阳性）**：
  1. `rg "markHostMounted|hostMounted" harmony` 全仓命中 **仅 WebMountSession.ets 内部 4 处**（36/109/110/140/143/146），无外部调用。主代理 F 的结论成立。
  2. `hostMounted` 唯一的置真入口就是这个零调用方法；`open()` 轮询条件 `i<50 && !this.hostMounted` → 500ms×… 实际 50×100ms=**5s** 后必然 `throw`（不是 15s；15s 是 `waitForPageEnd` 的超时，走不到）。
  3. 附带澄清：`attachUiContext` **是**被调用的（`WebMountHost.aboutToAppear:225-227`，因 4456 无条件实例化）→ 因此 `screenshotDataUrl` 的 `'no UI context'` 分支不会命中；能命中的只有 `open()` 的 mount 守卫。即「hook 已显 Web（visible=true 时确实创建 Web 组件）」与「open 必然 throw」并不矛盾：挂载动作发生了，但会话状态没被回写。
  4. 追加潜在缺陷（同区域，修复时必须一起处理）：`WebMountHost` 无 `aboutToDisappear`，销毁时不把 `hostMounted` 复位。因 `getWebMountSession()` 是**全局单例**（`:208-216`）而 `hostMounted` 是会话实例态——一旦补上 `markHostMounted`，ChatPage 销毁后该标志仍为真，下次进入 ChatPage 会跳过挂载等待，对新 `Web` 组件直接 `loadUrl`，从而走向 15s `loadTimeout`。修复应在 `WebMountHost` 挂载时置真、卸载时置假（或改为「按 controller 绑定态」判定）。
  5. 次级疑点（**待验证**）：多次 `pushUrl('pages/ChatPage')`（如 `ChatPage.ets:2041`）会产生两个 ChatPage → 两个 `WebMountHost` 争用同一全局 `WebviewController`；HarmonyOS 文档倾向「一个 controller 绑定一个 Web 组件」。无设备，标待验证，不并入本项结论。
- **实际验证**：静态证明已闭合；修复后需模拟器跑 `wm_open` 目测（依赖先修 AG-6）。
- **最小修复**：`WebMountHost` 内 `Web(...).onControllerAttached(() => { this.session.markHostMounted(); super.session.attachUiContext(this.getUIContext()); })`，并加 `aboutToDisappear(){ this.session.markHostUnmounted(); }`（新增对称方法）。回归：`open()` 不再抛 mount，`screenshotDataUrl` 返回 data URL。
- **历史**：无历史条目；本轮 F 主代理发现，A/G 独立复核确认并补充 4/5 两点。

### AG-6 — build 诊断独立复核：`{ERROR:12}` 但只列 11 条，根因是 cryptoFramework API 面用错（硬阻断 UI 验证）
- **级别** P0（构建阻断，直接解释「为何不能做 UI 验证」）；**置信度** 高
- **位置**：`docs/reviews/2026-09-13-harmony-deep-audit/build.log:53`（`Failed :entry:default@CompileArkTS`）、`:1567-1611`（11 条编号诊断）、`:1612`（`COMPILE RESULT:FAIL {ERROR:12 WARN:502}`）
- **诊断清单（11 条，实际文件行）**：
  - `platform_impl/SyncCryptoPort.ets:31:11` 未类型化对象字面量；`:90:11` any/unknown；`:21:19` `cryptoFramework` 无 `createSymKeySync`；`:28:12` 实参个数 1≠3；`:32:26` 无导出 `CipherSpec`；`:90:21` `Random` 无 `getRandomSync`
  - `platform_impl/WebMountOAuthSupport.ets:38:9` any/unknown；`:38:19` `Random` 无 `getRandomSync`
  - `pages/SettingExperimentalWebMountPage.ets:154:48` 对象字面量作类型；`:157:14`、`:182:12` 未类型化对象字面量
- **SDK 证据（本地 d.ts，非记忆）**：`/Applications/DevEco-Studio.app/Contents/sdk/default/openharmony/ets/api/@ohos.security.cryptoFramework.d.ts`
  - `createSymKeySync` 出现 **0 次**；`createSymKeyGenerator(algName)` 在 `:2112`，配套 `SymKeyGenerator.convertKeySync(key: DataBlob): SymKey`（`:2006`）
  - `getRandomSync` 出现 **0 次**；正确名为 `generateRandomSync(len: number): DataBlob`（`:1332`）
  - 无 `CipherSpec` 类型；`initSync` 的对称加解密重载是 `initSync(opMode: CryptoMode, key: Key, params: ParamsSpec | null)`（`:3551`；`:2304`/`:4411`/`:4984` 是别的重载），`doFinalSync(data: DataBlob | null): DataBlob`（`:4061`）
- **结论**：12 与 11 的差 1 = 聚合计数与逐条列举的口径差（工具输出只给出 11 条带文件行的诊断；`COMPILE RESULT` 的总数含汇总项），并非第 12 个隐藏错误。作者自述「实际 11 诊断」与日志一致。**主代理统一记录即可，本报告不重复记账，仅提供根因与「build 硬阻断 ⇒ 所有 UI 结论 pending」的前提证据。**
- **修复方向**（供对应 owner）：`createSymKeyGenerator('AES256').convertKeySync({data:key})` / `createRandom().generateRandomSync(n)` / `cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, key, null)`（GCM 的 iv/tag 用 `GcmParamsSpec`）。`SyncCryptoPort` 归 E、WebMount OAuth 归 F，A/G 不越权改动。

### AG-7 — 通知 Want 携带的 `sourceUrl` 在 EntryAbility 被丢弃
- **级别** P3；**置信度** 高
- **位置**：`platform_impl/NotificationNotifier.ets:41-45`（want.parameters 含 `topicId/title/sourceUrl`）→ `entryability/EntryAbility.ets:95-99`（只取 `topicId/title`，无 `sourceUrl`）→ `ChatListPage.ets:106-108`（只透传 `topicId/title`）→ `DeepReadArticlePage.ets:99` 的 `sourceUrl` 恒空。
- **影响**：通知点开时若文章缓存已过期/缺失，`DeepReadArticlePage:175-176` 会以 `seedUrl=null` 重跑，结果与原始来源不一致。缓存命中则无影响（`repository.get` 优先）。
- **修复**：EntryAbility 增 `sourceUrl`（同 `MAX_WANT_PARAMETER_LENGTH` 校验）并透传。回归：模拟器冷启动点通知（待设备）。

### AG-8 — `ui_color_mode` 持久化未 await/未 catch
- **级别** P3；**置信度** 高
- **位置**：`pages/SettingAppearancePage.ets:39` `getChatKvStore().put(UI_COLOR_MODE_KEY, mode);`（无 `void`/`catch`，非 await）。
- **影响**：写失败成为未处理 Promise rejection；且 UI 已切色但盘上未落，重启回旧模式。同文件 `setAccent`（47-56）有 catch，写法不一致。
- **修复**：`void getChatKvStore().put(...).catch(...)`，失败时回滚 `themeMode` 并 toast。

### AG-9 — 新增 Provider 依赖模块级 `PENDING_PROVIDER`，重入时空表单 + Save 静默无效
- **级别** P3；**置信度** 中高（静态可证，触发条件受限）
- **位置**：`pages/ChatProviderDetailPage.ets:27-31`（`export let PENDING_PROVIDER` + `setPendingProvider`，一次性对象传递）、`:59-79`（`aboutToAppear`：`isNew` 且 `PENDING_PROVIDER!==null` 才 `applyProvider`，随后无条件 `setPendingProvider(null)`）、`:144-153`（`save()`：`if (raw===null …) return;`）
- **链**：`ChatProviderSettingsPage.ets:354-360` 唯一设置点（`onPickPreset` → setPendingProvider + `isNew=1` push）。
- **触发**：进程被杀后由系统恢复该页 / 页面被重建而模块状态丢失 → `provider===null`、`isNew=1`、`PENDING_PROVIDER=null` → 渲染空表单，点「保存」直接 return，无任何提示（死界面）。
- **修复**：`provider===null && isNew` 时渲染「会话已失效」并 `router.back()`；或把待建模板 id 放进路由参数（可序列化、可恢复）。
- **注**：`export let` 在这里用于**同步单线程交接**而非响应式状态，不属于 skill 铁律 1 的违规（不产生 UI 刷新预期），故不按主题缺陷计。

### AG-10 — `ChatProviderDetailPage.onPageShow` 只刷新 provider 引用，不刷新表单草稿（观察，非缺陷）
- **级别** P3/观察；**置信度** 高
- **位置**：`:84-93`（`this.provider = hit`，表单字段不动）
- **分析**：`save()` 用 `parseModels(this.modelsText)` 且 `existing = raw.models`（按 `modelId` 复用对象），从模型参数页返回后模型列表变化不会丢；但 `name/apiKey/baseUrl` 若在别处被改，本页草稿仍是旧值，保存会写回旧值。当前只有本页自身能改这些字段，故判为**可接受偏差**（注释已声明「不重置用户正在编辑的表单草稿」）。

### AG-11 — `appBackgrounded` 契约与实际消费不符（注释引用已下线的 Index）
- **级别** P3（文档/死代码）；**置信度** 高
- **位置**：`entryability/EntryAbility.ets:1-3, 218-226`（写 `appBackgrounded`）与 `:2` 注释「Index 检查后暂停新 stage」；唯一消费者 `pages/ChatPage.ets:1820`（仅用于「完成通知」门）。
- **事实**：`Index.ets` 未注册（`AG-4`）、无入口，故「后台暂停新 stage」在活代码中不存在。生成/DeepRead 在后台继续跑（与「生成完成通知」+ 后台 polish 的既定设计一致），因此**不判为缺陷**，仅要求修正注释与契约描述，避免后续按错误契约实现「暂停」。

### AG-12 — ChatListPage 深度链与返回栈（确认设计正确，无缺陷）
- `ChatListPage.consumeDeepLink:85-111` 先清 AppStorage 再 `pushUrl`，避免参数残留；novel 三分支要求 `projectId+branchId+jobId` 齐备（`EntryAbility:100` 与页面同判据），`NovelWorkspacePage:201-205` 正确解析 `projectId/branchId/jobId/tab`。冷启动路径（`onCreate→routeFromWant` + `loadContent('pages/ChatListPage')` + `aboutToAppear` 消费）自洽。

### AG-13 — 系统权限实现与声明一致（确认，无缺陷）
- `module.json5:42-64`：仅声明 `INTERNET/GET_NETWORK_INFO/READ_CALENDAR`，其余（含 WRITE_CALENDAR、CONTACTS、LOCATION、BLUETOOTH 等）注释保留 —— 与 `SettingSystemAccessPage:1-14, 57-70`（日历实查、通知实查、文件「无需授权」）及 `PermissionStatusPortImpl:38-50`（未声明→catch→false）自洽；`specialAccessGranted` 恒 `null` 诚实降级为 Unsupported。无越权/假授权。

### AG-14 — 资源释放：定时器/监听清理已闭环（确认，无缺陷）
- `ChatPage.aboutToDisappear:656-701` 清 `greetTimer/cursorTimer`、`detachKeyboardListener`、`unsubscribeActivity/subAgentLive`、checkpoint 落库；`onPageHide:767-775` 停 cursor tick + 键盘监听。`Index.ets`（死）也有对称清理。`CouncilRunPage/MiniAppRunnerPage` 均有 `aboutToDisappear` 守卫（`alive` 标志）。未发现跨页回调泄漏。`EntryAbility.onWindowStageDestroy:210-216` 移除 `avoidAreaChange` 并清 `themeWindow`。
- 仅登记：`EntryAbility` 无 `onDestroy`（AppStorage/容器为进程级，不构成泄漏）；DeepRead 在途 run 无 ability 级取消（设计允许后台续跑）。

### AG-15 — 假阳性/降级清单（主动排除）
1. **`@State` 数组原地 `push/splice` 不刷新** —— **假阳性，不予报告**。官方文档 `arkts-state.md:125` 明确：`@State` 装饰 Array 时可观察整体赋值与元素赋值，且 `push/pop/shift/unshift/splice/copyWithin/fill/reverse/sort` **都会触发 UI 更新**。skill 铁律 3 的「原地 push 不触发」表述过强，本报告不据此静态报错。仓库现状也基本是整体赋值（`AssistantBasicPage:101`、`AssistantListPage:50/99/125/173`、`ChatSearchSettingsPage:171-210`、`MiniAppSettingsPage:100/137`、`MemoryPage:82-92`），无违规。
2. `ChatSearchSettingsPage.applyPatch` 的「深克隆 + patch + 整体赋值 + refresh」——**正确**，符合可观察语义。
3. `ChatProviderDetailPage` 的 `export let PENDING_PROVIDER`——**非响应式状态用途**，不按铁律 1 计（见 AG-9 注）。
4. `@State` 支持 `Map/Set`（API 11+）、`Date`（API 10+）——基线 12 满足，无版本违规；仓库未见 V1/V2 混用（无 `@ObservedV2/@Trace/@Local/@Param`）。
5. `tokens.ets` 的 `applyTheme` 在 `loadContent` 前完成——**启动闪帧**这一历史项（backlog V7）已修，勿当现存缺陷。
6. `SettingDisplayPage.ets:174` / `SettingAppearancePage.ets:112` 的 `opacity(x ? 1 : 1)`——看似「无操作」，实为作者造依赖；对 `darkMode` 的依赖在 SettingPage 缺失才是真问题（AG-1），此处不计独立缺陷。

---

## 3. 验证方式与盲区

- 已执行（只读）：`rg` 全仓符号交叉引用；`python3` 扫描 tokens 消费者/darkMode 依赖与路由可达性；本地 SDK `.d.ts` 核对 cryptoFramework API；`curl raw.githubusercontent.com/openharmony/docs` 取官方 @State 文档。
- 隔离 probe：本报告未新增 probe（AG 结论均为静态/官方文档可证，且核心 UI 面无法离线复现）；未复制简化实现冒充复现。
- **盲区（不隐藏）**：
  1. **UI 实测全部 pending**——根因是 build 硬失败（AG-6），非仅缺设备；AG-1/AG-2/AG-5/AG-7 的用户可见形态需模拟器截图确认。
  2. 暗色令牌的实际残留面取决于 ArkUI V1 的依赖粒度（组件级 vs 元素级）；本报告按官方「只更新依赖该变量的组件」表述判定，若框架实现为「组件级 dirty」则未读 `darkMode` 的组件同样会重建，AG-1 影响面会缩小——此差异**只能设备证伪**。
  3. WebviewController 重复绑定 / 全局会话跨 ChatPage 生命周期（AG-5.5）无文档级直接证据，标待验证。
  4. 未运行 build/不调用真实模型/未读签名材料（遵守契约）。
