# AmberAgent Android 产品级 RikkaHub 残留清理执行计划

> 状态：completed（计划范围已落入当前 Android `main` 工作区；工程门禁 GO，Debug/Graphite APK 门禁通过，严格发布门禁仍为 NO-GO）<br>
> 创建日期：2026-08-25<br>
> 当前工作树：`/Users/arquiel/Downloads/AI/amberagent-monorepo/apps/android`（`main`）<br>
> 冻结 HEAD：`bdd1f7d94e561f1ebb967c6736a261babb8ae2e9`<br>
> RikkaHub 固定历史对照：`b270766f06671d7456ce3d248622dc667648b6d1`（tag `2.4.11`）

## 1. 一句话目标

不改包名、数据库身份、商店条目或现有 Amber 业务，在保护聊天内核替换与 Novel/Terminal/Workspace 等并发 WIP 的前提下，移除 Android 产品运行时、已解析依赖、生产搜索端点、当前品牌入口和打包物中的未批准 Rikka 工程残留，同时保留依法需要的历史来源与许可证事实。

## 2. 权限、范围和不可破坏边界

- 仅修改当前 `apps/android` 工作树；不读取或修改 `apps/ios` 内容。
- 不 commit、push、stage、reset、clean、rebase、stash；不覆盖现有 dirty WIP。
- `docs/plans/2026-08-24-android-chat-kernel-independent-replacement-plan.md`、`docs/audits/android-chat-kernel-final-evidence.json`、`docs/audits/android-chat-kernel-migration-ledger.json` 定义的聊天/provider 生产 owner 已完成，不在本计划重做。
- 不重建 App，不启用 dormant runtime，不重构 Novel、Memory、Workspace、MCP、Skills、Council，不创建第二套图标、搜索或数据库框架。
- 必要许可证归属是允许残留；工程运行时/品牌入口门禁不得错误扩张为抹除 Git/provenance/法律事实。

## 3. D0 冻结基线

### 3.1 当前 WIP 边界

- Git 为 detached HEAD；启动时有大量聊天内核替换、Provider、Novel、DI 和测试 WIP。
- `DataSourceModule.kt`、`SettingProviderConfigPage.kt` 等潜在目标文件已经 dirty；修改前必须先读局部 diff，无法精确隔离则记录 owner 边界并跳过该文件。
- Git status 显示另一平台也有 WIP；只记录“存在”，不读取其内容。

### 3.2 已确认的产品级基线

- `debugRuntimeClasspath` 已解析 6 个 Rikka 坐标：
  - `com.github.rikkahub:markdown:66a56dffca`
  - `com.github.rikkahub:hugeicons-compose:1.3`
  - `com.github.rikkahub.jlatexmath-android:jlatexmath:1.3`
  - `com.github.rikkahub.jlatexmath-android:jlatexmath-font-greek:1.3`
  - `com.github.rikkahub.jlatexmath-android:jlatexmath-font-cyrillic:1.3`
  - `com.github.rikkahub:sqlite-android:-SNAPSHOT`
- 112 个 Kotlin 文件导入 `me.rerere.hugeicons`；153 个具体图标名，另有 `HugeIcons` 根对象。
- `AmberAgentSearchService` 发送生产请求到 `https://api.rikka-ai.com/v1/search`；其类型还进入 `SearchServiceOptions.TYPES`、`SearchService.getService`、设置编辑器、Settings 列表、Aggregator selector 与 DeepRead priority。
- `SettingAboutPage` 的 GitHub 与 License 当前仍指向 RikkaHub。
- `AIIconMatcher` 将 `rikka` 名称匹配为 `amberagent.svg`；`app/src/main/assets/icons/rikkahub.svg` 位于动态资产目录。
- D0 机器证据：
  - `docs/audits/android-product-detachment-baseline.json`
  - `docs/audits/android-product-detachment-current.json`
  - `docs/audits/android-product-detachment-migration-ledger.json`

## 4. Phase 依赖图

```text
D0 evidence freeze
 ├─> D1 asset/name cleanup
 ├─> D2a Markdown dependency
 ├─> D2b JLaTeXMath dependency
 ├─> D2c SQLite dependency (isolated high-risk gate)
 └─> D3 Search endpoint + persisted settings
D1 + D2a + D2b + D2c + D3 ─> D4 HugeIcons UI batches
D4 ─> D5 About/NOTICE/SBOM
D5 ─> D6 release gates + final evidence + handoff
```

