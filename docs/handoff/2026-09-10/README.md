# AmberAgent Android 追齐 iOS：完整交接

交接日期：2026-09-10（Asia/Shanghai）。这是用户主动暂停后的交接，不是实现完成报告。用户最后要求：停止当前连续实施，写清计划、进度、踩坑和接手 prompt。

**当前结论：Phase 0–3 已通过各阶段验收；Phase 4 仅部分 UI 改动落盘，尚未验证；Phase 5–9 未实施。没有提交、推送或发布。** 最后成功构建属于 Phase 3，不能证明当前含 Phase 4 WIP 的工作树可编译。

## 1. 接手时先读什么

按以下顺序读，避免把历史调研误当当前状态：

1. 本交接文档、下面的 Phase 4 三份分工记录。
2. [执行台账](../../plans/2026-09-09-android-ios-parity-execution.md)：各阶段真实状态、失败与修复、验证日志。历史失败条目会保留，须看其后最终结论。
3. [详细实施计划](../../plans/2026-09-09-android-ios-parity-plan.md)：W01–W19 的目标、依赖、验收和扩展范围。最初的估算是计划值，不是剩余工时承诺。
4. [深度调研](../../audits/2026-09-09-android-ios-parity-research.md)：D01–D20、Q01–Q11、E01–E03 的两端证据。它是实施前快照，部分缺口已经补齐，尤其默认 flags 已变化。
5. [下一位 AI 的完整 prompt](NEXT_AGENT_PROMPT.md)。

专项资料：

| 文档 | 用途 |
| --- | --- |
| [Phase 3 浏览器契约](../../plans/2026-09-09-phase3-browser-contract.md) | owner、scope、receipt 与分工。早期伪代码/分工表有历史性；最终源码和台账优先。 |
| [Phase 4 双端边界](../../plans/2026-09-09-phase4-miniapp-implementation-boundaries.md) | MiniApp 实际 bridge、权限、native owner 与生命周期接线。 |
| [Phase 4 协议交接](phase4-protocol-agent.md) | 尚未落码的协议/授权/SDK 设计和待改文件。 |
| [Phase 4 原生交接](phase4-native-agent.md) | native/runner 的准备状态、Android API 证据和未实现项。 |
| [Phase 4 UI 交接](phase4-ui-agent.md) | 已落盘 Q03/Q04/Q05、测试类、未完成验证。 |
| [Phase 5 账号与系统入口](../../plans/2026-09-10-phase5-account-system-boundaries.md) | Grok、Antigravity、日历、快捷入口、独立 TTS 的生产链路。 |
| [Phase 5 SSH 可行性](../../plans/2026-09-09-phase5-ssh-feasibility.md) | 客户端来源尚未解决；已有 job/Keystore 可复用；本机受控 SSH 证据。 |
| [Phase 6 flags/恢复交接](phase6-preparation-agent.md) | 十个 flags 的当前消费者；Responses 混合状态缺口。 |
| [Phase 7–8 平台可行性](../../plans/2026-09-09-phase7-8-platform-feasibility.md) | Health Connect API 等级、Wear transport 尚缺的实际基础。 |
| [Phase 8 后续准备](phase8-preparation-agent.md) | 暂停前 Bluetooth/Wear 只读核查的实证与未知项。 |
| [Novel 双端交换](../../audits/phase2-exchange.md) / [对话双端交换](../../audits/phase2-conversation-exchange.md) | 实际生产 exporter/importer 验证的支持子集，不能扩大为全量备份互通。 |

本目录还保留 [工作树清单](worktree-inventory.json)、[验证日志清单](verification-log-manifest.json) 及 `verification-logs/`。清单不是可恢复源码备份；未提交源码仍在当前工作树。

[交接资料 ZIP](handoff-bundle.zip) 收录本交接、分工报告、计划、调研、关键证据和验收脚本，保留仓库相对目录。**它不是整个项目或未提交源码的备份**；下一位仍须在当前 Android 工作树继续，或另行完整迁移工作树。

## 2. 仓库、授权与协作约定

### 路径和基线

