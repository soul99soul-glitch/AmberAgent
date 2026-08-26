# AmberAgent Zero-Rikka 新产品完全替换计划

> 新仓库、新历史根、新应用身份、零旧源码复用、零旧数据自动迁移

## 先看这一页：人话版

你要的不是“继续改旧项目”，而是“重新做一个新 App”。具体就是：

1. **把现在的 Amber 项目封存。** 不删历史，也不再拿里面的代码继续开发新产品。
2. **另外建一个全新的仓库。** 不是 fork，不复制旧代码、测试、图标、依赖或配置。
3. **Android 和 iOS 都换新身份。** 新包名、新 bundle ID、新签名、新商店条目，可以和旧 App 同时安装。
4. **第一版只做最基础的聊天。** 配置模型、发送消息、流式回复、保存聊天、取消、报错、重启后还能看到记录。
5. **基础版跑稳后再加功能。** 工具审批、MCP、搜索、TTS、Memory、Workspace、Novel 等一个一个重新写，不能从旧项目复制。
6. **旧数据默认不迁。** 用户重新填 API key。以后确实需要聊天记录，再单独做“旧 App 导出、新 App 导入”，新 App 不直接读旧数据库。
7. **最后做一次彻底扫描。** 新代码、新安装包、新图标、新依赖和新服务中都不能有 RikkaHub 的代码、坐标、网址、资源或运行时。

最关键的取舍：

| 你得到什么 | 你要放弃什么 |
| --- | --- |
| 新 App 在工程上真正独立 | 不能直接更新覆盖旧 App |
| 不再背旧代码和旧架构 | 需要重新开发，时间和成本明显更高 |
| 不再依赖 Rikka 服务、依赖和素材 | 第一版功能会比现在少 |
| 新旧 App 可以并存，回滚简单 | 用户要重新配置账号/API key |
| 新代码来源可以完整说明 | 旧版本曾经的历史关系无法靠技术抹掉 |

推荐执行顺序只有四步：

```text
封存旧项目
  → 新建空白 Android/iOS App
    → 先完成基础聊天并发布内测
      → 再逐项重写工具、Memory、Novel 等功能
```

如果你接受“新 App、不能无缝覆盖旧 App、默认不迁数据”这三个代价，这个计划就可以开始；如果任何一个不接受，就只能做旧项目内的渐进替换，不能叫完全切断。

## 0. 决策摘要

| 项目 | 本计划的默认选择 |
| --- | --- |
| 目标 | 建立一个与 RikkaHub 和现有派生代码都没有运行时、源码、依赖、资产或发布身份复用的新产品 |
| 路线 | 新产品重建，不在旧 monorepo 内逐文件重写 |
| Android | 新 `applicationId`、新签名身份、新商店条目、新数据库/备份格式 |
| iOS | 新 bundle ID、新 provisioning/signing、新商店条目、纯 native Swift，不使用 `Shared.framework` |
| Git | 新组织/新仓库或至少全新非 fork 仓库，从新 root commit 开始；不导入旧 `.git`、patch、源码或测试 |
| 品牌 | 默认新产品名、新图标、新 design tokens、新官网/域名；若保留 AmberAgent 名称，需单独确认权利和关联风险 |
| 数据 | 默认不自动迁移旧会话、设置、密钥或备份；用户重新配置 |
| 实现输入 | 产品负责人批准的需求、公开协议/官方 SDK 文档、独立设计稿和新测试数据 |
| 旧仓 | 只读封存，保留真实历史、许可证和审计证据，不删除、不改写 |
| 当前工作区 | `main@701e8c634ababe12fc1a0d03beccb36b2b55a0ca`；16 个 Novel WIP 文件继续保护 |
| Rikka 对照 | `b270766f06671d7456ce3d248622dc667648b6d1`，tag `2.4.11` |
| 本轮范围 | 只新增计划文档，不创建新产品仓库、不改产品代码、不迁移数据 |

## 1. 先说清楚“完全切断”能做到什么

技术和工程上可以做到：

- 新产品仓库与 RikkaHub/旧 Amber 没有共同 Git 历史和 fork 关系；
- 新发布包不包含旧源码、旧 namespace、旧 Maven 依赖、旧服务端点、旧素材、旧文案或旧二进制；
- 新 Android/iOS 使用独立应用身份、数据格式、网络服务、设计系统和发布流水线；
- 新实现只根据公开协议和批准的产品需求开发；
- 所有源码、依赖、素材、测试数据和生成物都有新的 provenance 记录。

技术上不能做到：

- 抹掉旧 Amber 版本曾与 RikkaHub 有直接 fork 谱系的历史事实；
- 靠改名、换包名、删除 Git 历史或重写代码自动取消旧版本的许可证/版权义务；
- 在复用当前 Amber 源码的同时声称新实现完全没有旧来源输入；
- 保留原商店升级链、原数据库和无缝数据迁移，却同时声称两个产品完全没有连续关系。

因此，本计划选择“**旧产品终止演进 + 新产品独立重建**”，而不是继续执行旧计划的 Track A 原仓 strangler。最终对外措辞、人员隔离有效性和许可证结论必须由权利/法务负责人批准；本文只规定工程证据。

## 2. 本计划与旧脱离计划的关系

已有审计计划：

- `docs/plans/2026-08-24-rikkahub-full-independence-plan.md`

它仍作为以下事实的审计输入：

- Android 真实生产链、数据边界、Provider/资源/依赖热点；
- 旧快照发现的 71 个 normalized-exact 功能文件、125 个高相似文件和 62 个 byte-exact 资源；
- iOS 93 个 production / 167 个 total Swift Shared imports；
- 旧 Room/DataStore/backup、iOS files/UserDefaults/Keychain 的迁移风险；
- 旧版本必须保留的 provenance、SBOM 和 NOTICE 问题。