D2a、D2b、D2c 彼此不共享回滚单元；D2c 失败不得用 Rikka fork 作为线上 fallback。D4 等所有功能依赖坐标稳定后再删除 HugeIcons 坐标，避免把依赖解析失败与 UI 迁移混为一体。

## 5. 每个 Phase 的强制闭环

1. 实现前写一句可验证成功标准。
2. 检查目标文件当前 status/diff，只改 Phase 必需行。
3. 执行与风险成比例的最小测试、compile、构建或产物扫描。
4. 独立 `gpt-5.6-luna`、`reasoning=max` subagent 只读 review，按 Critical/High/Medium/Low 检查范围/WIP、调用链、持久化/异常/取消/副作用、依赖来源、UI 几何/主题/缩放和反过度工程约束。
5. Root 复核并修复真实 Critical/High、该 Phase 引入或真实影响用户的 Medium；Low 只记录。
6. 复跑受影响验证，更新本计划、current manifest、ledger 与 status。
7. Gate 通过后自动进入下一 Phase。

## 6. Phase D0：冻结证据，不改产品

### 成功标准

固定 SHA/WIP 排除边界并生成可复核的 dependency/source/asset/APK/SBOM 基线、HugeIcons 文件/图标映射和 Search/About/NOTICE 调用链证据，产品源码零修改；若本机缺少 Android SDK，APK 项必须记录构建命令、明确环境阻塞并保持 gate 为 unavailable，不能记为 0 或通过。

### 生产面与证据

- 依赖：`gradle/libs.versions.toml`、`app/build.gradle.kts`、`:app:debugRuntimeClasspath`。
- 搜索：`search/.../SearchService.kt`、`AmberAgentSearchService.kt`、`SettingSearchServiceEditorSheet.kt`、`SearchAggregator.kt`、`DeepReadSourcePrefetcher.kt`、Settings 序列化/存储 owner。
- 资源：`AIIconMatcher.kt`、`AIIcon.kt`、`assets/icons/rikkahub.svg` 与 APK zip entries/strings。
- UI：只冻结受影响 surface 和当前 modifiers；D0 不作视觉变化。
- 合规：定位 NOTICE/SBOM/LICENSE 与 unknown，不把缺文件写成“已清零”。

### 最小验证

- `:app:dependencies --configuration debugRuntimeClasspath`
- machine manifest JSON parse、HugeIcons 112/153 一致性、`git diff --check`
- 若当前无 APK/AAB，执行一次 `assembleDebug` 建立可扫描基线；构建不能替代设备证据。

### 回滚

仅删除本 Phase 新增计划、manifest、ledger 和扫描脚本；不回滚任何既有 WIP。

## 7. Phase D1：低风险资源与名称匹配清理

### 成功标准

`rikka` 不再命中 Amber 当前产品图标，且确认 `rikkahub.svg` 无动态消费者后从源码与新 APK/AAB 中消失；Amber icon、尺寸和 UI 行为不变。

### 必需修改

- `app/src/main/java/app/amber/core/utils/AIIconMatcher.kt`：`PATTERN_AMBERAGENT` 只保留当前 Amber/auto 语义，不保留 Rikka 名称别名。
- `app/src/main/assets/icons/rikkahub.svg`：在 `AIIcon` 动态路径、字符串引用、APK entry 三层确认后删除。
- 如现有 matcher 定点测试可直接覆盖，只补一个真实行为断言；不建新测试体系。

### 验证/UI/回滚

- 静态 source/assets scan；相关 JVM test 或最小 compile；重建 APK 后检查 zip entries 与字符串。
- UI 审查：Amber/auto 仍映射 `amberagent.svg`，未知 Rikka 名返回 `null`，`AutoAIIcon` fallback 尺寸保持原 modifier。
- 回滚单元仅 matcher 一行与一个 SVG；不得恢复其他 Rikka runtime。

## 8. Phase D2a：Markdown fork 替换

### 成功标准

`com.github.rikkahub:markdown` 已从 resolved graph 清零，当前 Markdown AST/API 消费以固定官方 `org.jetbrains:markdown` 版本编译通过，GFM/表格/代码块/链接/流式 Markdown 与 DeepRead HTML 行为无已证实回归。

### 决策与生产消费者

