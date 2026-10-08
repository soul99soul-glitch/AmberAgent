**Amber Android 测试必要性审计 · 2026-10-05**

后续实施状态：优先项 1（当前 schema 检查与 `live_card` 备份漏表）已修复，40 项定点测试通过。用户随后授权扩大低价值测试删减，本轮已删除或合并原审计的 20 个候选，122 项定点测试通过；两轮独立窄范围审查均未发现阻塞问题。以下保留原只读审计记录，两轮实施证据在文末。审批扫描和 CI 接线仍是后续项。

结论：已有测试中存在可明确删减的演示实现、模板和重复证明，但不能按测试数量或源码扫描类型整批删除。优先修复已失效的备份表覆盖检查、审批扫描范围和 JVM CI 接线，然后按 owner 小批清理低价值测试。

本次应用用户指定的 [OpenClaw test-audit 技能](https://github.com/openclaw/openclaw/blob/main/.agents/skills/test-audit/SKILL.md)，采用只读 discovery/audit 模式。判断依据是独立用户行为、可信回归、协议/存储/安全/平台契约，以及剩余覆盖能否保护同一失败；OpenClaw 的验证命令按本仓 Android/Gradle 构建根调整。

审计对象为 `/Users/arquiel/Downloads/AI/amberagent-Android` 当前工作树，branch `fix/issue-21-tail-growth-crash`，HEAD `671bebda20c39a2b672eda28b0c64e31a46f87f6`。开始时有 167 项 tracked 修改、108 项 untracked 状态项。没有读取兄弟产品仓库，没有修改生产源码或测试；只新增本报告。

全库盘点后，重点阅读候选完整测试、生产 owner、入口与调用者、相邻覆盖、相关历史，并以三个独立责任区交叉审查。没有逐条完成整个测试库的删除资格审查，未审查项不能据此判为必要或无用。

| 盘点口径 | 数量 | 解释 |
|---|---:|---|
| Kotlin 测试源集文件 | 569 | JVM 548，instrumentation 21；包含辅助类及 runner |
| 上述文件行数 | 104,530 | 包括测试支持代码，不等于可删除规模 |
| `@Test` 标记 | 3,482 | 不包含 Kotest `test(...)`，不等于所有实际执行 case 数 |
| Rust 测试属性 | 81 | `native/` 的静态属性计数，本轮未执行 Rust 测试 |
| 文件读取候选 | 50 | 包含读取真实持久化文件/fixtures 的必要测试，不能全部当作源码 grep |

**优先修复的覆盖失效**

1. 备份 schema 检查被冻结在旧版，漏掉真实用户资产。

   [SyncArchiveTableCoverageTest.kt:16](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/test/java/app/amber/agent/data/sync/SyncArchiveTableCoverageTest.kt:16) 的 `syncTablesCoverDurableAppDatabaseTables` 应保留；它是独立存储契约。但该类在 65–66 行固定读取 `AppDatabase/15.json`，而 [AppDatabase.kt:145](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/agent/data/db/AppDatabase.kt:145) 当前为 version 20。

   本轮独立解析 schema 15/20 并对照当前 `SYNC_TABLES`：15 的持久化表差集为空；20 的差集为 `live_card`，其余没有新增缺口。[LiveCardEntity.kt:9](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/agent/data/db/entity/LiveCardEntity.kt:9) 明确将其定义为用户手动保存、无 TTL、全文权威来源的资产。真实入口是 `LiveCompanionPage/VM → LiveModeManager → LiveCardStore → LiveCardDAO`。

   [SyncArchiveManager.kt:375](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/main/java/app/amber/core/sync/core/SyncArchiveManager.kt:375) 只遍历 `SYNC_TABLES` 导出数据库表，清单未包含 `live_card`。因此当前表导出路径不会备份该表，旧 schema 检查却无法发现；本轮没有执行完整备份恢复来模拟用户丢失卡片。

   建议让测试对照当前数据库版本/对应 schema，并修复 owner 的漏表。不要通过更新排除项或删除测试掩盖问题。最小验证为本类加 `SyncArchiveManagerIntegrationTest`，在实际保存卡片后导出/恢复的 owner 场景确认行为。`LiveCardEntity` 引入可追至 `66acf8c2`（2026-09-17）。

2. 审批 lint 没有跟随模块迁移。

   [ToolApprovalPolicyLintTest.kt:90](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/test/java/app/amber/agent/data/agent/tools/ToolApprovalPolicyLintTest.kt:90) 的扫描根只有 app、tools/impl、tools/api、subagent，遗漏 `feature/tools/access`。生产 `SystemAccessTools.getTools()` 仍注册该模块的工厂。

   本轮查证，遗漏的 write-like 工具包括 `sms_send`、`contacts_write`、`calendar_create`、`calendar_update`、`calendar_delete`；这些工厂当前均正确声明 `needsApproval = true`、`allowsAutoApproval = false`。问题是检查无法防止它们日后回归，不是当前存在审批绕过。`CalendarAccessToolsTest` 只另外核对 update/delete 的工厂 flags，不能替代完整扫描。

   建议保留独立审批契约并补齐扫描根；至少验证实际工具声明的扫描负控。不要仅维护第二份工具名字全集。最小验证为 `ToolApprovalPolicyLintTest`、`CalendarAccessToolsTest` 与实际 dispatcher 的审批测试。

3. 当前仓库 CI 配置没有运行 Kotlin/JVM 测试。

   [android-native-build-check.yml:66](/Users/arquiel/Downloads/AI/amberagent-Android/.github/workflows/android-native-build-check.yml:66) 运行 `cargo test --workspace --locked` 与 `:app:assembleFullDebug`；release workflow 运行 `:app:assembleFullRelease`。检查过 Gradle task wiring，没有 assemble 间接依赖 JVM test 的接线。APK CI 成功不能作为 Kotlin/JVM、Compose 或设备测试通过的证据。本轮只审查仓库配置，没有核对远端 workflow 最近运行状态。

   建议先把关键 owner 测试接入 CI：durable/CAS、备份、真实协议和 Novel 存储。根据耗时再扩大；无需为此增加同一契约的重复测试。app 定点命令使用 `:app:testFullDebugUnitTest --tests ...`；`testDebugUnitTest` 当前是普通兼容 alias task。

**高置信删减或合并候选**

以下是审计建议，不是已执行删除。涉及不同 owner，应分开处理。共列出 20 个方法/命名 property 的删减或合并候选，另有日期方法的局部演示代码；计数不作为优先级依据。

| 组 | 候选数量 | 建议 | 生产代码收益 |
|---|---:|---|---|
| Toy projector properties | 4 | 删除测试自建实现及测试 | 无；可减少一个测试依赖 |
| `addition_isCorrect` 模板 | 4 | 删除 | 无 |
| Phase 0 default encoding 演示 | 2 + 局部片段 | 删演示，保留独立日期及文件格式契约 | 可删除 58 行测试专用生产模型 |
| `NovelSwiftCodecRedTest` | 4 | 删除已被更强 owner 覆盖的重复类 | 无 |
| Fixture 存在性清单 | 1 | 删除冗余存在性方法 | 无 |
| Chat timeline Spacer probes | 2 | 删除旧源码探针 | 无，生产 Spacer/分支仅标记 |
| Calendar missing-event helper replay | 2 | 合并到一个独立 helper 契约 | 无 |
| 首页 Continue 同场景二次渲染 | 1 | 合并有价值断言到真实布局测试 | 无 |

**A. 测试自建 projector，与生产投影脱节。**

- 精确位置：[ProjectorPropertyTest.kt:52](/Users/arquiel/Downloads/AI/amberagent-Android/core/agent-runtime/src/test/kotlin/app/amber/core/agent/runtime/ProjectorPropertyTest.kt:52)、58、65、76：`determinism...`、`idempotency...`、`truncation safety...`、`regenerate preserves all candidates in node`。
- 实际检测能力：只调用同文件 27 行定义的 `project()`；`ProjectedMessage`、`FinalEvent` 也在测试内自建，无生产调用者。第一项自身比较；最后一项只验证 Map 分组后的 ID 不重复。根据逻辑，即使 toy projector 恒返 emptyMap，四项仍全部成立。本轮没有实际修改 Kotlin 实现做 mutation test。
- 真实 owner/入口：`ChatEventProjector`、`InterruptedRunProjection`，由 DI writer、启动 recovery 和 chat runtime 调用；这里没有测试触及它们。
- 更强剩余证明：`InterruptedRunProjectionTest:107/192` 的真实截断工具/重复恢复，`ChatEventProjectorCheckpointSelectionTest:39`，`ChatTurnAgentTest:524` 的指定候选原位更新，`RoomAgentEventStoreProtocolTest:165` 的真实 DAO 幂等 append。它们分别保护实际 owner 的风险，不要求用另一套 toy property 替代。
- 历史：`f79e7e15`（2026-08-23）基线已是同样 toy 实现；无法从当前历史证明更早创建原因。
- 删除收益：85 行测试/支持代码；`core/agent-runtime/build.gradle.kts` 的 `libs.kotest.property` 在该模块无剩余消费者，可同时移除这一依赖行。`kotest.runner.junit5` 仍服务真实状态机测试，保留。生产 LOC 为 0。
- 风险低。验证：`:core:agent-runtime:test`；后续涉及生产投影时定点运行上述 app/Room owner。本轮这四项与 `RunStatusTransitionsTest` 的 13 项全部通过。

**B. 四个算术模板。**

- 精确位置：`ai/src/test/java/app/amber/ai/ExampleUnitTest.kt:13`、`common/src/test/java/app/amber/common/ExampleUnitTest.kt:14`、`search/src/test/java/app/amber/search/ExampleUnitTest.kt:13`、`document/src/test/java/app/amber/document/ExampleUnitTest.kt:14`；方法均为 `addition_isCorrect`。
- 实际检测能力：断言 `2 + 2 == 4`，没有产品 owner、入口、调用者或独立 Amber 契约；不需要替代证明。
- 历史：同为 `f79e7e15` 基线遗留模板。
- 收益：合计 66 行测试，无生产/支持代码收益。各模块仍有真实业务测试，包括 common 的 OAuth callback server，因此不能顺手移除 JUnit 依赖。
- 风险低。后续按各模块已有协议/OAuth/search/parser owner 做必要定点验证；本轮未单独运行模板。

**C. Phase 0 演示模型留在 src/main。**

- 精确位置：[SwiftEncodingIncompatibilityTest.kt:22](/Users/arquiel/Downloads/AI/amberagent-Android/feature/novel/src/test/kotlin/app/amber/feature/novel/serialization/SwiftEncodingIncompatibilityTest.kt:22) 的 `typedId_defaultEncodingIsObjectButUuidCaseContractIsUppercase`；49 行的 `associatedEnum_defaultSealedClassUsesClassDiscriminatorNotSwiftKeyedObject`；日期方法 42–45 行的 naive holder 片段。
- 实际检测能力：证明 kotlinx 默认序列化与期望不同，只使用 Default* 演示类型。生产自定义 ID/enum serializer 坏掉不会导致这两项失败。全仓引用检索确认 `NovelDefaultEncodingShapes.kt` 仅被自身及这个测试使用，`DefaultWrapperId` 连测试消费者都没有。
- 真实 owner/入口：Novel import ViewModel → workspace project import → `NovelPackageCodec` → `NovelSwiftCompatibleJson`，使用真实 ID/Date/associated enum serializers，完全不经过 Default*。
- 更强剩余证明：`NovelProjectCodecTest.wireShapeEnumsEncodeAsSwiftAssociatedObjects:160` 对真实模型输出独立字面值；codec/嵌套 wire suites 覆盖真实 fixtures 和 payload。
- 必须保留：日期方法 37–40 行以独立 `-978_307_200.0` 校验生产 Unix→Swift 偏移；双向 round-trip 可能抵消共同错误，不能代替它。78 行 `packageContractConstantsMatchIos` 保护格式、版本、MIME、大小上限，也保留。因此不得整类删除。
- 历史：`f79e7e15`；注释明确 Phase 0 证明默认不兼容，Phase 1 已采用 custom serializers。
- 收益：移除演示后可删除 58 行 `src/main/.../NovelDefaultEncodingShapes.kt`。风险低。验证：`:feature:novel:testDebugUnitTest`，筛选 `SwiftEncodingIncompatibilityTest`、`NovelProjectCodecTest`、`NovelNestedWireCompatibilityTest`、`NovelIosCurrentWireCasesTest`。本轮执行前两类，未执行后两类。

**D. Red/green 过渡类重复真实 codec owner。**

- 精确位置：[NovelSwiftCodecRedTest.kt](/Users/arquiel/Downloads/AI/amberagent-Android/feature/novel/src/test/kotlin/app/amber/feature/novel/serialization/NovelSwiftCodecRedTest.kt)：`decodeMinimalBlankProject_succeeds:15`、`decodePackageEnvelope_succeeds:23`、`canonicalHashAndRoundTrip_succeeds:31`、`packageFixtureChecksumStillValidWithoutCodec:48`。
- 实际失败/剩余证明：前两项同 minimal fixture 的解码/格式断言被 `NovelProjectCodecTest.decodeMinimalBlankProject:22`、`decodePackageAndInnerProject:128` 更强覆盖；round-trip 被 `roundTripMinimalProjectPreservesLogicalFields:93` 覆盖，hash 长度不构成独立 canonical bytes 证明；fixture checksum 被 `NovelFixtureIntegrityTest.packageEnvelopeMatchesChecksumAndStrictBase64:96` 的独立 JDK SHA/Base64/结构/长度校验覆盖。
- 生产调用者：真实 import/persistence 仍调用 codec，不能删生产 codec；私有 `readResourceBytes` 只服务此测试类。
- 历史：`f79e7e15`；注释说明从 Phase 0 red stubs 演化，未找到独占回归。
- 收益：71 行测试含私有 helper，生产 LOC 为 0。风险低。验证：`:feature:novel:testDebugUnitTest --tests '*NovelProjectCodecTest' --tests '*NovelFixtureIntegrityTest'`。本轮连同旧类一起运行，全部通过。

**E. 已有真实读取解码，却另外核对 resource 是否存在。**

- 精确位置：[NovelFixtureIntegrityTest.kt:133](/Users/arquiel/Downloads/AI/amberagent-Android/feature/novel/src/test/kotlin/app/amber/feature/novel/serialization/NovelFixtureIntegrityTest.kt:133) 的 `threeSharedProjectFixturesExist`，实际列了四个固定 fixture。
- 实际检测能力：resource URL 非 null。对应四个输入已由 `NovelProjectCodecTest:22/44/60/80` 真实读取并解码，资源缺失同样失败，没有独立运输/打包边界。
- 历史：Phase 0 无 decoder 时的存在性防线；当前有真实 decoder owner。fixture 本身仍是必要输入，不能删。
- 收益：移除一个方法及无用 import；同类其他方法仍需 read helper。风险低。验证沿用 D。本轮 fixture/codec 两类全部通过。完整性、wire 字面值及高 schema 负例控制必须保留。

**F. 两个 Chat timeline 契约，却查同一个全文件 Spacer。**

- 精确位置：[ChatListSupportTest.kt:197](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/test/java/app/amber/agent/ui/pages/chat/ChatListSupportTest.kt:197) 的 `tail compact markers entry keeps plan and lazy indexes aligned`；230 行的 `hidden assistant renders zero size placeholder keeping indexes aligned`。
- 实际检测能力：两者都只在整个 `ChatListNormalSection` 查 `Spacer(Modifier.fillMaxWidth())`。两个不同分支均有该文本；内存中分别移除任一出现，两条查找仍通过，因此不能定位自己命名的分支损坏。
- 真实 owner/入口：`ChatPage → ChatList → ChatListNormal`，实际 plan owner `ChatListSupport`。当前生产使用统一 `items(count = timelineEntries.size)`，条目数不由 Spacer 内容决定。本机 Compose foundation 1.11.0 依赖字节码确认该 count 被传给 `addInterval`。
- 更强剩余证明：`ChatTimelinePlanTest:15/54/89` 调真实 builder，校验条目序列、lazy item/message 索引、加载完成结构稳定、protected hidden tail 无重复 placeholder。
- 历史：`78693de7`（2026-09-26）统一 items 后删了旧 contentType 断言，仍留下全文件 Spacer probe。
- 收益：两个低价值 probe；repoFile 仍被同类其他测试使用，生产 Spacer/可能不可达分支仅标记，不删除。风险低。验证：`:app:testFullDebugUnitTest` 筛选 `ChatListSupportTest`、`ChatTimelinePlanTest`、`ChatTimelinePlanIncrementalTest`。本轮未执行 app suite。

**G. 日历 update/delete 名称，实际重复测试同一 helper。**

- 精确位置：[CalendarAccessToolsTest.kt:106](/Users/arquiel/Downloads/AI/amberagent-Android/feature/tools/access/src/test/kotlin/app/amber/feature/tools/CalendarAccessToolsTest.kt:106) 的 `updateMissingEvent_returnsVisibleError`；113 行的 `deleteMissingEvent_returnsVisibleError`。
- 实际检测能力：两者都直接调用 `requireCalendarEventSnapshot(id, null)` 并查异常文案，没有执行 update/delete tool，也未触及 ContentResolver 接线；区别只有 99/100 的 ID。
- 真实 owner/入口：`SystemAccessTools.getTools → createCalendarUpdate/DeleteTool → requireCalendarEvent → requireCalendarEventSnapshot`，随后才调用实际 Android resolver。
- 更强剩余证明：同类 `missingEventSnapshotProducesVisibleNotFound:52` 已检验异常类型及精确 ID 文案。建议保留一个 helper 契约/必要数据行；真实 update/delete 接线风险须在 tool owner 单独证明，不能靠重复 helper 命名假装已覆盖。
- 历史：该类可追至 `5f629d66`（2026-09-10）；当前还包含 25 行已有 WIP，后续只能局部合并这两个冗余方法。
- 收益：两个重复方法，无生产 seam 收益；`requireCalendarEventSnapshot` 仍有真实生产调用者，不能删。风险低。验证：`:feature:tools:access:testDebugUnitTest --tests '*CalendarAccessToolsTest'`。本轮未执行。

**H. 首页 Continue pill 的重复 Compose 场景。**

- 精确位置：[HomeCompactLayoutTest.kt:148](/Users/arquiel/Downloads/AI/amberagent-Android/app/src/test/java/app/amber/feature/ui/pages/sessionhome/HomeCompactLayoutTest.kt:148) 的 `continuePillAlwaysResumesTheShownCandidate`。
- 实际检测能力：再渲染一次相同 idle candidate，点击相同 continue action；同类 `idleCardHasTitleRightAlignedContinueAndCompactHeight:96` 已验证候选路由、真实几何和紧凑高度。后者更强。
- 额外差异：点击恰好一次应合并保留；数字 `3` 不存在的负控没有提供 count/三候选输入，不能独立证明所有旧徽章形态都不会回来。无计数徽章产品要求仍有效。
- 真实 owner/入口：`SessionHomePage → HomeFeatureRail`，生产只提供 latest candidate；无 count/picker 参数。
- 历史：`ddd94d46`（2026-09-14）原先测试多候选/count；`78693de7` 去掉这些生产参数后缩成重复单候选。
- 收益：合并后少一次 Compose fixture/render，生产/共享 support 均不删除。风险低。验证：`:app:testFullDebugUnitTest` 筛选 `HomeCompactLayoutTest`、`SessionHomeRouteTest`。本轮未执行。

**需要换证明、目前不能直接删除的测试**

| 测试 | 现有断言问题 | 必须保留的真实契约 / 后续边界 |
|---|---|---|
| `BackToBottomButtonTest:16/29/39` | 查变量/函数字符串及注释顺序；`&&` 改 `||` 仍通过，源码顺序不能证明无重叠 | 关闭 auto-scroll 后手动回底仍可用；点击真的改变生产列表位置；按钮/错误卡片 bounds。现有 anchor tests 没有覆盖生产按钮点击 |
| `ICloudDriveNodeRefTest.clientTreatsNodeRefAsPathScopedHint:46` | 精确局部变量/调用文本；resolvedPath 仅改名便失败，全文件 presence 也不能证明 hint 没有越权 | node_ref 只是 path-scoped hint，不能作为直接节点 capability。应通过真实 `ICloudDriveClient.stat/readText` 与独立 HTTP mock 请求记录验证。现有备份回归不覆盖恶意/不匹配 hint |
| `WebMountBridgeRefProtocolTest:10/34` | 混合 wire keys 与 JS 精确调用拼写；加调用空格便失败，函数名出现不能证明目标校验生效 | snapshot/target 协议及 stale fingerprint/rect 不点击错误对象。保留独立 wire 字段，需要对执行 bridge 的目标解析建立证明 |
| `SyncArchiveTableCoverageTest:42/53` | 精确 `stagedTableFiles` 名字；全文件有 begin/setTransactionSuccessful 就算一致快照 | 大恢复的内存有界、导出跨表一致 snapshot。现有 integration/V2 没有等价的内存/并发写入证明，先补 owner 证明再去源码形状断言 |
| `ExampleInstrumentedTest.useAppContext:17` | 共享 androidTest 只接受 `app.amber.agent` 前缀，与合法 novel/deepread applicationId 矛盾 | 产品 flavor 的实际交付包身份。修正/限制 full 后验证相应设备 task；本轮未观测设备失败 |

BackToBottom 真实 auto-scroll 关闭仍可手动回底的回归可追至 `1a709671`（2026-09-15），有明确保留理由。iCloud/WebMount 的 rename/whitespace 误伤在内存字符串扰动中核实，没有改仓库文件或运行其 app JUnit 类。Sync 的真实生产路径由本地备份、Drive、WebDAV、Local Folder、BackupVM 使用，不能删。静态测试不是统一删除理由；这里的问题是断言不足以独立执行所声称的契约。

**保留的必要覆盖与边界**

- Durable/CAS：`RunStatusTransitionsTest` 保护公开状态规则和旧 wire alias；`RoomAgentEventStoreProtocolTest` 在真实 DAO 检验 stale callbacks、terminal write-once、create-only、seq 和幂等 append。规则与持久化是不同失败边界，不能视为重复。
- AI streaming：`StreamTerminationGuardTest` 校验终止协议；provider termination tests 验证真正 adapter 交付 finish reason、usage-only chunk 不冒充成功，风险不同，均保留。
- 备份：错误密码、旧格式、设备绑定、DB/file rollback、partial commit journal/retry 是实际安全与恢复契约。schema 对照和 ephemeral cache 排除也必要；前者应跟当前版本修复。
- Novel：真实 store、ledger/CAS、恢复 epoch、多文件事务、ZIP/危险输入拒绝、job recovery、排队 UI 旧 epoch 拒写，保护不同生产失败模式。UI gate 测试不能替代 domain owner/CAS/durable 测试，反过来也不能覆盖 UI 排队生命周期。
- UI：真实 Compose bounds、large-text 布局、时间推进显示、LazyColumn 滚动与 issue #21 尾部增长回归应保留。`HomeSessionIconsTest.importedGlyphPathsCanAllBeParsedByCompose` 使用真实 PathParser 遍历 lazy 图标，能捕捉资产导致的运行崩溃，也保留。
- Prompt/wire：独立提示语义、协议 key、格式/default/迁移常量或固定字节可能合理；不能因为断言字符串而统一清理。

Scratch 仅按名字不判删。`ScratchVerifyTest` 的 ledger 计算 case 与 `ScratchVerifyE2ETest` 的真实 proposal/approve/unresolved/stale undo 不同；后者保留。Scratch rename case 已有更强新 storage regression，但当前 WIP 正在修复，先归并到正式 owner 再去重。`NovelSwiftCompatibleJson.decodeProjectDocumentAsJsonObject` 全仓只有声明，是本轮标记的死代码；删除需要另行授权。

跨端证据限制：`test-fixtures/novel-v1/README.md:47` 明确 legacy fixtures 为按 Swift Codable 规则手工编写。这些独立输入仍有契约价值，但本轮没有运行 Swift decoder/validator，也未读兄弟产品仓，不能把 Android fixture 通过称为完整双向跨端验证。

**本轮实际验证**

```sh
./gradlew :feature:novel-workspace:testDebugUnitTest \
  --tests '*NovelWorkspaceStorageReviewRegressionTest' \
  --tests '*NovelWorkspaceRestoreBoundaryTest' \
  --tests '*NovelWorkspaceExchangeTest' --console=plain

./gradlew :core:agent-runtime:test \
  --tests '*ProjectorPropertyTest' --tests '*RunStatusTransitionsTest' \
  :feature:novel:testDebugUnitTest \
  --tests '*NovelProjectCodecTest' --tests '*NovelFixtureIntegrityTest' \
  --tests '*NovelSwiftCodecRedTest' --tests '*SwiftEncodingIncompatibilityTest' \
  --console=plain
```

两次均 BUILD SUCCESSFUL，分别 31s/57s。读取本轮 Gradle XML 确认以下结果，不以旧报告代替当前执行：

| 模块/类 | 用例 | 失败/错误/跳过 |
|---|---:|---:|
| NovelWorkspaceStorageReviewRegressionTest | 11 | 0/0/0 |
| NovelWorkspaceRestoreBoundaryTest | 4 | 0/0/0 |
| NovelWorkspaceExchangeTest | 7 | 0/0/0 |
| ProjectorPropertyTest | 4 | 0/0/0 |
| RunStatusTransitionsTest | 13 | 0/0/0 |
| NovelProjectCodecTest | 10 | 0/0/0 |
| NovelFixtureIntegrityTest | 6 | 0/0/0 |
| NovelSwiftCodecRedTest | 4 | 0/0/0 |
| SwiftEncodingIncompatibilityTest | 4 | 0/0/0 |
| 合计 | 63 | 0/0/0 |

这些通过同时包含必要与低价值测试，不代表所有通过的测试都值得保留。未运行全量 app JVM、APK assemble、设备/UI instrumentation、Rust、真实 provider 或 Swift harness。

实际生产 LOC、测试 LOC 和测试支持 LOC 改动均为 0；没有 commit、push 或 PR。已有 tracked diff 的 SHA-256 在验证前后保持 `9f7f580f41358829eb5d436d69196ac28da3b904085df434b2290cd7e3e50854`；这是原有 tracked WIP 保持的检查，不是 untracked 文件完整性证明。

后续顺序建议：先修复备份 schema/live_card、审批扫描和必要 CI；再按 core toy/template、Novel codec 演示/去重、UI/helper 重复三批清理。涉及独立安全、布局、恢复或内存契约的源码断言，先建立唯一有效 owner 证明，再移除旧 probe。

**后续实施：当前 schema 与 Live 卡片备份修复 · 2026-10-05**

这一步只处理备份 owner。`SyncArchiveTableCoverageTest` 打开真实 Room 数据库取得 SQLite version，再读取对应 exported schema；不再固定 schema 15，也不通过新增 test-only 生产常量取得版本。`SYNC_TABLES` 加入 `live_card`，STANDARD/FULL 都通过现有表导出/导入路径保存卡片。

旧备份没有该表，直接将它变成必需表会破坏恢复兼容性。修复根据现有 `table:live_card` dataset marker 决定是否替换本机卡片，并把同一个不可变 preserved-tables 集合用于 metadata 预检、表 entry 校验、行过滤和实际 DB restore。没有 marker 的旧备份保留本机卡片；有 marker 的空表会按完整恢复语义清空；有 marker 却缺少 table entry 时，在文件替换和写库前拒绝。CONFIG_ONLY 和会话/图片保留语义沿用现有路径。

新增三个 integration 方法，直接使用实际 archive owner、真实 DAO 与加密 ZIP，不增加生产测试接口：卡片在两种模式下往返/仅配置/显式空表恢复；旧备份保留卡片并正常恢复附件；声明卡片但缺表时拒绝且保留本机数据。

修复前的四项定点测试中三项失败：schema 表差集为 `[live_card]`、导出缺少卡片 dataset、缺表备份未被拒绝；旧备份兼容 guard 通过。生产修复后，以下一次相邻回归全部通过：

```sh
./gradlew :app:testFullDebugUnitTest \
  --tests 'app.amber.agent.data.sync.SyncArchiveTableCoverageTest' \
  --tests 'app.amber.agent.data.sync.SyncArchiveManagerIntegrationTest' \
  --tests 'app.amber.agent.data.sync.SyncArchiveV2Test' \
  --tests 'app.amber.feature.board.hotlist.DeepReadRepositoryTest.historyIncludesExpiredRowsWithStatus' \
  --console=plain
```

本轮 XML 结果：integration 24、table coverage 5、V2 10、DeepRead 相邻用例 1，合计 40，失败/错误/跳过均为 0，Gradle BUILD SUCCESSFUL（31s）。首次编译发现已有 DeepRead fake 缺少 DAO 新增的 `observeDeepReadCount`，因此仅新增三行测试适配，用已有 snapshot flow 映射当前 row count；没有更改 DeepRead 生产行为。Room 当前不支持 `use`，版本读取使用显式 try/finally 关闭。

独立审查对照本轮开始前的文件快照，只检查此次四个文件的增量，未发现阻塞问题；本仓使用独立 agent 完成此检查，没有运行 OpenClaw checkout 的 autoreview helper。四个修改文件的 `git diff --check` 通过。没有执行全量 app tests、APK assemble、设备验收、commit 或 push。

相对于本轮开始前 WIP：生产 +23/-21 行（净 +2）；测试与支持 +110/-3 行（净 +107）。既有测试未删除，原有未提交修改保留。审计其余优先项和删减候选的状态未变。

**后续实施：低价值测试删减 · 2026-10-05**

根据用户“可用多删除一些低价值的测试”的授权，执行上述已查证的 20 个候选。本轮以开始前工作树快照为基线，保留前一轮备份修复和所有其他 WIP；没有继续扩大到未经逐项审查的测试。

| 类别 | 删除或合并的用例 | 保留的有效证明 |
|---|---:|---|
| 测试内自建 projector | 4 个 Kotest property | 真实 run 状态机；app 的中断恢复投影与 checkpoint 选择 |
| 四模块算术模板 | 4 | 各模块真实协议、OAuth、搜索与文档解析测试 |
| Novel 默认编码演示、重复 codec、fixture 存在性 | 7 | 独立日期偏移和包格式常量、真实 codec/嵌套 wire、JDK checksum 与严格 Base64 |
| Chat 全文件 Spacer 探针与首页重复 Continue 场景 | 3 | 时间线 plan/index owner；真实标题/按钮 bounds、紧凑高度、路由与点击次数 |
| Calendar 同一 missing-event helper 重放 | 2 | 异常类型与精确 event id、更新字段/时间范围、工厂审批 flags |
| 合计 | 20 | 未增加替代性重复测试 |

首页的单次 resume 与数字 `3` 不存在断言并入 `idleCardHasTitleRightAlignedContinueAndCompactHeight`，保留其实际布局和候选路由断言；没有删除真实 Compose 布局、字体或动画测试。Calendar 的已有 recurring/no-DTEND 测试修改保持原样。

删除前再次全仓核对引用，`NovelDefaultEncodingShapes.kt` 的 Default* 演示类型没有生产消费者，因此随演示测试移除整个 58 行文件；没有删除真实 serializer、codec 或其他仅标记的死代码。`core/agent-runtime` 不再使用 property 库，只删除该模块的一行 `testImplementation(libs.kotest.property)`；真实状态机所需 runner/JUnit/coroutines 依赖保留。

相对于本轮开始前快照：测试与支持 +8/-350 行（净减少 342）；测试专用生产模型 -58 行；Gradle 依赖 -1 行；合计 +8/-409 行（净减少 401）。删除 7 个完整文件，其中 6 个测试文件、1 个测试专用演示模型文件；修改另外 6 个代码/build 文件。删除方法计数为 16 个 `@Test` 加 4 个 Kotest property。

验证使用真实 Gradle Test task，先执行 Novel 等模块 JVM 测试，再执行 app 定点测试。结果来自本轮 XML，失败、错误、跳过全部为 0：

| 模块 | 本轮通过 case 数 | 实际保留并执行的 owner/suite |
|---|---:|---|
| feature/novel | 23 | SwiftEncodingIncompatibility 2、ProjectCodec 10、FixtureIntegrity 5、NestedWireCompatibility 4、IosCurrentWireCases 2 |
| core/agent-runtime | 13 | RunStatusTransitions |
| ai | 4 | StreamTerminationGuard |
| common | 11 | LoopbackOAuthCallbackServer |
| search | 15 | FreeSearchAggregator 9、SearchAdapterMapping 6 |
| document | 6 | BoundedInputStream 2、OfficeParserFixture 4 |
| feature/tools/access | 8 | CalendarAccessTools |
| app | 42 | ChatListSupport 13、TimelinePlan 8、TimelinePlanIncremental 2、HomeCompactLayout 4、SessionHomeRoute 2、InterruptedRunProjection 10、CheckpointSelection 3 |
| 合计 | 122 | 七模块 BUILD SUCCESSFUL（15s），app BUILD SUCCESSFUL（22s） |

复现命令：

```sh
./gradlew \
  :feature:novel:testDebugUnitTest \
  --tests '*SwiftEncodingIncompatibilityTest' --tests '*NovelProjectCodecTest' \
  --tests '*NovelFixtureIntegrityTest' --tests '*NovelNestedWireCompatibilityTest' \
  --tests '*NovelIosCurrentWireCasesTest' \
  :core:agent-runtime:test \
  :ai:testDebugUnitTest --tests 'app.amber.ai.provider.providers.StreamTerminationGuardTest' \
  :common:testDebugUnitTest --tests 'app.amber.common.oauth.LoopbackOAuthCallbackServerTest' \
  :search:testDebugUnitTest --tests 'app.amber.search.FreeSearchAggregatorTest' \
  --tests 'app.amber.search.SearchAdapterMappingTest' \
  :document:testDebugUnitTest --tests 'app.amber.document.BoundedInputStreamTest' \
  --tests 'app.amber.document.OfficeParserFixtureTest' \
  :feature:tools:access:testDebugUnitTest --tests '*CalendarAccessToolsTest' --console=plain

./gradlew :app:testFullDebugUnitTest \
  --tests 'app.amber.feature.ui.pages.chat.ChatListSupportTest' \
  --tests 'app.amber.feature.ui.pages.chat.ChatTimelinePlanTest' \
  --tests 'app.amber.feature.ui.pages.chat.ChatTimelinePlanIncrementalTest' \
  --tests 'app.amber.feature.ui.pages.sessionhome.HomeCompactLayoutTest' \
  --tests 'app.amber.feature.ui.pages.sessionhome.SessionHomeRouteTest' \
  --tests 'app.amber.feature.chat.impl.InterruptedRunProjectionTest' \
  --tests 'app.amber.feature.chat.impl.ChatEventProjectorCheckpointSelectionTest' --console=plain
```

独立 agent 以本轮快照生成的增量逐项检查删除资格、剩余证明及测试专用生产支持，未发现阻塞问题；这是本仓对 OpenClaw autoreview 的适配，没有运行上游 checkout 的 helper。此次目标文件的 `git diff --check` 通过。以开始前 2,737 个文件的 SHA-256 对照，目标范围之外的已有文件无变化；报告单独更新。没有改动原有无关 WIP，也没有为通过检查整理其他文件中的既有 whitespace。

没有执行全量测试、APK assemble 或设备验收，没有 commit、push 或 PR。122 是本轮定点测试数量，与前一轮备份修复的 40 项结果分开记录。