但新产品实现团队不使用旧计划中列出的旧源码路径作为编码参考，也不执行“旧类套新 façade 后逐步替换”。本计划用全新的产品规格、架构、数据模型和 UI 交付。

## 3. 四条不可妥协的红线

### 3.1 零源码复用

新仓禁止进入：

- RikkaHub 源码、patch、commit、内部测试、注释和资源；
- 当前/旧 Amber Android、iOS、KMP 源码及其 patch；
- 从旧实现自动翻译、改包名、跨语言改写或 AI paraphrase 的代码；
- 旧代码片段、私有符号、内部控制流或测试布局进入 prompt/issue/spec；
- 从旧 APK/IPA 反编译得到的实现材料。

公开协议字段、标准算法和第三方 SDK 可以使用，但必须从官方文档/独立 upstream 取得并记录版本。

### 3.2 零发布身份复用

严格默认：

- Android 新 `applicationId`、app signing key、deep-link scheme、OAuth redirect、notification channels 和商店 listing；
- iOS 新 bundle ID、App ID、Keychain access group、URL schemes、Associated Domains、App Groups、Widget/Watch/Activity identifiers 和商店 listing；
- 新官网、隐私政策 URL、support URL、update endpoint、telemetry project、crash reporting project；
- 新 API/backend domains，不代理或继续调用 `api.rikka-ai.com`。

如果后来决定保留原 app ID、bundle ID 或商店 listing，本计划自动降级为“工程独立迁移”，不能再使用最严格的完全切断声明。

### 3.3 零资产与依赖复用

- 不复制旧 icon、banner、font、emoji bundle、provider logo、illustration、sound、dictionary、prompt template、web bundle 或 native binary。
- 不使用 `com.github.rikkahub*`、`me.rerere.*` 或 Rikka 维护的 fork。
- 第三方库只从独立 upstream/官方 SDK 选择，固定 commit/version/hash/SPDX/NOTICE。
- 新 logo、design tokens、文案、页面布局由新的设计 brief 和设计稿产生，不照旧截图临摹。

### 3.4 默认零旧数据迁移

新产品不读取：

- 旧 Room/SQLite、DataStore、SharedPreferences；
- 旧 iOS conversation JSON、UserDefaults、Keychain；
- `.amberbackup`、旧 Android/iOS archive；
- 旧 credential refs、OAuth sessions、stored response cursors 或未完成 runs。

用户在新产品重新配置 provider、MCP、搜索、同步和凭据。若以后必须迁移用户数据，使用第 15 节的独立可选迁移项目；它不进入新产品核心完成门。

## 4. 组织与来源隔离

### 4.1 角色

| 角色 | 可访问 | 不可传递给实现团队 | 交付 |
| --- | --- | --- | --- |
| Product/Specification | 用户需求、公开产品行为；若法务批准可观察旧产品 | 旧源码、内部命名、patch、内部测试、截图复刻说明 | 新 PRD、状态/错误/恢复需求、验收场景 |
| Clean Implementation | 批准的 PRD、公开协议、独立设计稿、allowlisted dependencies | Rikka/旧 Amber 仓、旧二进制实现材料 | 新 Android/iOS/backend 源码 |
| Design | 新品牌 brief、目标用户与可用性需求 | 旧 UI 截图作为像素模板、旧 assets | 新信息架构、tokens、icons、screens |
| Compliance/Release | access log、provenance、SBOM、binary scans | 不直接向实现者传递旧实现细节 | 通过/隔离/拒绝结论 |

人员是否满足 clean-room/source-access 要求由法务确认。已经接触旧源码的人员可以参与产品决策、审计或规格整理，但是否能进入实现团队不能由工程计划自行认定。

### 4.2 新仓强制设置

- 新的非 fork repository，新 root commit；不使用 subtree、filter-repo 或 squash 导入旧仓。
- 不添加旧仓 remote，不开启 GitHub fork network 关联。
- CI 禁止下载旧仓 artifact；开发机实现工作区不挂载旧源码目录。
- issue、PR、prompt 和附件扫描禁止旧 source snippets/hash。
- provenance ledger 从 PR-00 开始追加，不允许事后补造来源。
- 旧 monorepo 和 Rikka clone 由审计/法务环境只读保存，与实现环境分离。

## 5. 新产品范围：先定义 MVP，不复制旧功能全集

“完全替换”不等于第一版重做现有全部功能。为了降低重写风险，第一版只闭合最小生产链：

```text
新建会话
  → 选择 provider/model
  → 发送文本
  → 流式响应
  → 本地持久化
  → 取消/错误
  → 关闭并重启后恢复已完成会话
```

第二批再加入 tool call/approval/effect、附件、图片、搜索、MCP、Memory；第三批才加入 Council、SubAgent、Deep Read、MiniApp、Novel、Workspace、自动化和系统后台能力。

### 5.1 MVP 必须包含

- provider/model 配置与安全凭据存储；
- text/reasoning streaming；
- Conversation/Turn/ContentBlock 新模型；
- create/list/open/rename/delete conversation；
- cancel/error/terminal；
- Android/iOS 各自本地持久化；
- 新品牌、设置、隐私和第三方声明页；
- 最小 telemetry/crash reporting，可由用户关闭。

### 5.2 MVP 明确不包含

- 旧数据导入；
- 自动 tool execution；
- 后台长任务、Live Activity、Watch；
- Novel/Council/SubAgent/MiniApp/DeepRead；
- backup/sync；
- 跨端共享源码；
- 为未来能力预建通用 Agent Kernel。