- 先以 JetBrains 官方仓库、官方 Maven 元数据和 fork 固定 commit 比较包名/API/patch；版本在实施记录中固定，不使用动态版本。
- 直接消费者由 import/compile 证据冻结，至少包括 rich text parser 与 `feature/board/impl` DeepRead 模板；不重写 Markdown UI。
- 只替换 catalog 坐标及编译所需最小 API；不引入 façade。

### 最小验证/UI/回滚

- 复用现有 Markdown/richtext/DeepRead tests；再跑受影响 compile。
- 静态 UI 审查常见块、长链接、表格横向行为、代码块、流式增量；设备缺失则明确未验证。
- 回滚仅 catalog/必要 API 小改；不联动 D2b/D2c。

## 9. Phase D2b：JLaTeXMath 三坐标替换

### 成功标准

三个 `com.github.rikkahub.jlatexmath-android` 坐标从 resolved graph 清零，唯一生产 `LatexText`/`MathBlock` 消费面在所选独立 upstream 上编译并通过现有公式验证，字体包与 Canvas 行为没有被静默移除。

### 决策与生产消费者

- 比较 fork 与 `ru.noties` 官方/上游源码、发布元数据、许可证和 API；`0.2.0` 仅为候选。
- 生产入口：`LatexText.kt`、`MathBlock.kt`、`MarkdownToAnnotatedString.kt`；只做必要包名/API 适配。
- 若 upstream 确实不兼容，选择最小独立替代并记录缺口；不重做 Markdown UI，不保留 fork fallback。

### 最小验证/UI/回滚

- compile + 现有富文本/公式测试。
- UI 矩阵：普通公式、Greek/Cyrillic、基线/尺寸、深浅主题、长公式/裁切；真机 Canvas 单独报告。
- 回滚仅三坐标与唯一消费面适配。

## 10. Phase D2c：SQLite fork 替换（独立高风险门）

### 成功标准

`com.github.rikkahub:sqlite-android` 从 resolved graph 清零，`DataSourceModule` 仍以兼容 factory 打开现有 Room 数据库并加载 sqlite-vector 自定义扩展；没有双 registry、fallback 或数据库身份变化。

### 决策与生产消费者

- 以 requery 官方仓库/发布元数据核对稳定版本、包名、native ABI、16KB page-size 与 fork patch；`-SNAPSHOT` 不直接映射成任意同包名版本。
- 先读 `DataSourceModule.kt` 当前 dirty diff，精确隔离 `RequerySQLiteOpenHelperFactory`/`SQLiteCustomExtension`；若无法隔离则记录 owner 边界而不覆盖。
- 影响面：全部 Room 数据、Conversation/Memory、WAL/FTS、sqlite-vector 加载；不建立第二数据库层。

### 最小验证/UI/回滚

- Room/Koin 定点 JVM tests、compile、assemble；检查 APK ABI/native libs 与 16KB 元数据。
- 现有数据库原地打开、migration、读写、WAL/FTS/vector、kill/relaunch 必须按设备证据单列；无设备不得宣称通过。
- Phase 回滚不以旧 fork 运行时 fallback 形式发布；若不能证明安全则 Gate 不通过并如实保留阻塞。

## 11. Phase D3：Rikka 搜索端点退出与旧配置闭环

### 成功标准

生产源码和新 APK/AAB 中 `api.rikka-ai.com` 为 0，`AmberAgentSearchService` 不再注册、可新建、被 Aggregator 选择或获 DeepRead 优先级；旧 `@SerialName("amber_agent")` 数据能让 Settings 正常加载，但旧 API key 不转给其他服务，也不保留生产路由。

### 必需修改

- 删除 `AmberAgentSearchService.kt` 与 `SearchService.getService`/`TYPES` 当前生产注册。
- 删除设置编辑器 `AmberAgentSearchOptions` 分支、Aggregator alias、DeepRead priority。
- 先定位真实 Settings serializer/store；用最小 migration/disabled legacy representation 处理旧 tag。旧 key 不复用、不自动映射 Bing/Jina 等服务；需要用户重新配置。
- 复用 Bing/Jina/SearXNG/Tavily/Brave/Exa 等现有实现，不自建后台。

### 最小验证/UI/回滚