- Android 工作根：`/Users/mi/Downloads/AI/AmberAgent/android`。
- 分支：`main`。HEAD：`ab984f6df1d6b5f9bae2b007ccdcf43f1d467e62`，本轮没有创建 commit。
- iOS 只读目录：`/Users/mi/Downloads/AI/AmberAgent/ios`。
- iOS 远端：`https://github.com/soul99soul-glitch/AmberAgent-iOS`。
- iOS 固定对照 HEAD：`1023e8a08f5957157226725b43dbf3e2e7078a17`。交接时重新核对仍为该值，工作树干净。
- 用户明确授权读取 iOS 远端和本地仓库，覆盖 Android AGENTS.md 中“不读兄弟产品目录”的一般限制。没有授权修改 iOS，本轮也没有改。

### 不要破坏已有工作

开始时 Android 已有 **39 个 tracked WIP**。其中包含 Feishu Office Pro/DocRadar 相关移除、构建配置和工具枚举调整，均不是本轮应恢复的“缺失文件”。

- 初始文件哈希：[2026-09-09-parity-wip-baseline.json](../../audits/2026-09-09-parity-wip-baseline.json)。值为 null 表示初始已经删除。
- 初始 patch：`/tmp/amber-parity-start.patch`，SHA-256：`f000760804b534bf17f31e7f23a6dcef759a35b3f4beda609946540e7def321f`。
- 初始列表：`/tmp/amber-parity-start-files.json`。交接时这两个临时文件仍存在；换机器前另行安全保留，不能假定 `/tmp` 永久存在。
- 当前大量未提交改动叠加了初始 WIP、Phase 1–3 和 Phase 4 半成品。`git diff HEAD` 不是纯本任务差分，更不是纯 Phase 4 差分。
- 不 `git reset`、不整文件 checkout、不得把 dirty tree 清空后重做。不自动提交/推送/发布，不新增外部依赖。若将来提交，遵循仓库 Lore commit trailers。
- `RouteActivity.kt` 为 CRLF。保持原换行，不为格式化制造整文件变更。原有少量行尾空白不应触发全仓清理。
- 交接检查中普通 `git diff --check` 将该文件新增 CRLF 行报为 trailing whitespace，完整输出见 [检查原文](diff-check-at-handoff.txt)；`git -c core.whitespace=cr-at-eol diff --check` 通过。没有为了消除该提示改产品文件。

### 用户的实施偏好

原授权是按阶段连续实施，每阶段让独立 subagent 检查逻辑闭环、入口到 owner/持久化的完整调用链，以及错位、对齐、边距、大小、大字体、IME 等 UI 细节；修完并验证再进入下一阶段。

用户明确不希望不断提问、等待“是否继续”。接手后对已授权、低风险可回滚工作直接推进；精准识别、精准修改，不做过度防御、过度兜底或过度设计。此次暂停是用户最新指令，当前 agent 已停止实施；下一位收到接手 prompt 后再恢复。

全程中文。每约 60 秒给有信息的进展；用户中途问进度/范围，回答后继续原任务，除非用户明确暂停或取消。当前会话 Ponytail full 已激活一次，不重复加载；新 AI 会话按实际 AGENTS.md 的新会话规则处理。

## 3. 全部阶段状态

| Phase | 内容 | 交接状态 |
| --- | --- | --- |
| 0 | 基线、计划、测试和 UI 环境 | 完成；独立计划复审通过。 |
| 1 | 模型刷新、配置阻塞、附件/历史/搜索反馈、任务和记忆写入 | 完成；96 项不同定点测试及逻辑/UI 复审通过。 |
| 2 | 继续工作、精确路由、Novel 恢复、跨端交换、备份恢复隔离 | 完成；逻辑/UI/实际迁移复审通过。真实云账号未实测。 |
| 3 | 子代理、浏览器可见会话、人工接管、多窗口/对话框、回执/确认 | 完成；90 项 JVM、7 项设置、17 项实际 WebView 测试通过，逻辑/UI 复审通过。 |
| 4 | 小应用系统能力；W18 的 Q03/Q04/Q05 | 用户主动暂停。页面部分已落码且未验证；协议/native 尚未落码。 |
| 5 | Grok/Antigravity、日历 CRUD 补齐、快捷入口、TTS 试听、managed SSH | 未实施；有只读边界报告。 |
| 6 | 手机集成、Q08/Q10、D/Q 全量 UI/升级/跨模块旅程 | 未实施；flags/Responses 已做只读准备。 |
| 7 | 健康摘要、提醒/闹钟、运动计划 | 未实施；API 可行性已核查一部分。 |
| 8 | 可穿戴伴侣、手机 owner、任务/草稿/结果交接 | 未实施；transport 尚未选定并验证。 |
| 9 | 全产品平台适配、构建、升级、端到端和视觉最终回归 | 未实施。Phase 6 手机集成通过也不能替代此阶段。 |