## 6. 新架构原则

### 6.1 两端先独立实现

Android 和 iOS 第一阶段只共享：

- 公开的 JSON behavior fixtures；
- provider protocol examples；
- product requirements；
- error/terminal vocabulary；
- release verification matrix。

不共享生产源码。只有两端各自发布且出现真实重复、语义稳定后，才能另立提案抽取纯 contract/reducer；不能从旧 `Shared.framework` 或 Android `:ai` 直接复制。

### 6.2 新领域词汇

最终命名由新 PRD 决定，不沿用旧 central DTO。建议从最小语义开始：

- `Thread`：用户会话容器；
- `Turn`：一次用户/模型交互；
- `ContentBlock`：text/reasoning/image/document/tool request/tool result；
- `InferenceEvent`：provider 归一化增量；
- `RunRecord`：一次生成运行的 durable state；
- `ToolRequest` / `ToolOutcome`：工具声明、批准和结果。

这些不是授权建立大框架。MVP 只实现 text/reasoning 所需字段；tool/image/document 在对应 Phase 再扩展。

### 6.3 平台所有权

| 能力 | Android | iOS |
| --- | --- | --- |
| UI/navigation | Compose + Android navigation | SwiftUI/UIKit + native navigation |
| persistence | 新 Room/schema 或独立文件 store | 新 Codable/file/SQLite store |
| credentials | Android Keystore | Keychain |
| background | FGS/WorkManager，仅需求出现后 | BGTask/UIKit expiration，仅需求出现后 |
| notification | Android notification | UNUserNotification/ActivityKit |
| networking | 经批准的 Android client | URLSession 或批准的 native client |
| DI/lifecycle | Android app-owned | iOS app-owned |

## 7. Phase 0：不可逆决策与冻结

**目标**：批准新产品路线，避免实现中途又要求无缝升级或复用旧代码。

**交付**：

- 新产品名、repository owner、Android application ID、iOS bundle ID；
- 新商店/签名/域名/backend/telemetry 决策；
- “默认无旧数据迁移”的产品批准；
- 旧仓 read-only/EOL/archive policy；
- source-access matrix 和实现团队名单；
- 第一版 provider 与 MVP 功能白名单。

**验收**：

- 每个身份值与旧产品不同，且没有旧 redirect/domain/keychain/app-group 复用；
- Product、Implementation、Compliance 权限已分离；
- 新 repo 尚无任何旧源码/asset/history；
- 法务明确哪些旧历史/许可证材料必须继续留在旧产品归档。

**回滚**：本 Phase 可停止，不创建新 app identity 前无外部状态。签名、商店条目和域名创建后按各平台流程保留审计，不删除记录来掩盖决策。

## 8. Phase 1：新 PRD、交互与公开协议规格

**目标**：实现团队不看旧代码也能完成 MVP。

### 8.1 新 PRD

每个需求写：

```text
requirement_id
user goal
precondition
input
displayed result
durable state change
cancel/error behavior
privacy/security rule
acceptance evidence
```

不要写“行为和 Rikka/旧 Amber 一样”，不要附旧源码或内部类型名。

### 8.2 独立 UX

- 从用户任务重新设计信息架构和导航，不沿用旧 screen/route 列表。
- 新品牌、颜色、字体、spacing、icons、motion 和空状态全部有独立设计来源。
- 可访问性、动态字体、深色模式和不同屏幕尺寸在新设计稿中定义。
- 旧 UI 截图只能由法务批准的规格人员用于差异审计，不能作为实现团队的像素模板。

### 8.3 Provider 规格

只从官方/公开资料建立：

- OpenAI Responses/Chat（如果产品都需要）；
- Anthropic Messages；
- Gemini/Vertex；
- 经批准的 OpenAI-compatible providers；
- model listing、auth、streaming、reasoning、usage、error、cancel。

每份 fixture 记录官方文档版本和来源；不使用旧 provider parser 输出作为 golden oracle。

**验收**：Implementation Team 能仅凭 PRD、设计稿、官方协议和新 fixtures 描述 MVP 主链；没有旧 symbol/source/hash 进入输入包。

**回滚**：未批准或含旧实现细节的 spec 整体 quarantine，重新起草；不能只删除几行后继续使用。

## 9. Phase 2：新仓骨架、供应链与 CI

**目标**：建立空壳可构建、来源可追踪的 Android/iOS 新仓。

**最小目录建议**：

```text
apps/android/
apps/ios/
specs/
fixtures/
provenance/
tools/release-audit/
```

不创建 root shared runtime；`specs/fixtures` 是首期唯一跨端输入。

### 9.1 CI 门禁

- build/test/package；
- dependency allowlist、lockfile、SBOM、license policy；
- asset manifest/hash；
- secret scan；
- forbidden source/hash/path/name scan；
- final APK/AAB/IPA/framework/resource scan；
- provenance ledger completeness。

### 9.2 Provenance ledger

每个 source/dependency/asset/fixture/generated/binary 记录 origin、revision/hash、SPDX、allowed use、author/source-access、reviewer 和 output commit。

### 9.3 验收

- Android/iOS 空壳在各自官方工具链构建；
- repo history 只有新 root 之后的 commits；
- resolved dependencies 全来自 allowlist，且无 Rikka fork/namespace；
- 空包二进制扫描无旧字符串、资源 hash、bundle/class 名；
- 实现开发机和 CI 无旧仓 checkout/remote。

**回滚**：删除尚未发布的空壳分支即可；保留审计日志。若发现旧输入进入 repo，quarantine 整个受影响分支和构建产物，从最后无污染 commit 重新开始。

## 10. Phase 3：Android MVP 垂直切片