- 旧 JSON fixture/现有 Settings 测试：包含 `amber_agent` 时整份 Settings 不崩，条目被禁用/移除，其他服务与 enabled IDs 保留。
- Search/Settings/DeepRead 定点 tests + compile；APK endpoint scan。
- UI 审查：旧配置不显示为可用服务、不泄露 key、不把选择静默改成另一服务；空服务状态使用现有 UX。
- 回滚整个 legacy decode + registry 移除切片；不恢复生产端点作为 fallback。

## 12. Phase D4：HugeIcons 直接迁移到现有 Lucide

### 成功标准

112 个文件中的 `me.rerere.hugeicons` import 和 resolved HugeIcons 坐标均为 0；每个调用点直接使用项目现有 Lucide API，原 modifier/布局/语义不变。

### 批次与 owner 边界

1. Chat/消息/输入区。
2. Settings/Provider/Search（包括 About，但链接在 D5 改）。
3. Board/Workspace/Novel。
4. 其余页面和调试入口。

每批从 baseline manifest 的 `hugeicons.files` 精确映射；先检查 dirty diff，能隔离才改。禁止新增 `AmberIcons` 门面、生成器、第二图标系统或为一次性映射创建抽象。

### 最小验证/UI/回滚

- 每批 compile；直接相关现有 UI tests 可复用，不为 153 个图标建立穷举测试。
- 静态逐 surface 对照：保留 Icon modifier、通常 24dp 视觉尺寸、现有 48dp 触控容器、alignment/padding/spacing、stroke 视觉重量、深浅主题、字体放大、窄屏和 contentDescription。
- 有模拟器/真机时按批抽查；无设备明确未验证。
- 每个批次独立回滚，不把整个 112 文件批量覆盖。

## 13. Phase D5：About、NOTICE 与 SBOM

### 成功标准

用户主 About 页把 GitHub 指向 Amber canonical，把 License 指向 Amber 自己的 LICENSE 或 Open Source Notices；依法需要的 RikkaHub/第三方归属保留在 Notices/Acknowledgements，SBOM/NOTICE 无 unknown。

### 必需修改与合规边界

- `SettingAboutPage.kt` 当前产品 GitHub：`https://github.com/soul99soul-glitch/AmberAgent`。
- License 链接指向 canonical 仓库自身 `LICENSE`，或现有 Open Source Notices 页面；不把上游 LICENSE 冒充当前产品许可证。
- 按最终 resolved graph 与源码归属更新现有 NOTICE/SBOM；若仓库没有正式生成链，做最小可维护清单并记录生成方式，不作法律“清零”声明。
- 核对 GPL/AGPL/Apache/MIT 等真实许可证；不删除仍适用的 RikkaHub 历史归属。

### 最小验证/UI/回滚

- URL/source scan、NOTICE/SBOM parse/unknown gate、About compile。
- UI 审查链接文案、行高/裁切/触控区、深浅主题、窄屏。
- 回滚仅 About 链接/文案与本次 notices 增量。

## 14. Phase D6：最终发布门禁

### 成功标准

所有工程门禁为 0/通过，构建产物与证据 JSON 可复核；设备/UI/数据库未验证项被单独列出，不用单测或构建冒充。

### 强制门禁

- resolved dependencies：`com.github.rikkahub* = 0`。
- source imports：`me.rerere.hugeicons = 0`。
- production source/APK/AAB：`api.rikka-ai.com = 0`。
- source/assets/APK/AAB：`rikkahub.svg` 以及未批准 namespace/坐标 = 0；NOTICE/Acknowledgements 白名单单独分类。
- About 当前产品仓库不再指向 RikkaHub。
- SBOM/NOTICE unknown = 0。
- 相关 JVM tests、compile、`assembleDebug`（按可用发布 variant再补 AAB）、`git diff --check` 通过。
- Markdown/LaTeX/SQLite/Search/Icon UI 的模拟器、真机、真实数据库升级、kill/relaunch 分开报告。

### 最终产物

- `docs/audits/android-product-detachment-current.json`
- `docs/audits/android-product-detachment-final-evidence.json`
- completed plan/ledger、各 Phase review 摘要、构建产物 SHA-256。
- bridge handoff 给 `main-codex`。

### 最终结论措辞

若所有工程门禁通过，只能写“Android 工程运行时/依赖/当前品牌残留已按本计划清理”。不得写成“与 RikkaHub 无历史关系”或“许可证义务已消失”；直接 fork lineage、历史 provenance 和适用归属不可抹除。

## 15. 执行记录