不要把 E01（手表）/E02（健康等）静默丢掉，也不要把“手机主线完成”写成全产品完成。没有真实设备/账号的能力必须分别标记源码、模拟、设备和实网证据。

## 4. 已完成实现的关键事实

### Phase 1：日常交互与写入契约

- 模型候选刷新与实际选择分离，刷新失败可原位重试，旧响应不能覆盖新 provider/账号；发送前配置阻塞有持续修复入口。
- 附件导入/解析失败可见，修正损坏 PDF 的真实 parser 错误信号；取消编辑只清理本次新附件，已发送附件继续保留。
- 首页标题筛选、历史/全文搜索失败状态和异步旧结果隔离；FTS 底层不再吞异常。
- 任务按原子快照持久化再发布，终态清旧错误；记忆编辑/删除使用实际 CAS，分类、置顶随保存提交，冲突保留草稿和当前版本。
- 实屏验证过默认/1.5 倍字体、长内容、IME。修正分类按钮裁字、弹窗保存被键盘覆盖等真实问题。
- Phase 1 定点不同测试总数 96，不把重复跑的模型测试再次累计；没有真实 OAuth 或在线生成账号实测。

### Phase 2：恢复、路由和交换

- Novel `.amber/jobs` 恢复：应用启动和项目页 reconcile，WorkManager 入队 Operation 完成后才成功返回；陈旧 running、损坏 job、精确批次、dismiss/新批次路径闭环。
- 延续现有 executionId/CAS/ledger，不复制第二套恢复框架。已提交章节不重做；模型生成尚未形成 durable payload 时，进程被杀不能假装可自动重放。
- Continue sources 精确定位 Novel、MiniApp、ImageGen、DeepRead；Koin 同接口注册改具名，避免覆盖。
- Room 16→17 包含 `source_url`、MiniApp `lastRunAt`、tool effect 索引等。历史 16.json 已恢复原件，新增 17 schema 保留；真实迁移在指定模拟器通过。
- W06 restore gate 使用共享 epoch：读取/长任务开始时捕获 epoch，短 durable write 前核对；网络/模型期间不持大锁。Chat、工具、子代理、Council、MiniApp、记忆、Board、附件清理/SQL 清理等实际 writer 已接线。
- Chat 的同步 update 只更新内存，物理保存/附件删除由 suspend owner 完成；迟到 purge 使用 invocation epoch/tombstone，不能删恢复后的数据。
- SyncProviderV2 默认开，用户显式 false 仍保持 false。加密导出、SAF 导入/导出、大字体/IME 实测；真实 WebDAV/Google 账号未实测。
- Novel 已做 iOS 生产 exporter → Android importer/edit/exporter → iOS importer/validator；普通对话也走双方实际生产 codec。支持范围见专项报告。运行 jobs/sessions/ledger 不自动迁移，全量 Android 备份跨端恢复未宣称支持。

### Phase 3：子代理与浏览器

W07：`SubAgentManager` 的 thread_id/followup/send/interrupt 已有实际消费者和同张任务卡。ThreadGraphV2 默认开。每个 generation 有独立 WebMount scope；旧 RuntimeRun 的迟到终态先做 identity 校验；interrupt 同时结束 ThreadGraph、AgentTask、transcript 与浏览器 scope；冷启动 followup 恢复 DELIVERED 消息再 drain。消息已投递不等于任务已完成。

W08/W09 的关键生产边界：