**目标**：从零闭合 Android 前台文本聊天，不复制旧 `ChatService`、`GenerationHandler`、Provider/Message 模型或 Room schema。

### 10.1 最小实现顺序

1. 新 Application/Activity/Compose navigation；
2. 新 provider configuration 与 Keystore secret reference；
3. 一个官方 provider adapter；
4. 新 `Thread/Turn/ContentBlock` 持久化；
5. foreground send/stream/cancel/error/terminal；
6. conversation list/open/rename/delete；
7. kill/relaunch 后读取已提交会话；
8. 新 settings/privacy/notices UI。

### 10.2 数据边界

- 新数据库名、表名、schema version、实体和序列化格式；
- 不创建旧 `conversationentity`、`message_node`、`select_index` 或旧 settings keys；
- 先实现单一线性会话；只有 PRD 明确要求分支/variants 时再添加；
- stream 中间态写入独立 staging/run record，terminal 后原子提交；
- Key 仅进 Keystore；数据库只存 logical credential ref。

### 10.3 Provider 边界

- wire DTO 位于 provider adapter 内；
- domain 只接收新 `InferenceEvent`；
- 只实现官方协议明确的 text/reasoning/usage/error/cancel；
- 不加入 tool/image/document 的假想字段；
- provider 错误显式返回，不自动切换 provider 或 silent fallback。

### 10.4 验收

- 新安装、首次配置、发送、流式显示、取消、错误、重启读取闭合；
- API key 不出现在 DB/UserDefaults-equivalent/log/fixture；
- malformed event、EOF、HTTP error 不被标记为 completed；
- 新 Room/files schema 与旧 schema 没有 reader/writer 关系；
- release APK 解包无旧包名、坐标、endpoint、资源 hash和旧 contract symbol。

### 10.5 最小验证

- provider event reducer 定点 JVM tests；
- database create/read/terminal-commit/cancel tests；
- `compileDebugKotlin`、`assembleDebug`；
- emulator 安装启动；
- 一台真机 Keystore/进程重启；
- 一个真实 provider text/cancel/error smoke。

**回滚**：MVP 使用全新 app ID，可与旧 App 并存。停止 internal/closed testing 即可，不需要降级旧数据库，也不修改旧 App 数据。

## 11. Phase 4：iOS MVP 垂直切片

**目标**：新建纯 native Swift 产品，不复制现有 Swift/KMP 源码，也不创建 `Shared.framework` 替代层。

### 11.1 最小实现顺序

1. 新 SwiftUI App/navigation/design tokens；
2. 新 provider configuration 与 Keychain reference；
3. `URLSession` 或经批准 client 的一个官方 provider adapter；
4. 新 native `Thread/Turn/ContentBlock/InferenceEvent`；
5. Codable + file/SQLite 的新 canonical store；
6. foreground stream/cancel/error/terminal；
7. conversation list/open/rename/delete；
8. 新 settings/privacy/notices UI。

### 11.2 明确禁止

- 不导入现有 93/167 Shared consumers；
- 不复制 `ChatGenerationCoordinator`、`IOSAgentToolEngine`、`IOSConversationStore`、`IOSSharedSettingsStore` 或其 tests；
- 不链接 KMP、Kotlin/Native、`Shared.framework`、旧 `.klib`；
- 不复用旧 bundle/App Group/Keychain access group/CloudKit container。

### 11.3 验收

- clean repo 中 `import Shared` 与 `@preconcurrency import Shared` 为 0；
- final binary `otool -L` 无 Shared/Kotlin runtime；
- 新安装、配置、stream、cancel、error、relaunch 闭合；
- Keychain 真凭据不进入 settings/log/backup；
- 新 bundle ID 与旧 App 并存，删除新 App 不影响旧 App。

### 11.4 最小验证

- Swift reducer/provider/store focused tests；
- `xcodebuild build-for-testing`；
- Simulator install/launch；
- 真机 Keychain/锁屏/relaunch；
- 一个真实 provider text/cancel/error smoke。

**回滚**：停止 TestFlight/internal distribution；旧 App 仍可独立使用。新 App 不读写旧 container，因此无需数据降级。

## 12. Phase 5：Tool、Approval、Effect 与 Durable Run

**目标**：在两个 MVP 都稳定后，从行为规格重建工具与运行可靠性；不复制旧 ledger/runtime。

### 12.1 新状态语义

只实现产品规格要求的最小状态：

```text
Run: Running → WaitingUser / Completed / Failed / Cancelled / OutcomeUnknown
Tool: Prepared → AwaitingApproval → Denied / Started → Finished / OutcomeUnknown
```

需要更多状态时必须由真实失败模式推动，不能照旧枚举补齐。

### 12.2 实施顺序

1. serializable tool declaration 与参数 schema；
2. approval request/decision；
3. effect journal 与 idempotency key；
4. single tool execution；
5. tool result 进入下一 turn；
6. scoped cancel；
7. checkpoint 与 kill/relaunch reconcile；
8. parallel tool 仅在明确产品需求出现后加入。

### 12.3 必须验证的真实风险

- approval 已展示但 app 被杀；
- effect 已开始但结果未提交；
- provider 返回重复 tool ID；
- 用户取消与 tool 完成竞态；
- network success 但本地落盘失败；
- notification action 指向已结束或不同 run；
- process relaunch 后不可逆操作不能无条件重放。

### 12.4 验收

- UI 只投影 durable state，不成为事实源；
- 每个不可逆副作用有稳定 idempotency key；
- `WaitingUser`、`Cancelled`、`Failed`、`Completed` 不互相冒充；
- foreground、notification 和后续 background consumer 使用同一 run owner；
- 完整链 `stream → tool → approval → effect → result → next turn → terminal` 通过。