| Phase | 状态 | 验证 | 独立 review | 未验证/边界 |
|---|---|---|---|---|
| D0 | completed | resolved 6；112/153；独立 manifests；Search 调用链；JSON/diff check | 首轮 NO-GO 后修复；复审 GO，0 findings | 当前环境无 Android SDK，APK gate unavailable；设备行为未验证 |
| D1 | completed (environment-conditional) | source asset/matcher scan、current manifest、diff check 通过 | 代码 GO；0 Critical/High，1 Medium（compile/APK unavailable），1 Low（状态文档已修） | Android SDK 缺失，compile/APK/UI device 未验证；按用户环境例外继续 |
| D2a | completed (environment-conditional) | official 0.7.8 resolved；JVM probe；`:core:agent-utils:test`；CJK compatibility test；resolved Rikka 6→5 | 首轮 NO-GO：CJK High 已用两消费者共享的最小 descriptor 修复；复审 GO，0 Critical/High | Android app compile/device UI unavailable |
| D2b | completed (environment-conditional) | official 0.2.0 resolved；API/AAR/Greek/Cyrillic 实物对比；TTF cmap + XML PUA 映射校验；resolved Rikka 5→2 | 0 Critical/High；3 Medium 中 asset/timestamp 均已用实物与时区核验关闭，SDK/Canvas 边界保留 | Android compile/Canvas/theme/baseline/device UI unavailable |
| D2c | engineering completed; release NO-GO | pinned upstream merge-base resolved；AAR 仅一 class 差异；4 ABI native SHA 相同；64-bit ELF 0x4000；Amber 启动保留 32MB default；resolved Rikka 2→1 | 0 Critical；1 High 为缺少数据库设备验证，供应链/代码有边界 GO | Android compile、原地升级、Room migration、WAL/FTS/vector、kill-relaunch、16KB device 均未验证 |
| D3 | completed (environment-conditional) | endpoint/生产 registry/editor/Aggregator/DeepRead 清零；raw legacy decode、secret orphan 清理、DataStore/WebDAV/S3/sync restore 与启动内存 gate 闭环 | 首轮 High 指向备份恢复与持久化缺口；两轮修复后 re-review engineering GO | Android compile、真实 backup restore/provider smoke unavailable |
| D4 | completed (environment-conditional) | 112/112 文件、153→137 Lucide 映射；HugeIcons source/resolved dependency 均为 0；`Clock`/`Share2` 语义修复 | 首轮 2 Medium 已修；re-review GO，0 Critical/High/Medium | Android compile、APK/AAB、真机/模拟器图标几何/触控未验证 |
| D5 | completed (environment-conditional) | canonical About；NOTICE/SBOM；3 LicenseRef 全定义；7 license + 6 derived asset hash；debug/release Lucide variant；Greek GPL-2.0-only | 四项合规 Medium 与 Greek 授权 Medium 精准修复；最终针对性 re-review GO，0/0/0/0 | APK/AAB merged assets、About/license screen、实际 JLaTeX/Lucide rendering 未验证 |
| D6 | completed; strict release NO-GO | debug/release Rikka dependency 均 0；settings/app compile、27/27 定点测试、`assembleDebug`、`assembleGraphite`、Release merged-assets、四个 APK 硬 token 扫描与 diff check 通过 | 独立 Luna Max 终审：工程 GO；当前落盘后复审另行记录 | Release 被缺失的 `app.amber.agent` Firebase client 正确阻断；无设备/数据库/provider 运行证据 |

> 上表 D0-D5 的“无 SDK/compile/APK”文字是各 Phase 当时的证据边界；2026-08-25 当前工作树已补齐 SDK、compile、定点测试、Debug/Graphite APK 和 Release merged-assets 证据。它们不替代真机 UI、真实数据库升级、kill/relaunch 或带私有 Firebase 配置的 Release APK/AAB。

### D5 gate 证据