- `app/src/main/java/app/amber/feature/webmount/primitives/WebMountSessionOwner.kt`：唯一会话归属 owner，NONE/AGENT/HUMAN、conversation/run、lease、显式 reopen、endRun。
- `WebViewPool.kt`：在 acquire→pin 窗口用 reservation 保护，防 LRU 淘汰；取消复用只清 reservation，保留原页面；取消新建清 orphan；精确 handle/token 防误销毁新 owner。
- `SessionHandle.kt`：`loadUrl/loadUrlNoWait/evalRaw/callBridge/callPageFn` 接入 `dispatchWithLease`；owner 校验与实际 Main dispatch 使用同一临界区。失效 lease 抛 typed exception。关闭使用 `closeIfLeaseActive`。
- 工具 scope 来自真实宿主，剥离模型输入中的保留 scope 字段；`wm_tab_list` 只返回当前 conversation/run 合法范围；Feishu snapshot/network summary 不再绕过 owner。
- 语义 ref 需要 snapshot_id，旧 ref/已替换目标拒绝；坐标 tap 提供 snapshot_id 时同样校验，未提供时保留旧坐标行为。
- receipt 区分 `dispatched/verified/unknown/failed`，可读 DOM 或动作前已经成立的目标不当作 `goal_verified`；导航和写型 signed fetch 也返回诚实回执；取消异常透传，不泛化自动重试。
- 对话框让出 AGENT 后可由 HUMAN 接管。只有正常 COMPLETED 保留已明确 handoff 的 pending dialog；失败/取消/interrupt 会清理。结束 run 后网页定时器新来的无 lease dialog 直接取消，已有明确 pending handoff 不受影响。
- popup 保留 opener/cookie。UI key 用实际 WebView 实例，避免旧 DisposableEffect 把新主页面 detach 成黑屏。watch 为只读；接管/交还复用同一 handle，不重建页面丢草稿。
- `PermissionDecisionResolver` 对 `wm_site_add/remove` 单次确认优先于 unattended/auto；已有显式批准仍可执行。只改这两个工具，不改全局自动审批政策。
- `AgentToolDispatcher` 同一个 batch 内，同 `parallelGroup` 串行、不同组并行；复用现有 coroutineScope/async，没有全局调度框架。
- 点击胶囊、COT 展开/步骤行的透明点击区 ≥48dp；多浏览器会话卡片最大 240dp 后内部滚动。Council 部分失败不显示成功对勾，冷 transcript 恢复席位和正文，真实终态工具结果优先于旧 running。

关键验收：[Phase 3 测试索引](../../audits/parity-artifacts/phase3-verification.json)、[17 项设备输出](../../audits/parity-artifacts/phase3-device-closeout.log)。不要将 shim 幂等安装等非业务动作继续扩大为一套新的权限框架。

## 5. Phase 4 的精确暂停点

### 已落盘，但没有运行验证

独占 UI 分工 `phase1_attachments` 已改：

- `app/src/main/java/app/amber/feature/ui/pages/setting/SettingAgentExecutionPage.kt`：移除无消费者的 Live voiceInputEnabled 正式开关；兼容字段和 VM setter 保留。
- `app/src/main/java/app/amber/feature/ui/pages/synara/SynaraWorkspacePage.kt`：主 frame loading/progress/error、保留 HTTP/网络失败、重试与连接设置入口，子资源错误不误判整页失败。
- `app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSourceEditor.kt`：未保存退出统一判断，保存中禁编辑/关闭，保存失败保留草稿，滚动/IME 调整。
- `app/src/main/res/values/strings_parity_phase4.xml` 与 `values-zh/strings_parity_phase4.xml`。
- 新测试：`MiniAppSourceEditorStateTest`、`SynaraWorkspaceLoadStateTest`。它们是 helper 状态测试，不能冒充真实 Compose/设备交互测试。

具体见 [UI 分工交接](phase4-ui-agent.md)。**这些文件没有经过本轮 Gradle、设备或独立 review。** 不确定能否编译，不是已知编译通过，也没有确认的运行失败。

主任务另新增：

- `scripts/miniapp-parity/index.html`：用真实 Amber SDK 的合成验收小应用，Node 语法检查通过。
- `scripts/miniapp-parity/seed-emulator.py`：限定 emulator-5554 且要求 qemu 属性，将固定测试行写入测试 app SQLite；已执行成功一次。它是测试数据植入，不是生产 importer 验证。
- fixture id：`parity-system-capabilities`；声明 `haptics/device/screen/speech/share/openURL`。不植入允许 grant，要实际验证授权。
- 关闭只读、无保存快照模拟器后不要假定这些测试数据仍存在；新启动后可重装并重跑脚本。

### 尚未落盘的核心能力

`phase2_routes` 已确认：MiniApp 权限枚举、全局设置、parser/prompt、JS SDK、bridge、sandbox、设置 UI **都还没有开始本阶段修改**；`MiniAppSystemCapabilityHandler.kt` **尚未创建**。

约定的最小接口是设计，不是现成代码：