**验证**：最少状态机/持久化测试、各落盘边界故障注入、Android/iOS 真机 kill/relaunch。不得用旧 `ToolEffectLedger`、旧测试或旧 runtime 当 oracle。

**回滚**：按工具 family 关闭；保留明确的 unavailable/failed 状态，不 fallback 到旧 app 或旧 tool runner。

## 13. Phase 6：Provider Families、Search、Image、TTS、Document

**目标**：所有外部协议和高继承叶子都从公开资料或独立 upstream 重新实现。

### 13.1 Provider family 顺序

1. 首发官方 provider；
2. OpenAI-compatible endpoints；
3. Claude；
4. Gemini/Vertex 与各 OAuth 模式；
5. image generation/edit；
6. TTS/speech。

每个 family 单独 PR、feature flag、真实 smoke 和删除条件。OpenAI-compatible 不默认等价于 OpenAI；image/TTS 不伪装成 text stream。

### 13.2 Search

- 使用新的 search contract、provider credentials 和错误模型；
- 不调用 `api.rikka-ai.com`，也不代理旧 endpoint；
- 重新实现 citations、URL dedupe、scrape、image budget 和 partial failure；
- 只发布真实验证过的 search providers。

### 13.3 TTS

- 平台各自实现 text chunk、queue、prefetch、pause/resume、speed、seek/skip/stop 和 audio focus/session；
- voice/provider descriptor 只在真实双端需求稳定后考虑共享；
- 不复制旧 `AudioPlayer`、controller、synthesizer、chunker 或 provider code。

### 13.4 Document

- 从用户需求重新选择支持格式；不因为旧 App 支持就默认全部重做；
- 每个 parser/renderer 使用独立 upstream 或新实现；
- MuPDF 等 third-party 必须单独记录版权、版本、构建方式、SPDX/NOTICE；
- 验证坏文件、大文件、路径穿越和资源上限。

### 13.5 验收

- 每个 released family 有 request/stream/error/cancel fixture 和真实 smoke；
- search/TTS/document 各有真实设备输入，不以 mock 替代；
- resolved dependencies、assets 和 native binaries 没有 Rikka fork/hash；
- provider/search/TTS credentials 只存平台安全存储；
- 失败时明确禁用或报错，不 silent fallback 到旧服务。

**回滚**：单独禁用 provider/search/TTS/document format；不恢复 Rikka endpoint、fork、资源或旧 parser。

## 14. Phase 7：Amber 产品能力重新立项实现

**目标**：Novel、Memory、MCP、Skills、Workspace 等也不复制当前 Amber 源码，只保留经批准的用户价值。

### 14.1 取舍门

每个 domain 先回答：

1. 用户可观察价值是什么？
2. 首发是否必须？
3. 最小状态/持久化是什么？
4. 有无安全或不可逆副作用？
5. Android/iOS 是否都需要？

没有真实消费面或只存在 dormant/default-off 代码的能力，默认不重建。

### 14.2 推荐顺序

1. Workspace 基础读写与附件；
2. MCP connect/call + approval；
3. Skills 的安全读取/启用；
4. Memory 的用户可见 recall/candidate；
5. Novel project/document/workspace；
6. Council/SubAgent；
7. Deep Read/MiniApp/Board/Automation。

### 14.3 Workspace

- Android SAF 与 iOS files/bookmarks 各自实现；
- 新 workspace root、artifact model 和权限生命周期；
- 验证撤权、重启、大文件、并发写和 share/export；
- 不复制旧 WorkspaceManager、路径常量、mirror/native bridge。

### 14.4 MCP 与 Skills

- MCP 依据公开 JSON-RPC/transport 规格实现；OAuth/token 走新 identity；
- namespace collision、reconnect、preview→approve→apply、redaction 有明确行为；
- Skills 使用新的 manifest/version，最小实现 read/enable；promotion/rollback 只有需求出现后加入；
- path traversal、symlink、private key、secret、`mcp.json` 等安全边界必须验证。

### 14.5 Memory

- 从用户可解释的 candidate/recall 开始，不先复制旧自动提取/Dream 架构；
- storage、ranking、budget、sensitive-content policy 和 provenance 重新定义；
- 自动提取、embedding、Dream 只有真实用户价值和恢复语义明确后加入。

### 14.6 Novel

- 当前 16 个 Novel/Novel Workspace WIP 永远不进入新实现输入；
- 只有 owner-approved behavior spec 可传给实现团队；
- 按 project/document → chapter/workspace → branch/undo → ghostwrite → pause/resume/retry → plot/unresolved → UI 的纵向切片交付；
- 每片包含 owner、持久化、取消、冲突、kill/relaunch 和 rollback；
- 不复制现有 Novel source、test、fixture、prompt、schema 或 UI。

### 14.7 验收与回滚

- 每个 domain 有唯一 production entry、owner、store 和真实消费者；
- 每个 domain 至少验证 normal、cancel/error、relaunch/recovery；
- domain 级 feature flag，关闭不会破坏 Chat 核心；
- 失败时删除/关闭新 domain，不回退到旧 App 的实现。

## 15. Phase 8：平台入口、后台与系统表面

**目标**：只在主链稳定后重建系统能力，避免把声明或 dormant code 当完成。

### 15.1 Android 全入口 manifest

逐项决定 `implement / intentionally unsupported / removed`：

- launcher/Chat；
- share/shortcut/deep link；
- OAuth callback；
- notification action；
- foreground service；
- WorkManager/Cron；
- file picker/FileProvider；
- Session/History/MiniApp/domain runners；
- restore/import；
- 规格明确需要的 accessibility/screen-capture/notification-listener surfaces。