- `NOTICE` 使用 `https://github.com/composablehorizons/compose-icons`，区分 Compose Icons MIT、Lucide ISC 与 Feather 派生图标 MIT/Cole Bemis copyright；两份原文在 `app/src/main/assets/licenses/` 随包，hash 由 SBOM 固定。
- RikkaHub 仅保留历史 provenance：`runtime_component=false`、`out_of_scope_historical_provenance`、`NOASSERTION`；固定历史 commit 的 raw LICENSE SHA-256，并明确这不是法律 clearance。
- Greek module 与 fonts 统一为 `GPL-2.0-only`，证据是 Maven POM SHA-256、随包上游 `LICENSE` 与完整 `COPYING`；核心/Cyrillic linking exception 不被错误外推到 Greek。
- 3 个 LicenseRef 的 referenced/defined 集合相等且 `unresolved_count=0`；7 个随包许可证文件与 6 个派生资产路径/hash 全部一致。JLaTeXMath holders、Knuth、OFL、dsrom free-use、Greek/Cyrillic 和上游 public-domain 声明均保留。
- Gradle metadata 将 base 坐标转发为 debug `icons-lucide-android-debug`（module `66b7a06c…`、AAR `3c3a3bcd…`）与 release `icons-lucide-android`（module `93c1ac6b…`、AAR `c8939093…`）；SBOM 分别记录 PURL、classpath、variant 与完整 hash。
- 字体 XML 指向重命名 TTF，Unicode cmap 复核为 `E000=Omega`、`E001=harpoonleftright`；生产源码/依赖中 Rikka endpoint/namespace gate 为 0。最终 D5 Luna Max 针对性复审为 GO，Critical/High/Medium/Low 均为 0。

## 16. Post-completion 旧 owner 与重复测试退役

### 成功标准

非历史、非 Novel 并发 WIP 的 Android 源码和测试中，旧 `GenerationHandler`/`ProviderManager`/`generationHandler` owner 命名与 D6 报告的两处 `HugeIcons` 注释残留为 0；零消费者包装器与重复测试删除后，独有安全边界仍由当前 owner 测试覆盖。

### 实施与边界

- `GenerationSubAgentRunner` 构造参数及 Koin named argument 从 `generationHandler` 统一为 `generator`。
- 删除 `AgentToolDispatcher` 和 `PermissionDecisionResolver` 中零外部调用的 `shouldPauseForApproval` 布尔包装器；生产决策链统一为 `resolveDecision -> resolve`，保留 trace。
- 删除 13 个重复 wrapper 用例的 `GenerationHandlerAutoApprovalTest`；将独有的 `ask_user`、HTTP read/mutation、memory read/mutation 三组边界合并到现有 `PermissionDecisionResolverTest`。
- 移除 D6 两处 `ChatPage` 注释用词，并将误导性 Phase/旧 owner 源码注释改为当前契约。
- 两个审计脚本只保留为已冻结证据的手动复现工具，明确不是 Android runtime 或常驻 CI/release gate。
- 不删除 legacy Search DataStore/备份迁移、CJK Markdown shim、`amber_agent` 数据库/备份稳定标识或 NOTICE/SBOM 历史与许可事实。
- `NovelWorkspaceRuntimeTest` 是并发 dirty WIP，其两处旧 owner 注释作为接受的 Low owner 边界保留，不覆盖。

### 验证与独立 review

- `rg` 与 CodeGraph v1.39.0 双重确认两个 wrapper 删除前无外部调用；删除后生产决策链仅保留 `resolveDecision -> resolve`。
- Python 审计脚本语法、JSON parse 和 `git diff --check` 通过。
- 使用本地 JDK 21 与 Android SDK 运行包含 `PermissionDecisionResolverTest` 在内的 27 项聊天/runtime 定点测试，27/27 通过；同时通过 app Debug 主代码与单测源码编译。
- 独立 Luna Max 终审：工程 GO，Critical 0 / High 0 / Medium 0 / Low 1；唯一 Low 为上述 Novel dirty WIP 注释边界。
- `assembleDebug` 与 `assembleGraphite` 通过；扫描发现并用当前源码覆盖两个陈旧 Graphite APK 后，Debug/Graphite 四个 APK 对 `api.rikka-ai.com`、`com.github.rikkahub`、`me.rerere.hugeicons`、`rikkahub.svg` 均为 0。Release merged-assets 同样为 0。
- 当前 UI delta 经独立静态审查和针对性复审为 GO；Workspace 根/当前面包屑取消无动作点击语义，祖先路径导航保持完整，面包屑和目录行保留 48dp 最小触控高度。最终精修后 Debug/Graphite 已再次构建并重扫为 0。
- 严格发布门禁仍为 NO-GO：仓库没有 `app.amber.agent` 的私有 Firebase client，Release APK/AAB 被既有 guard 正确阻断；设备 UI、真实数据库升级、kill/relaunch 和 provider/backup runtime 仍不可用。