```kotlin
interface MiniAppSystemCapabilityHandler {
    val supportedMethods: Set<String>
    suspend fun dispatch(method: String, params: JsonObject): JsonElement
}
```

预计位于 `app.amber.feature.miniapp`；bridge 注入参数命名 `systemCapabilityHandler`。`close/foreground/lifecycle` 留给 native owner。发现集合应是固定契约与 native 实际 supportedMethods 的交集。

原生 owner/runner 亦尚未完成，详见 [native 分工交接](phase4-native-agent.md)。**TTS 曾拟拆给 `phase1_models`，但主任务在用户插话前没有发出该 followup，TTS 子任务未真正启动。** 不要等待一个不存在的 MiniAppSpeechEngine 实现。

拟定的 TTS 小类接口（可按实际需要调整，不是已实现承诺）：`MiniAppSpeechEngine(Context)`，`suspend dispatch(method, JsonObject): JsonElement`，`suspendForBackground()`，`close()`。Phase 5 独立试听页只复用必要能力，不建通用音频平台。

### Phase 4 实现必须守住的契约

- iOS 实际六组权限是 haptics/device/screen/speech/share/openURL；`qrcode.generate` 无 per-app permission，但受系统总开关控制。现有 Room grant/audit 可复用，不新建权限数据库。
- Android 旧 sandbox 对 null grant 的旧能力处理不能直接复制到新系统能力；新能力未决时须真实确认，确认后再核对当前 app version/htmlHash、setting 和权限，再短提交/执行。
- appProvider 可能是 runner 捕获的旧 app，不能代替 repository 当前持久版本。不要跨用户确认、网络或 TTS 持久持锁。
- `app.info/app.capabilities` 与 iOS 同名；发现阶段不弹授权，只描述实际方法、声明、开关和 decision。旧版本/旧 MiniApp 继续可用。
- SDK wrapper 要支持 iOS 的标量/对象形式，如 `screen.setBrightness(0.6)`、`speech.speak(text 或 options)`、`openURL(string 或 options)`、`qrcode.generate(string 或 options)`。
- 已确认工程直接依赖 `libs.zxing.core`，已有 `QRCodeWriter`，不是只有扫码 quickie。QR 不需新依赖；实际生成后必须解码验证。
- screen 只影响 runner window，后台/关闭/重载恢复，不能覆盖后来 owner 的修改。TTS 初始化失败、无引擎、暂停/继续、退出清理须诚实，不能假成功。
- Android share chooser 打开/选择接收应用不等于对方已发送成功。openURL 每次显示目标并确认，拒绝非法/本地/private URL，不从 WebView 普通导航绕过。

## 6. 下一位的实际起步顺序

1. 读本交接和三份 Phase 4 分工报告；检查 HEAD、dirty tree、当前 AGENTS.md。保留初始 WIP；不要先 pull/重置仓库。
2. 先编译并跑 Phase 4 已落盘的两个 UI helper 测试和既有编辑版本/Runner/Synara 测试，确认交接半成品可集成；失败只修当前范围。
3. 重新安排 Phase 4 文件所有权：协议/授权/SDK 一组；native/runner 一组；可将 TTS 独立小类分出去；UI 精准修复一组。不要让两位同时改相同文件。
4. 先冻结上述小接口，再完成真实接线。主任务保留集成/Gradle/模拟器控制权，避免编译中有人追加半份代码。
5. 实际 runner 验证：发现不弹权限、未声明/关闭/拒绝/允许、授权持久化、QR 解码、亮度/常亮离页恢复、TTS/无引擎、分享取消、外链拒绝、重载/后台/关闭。
6. 实屏检查编辑器返回/点外/关闭、失败不丢草稿、保存中控制、窄屏/1.5 字体/IME；Synara 主 frame 失败/重试/回设置。
7. 独立逻辑与 UI subagent review，修明确问题，定点复验，再将 Phase 4 标记完成并进入 Phase 5。继续直至 Phase 9，不能只停在代码“看起来接上了”。

第一批可用测试命令（仅建议，交接期间未执行）：

```sh
./gradlew :app:testDebugUnitTest \
  --tests app.amber.feature.ui.pages.miniapp.MiniAppSourceEditorStateTest \
  --tests app.amber.feature.ui.pages.synara.SynaraWorkspaceLoadStateTest \
  --tests app.amber.feature.miniapp.MiniAppSourceEditorVersionTest \
  --tests app.amber.feature.ui.pages.miniapp.MiniAppRunnerLoadStateTest \
  --tests app.amber.feature.ui.pages.synara.SynaraConnectionTest \
  --offline --console=plain -Pksp.incremental=false
```