每个 implemented 入口必须记录 UI gate、domain/run owner、durable persistence、cancel 和 recovery；不能绕过新 runtime 直接调用 provider/store。

### 15.2 iOS background

先由产品选择：

1. 后台只保存 checkpoint，前台 stream 被系统中止后稍后 reconcile；或
2. 新 backend 提供 durable job/idempotency/status/push，App 只提交和同步。

不得把 BGTask 当无限时长 worker。必须验证锁屏、background、expiration、无网/恢复、kill/relaunch、重复 adopt 和唯一 terminal。

### 15.3 iOS Live Activity / Widget / Watch

- 使用新的 extension identifiers、App Group 和 native contract；
- Live Activity 只保存 run ID、阶段、进度、时间和非敏感摘要；
- Watch 首期只显示 last-known state 和发送幂等控制命令；iPhone 是唯一 provider/data owner；
- 不迁旧 Activity/Watch state，不复用旧 bridge、resource 或 entitlements。

### 15.4 凭据与身份

- Android 新 Keystore alias/domain；iOS 新 Keychain access group；
- 用户重新登录/录入，旧 secret 不复制；
- 后台可访问性级别由明确需求决定，默认最小权限；
- 新 OAuth clients、redirects、APNs/Firebase/Crash/analytics projects；
- secrets 不进日志、fixtures、normal backup、notifications 或 Live Activity。

### 15.5 验收

- 全入口无 unknown owner；
- Android FGS/Worker/notification 和 iOS BGTask/Activity/Watch 只依赖新 owners；
- 真机系统调度、权限拒绝、expiration、deep link、kill/relaunch 分别验证；
- 新 entitlements/manifest 权限是最小集合；
- final binary 不含旧 identifiers、app groups、redirects、channels 或 service domains。

**回滚**：系统能力逐项关闭；保留 foreground core。若 backend durable job 不稳定，停止后台提交并显式告知用户，不偷偷切回本地旧行为。

## 16. Phase 9：可选的用户数据导出/导入项目

**默认状态：不做。** 这是为了最大化新旧产品切断。只有用户明确接受“数据连续但产品/源码独立”的折中后才启动。

### 16.1 隔离边界

```text
旧 App / 独立 legacy-exporter
  → 公开、版本化、签名的 neutral export
    → 用户显式选择文件
      → 新 App staging importer
        → validate/digest
          → commit marker last
```

- 新 App 不读取旧 Room/DataStore/KMP/iOS internal formats；
- legacy exporter 位于旧产品维护边界，由有旧源码访问权限的团队负责；
- 新 App 实现团队只看到公开 neutral schema 和测试样本；
- exporter/importer 的权利与人员隔离由法务批准。

### 16.2 可迁移

- 用户创建的会话文本和附件；
- 所有 variants、selected branch 和顺序；
- 用户创建的 workspace/novel content；
- 非敏感设置的公开表示；
- unknown fields 作为 opaque payload，仅在明确允许时保留。

### 16.3 不迁移

- API keys、OAuth tokens、cookies、Keychain/Keystore values；
- 未完成 run、provider cursor、tool effect、notification/background lease；
- app identity、Firebase/APNs/CloudKit/session；
- 旧 executable code、Skill executable/MCP credential 配置；
- 旧 analytics/crash identifiers。

### 16.4 验收

- staging、完整校验、digest、commit marker、幂等重跑；
- 损坏/超大/未知输入明确失败，不静默跳过；
- 导入失败只删除新 App staging，不修改旧 App 或原导出包；
- 凭据缺失明确提示重新认证；
- 迁移器不成为新 App 对旧内部 schema 的长期依赖。

**回滚**：删除新 App staging/已导入副本；旧 App 和原文件保持不变。此 Phase 失败不阻塞新产品发布。

## 17. Phase 10：发布、旧产品 EOL 与关系清零

**目标**：新产品独立发布，旧产品停止演进但保留必要历史和用户出口。

### 17.1 新产品发布

- 新 Google Play/App Store listings；
- 新 signing/provisioning、OAuth、APNs/Firebase、domains、privacy URLs；
- 新 release notes、support、telemetry 和 incident playbook；
- internal/TestFlight → closed beta → staged rollout；
- 新产品的 rollback 只回到自己的上一 release。

### 17.2 旧产品处理

- 旧 repo/read-only archives、licenses、releases、issue/PR history 不删除；
- 根据法务/商店政策发布 EOL/停止维护说明；
- 如启用 Phase 9，只提供有期限的数据导出，不再新增业务能力；
- 不用改写历史、删除 attribution 或关闭 fork relation 来制造“从未有关”的假象。

### 17.3 最终扫描范围

扫描新仓和 APK/AAB/IPA：

- Git objects/remotes/refs/submodules；
- source/test/generated/templates/prompts；
- resolved Gradle/SwiftPM/Cargo/npm dependencies；
- strings/URLs/endpoints/identifiers；
- assets/fonts/icons/banners/dictionaries；
- native libs/frameworks/web bundles；
- signing/entitlements/manifest/Info.plist；
- SBOM/NOTICE/provenance completeness。

### 17.4 验收

- 新仓无旧 Git objects、remote、ref、patch 或 source input；
- 新发布包无未批准 Rikka/旧 Amber namespace、coordinate、endpoint、identifier、asset hash 或 binary；
- 所有活跃文件、依赖、素材和生成物均有独立来源；
- Android/iOS 新身份可与旧 App 并装；
- 真机、真实 provider、后台、通知、kill/relaunch 分别通过；
- 至少一个候选发布观察窗口无数据丢失、重复副作用或 P0/P1 级缺陷；
- 法务/权利负责人批准最终产品表述。