## 7. 构建与设备操作手册

### Gradle

- 工作目录必须是 Android 独立构建根。仅主任务串行执行 Gradle，agent 负责改动和静态检查。
- 本机 Gradle cache/ADB/emulator 的 shell 访问需要工具 `require_escalated`。这只是工具的自动审批环境，不需要重复向用户确认已授权的普通构建。
- 使用 `--offline --console=plain -Pksp.incremental=false`。本机 KSP 增量模式曾报 `Unexpected owner function: null`；临时关增量通过，没有改项目构建设置来掩盖问题。
- 按改动风险跑定点测试，再 compile/assemble。通过后不要无理由反复扩测试。当前最后成功 APK 时间约 2026-09-10 00:33，属于 Phase 3 收口。
- 主 APK：`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`；测试 APK：`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。哈希见工作树清单。

### 设备：最重要的安全边界

**绝不能无选择运行 `connectedDebugAndroidTest` 或使用无 `-s` 的 adb。** 曾有一次 Gradle connected 自动选中了模拟器和用户连接的 M154FF 手机，两者迁移都失败，Gradle 随后自动卸载测试安装。该事件已如实记录在台账。此后只用模拟器，不能重复碰用户手机。

- 唯一测试目标：`emulator-5554`。ADB：`/Users/mi/Library/Android/sdk/platform-tools/adb`。
- AVD：`od_mobile_test`，API 35，1080×2400，420 dpi。
- 已关闭本次启动的模拟器 PID 31229；关闭前字体为 `1.0`、reverse 列表为空。没有测试 HTTP 服务或 Gradle wrapper 在执行。
- 原启动参数：`-avd od_mobile_test -read-only -no-snapshot-save -no-audio -no-window`。二进制来自 `/opt/homebrew/share/android-commandlinetools/emulator/emulator`；恢复前核对路径和已运行 emulator，避免端口归属变化。
- 不保存该合成测试实例覆盖用户原 AVD 快照。新启动后重新确认 serial/AVD，再执行限定的 install/test。

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest \
  --offline --console=plain -Pksp.incremental=false
/Users/mi/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
/Users/mi/Library/Android/sdk/platform-tools/adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
/Users/mi/Library/Android/sdk/platform-tools/adb -s emulator-5554 shell am instrument -w \
  -e class app.amber.feature.webmount.WebMountParityDeviceTest,app.amber.feature.webmount.WebMountLateDialogDeviceTest \
  app.amber.agent.graphite.test/app.amber.agent.AmberAgentAndroidTestRunner
```

最后这两个浏览器测试类共 17 项，需要先恢复受控 HTTP 服务和 reverse：

```sh
python3 -m http.server 18765 --bind 127.0.0.1 --directory scripts/webmount-parity
# 另一个终端，仅模拟器：
/Users/mi/Library/Android/sdk/platform-tools/adb -s emulator-5554 reverse tcp:18765 tcp:18765
```

测试完成停止服务、移除该 reverse。测试代码默认 `http://127.0.0.1:18765/index.html`，只验证合成数据，不验证真实登录。Phase 4 fixture 无需这个服务：

```sh
python3 scripts/miniapp-parity/seed-emulator.py --adb /Users/mi/Library/Android/sdk/platform-tools/adb
```

进入首页“小应用”→“MiniApp 系统能力验收”。该脚本只写固定 fixture id，不写 grant。UI 按钮位置会随字体/页面变化，先读当前界面或截图，不复用未经核对的坐标。

`uiautomator dump` 偶有长时间停顿，使用 subprocess timeout；必要时 `adb exec-out screencap -p` 直接截屏查看。实际截图为 1080×2400，工具可能缩放显示，点按应换算原尺寸。`run-as sh -c` 重定向必须正确 shell quoting；主任务曾因 adb shell 重新解析未引用的重定向而失败，现 seeder 走 SQLite stdin。

## 8. 验证证据与已知边界

| 范围 | 最终证据 | 不能推导出的结论 |
| --- | --- | --- |
| Phase 1 | 96 个不同测试；配置、附件、记忆等默认/大字体/IME 实屏；独立逻辑/UI review | 不表示真实 OAuth/在线模型已 smoke。 |
| Phase 2 | 最终 runtime 43 项、Council 模块 7 项、gate/Files 9 项、修复批次 6 项、真实迁移 1 项等，批次可能重叠，不合计成虚假的总数 | 不表示全量 Android 备份可跨端导入，不表示真实 WebDAV/Google 账号通过。 |
| Phase 3 | 最终 JVM 90、设置 7、实际 WebView 17；最终 popup/confirm/reopen 三图；两类独立 review | 不表示真实第三方 SSO/验证码服务完成。 |
| Phase 4 | fixture 语法与 seed 成功；UI agent 仅静态检查 | 不表示产品编译、JS/native 接线或设备能力通过。 |

关键截图在 `docs/audits/parity-artifacts/`：

- Phase 1：`phase1-memory-large.png`、`phase1-memory-large-ime.png`、`phase1-memory-large-ime-bottom.png`、`phase1-pdf-read-failed.png`。
- Phase 2：`phase2-home-final.png`、`phase2-home-large-final.png`、`phase2-backup-export-*.png`、`phase2-novel-*.png`。
- Phase 3：`phase3-browser-auto-reopen-final.png`、`phase3-browser-confirm-final.png`、`phase3-browser-popup-returned-final.png`；另有 draft/returned/large/large-ime。
- **`phase3-browser-popup-returned.png` 是修复前黑屏失败证据，故意保留。不要拿它否定最终修复，也不要删除后假装没有踩坑。**

本目录已复制 13 份关键 Gradle/设备日志，包含一次明确 late-dialog 失败复现和后续成功，见 [日志清单](verification-log-manifest.json)。`app/build/test-results` 会被下一次筛选测试覆盖；Phase 3 的 90 个测试名称/状态另存 `phase3-verification.json`。

## 9. 踩坑与已作出的取舍

1. **不要把 UI gate 当 durable 保护。** 恢复隔离必须核查所有实际写入者，按 `UI gate → domain owner/CAS → durable persistence` 追。异步附件 unlink、purge 和 WorkManager 都曾有真实漏点。
2. **真实 owner 的测试比同构 helper 更有价值。** 删除过只复制 handler 逻辑的测试；保留实际 Room、WorkManager、Tool factory、WebView bridge 等测试。源码 grep 不能证明生产接线。
3. **review 要收敛。** 要求 reviewer 给具体触发、代码点和可复现证据。迟到 JS dialog 最初只是推测，实际 WebView 定时器测试失败后才修。没有为 host-shim 幂等安装或无证据极端组合造新状态机。
4. **取消不是普通错误。** `runCatching/getOrElse` 很容易吞 `CancellationException`；工具、Feishu、签名桥等已定点改为透传。未知副作用不能写成失败后自动重试。
5. **owner 校验与物理动作之间不能留 TOCTOU。** 只在 coroutine 入口检查不够，实际 Main dispatch 要用现有 lease guard。旧 generation 终态用 RuntimeRun identity，不能只看公共 thread id。
6. **WebView 的生命周期很具体。** headless WebView 的 `view.post` 不总能完成预期调度，实际 dialog resolve 改走 Main Handler。popup Compose key 不能依赖滞后 selectedPopupId；旧清理会误 detach 新实例。
7. **池复用和新建取消要分开。** reservation 防 pin 前被 LRU 淘汰；取消复用不能销毁用户已有页，取消新建要清 orphan。LRU 懒序列遍历中删集合曾有 CME，先 `.toList()` 再移除。
8. **48dp 看外层不算数。** `minimumInteractiveComponentSize()` 放 clickable 外层时，语义节点仍可能很小；真实 Compose 测试证伪后调整为 `clip → clickable → minimumInteractiveComponentSize`，视觉内容不扩大。
9. **冷启动状态不能简单“最后数组优先”。** Council 新 read 的 interrupted 不能被旧 running transcript 覆盖，旧 start/running 也不能盖住真正 completed transcript。按终态语义取值，没有做通用时间合并平台。
10. **部分失败与任务中心完成是不同维度。** Council 有聚合结果但部分席位失败，并不自动要求修改整个 task 状态或开启全局 retry；本轮只修 UI 对勾与恢复显示。以后需实际产品定义和 retry owner 证据。
11. **测试 fixture 本身会错。** JUnit 表达式方法曾返回非 void；Int/Long 比较失败；时间固定 1970 被 TTL 淘汰；wire 时间只精确到秒。不要为这些测试错误改业务语义。
12. **WebMount `text=` selector 不选 `<output>`。** 两项旧失败其实是 fixture 等待方式错误，改 XPath 并 `visible_only=false`；没有继续改正确的 popup/dialog 生产代码。
13. **Robolectric 与真实 SQLite 不完全相同。** 缺 `json_extract` 时，ConversationExchange preview 用真实所需的 title 投影，没加泛化 SQL 兼容层。迁移仍须真实设备验证。
14. **历史 Room schema 不能被当前 KSP 覆盖。** 曾因 16.json 被误生成新列导致重复迁移失败；确认文件不属初始 WIP 后只恢复历史原件，不给生产迁移乱加“列存在就跳过”。
15. **Koin 多接口注册会覆盖。** Continue sources 采用具名注册，不能认为多个 `single<同接口>` 就是集合。
16. **CardGroup 曾只更新普通 mutableList 投影。** 用真实 Compose 测试定位 Loading→成功不刷新，非 skip 注解尝试失败后删除，最终直接渲染 composable item/rawItem；不要恢复被证伪的补丁。
17. **工具审批与 agent 通信有限制。** 本会话 `send_message` 给已完成 agent 不会启动新任务，应 `followup_task`；部分 agent 的 app-server direct input 被拒，主任务负责转发接口。新 AI 不必复用旧 agent id，也不能假定暂停的旧 agent 会自动继续。
18. **Python 语法检查别写受限 cache。** `py_compile` 曾尝试写 `~/Library/Caches/com.apple.python` 被 sandbox 拒；用 `ast.parse` 做本次脚本语法检查通过，没有修改权限。