**回滚**：暂停新 listing/rollout，回到新产品上一 release。旧 App 是否继续可下载由 EOL policy 决定，但不得作为新产品运行时 fallback。

## 18. 当前 Go / No-Go

当前状态：**NO-GO for implementation**。

原因：

- 新产品名、app IDs、签名、商店和 backend identity 尚未批准；
- 新仓、source-access matrix 和实现团队隔离尚未建立；
- 当前工作区仍有 16 个 Novel WIP 与未跟踪计划文档，不能作为新产品输入；
- 新 PRD、独立 UX、公开协议 fixtures 尚未完成；
- 权利/许可证对 Track B 流程和最终表述尚未批准。

以下全部满足后切为 GO：

1. 产品/权利负责人书面选择本 Zero-Rikka 新产品路线；
2. 接受新 app ID/bundle ID、平行商店条目、用户重新配置和默认无数据迁移；
3. 新 repo、新 root commit、独立实现环境和 access log 已建立；
4. 实现人员资格、规格输入和依赖/素材 allowlist 已由 Compliance 批准；
5. MVP PRD、独立设计、provider fixtures 和验证矩阵冻结；
6. 旧仓/WIP/历史/许可证归档策略批准。

在 GO 前只允许：决策、权利审查、独立设计、行为规格、公开协议研究和新仓空壳准备。不得从旧仓复制或翻译实现。

## 19. 里程碑与 PR 骨架

### 19.1 里程碑

| 里程碑 | 完成门 |
| --- | --- |
| Z0 Charter | 新产品路线、身份、无迁移默认和 EOL policy 批准 |
| Z1 Isolated | 新 repo/root/access matrix/provenance CI 通过 |
| Z2 Spec-ready | MVP PRD、独立 UX、公开协议 fixtures 冻结 |
| Z3 Android MVP | 新 Android 前台聊天在真机/真实 provider 闭合 |
| Z4 iOS MVP | 新 native iOS 前台聊天在真机/真实 provider 闭合 |
| Z5 Durable tools | approval/effect/run/relaunch 两端通过 |
| Z6 Provider/leaf | released provider/search/TTS/document 逐项通过 |
| Z7 Product domains | 选中的 Workspace/MCP/Skills/Memory/Novel 等重新实现 |
| Z8 System surfaces | Android/iOS 后台、通知、extensions/Watch 等有明确结论 |
| Z9 Candidate | provenance/SBOM/binary/identity/device gates 通过 |
| Z10 Released | staged rollout 观察窗口通过，旧产品进入 EOL policy |

### 19.2 推荐 PR 序列

```text
PR-000 replacement-charter
PR-001 source-access-policy
PR-002 new-product-identity

PR-010 product-requirements
PR-011 independent-design-system
PR-012 public-provider-fixtures

PR-020 new-repo-bootstrap
PR-021 provenance-sbom-ci
PR-022 android-empty-shell
PR-023 ios-empty-shell

PR-030 android-foreground-chat
PR-031 android-new-store
PR-032 android-provider-<family>

PR-040 ios-foreground-chat
PR-041 ios-new-store
PR-042 ios-provider-<family>

PR-050 tool-approval-contracts
PR-051 android-effect-run
PR-052 ios-effect-run

PR-060 search-<provider>
PR-061 tts-<platform-provider>
PR-062 document-<format>

PR-070 domain-<workspace|mcp|skills|memory>
PR-071 novel-spec-and-android
PR-072 novel-ios

PR-080 android-system-surfaces
PR-081 ios-background-activity-watch

PR-090 optional-neutral-exporter
PR-091 optional-new-app-importer

PR-100 final-provenance-binary-audit
PR-101 store-candidate
PR-102 legacy-eol
```

序号表示依赖方向，不要求一次 PR 完成所有平台。每个 PR 只做一个纵向切片；不得把新 schema、provider、UI redesign 和大规模删除混在一起。

### 19.3 每个 PR 必填

```text
requirement/spec IDs:
public protocol/dependency sources:
source-access statement:
new files and provenance IDs:
production entrypoint and owner:
durable state / credential / side-effect impact:
focused verification:
device/provider/background evidence:
unverified surfaces:
feature flag / rollback:
forbidden-source scan result:
reviewer approvals:
```

## 20. 最小验证矩阵

| 层级 | 必须证明 | 不能替代 |
| --- | --- | --- |
| Source/provenance | 新 root、来源完整、无旧输入 | 行为正确 |
| Dependency/SBOM | 无 Rikka forks，第三方权利清楚 | 运行时正确 |
| Fixture/unit | 新 contract/reducer/codec 的确定语义 | 真机/网络/后台 |
| Compile/archive | clean environment 可构建签名 | 真实 provider |
| Integration | 新 entry→owner→store/provider 全链 | OS 系统调度 |
| Emulator/Simulator | UI/安装/基本 lifecycle | 真机密钥/通知 |
| Device | Keystore/Keychain、权限、文件、通知 | 所有 provider |
| Real provider/service | auth、stream、tool、error、cancel | kill/relaunch |
| Background/kill | expiration、reconcile、唯一 terminal | 来源/许可证 |
| Binary/store | final artifact 身份、字符串、资源、依赖 | 产品价值 |

### 20.1 Android 最小命令类别

实际模块名由新仓确定，但每个候选至少执行：

```text
focused JVM tests
compileDebugKotlin
assembleDebug
clean release bundle/package
APK/AAB identity/dependency/resource scan
```

### 20.2 iOS 最小命令类别

```text
focused Swift tests
xcodebuild build-for-testing
xcodebuild archive
Simulator install/launch
otool/framework/entitlement/resource scan
```