## 10. 后续阶段尚未解决的实质问题

- **Phase 5 账号**：Grok 与 Antigravity 都不是给已有 API key/Code Assist 模式换标签。需 provider 绑定的 auth store、刷新、模型发现、真实发送链；不能复制私人 token。无合法真实测试账号时只能报告实现/模拟测试完成，不能宣称线上可用。
- **SSH**：当前 Alpine rootfs 没有 ssh/ssh-keyscan，没有现成 JVM SSH client。Termux 外部命令不是 managed SSH。客户端来源、版本固定与信任流程仍需解决；不可自行写 SSH 协议或悄悄加库。现有 Keystore/SecretStore、job、输出 caps/取消可复用。第一版非 PTY，断开不代表远端命令停止。
- **Phase 6 Q08**：Responses 服务端 COMPLETED 与本地非幂等 STARTED effect 的同一 run 混合状态未闭合。保持 `OpenAIResponsesResume` 关闭，先用实际 Room/RunRecoveryService 补回归，再决定修复和开放。
- **flags**：当前默认开的是 DurableToolEffects、TypedRunTerminal、ThreadGraphV2、SyncProviderV2；其余逐项判断，不全开。旧研究“8 个默认关闭”已过时。`NovelPackageV2` 没找到直接行为 consumer，不能仅因枚举存在宣称接线；旧 P4-03 编号也不等于本次 Phase 4。
- **Phase 7**：平台 `HealthConnectManager` 是 API 34，PlannedExercise 是 API 35；不存在已核实的 `FEATURE_PLANNED_EXERCISE` 字段。Manifest/SDK 类型存在不等于用户授权和实际健康数据流已完成。提醒/闹钟需真正 owner、持久化、取消/重启路径。
- **Phase 8**：没有 Wear target、phone listener/shared relay/交接 owner。Synara 是 Mac 工作台，不能冒充手机 owner。官方允许网络通信不代表手机已有 endpoint；Bluetooth 后续事实见专项交接，未选定/实测 transport 前不能宣布伴侣闭环。
- **Phase 9**：仍需实际手机/手表适配、升级、跨模块旅程、可访问性和最终 UI；E03 对应 Android 通知/任务面板，不机械复制 Live Activity 外观。

## 11. 停止状态

产品实施已停止，所有本轮 active subagent 仅被要求写交接报告后结束。测试模拟器关闭，HTTP 服务停止，reverse 已清理，字体恢复。没有在用户暂停后继续 Phase 4 实现、测试或构建。

下一位不需要重新做整轮调研；先核对这份交接与真实工作树，从 Phase 4 半成品继续。主线目标和逐阶段独立 review 方式保持不变。