模拟器、真机、真实 provider、后台、Watch/Activity 分开记录。旧 App 的测试通过不能作为新产品验证。

### 20.3 只新增必要测试

优先覆盖：

- provider stream/terminal/cancel；
- secret 不落普通存储；
- conversation terminal commit；
- effect 落盘边界与重复副作用；
- run scoped cancel/kill recovery；
- 可选 importer 的损坏/中断/幂等；
- app identity/binary 来源门。

不为提高覆盖率复制旧测试矩阵，也不测试私有实现细节。

## 21. 最终关系清零 Gate

### 21.1 Repository

- 新仓不是 fork，root commit 独立；
- 无旧 Git objects、remote、refs、submodule、gitlink、LFS objects、patch 或 CI cache；
- implementation issue/PR/prompt 不含旧源码片段或内部测试；
- source-access/provenance ledger 完整。

### 21.2 Source and architecture

- 新产品不包含或包装旧 ChatService/GenerationHandler/Provider/Message/Settings/Store/Runtime；
- 没有旧 package、private symbol、control-flow layout 或 test layout 的未分类命中；
- 公开协议/common vocabulary、third-party、generated 分别有来源，不能混称原创；
- similarity scan 同时对 RikkaHub 与旧 Amber corpus 运行，所有高命中均人工分类，无 unknown。

### 21.3 Dependency and service

- resolved dependency 无 `com.github.rikkahub*`、Rikka forks 或未经批准 `me.rerere.*`；
- 不调用 `rikka-ai.com` 或旧 Amber/Rikka endpoints/proxies；
- OAuth/Firebase/APNs/CloudKit/telemetry/backend domains 全部属于新产品；
- native/web bundles 都有独立 upstream 和 SBOM。

### 21.4 Identity and assets

- 新 app IDs、bundle IDs、signing、schemes、authorities、groups、channels、store listings；
- 新 logo、icons、tokens、fonts、illustrations、sounds、banners、prompts、dictionaries；
- 对旧 Amber/Rikka asset corpus 的 byte-hash 命中为 0，独立第三方共同资产必须按其 upstream allowlist 分类；
- final binary 无旧 identifiers、URLs、brand strings 或 resource hashes。

### 21.5 Behavior and release

- Android/iOS MVP 生产链在真机/真实 provider 分别闭合；
- released tools/domains/system surfaces 各有唯一 owner 和 durable state；
- no old fallback、no dual writer、no duplicate side effects；
- staged rollout 观察窗口通过且新产品 rollback artifact 实际可安装；
- 权利/法务批准最终产品描述，但不得写“技术已经抹掉旧历史”。

## 22. 粗略投入

这是从零重建，不是普通重构。粗略容量：

- 决策、规格、设计、供应链：6–10 engineer-weeks；
- Android MVP：12–20 engineer-weeks；
- iOS MVP：12–20 engineer-weeks；
- durable tool/run/provider families：20–35 engineer-weeks；
- 选定的 Amber domains：20–45 engineer-weeks；
- 系统 surfaces、发布、来源/权利审查：12–20 engineer-weeks。

总量约 80–140 engineer-weeks，取决于是否重建 Novel/Council/SubAgent/MiniApp、provider 数量和后台系统能力。两支平台团队加产品/设计/QA/Compliance，MVP 可先行，完整替换通常按 9–15 个月规划；这不是发布日期承诺。

## 23. 立即执行顺序

当前只做三件事：

1. **批准或否决 Zero-Rikka charter**：确认接受新 app identity、平行商店、默认无迁移和旧产品 EOL。
2. **建立隔离环境**：新非 fork repo、实现团队权限、provenance ledger、dependency/asset policy。
3. **冻结 MVP PRD**：只定义 text/reasoning chat、secure credential、new store、cancel/error/relaunch 和新设计。

在这三项完成前，不写 provider、runtime、数据库或 UI 生产代码。最容易犯的错误是先从旧仓复制一个“临时可用”的模型/stream/store；一旦发生，本计划的零复用证据就被破坏。

## 24. Definition of Done

- [ ] Zero-Rikka charter、身份、无迁移默认和 EOL policy 已批准。
- [ ] 新 repo/root/remote/implementation environment 与旧仓隔离。
- [ ] 新 Android/iOS app IDs、signing、store listings、backend/OAuth/telemetry 全部独立。
- [ ] 新产品没有复制 Rikka 或旧 Amber 的源码、测试、fixture、assets、build/runtime 实现。
- [ ] 新 Android/iOS MVP 在真机和真实 provider 上闭合。
- [ ] 所有 released provider/search/TTS/document families 各自通过真实验证。
- [ ] Tool approval/effect/run/cancel/recovery 不重复副作用并通过 kill/relaunch。
- [ ] 选中的 Amber domains 均从批准 PRD 重新实现；当前 16 个 Novel WIP 未被使用或覆盖。
- [ ] final APK/AAB/IPA 无旧 dependency、endpoint、identifier、brand、asset hash 或 binary。
- [ ] provenance、SBOM、NOTICE、source-access 和 license review 无 blocker。
- [ ] 新产品 candidate 观察窗口和自身 rollback 已验证。
- [ ] 旧产品按批准 EOL policy 只读保留，不作为新产品 fallback。
- [ ] 最终对外声明由权利/法务批准，并如实区分新实现与不可抹除的历史事实。

完成以上全部项目后，可以工程上表述为：

> 新产品是从独立仓库、独立身份、独立设计和公开协议重新实现的，不再运行、依赖或分发 RikkaHub/旧 Amber 的实现和资产。

不能仅凭工程完成状态表述为“历史上从未与 RikkaHub 有关”或“旧版本的许可证义务已经消失”。
