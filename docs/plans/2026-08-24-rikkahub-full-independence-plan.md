# AmberAgent 全盘脱离 RikkaHub 计划

> Android-first / iOS-follow，证据驱动、可分批回滚

> **2026-08-24 决策更新：** 用户已选择“新产品、零旧源码复用”的严格路线。本文继续作为历史审计与 Track A/Track B 比较依据；实际执行以 `2026-08-24-zero-rikkahub-new-product-replacement-plan.md` 为准。

## 0. 文档状态

| 项目 | 当前值 |
| --- | --- |
| 文档日期 | 2026-08-24 |
| 状态 | Historical audit / superseded for strict execution；尚未开始生产代码迁移 |
| 计划主线 | Android 先切断生产控制链，iOS 随后收缩并移除 `Shared.framework` 派生面 |
| 审计快照 monorepo HEAD | `b89cf4ec35263c37b8157dadd9b20a16e63d94b1` |
| 审计快照 Android 分支 | `codex/android-novel-ghostwrite-fixes`，相对 `staging/main` behind 2 |
| 严格路线决策时工作区 | `main@701e8c634ababe12fc1a0d03beccb36b2b55a0ca` |
| 官方 RikkaHub 对照 | `b270766f06671d7456ce3d248622dc667648b6d1`，tag `2.4.11` |
| 谱系审计源 | 迁入 monorepo 前的 Android 仓库 `8d8f33db1af1e72a54bf620338eb6f88a016a251` |
| 保护中的 WIP | Android 16 个 Novel / Novel Workspace 文件；本计划不得覆盖、重排或拿它们当稳定基线 |
| 本轮变更 | 只新增本计划文档，不修改产品代码、数据或构建配置 |

这份文档是工程与来源审计计划，不是法律意见。版权、AGPL/商业许可、商标、第三方素材和商业秘密风险，最终仍需由有权限的权利链/法务审查确认。

## 1. 一句话结论

Android 目前仍应判定为**高来源相似、核心生产骨架高相似、但已有大量 Amber 自有业务扩展**；不能靠包名、应用名或 UI 换皮宣称脱离。推荐采用原仓可执行的“生产代码独立 + 来源透明”路线：先冻结来源和行为，再用 Amber-owned contract 与 strangler 逐段替换 `Provider → GenerationHandler → ChatService → persistence/UI`，最后清掉 Rikka 依赖、端点、素材和二进制残留。

iOS 不应照 Android 再重写一遍。iOS 的 SwiftUI/UIKit、文件存储、Keychain、后台执行、Live Activity 和恢复体系已经明显平台化；应保留这些资产，只替换 `Shared.framework` 暴露的高相似 KMP Provider/Model/Message/Conversation contract，并让 Swift 生产代码先收口到一个窄 façade。

## 2. 当前相似性判断

### 2.1 不使用一个混合百分比

“相似性”至少包含五个不同问题，不能相加或简单平均：

1. **Git 谱系**：是否从同一代码历史直接分叉。
2. **当前活跃源码**：现有生产代码中还有多少行、文件或结构可直接追溯。
3. **生产架构**：真实入口、owner、持久化、取消、恢复是否仍走相同控制链。
4. **产品/交互**：能力集合、信息架构和 UI 投影是否相似。
5. **依赖/素材/品牌**：坐标、端点、图标、文案、许可证和打包产物是否仍指向 Rikka。

本计划的完成标准不是把某个总分压低，而是让每一类残留都有明确 owner、来源结论和退出门。

### 2.2 Android：当前仍高相似

从迁入 monorepo 前的 Android 完整历史审计得到：

| 指标 | 当前证据 | 解释 |
| --- | ---: | --- |
| 共同 exact commits | 2,340 | 证明是直接分叉谱系，不是偶然长得像 |
| 最新共同祖先 | `1541ec39972eb671b249886aa76db901c6de4e6f` | 2026-05-01 |
| 生产 Kotlin | 1,079 文件 / 253,305 行 | 不含测试、生成物和当前 WIP |
| exact-Rikka blame | 49,917 行 / **19.71%** | 保守下界；大规模 rebrand 会把继承行归到新 commit，不能当语义相似度上界 |
| `ai` | **48.48%** exact-Rikka blame | Provider/Model/Message/stream 是第一优先级 |
| `search` | **76.10%** | 高继承叶子模块 |
| `tts` | **80.71%** | 高继承叶子模块 |
| `common` | **66.83%** | 高继承基础模块 |
| `document` | **71.09%** | 含第三方 MuPDF 边界，不能把全部都算 Rikka 私有实现 |
| `core/model` | **50.89%** | Conversation/MessageNode wire 需重新定界 |
| `app` | **19.52%** | 总量最大，且新旧代码混杂，不能一次性重写 |

`19.71%` 是 commit provenance 下界，不是“只有 19.71% 相似”。2026-05-28 的批量 rebrand/路径迁移曾一次触及 712 个文件；路径、namespace 和 blame owner 改变不代表逻辑已经重写。旧审计在更早快照还发现 fork 点约 72% 文件字节相同、当时当前树约 40.5% 仍与 fork 点相同；这些旧数字只作为风险提示，Phase 1 必须用本文件记录的固定 SHA 重新生成可复现内容指纹。

本轮也已对当前 monorepo HEAD 与固定 Rikka tag 做内容指纹。下面是**当前审计快照**，不是已入库的长期 CI 产物；P1 必须将脚本、工具版本、扩展名、排除清单、source manifest 和输出报告固化后重算。口径排除了测试、build/generated/schema/vendor、MuPDF、web-ui、locale-tui 和构建产物，并去除注释、package/import、空白及品牌/namespace 差异：

| 当前内容指纹 | 结果 |
| --- | ---: |
| 内容指纹纳入的 production source | 1,109 文件 / 264,076 行；精确扩展名/行数算法由 P1 manifest 固化 |
| raw SHA-256 exact | 0 文件 |
| namespace/注释/格式归一 exact | 78 文件 |
| 其中只含协议/模型字段 | 7 文件 / 192 行 |
| 应进入替换 ledger 的 exact 功能文件 | **71 文件 / 5,899 行** |
| 协议字段过滤后 Jaccard ≥ 0.80 | **125 文件 / 16,898 行** |
| 当前 byte-exact 共享生产资源 | **62 个 / 8.76 MB**，占 Android 资源 35.20% |

模块级 7-token shingle 的 Android→Rikka 重合率为：`tts 84.37%`、`common 74.72%`、`document 67.06%`、`search 63.07%`、`ai 41.50%`、`core 15.01%`、`app 10.62%`、`feature 1.43%`、`native 0.17%`。这进一步确认：高风险主要位于继承基础层和 app 中的基础设施/UI 叶片，而新 Agent/Novel 业务层本身不该成为首轮重写对象。

生产链也仍明显保留原始骨架：

```text
RouteActivity
  → ChatPage
    → ChatVM
      → ChatService.sendMessage
        → GenerationHandler.generateText
          → ProviderManager
            → OpenAI / Google / Claude adapters
```

Amber 已经在这条链上加入 Tool Effect Ledger、typed terminal、run ownership、Responses resume、Memory、MCP 安全治理、Skills promotion、Workspace、Council、MiniApp、Novel 等大量新能力；这正是为什么不能“整仓推倒重写”。正确做法是保住 Amber-owned domain，通过新边界替掉下面的继承骨架。

### 2.3 Android 的明确发布残留

当前至少存在以下硬残留：

| 残留 | 当前证据 | 最终要求 |
| --- | --- | --- |
| Rikka Maven forks | `gradle/libs.versions.toml` 中 4 组 `com.github.rikkahub*` 坐标 | 换成经审查的独立上游/自有实现；不能只换 group 名 |
| Rikka 搜索服务 | `search/.../AmberAgentSearchService.kt` 使用 `https://api.rikka-ai.com/v1/search` | 替换服务并迁移配置/错误语义 |
| About 链接 | `SettingAboutPage.kt` 仍指向 RikkaHub 及其 LICENSE | 改为 Amber 来源页和本地第三方声明；历史归属写入 provenance 文档 |
| 品牌资产 | `amberagent.svg` 与 `rikkahub.svg` SHA-256 完全相同 | 重新创作并替换全套 launcher/debug/Graphite 资源 |
| 共享资源 | 62 个生产资源 byte-exact，共 8.76 MB；包含大量 provider icons、banner、emoji/font/drawable | 逐项判定第三方可保留、自有重制或删除；最终未批准 exact hash 为 0 |
| namespace | 约 561 处 `me.rerere`，主要来自 HugeIcons | 区分第三方 namespace 与 app 残留；替换/许可后清零未批准项 |
| 发布物来源 | MuPDF、Alpine/proot、MNN、Cargo、Bun、字体、字典、Provider logo | 全部进入 SPDX/SBOM/NOTICE 与 asset manifest |

### 2.4 iOS：不要按 Android 的相似度处置

iOS 当前不是“Android 代码换成 Swift”：

- SwiftUI/UIKit 主壳、Chat timeline、ViewModel、UserDefaults/Keychain、文件持久化、BGTask、Live Activity、Watch 和 durable run 已经是 iOS-owned。
- `apps/ios/shared/build.gradle.kts` 仍聚合/导出 32 个 KMP 模块，`project.yml` 动态构建并嵌入 `Shared.framework`。
- 当前 `iosApp/iosApp` 有 **93 个生产 Swift 文件**使用 `import Shared` 或 `@preconcurrency import Shared`；全 iOS Swift（含测试/其他 target）有 167 个。应以符号级真实消费清单继续细分，而不是把每个 import 都算作核心依赖。
- 高相似热点集中在 KMP 的 `Provider`、`ProviderSetting`、`Model`、`ModelDsl`、`Message`、`MessageStreamAccumulator`、OpenAI/Claude adapters 和 Conversation wire。
- iOS 整体直接代码相似度已低，但核心聊天行为仍有中高语义对应。它的任务是**收缩共享派生面**，不是重做现有 Swift UI/后台/恢复。

## 3. “全盘脱离”的两种工程流程

### 3.1 Track A：可审计的工程独立（推荐执行路线）

目标：在现有 monorepo 内实现运行时代码、依赖、素材和发布物的独立；保留真实 Git 谱系和依法需要的 attribution/license。

工程证据完成后，拟议发布表述可以是：

> AmberAgent 的当前生产实现、依赖和品牌资产已经独立，历史来源与第三方义务在 provenance/NOTICE 中透明保留。

工程证据本身不能支持以下表述：

> AmberAgent 从未派生自 RikkaHub，或重写后自动不再承担既有许可证义务。

这是本计划各 Phase 默认采用的路线，当前团队可直接执行。

最终对外表述、许可证效果和适用法域判断仍由法务/权利 owner 批准。

### 3.2 Track B：source-access 隔离的 clean-room 流程 / 新历史根

只有当业务目标是证明独立创作、建立全新 Git 历史根，或法律判断要求隔离时才选：

- 旧仓只读封存，新仓从新 root commit 开始，不复制旧 `.git`、源码、patch、内部测试或素材。
- Reference/Specification Team 可观察旧产品并输出批准的行为规格；Clean Implementation Team 只能接触规格、公开协议和合规依赖；Compliance Reviewer 独立复核。
- 已接触旧实现的贡献者在工程台账中标记其 source-access status；是否可以参与实现、以及最终能否使用 clean-room 表述，由法务按适用法域和实际隔离证据决定。
- 同包升级若需迁移旧数据，推荐由最后一个旧版本导出中立 `amber-export-v1`，新应用只实现该公开中立格式的 importer；不要把旧 parser 复制进新仓。

Track B 的交付仓应与本 monorepo 分离。若最终再合入 monorepo，Git 历史隔离是否仍成立需重新审查；本文不判断其法律效果。

### 3.3 Phase 0 必须做出的产品决策

| 决策 | 选项 | 对计划的影响 |
| --- | --- | --- |
| 独立等级 | Track A / Track B | 决定是否可由当前团队、当前仓执行 |
| Android 分发 | 原 `applicationId` 升级 / 平行新 App / 全新产品 | 决定签名、数据迁移、回滚和兼容窗口 |
| iOS 分发 | 原 bundle 升级 / 平行新 App | 决定旧文件、UserDefaults、Keychain 是否必须原位兼容 |
| 许可证目标 | 保留派生许可与 attribution / 寻求重新授权 | 决定哪些源码可以保留，不能由工程扫描代替 |
| 视觉独立 | 只换品牌资产 / 重做 design tokens 与核心流程 | 决定 UI Phase 的规模；默认只做有证据的来源替换 |

未做出这些决策前，可以执行审计、fixture 和 façade 准备，但不能授权重写 Git 历史、删除许可证或发布新产品。

## 4. 成功标准、非目标与红线

### 4.1 总成功标准

达到以下全部条件才可把 Track A 标记为完成：

1. Android 最终默认生产链不再调用继承的 `ChatService`/`GenerationHandler`/旧 Provider contract；过渡期允许 `ChatService` 仅作为薄 wrapper，但不得拥有 run/DB/provider，且 wrapper/兼容 adapter 最终删除。
2. P1 全入口 manifest 中的前台、FGS、Worker、MiniApp、通知、share/OAuth/restore、Session/History 和 domain runner 全部迁入新 owners、明确移除或证明为 partial/dead；没有未知绕行入口。
3. Android 生产源码、resolved dependency 和 APK/AAB 中没有未批准的 Rikka namespace、坐标、端点、链接、品牌字符串或旧资源 hash。
4. Android Room/DataStore/backup 的旧数据可无损迁移；MessageNode 全 variants、selected branch、未知字段和 credential references 不丢失。
5. iOS Swift 生产消费者只依赖 iOS façade/中立 contract；最终 app binary 不再链接 `Shared.framework`，除非决策明确保留一个已证明中立的 root-core framework。
6. Provider、stream reducer、tool approval/effect、cancel、terminal、recovery 在 Android/iOS 各自真实生产入口通过。
7. 每个 active source、dependency、asset、fixture、generated output 和 release binary 都有 provenance/SBOM/NOTICE 结论。
8. 模拟器、真机、真实 provider、后台提交/adopt/expiration、kill/relaunch 分别报告，不能互相替代。
9. 所有临时 feature flags、shadow comparator、dual-read 和 legacy writer 均有 owner、期限和删除记录。

### 4.2 明确非目标

- 不以本计划重做 Novel、Council、MiniApp、Memory、Skills 或 Workspace 产品设计。
- 不把 Android Compose、Room、DataStore、WorkManager、Keystore 或 iOS SwiftUI、Keychain、BGTask 抽到 root `core/`。
- 不直接启用当前 `useKernelPath = false` 的 dormant Agent Kernel 来宣称完成。
- 不一次性搬空 `app`、重建所有模块或同时改 UI、schema、provider 和 runtime。
- 不为了计划本身扩建测试体系；每个切片只补直接覆盖迁移行为和高风险边界的最少验证。
- 不删除真实需要保留的许可证、copyright、第三方 notice 或审计历史。

### 4.3 工程红线

- 不对 provider/tool/message/通知/扣费等外部副作用做 shadow execution 或无保护 dual trigger。
- 不让旧 writer 与新 writer 同时成为 canonical owner。
- 不把 `Conversation.currentMessages` 当迁移源；必须保留全部 `MessageNode.messages` variants 与 selection。
- 不用 typed `decode → encode` 迁移未知 JSON；未知字段和未知 sealed subtype 必须 opaque round-trip。
- 不把 Android Keystore 或 iOS Keychain 密钥复制到中立 fixture、日志或普通 backup JSON。
- 不用静默 fallback 掩盖 provider、schema、credential 或迁移错误。
- 不拿默认关闭的 Split UI/Kernel、文件存在、编译通过或 mock 测试代替真实生产接线。
- 不触碰当前 16 个 Novel WIP 文件，直到其 owner 将它们合并到稳定基线或明确授权。

## 5. 目标架构

### 5.1 依赖方向

```text
Android UI / iOS UI
        ↓
platform ChatFacade / domain use cases
        ↓
Amber contracts + pure reducers
        ↓
platform adapters
  ├─ provider transport/auth
  ├─ conversation/run persistence
  ├─ tools and approval/effect ledger
  ├─ background/lifecycle/notification
  └─ platform files/credentials
```

旧实现只能位于最外层的 `legacy-adapter`，不能成为新 domain 的依赖。

### 5.2 root `core/` 的准入边界

只有同时存在 Android 与 iOS 两个真实生产消费者，并通过同一组 fixtures 的内容，才可提议进入仓库根 `core/`：

- 稳定领域模型与 serialization contract；
- agent events 和 pure reducer；
- tool declaration/approval/outcome contract；
- provider-normalized stream events 与 pure merge；
- prompt/context budget 纯逻辑；
- cross-platform golden fixtures。

以下继续平台所有，不能为了“去重”提前共享：

- request builder/parser 与 provider transport/auth；
- UI/ViewModel/navigation；
- Room/DataStore/UserDefaults/Keychain/文件系统；
- WorkManager/BGTask/通知/Live Activity；
- DI、WebView、SAF、媒体/OCR、native framework link；
- 尚未在两端稳定的 Memory policy、Sync protocol、Council/SubAgent semantics。

### 5.3 Provider/Message 新边界

现有 `UIMessage → MessageChunk → UIMessageChoice` 形状和 `ProviderSetting`/`ModelDsl` 是高风险继承面。新边界只表达协议语义，暂定为：

- `ProviderConfig`：provider id、endpoint、credential reference、transport options；不含 UI、Context、Keychain/Keystore 或 OAuth client。
- `ModelDescriptor`：wire id、display name、modalities、capabilities、context window。
- `ConversationMessage` / `ContentPart`：role 与 text/image/audio/document/reasoning/tool call/tool result。
- `ToolDeclaration`：name、description、input schema；不含 execute closure、approval 或 ledger。
- `StreamEvent`：text/reasoning delta、tool started/arguments delta/complete、usage、citation、completed/failed/disconnected。
- `ConversationStreamReducer`：只消费 normalized events，不知道 OpenAI/Claude/Gemini wire DTO。

这些只是 Phase 3 的候选名称与责任，不授权提前创建通用框架。最终 API 必须由 fixture 和两个消费者收敛。

## 6. 总体阶段与关键路径

```text
P0 决策/冻结
  → P1 来源与相似性基线
    → P2 行为规格 + 可靠性门
      → P3 中立 contract / fixtures
        ├→ P4 Provider / Model / Stream 替换
        └→ P5 vNext 数据 / 迁移
             → P6 Android Chat/Runtime strangler
               → P7 Android tools/domains/leaf modules
                 → P8 Android UI/dependency/brand release boundary
                   → P10 双端切流与旧实现删除

P3 → I0-I5 iOS façade/consumer/provider/storage migration → P10

P10 → P11 最终 provenance/license/release gate
```

Android 是第一条生产切流主线；iOS 从 P3 起并行准备，但只在 contract/fixture 稳定后迁移。总工期取决于团队人数、provider 数量、同包数据兼容和是否选择 Track B，不能用单一日期承诺代替每个 gate。

## 7. Phase 0：范围决策、稳定快照与 WIP 隔离

**目标**：先确定“脱离到哪一层”和“如何升级用户”，建立任何人都能复现的输入快照。

**前置**：无。

**预计**：2–4 个工程日 + 权利/产品决策时间。

### 7.1 产物

- `docs/current` 或外部审计仓中的 `detachment-decision.md`：记录 Track A/B、分发模式、许可证目标和视觉范围。
- Android/iOS/Rikka 精确 SHA、branch、tag、remote、status、submodule、resolved dependency lock 与 source manifest。
- WIP exclusion manifest：列出当前 16 个 Novel 文件及 owner，后续 similarity/fixture 不把未提交内容当 released baseline。
- Android 原仓谱系 manifest：共同祖先、2,340 shared commits、rebrand 批次及 blame 统计命令。
- `access-matrix.md`：仅 Track B 需要，定义 Specification/Implementation/Compliance 三组权限。

### 7.2 实施步骤

1. 由产品/权利 owner 选择 Track A 或 Track B。
2. 选择 Android/iOS 是同包升级、平行 App 还是全新产品。
3. 固定官方 RikkaHub `b270766f...` / `2.4.11` archive 与 SHA-256，不再用浮动 `master` 做完成判定。
4. 固定 Android monorepo HEAD、迁入前原仓 HEAD、iOS HEAD，并保存 `git status --short --branch`。
5. 对 WIP 建立只读排除清单；任何 Phase 若需要触及同一文件，必须先由 owner 合并或重新划定边界。
6. Track B 另建仓库和 root commit；本 monorepo 只保留规格/迁移协调，不接收 clean implementation 的旧源码输入。

### 7.3 验收

- 三个 SHA 和 source manifest 可在隔离机器上重现。
- 每个 WIP 文件都有 owner/状态；本计划没有覆盖它们。
- 决策文档明确哪些表述可以用于发布，哪些不可以。
- 对 Track B，Implementation Team 无旧 repo/read permission，且 prompt/issue/fixture 不含旧实现细节。

### 7.4 验证

```bash
git status --short --branch
git rev-parse HEAD
git -C <rikkahub-clone> rev-parse HEAD
git -C <rikkahub-clone> describe --tags --exact-match
```

### 7.5 回滚与停止条件

- 本 Phase 无数据/代码迁移，可直接停止。
- 若无法确认许可证/分发模式，只允许继续做只读 inventory 和 behavior spec，不得删 notice、重写历史或发布“已完全独立”声明。

## 8. Phase 1：Provenance、相似性和发布物基线

**目标**：把“感觉很像”变成路径级 ledger，并把误报、第三方公开协议和真实 Rikka-derived 实现分开。

**前置**：P0 固定快照。

**预计**：1–2 工程周。

### 8.1 统计口径

对 production source、test、generated、third-party/vendor、asset、binary 分开统计，至少输出：

- whole-file SHA-256 exact match；
- rename-aware Git provenance 与 shared-commit blame；
- 去注释/空白/namespace 后的 normalized token/line fingerprint；
- 结构相似度预警，仅用于人工复核；
- Gradle/Cargo/Bun dependency graph；
- resource/binary hash；
- product route/capability mapping，明确 production/partial/dead。

先用两组无关 Kotlin/Compose 项目校准语言和框架的自然相似基线。相似度工具只产生 review queue，不产生法律结论。

固化落点：

- 可复现脚本与版本：monorepo `tools/provenance/rikkahub/`；
- 人读摘要：`docs/current/RIKKAHUB_DETACHMENT.md`；
- machine-readable source/asset/dependency manifests：`docs/current/provenance/`；
- 大型逐文件报告与中间 shingle 数据：以 Android SHA + Rikka SHA 为 key 的只读 CI/release artifact，不把数十 MB 生成物提交进普通源码目录。

manifest 必须记录：纳入的扩展名、logical/physical line 算法、symlink/submodule 处理、每条 exclude、namespace normalization、tokenizer/shingle 参数、通用 token 阈值、工具/runtime 版本和 dirty-worktree 拒绝规则。当前 1,079/253,305 是 production Kotlin blame 口径，1,109/264,076 是本轮内容指纹口径；两者分母不同，不得相互换算。

### 8.2 Provenance ledger

建议采用 append-only NDJSON 或受控外部数据库，每条至少包含：

```text
artifact_id
artifact_kind              # source/dependency/asset/fixture/generated/binary
origin_type                # original/public-spec/third-party-cleared/reference-observation
origin_locator
origin_revision_or_hash
license_spdx
license_evidence
accessed_by_team_and_date
allowed_use
derived_spec_ids
output_commit_and_hash
reviewer
status                     # approved/quarantined/rejected
notes
```

AI 生成代码也要记录模型、日期、输入是否包含参考材料和人工复核结果；不得把旧源码片段塞入 prompt 后再标成 original。

### 8.3 Android 分区盘点

| 批次 | 当前判断 | Phase owner |
| --- | --- | --- |
| `ai` / provider/model/message/stream | 高相似、高风险 | P3–P4 |
| `search` / `tts` / `common` / `highlight` | 高 inherited 比例，可做独立叶子替换 | P7 |
| `document` | inherited + MuPDF 第三方边界 | P7 + P11 |
| `app` Chat/DI/settings/persistence/UI | 新旧混合，必须按生产链 strangler | P5–P8 |
| Memory/MCP/Skills/Workspace/Run/Board/MiniApp | Amber 深改/新增，保留 domain，替换其下游依赖 | P6–P7 |
| Novel/Novel Workspace | Amber 新产品层且当前有 WIP | 最后迁依赖，不重写业务 |
| assets/native/web-ui/runtime | 逐项 provenance/SBOM | P8/P11 |

### 8.4 iOS 盘点

- 固定 32 个 `Shared` exports。
- 输出所有 `import Shared`、`@preconcurrency import Shared` 及实际使用 symbol；把单纯 import、test helper、link-only 与运行时消费者分开。
- 固定主要运行时消费者：`ChatGenerationCoordinator`、`IOSChatBackgroundGenerationCoordinator`、`IOSAgentToolEngine`、`ChatViewModel`、`CouncilRunner`、`SubAgentRunner`、`NovelLiveModelAdapter`、settings、conversation、backup/provider stores。
- 保存 `Shared.framework` headers、build inputs、binary hash 和 `otool -L` 基线。

### 8.5 全入口与 Provider family manifest

不能只盘点前台 Chat。P1 必须为每个入口记录 caller、runtime owner、persistence owner、cancel owner、background/recovery、provider/tool side effects、迁移 Phase 与验证：

- `RouteActivity → ChatPage/ChatVM` 前台聊天；
- `AgentGenerationForegroundService` 与 notification action/deep link；
- `AgentCronWorker` 及仓内真实注册的其他 WorkManager workers；
- `MiniAppRunnerPage`、MiniApp search/generation；
- `SessionHomeVM`、`HistoryVM`、share/shortcut/OAuth/backup restore 等入口；
- DeepRead/Council/SubAgent/Board/Novel/Workspace 等 `*Orchestrator` 或 runner；
- `AmberAgentApp` 启动恢复、scheduler 和 receiver/bootstrap 链。

同一阶段还要生成 provider-family matrix：

- 官方 OpenAI Chat/Responses；
- OpenAI-compatible（如 DeepSeek、Mistral、xAI/Grok 及自定义 endpoint）；
- Claude；
- Gemini/Vertex 与各 OAuth 模式；
- image generation 与 image edit；
- TTS/speech；
- 每个 family 的 model list、auth、request/stream、tool/reasoning/usage、cancel/error、真实 consumer。

### 8.6 验收

- 每个 active artifact 属于且仅属于：retain、rewrite、replace dependency、legacy adapter、third-party cleared、remove。
- 没有把公开 wire 字段（如 `role`、`tool_calls`）直接当作 Rikka 私有代码；也没有用“公开协议”掩盖相同命名、控制流和测试布局。
- 相似性报告能从固定 SHA 一键重算，且未纳入 build/cache/WIP。
- Rikka links、endpoint、Maven coordinates、resource hashes、binary strings 已全部进入 ledger。
- 全入口和 provider-family matrix 没有 unknown owner；仅声明但无生产消费者的路径标为 partial/dead，不拿来证明完成。

### 8.7 回滚

本 Phase 只增审计产物。发现来源不明的依赖/素材时标记 `quarantined`，不做猜测性替换或静默删除。

## 9. Phase 2：黑盒行为规格与可靠性前置门

**目标**：先固定必须保住的 Amber 行为，再替换实现；避免“相似度下降了，但用户数据、审批和恢复坏了”。

**前置**：P1 inventory 至少覆盖 Chat/Provider/Conversation/Run/Settings。

**预计**：2–4 工程周，可与后续 scaffold 局部并行。

### 9.1 Behavior spec 格式

每个规格只描述可观察行为：

```text
spec_id
reference_build
device_os_provider_config
preconditions
input
observable_output
state_transition
persistence_effect
error_cancel_recovery_behavior
timing_tolerance
allowed_variance
fixture_hash
```

Track B 的规格严禁包含旧源码片段、私有变量名、内部测试实现或可反推出具体实现的 patch。非确定输出使用 deterministic mock、canonical JSON、性质断言或 metamorphic relation。

### 9.2 可靠性底座必须先验收

这些能力已经有 Amber 实现，但此前“Implemented”不等于真实环境“Verified”。在其下方替换存储/runtime/provider 前必须先证明：

| 底座 | 要保住的语义 | 最小完成证据 |
| --- | --- | --- |
| SecretStore | Keystore/Keychain 中真实值，配置中只有 ref/mask | 锁屏、升级、key invalidation、迁移中断重跑；真实 provider/MCP |
| Tool Effect Ledger | `Prepared → Started → Finished/OutcomeUnknown` | 各落盘边界故障注入，不重复不可逆副作用 |
| Typed terminal | `WAITING_USER/CANCELLED/FAILED/STEP_LIMIT/COMPLETED` 不互相冒充 | 持久化、重启和 UI 投影一致 |
| Run ownership | stop/cancel/notification 只影响目标 run | 并发 run、进程重启和 scoped cancel |
| checkpoint/queue/token fit | 不丢 steer/mailbox/pending message，不越预算 | kill/relaunch 与最终一次 fit |
| production canary | stream→tool→approval→effect→result→next turn→terminal | 可定位到真实默认入口，而非 dormant Kernel |
| 数据兼容冻结 | DB、settings、backup、intent/JNI 兼容面不被误删 | schema/fixture/upgrade matrix |

### 9.3 必需规格集

- Chat：send、stream、queue、steer、stop、retry/regen、edit、fork、select variant、delete。
- Tool：parallel/sequential calls、approval、deny、cancel、outcome unknown、resume。
- Provider：model list、text/reasoning、multimodal、usage、malformed stream、EOF、HTTP error、cancel。
- Provider-family：官方 OpenAI、OpenAI-compatible、Claude、Gemini/Vertex/OAuth、image generation/edit、TTS 各自至少一条 request/response/error spec；不能用 OpenAI text fixture 代表全部。
- Persistence：checkpoint、stale write、large message node、branch selection、delete-vs-background-write。
- Settings/credentials：redaction/rehydration、unknown provider/auth、backup/restore。
- Lifecycle：background submit/adopt/expiration、notification deep link、kill/relaunch。
- Domain canaries：Memory、MCP、Skills、Workspace、Council、MiniApp、Novel 各一条真实生产消费者链。
- Entrypoints：前台 Chat、FGS、Cron/Worker、MiniApp、Session/History、share/OAuth/restore、各 runner/orchestrator 的 owner/cancel/persistence/recovery 规格。

### 9.4 验收

- 每个待替换组件至少有一条 normal、一条 error/cancel、一条 recovery spec。
- fixtures 不含密钥、真实用户对话、随机 UUID/时间或旧源码。
- Android/iOS 对共享语义使用同一 fixture schema，但测试代码和平台生命周期保持各自所有。
- 未通过可靠性门的能力不得进入删除旧实现的 Phase；可以继续做 façade，但不能切默认路径。

### 9.5 回滚

规格和 fixtures 版本化。发现规格抄入实现细节时 quarantine 并重新生成；不得让 Implementation Team 继续基于污染输入开发。

## 10. Phase 3：中立 contract、Golden Fixtures 与反向依赖切断

**目标**：建立最窄的 Amber-owned seam，让两端逐渐停止传播 Rikka 形状的 central DTO。

**前置**：P2 首组 Chat/Provider/Conversation fixtures 冻结。

**预计**：3–6 工程周。

### 10.1 模块落点原则

第一版 contract 不直接放根 `core/`。推荐先在两端各自建小型 app-owned contract/adapter，或使用明确的孵化目录；当 Android/iOS 两个真实消费者通过同一 fixtures 后，再按 `docs/current/CORE_BOUNDARY.md` 提议抽取。

禁止把当前 Android `:ai` 直接当中立 core：它依赖 Android/Compose/OkHttp；也禁止把 iOS `ai-core` 原样提升，因为其中仍有高相似类型和 Swift bridge 形状。

### 10.2 第一批 contract

- provider config/model descriptor；
- conversation message/content part；
- normalized stream event/terminal result/error；
- tool declaration 与 tool outcome；
- conversation/node/variant wire；
- pure stream reducer；
- JSON fixture schema 与 canonical comparator。

平台外置：credential backend、OAuth、HTTP/SSE client、UI strings、closures、Room/UserDefaults、background/cancel handle。

### 10.3 Golden fixture 最小矩阵

1. text + empty delta；
2. reasoning start/delta/end 与 text 混合；
3. tool arguments 分片、late id、parallel index、index 0 reuse、replace/done；
4. usage-only final chunk；
5. citation/annotation 去重；
6. 多图/多模态与无 identity 图片；
7. OpenAI Chat `[DONE]` 与 OpenAI Responses completed/incomplete/failed；
8. Claude thinking/tool-use/tool-result/message stop；
9. Gemini functionCall/finishReason/thought metadata；
10. malformed JSON、EOF、cancel、HTTP error、重复 terminal；
11. Conversation 全 variants、selected id/index、unknown fields；
12. Settings/provider/auth unknown subtype 与 credential ref。

### 10.4 接入顺序

1. 旧实现实现新接口，先作为 adapter，不改默认生产 owner。
2. pure reducer 同时在 Android JVM 和 iOS/KMP 或 Swift fixture runner 执行。
3. 新 consumer 只能依赖 contract；legacy adapter 可以反向依赖旧类型。
4. 添加静态依赖守卫，阻止新 domain 继续 import 旧 `UIMessageChoice`、`ProviderSetting`、`ModelDsl` 等。
5. 两端真实消费稳定后，再决定是否抽入根 `core/`。

### 10.5 验收

- 相同 canonical input 在两端得到相同 normalized events、reducer snapshot 和 terminal。
- Tool ID/index 不串线；usage、reasoning、citation、多模态不丢失。
- shared contract 不出现 Android/iOS 平台类、密钥、网络 client 或 UI lambda。
- 至少一个 Android Chat canary 和一个 iOS `IOSAgentTextProvider` consumer 实际通过 seam。
- 未新建第二套未消费的“通用 Agent Kernel”。

### 10.6 回滚

新 seam 可切回 legacy adapter，且此 Phase 不改变 canonical 数据格式。若 contract 需要频繁 platform exception，停止抽取并把差异留在平台 adapter，而不是扩充万能接口。

## 11. Phase 4：Provider、Model、Message 与 Stream 实现替换

**目标**：依据公开 provider 协议和 P2/P3 fixtures，替换高相似 `ai` substrate；保留 Amber 自有 approval/ledger/runtime 语义。

**前置**：P3 contract/reducer 可被两端消费。

**预计**：6–10 工程周，按 provider family 独立交付。

### 11.1 Android 当前热点

- `ai/src/main/java/app/amber/ai/provider/Provider.kt`
- `ProviderSetting.kt`、`Model.kt`、`registry/ModelDsl.kt`
- `ai/ui/Message.kt`、`MessageStreamAccumulator.kt`
- OpenAI/Claude/Google provider、wire DTO、SSE/parser/termination guard
- `ProviderManager.kt`
- `app/.../GenerationHandler.kt` 的 provider-facing 边界

### 11.2 iOS 当前热点

- `apps/ios/ai-core/src/commonMain/.../provider/*`
- `Message.kt`、`MessageStreamAccumulator.kt`
- `ai-provider-openai/.../OpenAIKmpProvider.kt`
- `ai-provider-claude/.../ClaudeKmpProvider.kt`
- native `IOSGeminiProvider.swift`、`IOSAgentToolEngine.swift` 的 contract bridge

### 11.3 迁移批次

#### P4.1 Model/config compatibility

1. 旧 `ProviderSetting` JSON 继续由 legacy decoder 读取。
2. decoder 输出中立 `ProviderConfig` + platform auth config；未知 provider/auth 保存为 opaque，不默认降级成 OpenAI/API key。
3. 新 model catalog 不复用旧 `ModelDsl` 的结构/命名；只实现已由 fixture 和真实消费者证明的匹配规则。
4. Android Compose 描述、Context、Keystore 和 iOS description text、Keychain/OAuth 都留在平台层。

#### P4.1b Provider-family registry

- 把 OpenAI-compatible endpoint 视为独立 family policy，而不是默认等同官方 OpenAI；DeepSeek、Mistral、xAI/Grok、自定义 endpoint 分别记录 capability override、header/body 和错误差异。
- Vertex、Gemini Code Assist、Codex/Coding Plan、Antigravity 等 OAuth/auth mode 映射留在平台 registry。
- image generation、image edit 与 TTS 有独立 request/asset/lifecycle contract，不伪装成 text stream。

#### P4.2 Reducer first

先让旧 providers 输出 normalized `StreamEvent`，由新的 `ConversationStreamReducer` 形成结果。旧 `MessageStreamAccumulator` 暂时作为 oracle；达到行为门后再删除。

#### P4.3 OpenAI Chat Completions

- 依据 OpenAI 公开协议独立实现 request/response/SSE adapter。
- 验证 headers/body、text/reasoning、tool calls、usage、finish/error/cancel。
- Codex/Coding Plan OAuth、API key rotation、custom body 保留在平台 adapter，不进入 contract。

#### P4.4 OpenAI Responses

- 单独建 adapter，不把 Responses 事件硬塞进 Chat Completions DTO。
- stored response id/cursor、resume 和后台 transport 是平台 capability。
- cursor 落盘前后、completed/incomplete/failed、duplicate terminal 分别有 fixture/故障注入。

#### P4.5 Claude

- 覆盖 `message_start`、content block、thinking、`input_json_delta`、tool use/result、message stop、prompt-cache metadata。
- EOF before terminal 与 provider error 不得静默当成功。

#### P4.6 Gemini/Google

- 单独映射 `functionCall`、finish reason、thought/signature metadata。
- Android Google OAuth/Vertex 与 iOS Gemini/Antigravity 保持平台差异。
- 生成稳定 stream-scoped tool call id；不能用 OpenAI 假设强行适配。

#### P4.7 Image generation/edit 与 speech

- 冻结 image input/reference/mask/size/quality、binary/file ownership、progress/cancel/error 语义。
- Android/iOS 已有不同 native/provider 消费者时保留平台 adapter，不为统一而复制大文件到 shared contract。
- TTS 的 chunking/player/session/audio-focus 属于 Android/iOS 平台层；只有经双消费者证明的 request/voice descriptor 可进入中立 contract。
- 每个真实启用的 provider family 至少有一条 fixture 和一次真实 smoke；未启用/dormant 分支从 release matrix 移除，不能作为“已支持”。

### 11.4 每个 provider family 的完成门

**结构**：

- production 不再引用旧 wire DTO/accumulator/provider API；
- 新实现没有复制旧 package、内部命名、控制流或测试布局；
- shared contract 没有 auth/UI/platform transport；
- provenance ledger 标明 public-spec 来源、版本和人工 review。

**行为**：

- model listing、普通文本、reasoning、tool loop、usage、cancel、malformed/EOF/error 全部通过；
- Android `GenerationHandler` adapter 和 iOS `IOSAgentTextProvider` 各有 canary；
- mock fixtures 之外，至少一次真实 provider smoke 单独记录。
- P1 matrix 中每个 released family（OpenAI-compatible、OAuth、image generation/edit、TTS 在内）均有独立结论；不适用项明确移出产品范围。

**持久化**：

- 旧 provider/model/settings JSON 可升级；
- credential ref、custom headers、stored response cursor 不丢；
- fixture/log/error 无密钥。

### 11.5 回滚

- provider family 独立 feature flag，单个 run 从开始到结束固定 adapter version。
- 切回 legacy provider 时继续使用同一中立 run/conversation owner，避免双写。
- 删除旧 adapter 前保留一个已验证 release artifact 和旧 settings decoder；至少经过一个发布兼容窗口。

## 12. Phase 5：vNext 数据协议、双读单写与备份迁移

**目标**：让用户数据与 Rikka-derived Room/DataStore/KMP wire 解耦，同时保留同包升级能力和未知字段。

**前置**：P0 选定分发模式；P2 数据 fixtures；P3 conversation/settings contract。

**预计**：6–10 工程周。

### 12.1 当前 Android 事实

- Room 当前为 v15；真实会话由 `conversationentity` 元数据和 `message_node` 的完整 `messages` JSON/`select_index` 组成。
- `conversationentity.nodes` 生产写入固定为 `"[]"`，不能当真实节点源。
- `ConversationRepository.updateConversation()` 删除并重插所有 nodes；读取超大 Blob 时存在跳过行为。迁移器必须直接读原始行，坏行/超大行要失败并报告，不能复用静默跳过。
- `settings` 是一个 Preferences DataStore，Provider/Assistant/Search/MCP/WebDAV/S3/TTS 等保存为 JSON 字符串。
- typed Kotlin serialization 使用 `ignoreUnknownKeys`；直接 decode/encode 会丢未知字段。
- SecretStore 已用 Keystore AES/GCM；DataStore 应只有 mask/reference。
- `.amberbackup` Android archive v2 包含 settings、secrets refs、Room JSONL 和文件；iOS archive v1 不是同一协议。

### 12.2 当前 iOS 事实

- canonical conversation 当前是 `Documents/conversations/{id}.json`；`index.json` 只是派生索引。
- iOS Conversation 比 Android 多 `memoryMode`，Android 导入不得丢失。
- `IOSConversationStore` 具有 metadata owner/stale-write fence，应保留。
- settings 分布于 `SettingsStore`、`ProviderRegistryStore`、`IOSSharedSettingsStore`；凭据由 Keychain side table 持有。
- iOS archive v1 只有 settings 与可选 conversations zip，restore 跨文件并非单事务。

### 12.3 vNext envelope

不要直接把 Room table 或当前 KMP `Conversation` JSON定义成跨端协议。建议中立格式：

```json
{
  "$format": "amber.data",
  "$schema": "conversation",
  "$schemaVersion": 1,
  "$id": "stable-id",
  "$source": { "platform": "android", "storage": "room-v15" },
  "data": {},
  "extensions": {},
  "opaque": {}
}
```

规则：

- container/archive version 与 dataset schema version 分离；
- known field 更新后 merge 回原始 JSON，未知成员原样保留；
- Conversation 保存 node id/order、完整 variants、`selectedVariantId` 和 legacy `selectIndex`；
- UIMessage/part 未知 subtype 保存 raw JSON；
- Android-only `council_state`、iOS-only `memoryMode` 进入明确的 extension/opaque 区；
- credential 只保存 stable logical ref、owner、field、mask、availability；不复制真实 Keystore/Keychain value。

### 12.4 状态机：legacy → shadow → vNext

```text
legacy canonical
  → read-only shadow export to staging
  → digest + semantic comparator
  → commit marker written last
  → vNext reader first / legacy fallback
  → all new writes only to vNext
  → legacy becomes read-only projection
  → compatibility window
  → delete legacy writer, then reader, then storage
```

建议 marker 字段：state、migrationId、sourceDigest、vnextDigest、legacySnapshotPath、generation、minReaderVersion。

### 12.5 Android 批次

#### P5.1 Raw inventory/fixtures

- Room v15 表/列、原始 DataStore keys、SecretStore refs、backup v1/v2。
- fixtures 覆盖多 variants、坏 selectIndex、大 Blob、未知 provider/part、附件、`council_state`、iOS `memoryMode`。

#### P5.2 Shadow export

- 原始 SQLite cursor 读 conversation/message_node；不经会跳过大 Blob 的 repository。
- DataStore 先保留 raw JSON，再投影 known fields。
- 写到独立 staging；不改 Room/DataStore。
- compare source/vNext digest、known semantics、branch selection、attachment hash、credential refs。

#### P5.3 Dual-read / one-way-write

- vNext reader first，legacy reader fallback。
- Chat/Conversation/Settings 写入口只写 vNext。
- Room FTS/summary/stats 可暂作 projection，但只能从 vNext commit 重建，不能反向覆盖。
- kill/relaunch 时根据 marker 恢复 shadow/rollback_pending。

#### P5.4 Neutral backup

- Android v1/v2、iOS v1 只作为 read-only importer。
- 新 archive 使用独立版本化 datasets：settings、conversations、attachments、credential refs、opaque/platform、manifest；先保证同平台旧→新可恢复。
- 全部 payload 解密/校验/staging 后一次 commit；缺必需 dataset 必须拒绝。

### 12.6 iOS 批次

- 新 canonical 可放 `Documents/amber-data-vnext/`，旧 `conversations/` read-only fallback。
- `IOSSharedSettingsStore` 先变 vNext adapter；旧 UserDefaults/ProviderRegistry 只读兼容。
- Keychain 继续是真凭据 backend，缺失时保留配置并显示 credential unavailable。
- `index.json`、previews、icons 均从 canonical vNext 重建。
- restore 必须通过 storage owner + staging，不得绕过 mutex 直接写文件。

### 12.7 迁移验收

- 全 variants、selection、unknown fields/subtypes、attachments、credential refs round-trip。
- 未知 provider 不静默降级；大/坏 row 明确失败且 source 未改。
- migration 可中断、可重跑；commit marker 最后落盘。
- legacy snapshot 可恢复；关闭新 reader 不删除 vNext 数据。
- Android/iOS 各自的新 archive 能读本平台旧格式，旧 archive 只读导入行为有明确矩阵。
- **可选 M4x**：只有产品明确需要跨平台迁移时，再要求 Android/iOS 对同一中立 subset 互读；opaque platform data 和凭据不因此强制跨端。M4x 失败不得阻塞“脱离 Rikka”主线。

### 12.8 回滚的真实限制

one-way-write 后，旧版本不会自动看到 vNext 期间的新写入。回滚必须三选一：

1. 回到迁移前 legacy snapshot，明确丢弃 vNext 期间增量；
2. 运行经过验证的 vNext→legacy reverse exporter；
3. 要求升级到支持 vNext 的最低 reader version。

不能用“把 flag 关掉”掩盖数据可见性差异。旧 schema 删除前至少保留两个发布周期或产品明确批准的 90 天兼容窗口，二者取更严格者。

## 13. Phase 6：Android Chat/Runtime Strangler

**目标**：不重做产品能力的前提下，把真实默认生产控制权从继承的 `ChatService`/`GenerationHandler` 巨型编排器迁到 Amber-owned façade、run coordinator 和 stores。

**前置**：P3 contract；P4 至少一个 provider；P5 stores 可由 adapter 接入；P2 可靠性门通过。

**预计**：8–12 工程周。

### 13.1 当前真实入口与旁路

必须以此作为迁移起点：

- `AmberAgentApp.kt`：Koin、settings/secret migration、Room、durable recovery、skills、scheduler bootstrap。
- `RouteActivity.kt` → `ChatPage.kt` → `ChatVM.kt`。
- `ChatService.sendMessage` → pending loop → `handleMessageComplete` → `GenerationHandler.generateText` → `ProviderManager`。
- `useKernelPath = false`；Agent Kernel 不是默认生产 owner。
- ChatPage/DeepRead/Markdown 等 split flags 多数默认关闭，不能作为迁移完成证据。
- `SearchAggregator` 仍被 MiniApp 消费，不能因 Chat 已走 `SearchOrchestrator` 就直接删除。
- P1 全入口 manifest 还必须覆盖 `AgentGenerationForegroundService`、`AgentCronWorker`/其他真实 Worker、MiniApp runner、Session/History、notification receiver、share/OAuth/restore 以及 DeepRead/Council/SubAgent/Board/Novel 等 runner/orchestrator；前台链只是第一条切片，不代表全盘完成。

### 13.2 最小目标边界

```text
ChatFacade
  → ConversationStore
  → RunCoordinator
       → GenerationEngine
       → ProviderGateway
       → ToolGateway
       → RunStore / EffectLedger
  → ChatProjection
```

这些边界只服务当前生产链，不另建通用 runtime framework。

### 13.3 切片顺序

#### P6.1 Production manifest and adapter shell

- 过渡期允许 `ChatService` 继续作为 UI-facing 薄 wrapper，但其内部第一步转到 `ChatFacade`，且不得再拥有 run/DB/provider。
- 旧 `GenerationHandler` 先实现 `GenerationEngine` adapter；不改行为，不改 Room。
- 为每个 run 固定 runtime/adapter version，避免中途切 flag。
- 加一条无外部副作用的 canary，比较 normalized events/terminal；不双写 Conversation。

**Gate**：普通 text stream、cancel、provider error 与旧路径等价；新路径可一键关停。

#### P6.2 Conversation owner

- 用 `ConversationStore` adapter 包住现有 `ConversationRepository`/DAO。
- UI 和 runtime 不直接写 DAO；Conversation 与 Run 状态分开。
- 先保持 Room v15，不在同一 PR 改 schema。
- 覆盖 header/window load、append user、assistant checkpoint、branch/regen/delete/search。

**Gate**：长会话分页、variants/select、stale write、delete-vs-background checkpoint、kill/relaunch。

#### P6.3 Run owner and terminal

- `RunCoordinator` 统一 begin/pause/wait/finish/recover。
- 复用现有 `RunOwnershipRegistry`、RunTerminal/Resume stores、Tool Effect Ledger；不另写第二套 ledger。
- UI coroutine/notification 只投影状态，不成为事实源。

**Gate**：`WAITING_USER`、`CANCELLED`、`FAILED`、`STEP_LIMIT`、`COMPLETED` 持久化互斥；scoped cancel 不跨 run。

#### P6.4 Generation loop

- 新 coordinator 接管 provider stream、tool approval/effect/result、next turn 和 final token fit。
- `GenerationHandler` 从默认调用链退为 legacy adapter。
- 任何外部副作用只执行一次；shadow 只比较 pure normalization/reducer。

**Gate**：完整 canary `stream → tool → approval → effect → result → next turn → terminal`，并在每个 effect 落盘边界故障注入。

#### P6.5 UI entry cutover

- `ChatVM` 只消费 `ChatFacade`/projection，不直接知道 provider、MCP transport、WorkspaceManager、SecretStore。
- `ChatPage` 保持现有 Compose 行为，不在此批次 redesign。
- stop/regen/edit/fork/share/attachment/notification deep link 逐项切换。

**Gate**：`RouteActivity → ChatPage → ChatVM → ChatFacade` 成为前台唯一默认入口；`ChatService` wrapper 随后删除，或仅作为有 owner/到期版本的 migration adapter。P1 全入口 manifest 中的 FGS、Worker、MiniApp、通知、Session/History 和各 runner/orchestrator 也必须改依赖同一组新 owners，不能继续绕回旧 ChatService/GenerationHandler。

### 13.4 重点文件/模块

- `app/src/main/java/app/amber/core/service/ChatService.kt`
- `app/src/main/java/app/amber/core/ai/GenerationHandler.kt`
- `app/src/main/java/app/amber/feature/ui/pages/chat/*`
- `app/src/main/java/app/amber/core/repository/ConversationRepository.kt`
- `app/src/main/java/app/amber/feature/runtime/*`
- `core/ai/generation/api`、`core/ai/api`、`core/model`
- Room entities/DAO 与 DI modules，仅在对应切片需要时修改

### 13.5 验证

每个切片优先复用现有：

- generation/runtime/production-chain canaries；
- Conversation repository/branching/large-node tests；
- Tool Effect Ledger、Run Terminal、Run Ownership、Resume tests；
- 相关 JVM tests，然后 `:app:compileDebugKotlin`；到切默认 owner 时扩大到 `assembleDebug`。

最终另行执行真机安装启动、真实 provider、MCP approval、后台/通知、kill/relaunch；这些不得由 JVM/compile 代替。对全入口 manifest 逐项记录 `migrated / intentionally removed / partial-dead confirmed`；没有 owner 的入口会阻塞 P10 删除。

### 13.6 回滚

- 每个切片一个 flag/adapter boundary，不以全局 `newRuntime=true` 粗粒度切换。
- 回滚只切 owner，不回写/降级 schema；数据按 P5 marker/reader 规则处理。
- 连续稳定窗口前保留 legacy adapter；切流失败先恢复旧 owner，再分析 divergence，不静默合并两个结果。

## 14. Phase 7：Android Tools、Domains 与高继承叶子模块

**目标**：让 Amber 自有业务不再依赖继承的底层实现，同时优先替掉指纹最高、边界最清晰的叶子模块。

**前置**：P6 ChatFacade/GenerationEngine/Store seam 稳定；P4 credential/provider seam。

**预计**：6–10 工程周，可按叶子并行。

### 14.1 先保留 Amber-owned domain

以下不做业务重写，只迁依赖：Memory、MCP 安全治理、Skills promotion/rollback、Workspace mirror/artifact、Tool Effect/Run、Board、MiniApp、Council/SubAgent、Novel。

### 14.2 Domain 接缝顺序

#### P7.1 MCP

- 建立 transport-neutral `McpGateway`；GenerationEngine 不认识具体 client/transport。
- OAuth token 通过 CredentialVault/SecretStore；header/token 不进入 prompt/log/backup。
- 保留 `mcp__server__tool` 和旧 `mcp__tool` alias 的有期限兼容。
- 验证 SSE/Streamable HTTP、cold OAuth、reconnect、namespace collision、import preview→approve→apply。

#### P7.2 Search

- Chat 默认继续使用 Amber `SearchOrchestrator`；MiniApp 的 `SearchAggregator` 作为明确 adapter。
- 替换 `api.rikka-ai.com`，配置迁移失败必须显式暴露。
- 重写/替换 inherited provider services；保留 citation、URL dedupe、image budget、partial failure、WebView fallback 语义。

#### P7.3 Memory

- 仅通过 ConversationStore 读取 conversation；recall 不直接写 Chat。
- 保留候选、ranking/budget、CAS、provenance、Dream/auto extraction。
- 验证 sensitive memory、stale write、kill/relaunch 与候选持久化。

#### P7.4 Skills

- 保留 manifest allowlist、path/symlink、secret/private key/mcp.json 阻断、promotion atomicity、rollback。
- 迁移后旧 SKILL.md 仍可读；不把旧 skill loader 代码原样提升到共享 core。

#### P7.5 Workspace

- 保留 SAF、`/workspace` 约束、POSIX mirror、share/upload、artifact registry。
- 工具只依赖 Workspace port；platform SAF/files 仍在 Android。
- Novel Workspace 在当前 WIP 稳定后最后只切依赖，不重写 ghostwrite/ledger/undo。

#### P7.6 Backup/Sync

- Backup 只通过 vNext stores 导出，不直接扫描业务对象。
- 保留 legacy archive reader、verify→apply 两阶段、passphrase/device binding、path traversal 防护。
- WebDAV/S3/Drive provider 分别记录真实服务 smoke；不能用 archive unit test代替。

### 14.3 高继承叶子替换优先级

| 批次 | 当前指纹 | 处置策略 |
| --- | ---: | --- |
| `tts` | 84.37% shingle overlap | 先冻结 TTS contract，再独立实现 player/chunker/provider 或换合规 SDK |
| `common` | 74.72% | 删除无必要 generic helpers；其余依据标准库/公开协议小步重写 |
| `document` | 67.06% | parser 与 MuPDF 第三方边界分开；第三方许可代码不伪装原创 |
| `search` | 63.07% | 按 provider 一个 PR 重写，先切旧 Rikka endpoint |
| `ai` | 41.50% | 由 P4/P6 完成，不在本 Phase 重复实现 |
| `highlight` | 2.05% | 先核对真实 provenance，不因目录历史假定必须重写 |

### 14.4 典型 replacement ledger 热点

- TTS：`AudioPlayer.kt`、`TtsController.kt`、`TtsSynthesizer.kt`、`TextChunker.kt` 与 provider implementations。
- Common：`AcceptLang.kt`、SSE/JSON/cache/FileIO helpers。
- Document：Docx/Epub/Pptx parsers；MuPDF 单列 third-party-cleared。
- Search：`SearchService.kt` 与各 provider service。
- App exact/near-exact：permission state、export serializer、MCP OAuth client、Favorite/Files repositories、rich text/Assistant 页面等，按消费链而非文件名批量替换。

### 14.5 验收与回滚

- 每个 domain 只有一个 writer/side-effect owner；按 domain flag 回滚。
- 高继承模块逐个通过 unit/compile/consumer canary 后删除旧实现。
- Novel 当前 WIP 未被触碰；对其仅有接口迁移 PR，且在 owner 合并后进行。
- Search/TTS/document 真实输入、错误、取消和资源释放验证；不为替换而扩大无关测试矩阵。

## 15. Phase 8：Android UI 壳、依赖、素材、品牌与打包边界

**目标**：在生产控制链稳定后，清除可见/打包层残留并建立可持续发布门禁。

**前置**：P6 默认 Chat path 已切换；P7 主要 leaf consumers 已迁移。

**预计**：4–8 工程周，许可证调查时间另计。

### 15.1 Application/UI 壳

- `RouteActivity` 最终只负责导航/deep link；`AmberAgentApp` 只负责 bootstrap coordinator。
- Chat/Settings/History/Backup/Assistant 的 inherited UI 文件按页面行为 spec 小步替换。
- 不追求像素差异本身；目标是新的 information architecture/design tokens/组件来源有独立设计依据。
- Share、shortcut、OAuth callback、notification deep link、backup restore、MCP callback、file picker 必须保留生产入口。

### 15.2 直接 Rikka 依赖退出

依赖逐个做“功能 contract → 独立替代 → consumer verify → 删除坐标”：

1. markdown fork；
2. sqlite-android fork；
3. jlatexmath-android fork；
4. hugeicons-compose fork。

可以选择经审查的独立上游、不同合规依赖或 Amber-owned 实现。仅将现有 fork 重新发布到 Amber Maven group 不构成脱离；若暂时保留，必须标记为过渡并继续 NOTICE/attribution。

### 15.3 品牌与资产

- 删除 orphan `rikkahub.svg`，重新创作 `amberagent.svg`。
- 同步替换 launcher、debug、Graphite、notification、splash 等变体。
- 对 62 个 byte-exact 资源逐项分类：第三方 cleared、重新创作、删除。
- Provider logos、JetBrains Mono、emoji data、banner、drawable、字典各自记录 origin/version/hash/license/allowed use。
- `AIIconMatcher` 的 `rikka` alias 先核对真实兼容调用方；有期限迁移后再删，避免把假阳性当产品 bug。

### 15.4 第三方发布物

建立四类 inventory/SBOM：

- Gradle runtime/compile dependencies；
- Cargo/native libraries/tree-sitter grammars；
- Bun/npm/web-ui/generative-libs；
- bundled assets/fonts/dictionaries/provider logos/Alpine/proot/MuPDF/MNN。

MuPDF 的 Artifex 版权/AGPL-commercial 条款、内嵌 Alpine/proot/libtalloc、MNN submodule、native `.so` 必须单独审查。它们不是 Rikka app source，也不能因“不是 Rikka”就跳过发布义务。

### 15.5 About/NOTICE

- About 不再跳到 RikkaHub 主页或其 LICENSE 作为当前产品来源页。
- 添加本地 Third-party notices/SBOM view；历史派生和必要 attribution 放在 provenance/legal 文档中。
- 在权利链未确认前，不擅自把现有分段 AGPL/商业 license 改成 Amber 独占许可证。

### 15.6 打包验收

- resolved dependencies 无未批准 Rikka coordinate。
- source 与 APK/AAB 解包扫描无未批准 `rikkahub`、`rikka-ai`、`me.rerere.rikkahub`、旧 URL、旧 endpoint、旧 asset hash。
- 资源/字体/native/JS 组件无 unknown-license blocker。
- release packaging、安装、启动分别验证；编译通过不等于发布完成。

### 15.7 回滚

依赖、页面、asset family 分批提交；保留旧 release artifact。若替换库行为不兼容，回滚单一 consumer/coordinate，不恢复 Rikka endpoint 或伪装来源。许可证不清晰的资源 quarantine，不以临时换名发布。

## 16. Phase 9：iOS Follow-on——保住 native 产品层，移除 Shared 派生面

**目标**：Swift 生产代码从 `Shared.framework` 的高相似 KMP 类型解耦；保留已验证的 iOS UI、持久化、后台、恢复和系统能力。

**前置**：P3 contract/fixtures 稳定；P4/P5 的 wire 和 migration 规则已冻结。

**预计**：8–14 工程周，可与 Android P6–P8 部分并行。

### 16.1 iOS 不重写的 owner

以下继续 iOS-owned：

- SwiftUI/UIKit、navigation、ViewModel 与 native chat timeline/projection；
- `IOSConversationStore` 的 actor/single-writer/stale-write fence；
- UserDefaults、Keychain、credential side table；
- `BackgroundGenerationKeepAlive`、Chat BG task、Live Activity、Watch、notification；
- `IOSDurableRunStore`、approval/recovery/cancel ownership；
- WebView、媒体/OCR、权限与 native framework link。

它们可以迁移 contract 类型，但不借机重写 UI 或生命周期。

### 16.2 I0：冻结 Shared 消费图

产物：

- 32 个 exports 清单；
- 93 个生产 Swift Shared imports、全 iOS 167 个；两者都包含普通 `import Shared` 与 `@preconcurrency import Shared`；
- 符号级 runtime consumers、import-only false positives、tests、link-only 分类；
- `Shared.framework` headers/build inputs/hash、`project.yml` build/embed/link 入口、`otool -L` 基线。

优先 runtime consumers：

- `ChatGenerationCoordinator.swift`
- `IOSChatBackgroundGenerationCoordinator.swift`
- `IOSAgentToolEngine.swift`
- `ChatViewModel.swift`
- `CouncilRunner.swift`、`SubAgentRunner.swift`
- `NovelCreation/NovelLiveModelAdapter.swift`
- `IOSBoardPersistence.swift`、`IOSContextCompactionCoordinator.swift`
- `IOSSharedSettingsStore.swift`、`IOSConversationStore.swift`、`IOSSyncBackup.swift`
- Provider resolver/config/detail/native adapters

**Gate**：每个 exported symbol 有 owner、consumer 和 target replacement；不能拿 import count 代替真实接线。

### 16.3 I1：iOS Golden Fixtures

建议在 iOS 平台目录建立 detachment fixture runner，内容复用 P3 schema，覆盖：

- message/part/usage/time/UUID；
- multimodal/reasoning/tool/parallel tool；
- stream merge、usage-only、finish/error/cancel；
- Conversation nodes/variants/select/pin/title/memoryMode；
- Settings provider/auth/model/council/redaction；
- OpenAI/Claude/Gemini headers/body/events；
- background cursor/terminal 的纯数据部分。

先用当前 KMP 作为临时 oracle 生成并人工批准语义 fixture；Track B 则由 Specification Team 输出，Clean Implementation Team 不接触 KMP 实现。

### 16.4 I2：窄 `IOSCoreFacade`

第一阶段：

```text
Swift production
  → IOSCoreFacade
    → IOSKMPAdapter
      → Shared.framework
```

目标阶段：

```text
Swift production
  → IOSCoreFacade/native contracts
    → native provider/storage/settings adapters
      → optional neutral root core only
```

Facade 候选只包含：native message/part/chunk、conversation/node/summary、provider/model/auth references、settings codec、conversation wire、`IOSAgentTextProvider`。Swift 生产代码不再直接暴露 `MessageKt`、`ProviderSettingKt`、`ModelDslKt`、`SettingsKt`、`JsonConversationStorage`、`OpenAIKmpProvider` 或 `ClaudeKmpProvider`。

**Gate**：除一个临时 adapter package 外，新增 Swift 代码禁止 `import Shared` 或 `@preconcurrency import Shared`。

### 16.5 I3：先迁叶子消费者

按风险从低到高：

1. headers/model config：`IOSProviderRequestHeaders`、`ChatProviderConfiguration`、ProviderRegistry、ModelDefaults、SeatEditor/Composer；
2. provider config/auth UI：Codex/Grok/Gemini resolver、ProviderConfigTool、ProviderDetail；
3. message projection/UI type bridge：CollectionMessageList、MessageProjection、ToolTimeline、MessageBubble、MiniApp message factory、RunRecovery；
4. settings/backup helper：Skill/MCP tools、PromptInjection editor、Sync backup helper。

每批只切类型和 adapter，不重做滚动、layout、background 或状态机。

**Gate**：每批删除对应直接 Shared symbol；native XCTest + build-for-testing 通过；UI snapshot/interaction 只覆盖实际类型迁移风险。

### 16.6 I4：Provider 与 runtime consumers

迁移顺序：

1. 保留现有 `IOSAgentTextProvider` seam。
2. 用 `OpenAIKmpProviderAdapter` 临时验证 façade。
3. 依据公开协议和 P4 fixtures 实现/接入新的 iOS OpenAI adapter。
4. 实现/接入 Claude adapter。
5. Gemini/Grok 保留已有 native transport，但改用 native/neutral message/model contract。
6. 依次切 `IOSAgentToolEngine`、`ChatGenerationCoordinator`、background coordinator、Council/SubAgent/Novel consumers。

必须保留：

- Swift 可捕获错误与明确 cancellation handle；
- tool approval/effect ledger/next turn；
- `uiOnly / submitted / adopted` 三态；
- stored response/cursor、后台 expiration 和 kill/relaunch 恢复；
- run owner 与终态单写者。

**Gate**：text→tool→approval→result→next turn→terminal 在 foreground/background 分别可定位；真实 provider 与系统后台证据单独报告。

### 16.7 I5：Native Settings codec

`IOSSharedSettingsStore` 暂时保留名字和 storage ownership，只替换 KMP `Settings` codec：

- 保持旧 UserDefaults key 和 JSON field/SerialName/default 兼容；
- API key 先进入 Keychain side table，再 redacted JSON 落盘；restore/clear 顺序不变；
- unknown provider/auth/model/council/custom model raw fields round-trip；
- Android-only auth tag 显式呈现，不静默降级；
- JSON 损坏明确诊断，不用空默认覆盖用户配置。

**Gate**：旧 settings fixture、Keychain missing、redaction/rehydration、backup restore、kill/relaunch 通过；UserDefaults/log 无真实 credential。

### 16.8 I6：Native Conversation wire/storage

- `IOSConversationStore` 保持 iOS-owned 生命周期、文件 owner、原子写、sequence fence、branch semantics。
- 接入 P5 vNext wire；旧 `{id}.json/index.json` 只读兼容。
- native canonical writer 切换前，先做 shadow parse/semantic compare；不双写两个 canonical conversation。
- backup/import 走 storage owner + staging。

**Gate**：新旧文件可读，full variants/selection/memoryMode/unknown fields round-trip；pin/title/partial update、index rebuild、crash recovery、kill/relaunch 通过。

### 16.9 I7：Model catalog/DSL

- 最后替换 `ModelDsl` 的 exact/regex/negative/token sequence 和 ability/modality/context window。
- native catalog 通过依赖注入供 Chat/Council/Novel 消费，避免继续扩大全局 registry。
- 未知模型/能力显式暴露，不静默套默认模型。

**Gate**：catalog fixtures 与真实 model list smoke 一致；consumer 不再引用 KMP generated model symbols。

### 16.10 I8：停止 Shared 导出和链接

只有下列静态门为零才执行：

```text
^(?:@preconcurrency )?import Shared$
MessageKt / ModelDslKt / ProviderSettingKt / SettingsKt
JsonConversationStorage / ConversationFile / KotlinUuid
OpenAIKmpProvider / ClaudeKmpProvider
Shared.framework
```

允许出现的位置仅限历史/provenance/migration 文档，不能在 app、tests、build scripts 和 final binary。

删除顺序：

1. Swift adapter 最后一个 Shared reference；
2. `iosApp/project.yml` pre-build、framework search、embed/link；
3. `shared/build.gradle.kts` iOS framework exports/links；
4. 仅服务 iOS bridge 的 source sets/modules；
5. 无任何消费者的旧 KMP modules。

不要因为 iOS 已脱离就删除 Android/JVM 仍在消费的模块；`AmberNative.xcframework` 也必须独立核对，不能随 `Shared.framework` 误删。

### 16.11 iOS 验证与回滚

每个子阶段按顺序报告：相关 JVM/common baseline（若仍适用）→ native XCTest → `xcodebuild build-for-testing` → Simulator install/launch → 真机 → 真实 provider → BG submit/adopt/expiration → kill/relaunch。

回滚 seam：

- provider 可退回 `IOSAgentTextProvider` 的 KMP adapter；
- settings/conversation 新 writer 未通过 round-trip/recovery 前保持 read-only/shadow；
- 删除 Shared 前保存 framework hash、Gradle inputs 和旧 release artifact；
- 回滚 façade/link，不修改或重写用户数据来“适配”旧版本。

## 17. Phase 10：双端切流、稳定窗口与旧实现删除

**目标**：在两个平台均由新 owner 运行后，按依赖逆序删除 adapters、flags、旧 storage 和旧实现。

**前置**：Android P6–P8，iOS I0–I7；P5 compatibility window 已定义。

**预计**：3–6 工程周 + 至少一个真实发布观察窗口。

### 17.1 删除顺序

1. 关闭 shadow comparator；保留审计结果，不再运行双路径。
2. 删除 legacy writer，保持 reader/importer。
3. 删除旧 ChatService/GenerationHandler/provider runtime 生产入口。
4. 删除过期 feature flags、DI binding 和 adapter。
5. 兼容窗口后删除 legacy local reader/storage projection。
6. 最后删除旧 archive importer；它应比旧本地 schema 至少多保留一个发布周期。
7. iOS 完成 I8，Android 清除旧 Maven/endpoint/assets。

### 17.2 连续稳定门

旧实现删除前，至少连续一个候选发布周期满足：

- 无 P0/P1 semantic divergence；
- 无数据丢失、branch 丢失、重复副作用或跨 run cancel；
- migration/recovery success 达到产品批准阈值；
- crash/ANR、latency、memory、provider error 不劣于批准预算；
- real provider/device/background/kill-relaunch 证据齐全；
- kill switch 未触发，且 rollback artifact 可用。

具体数值阈值由 P0 产品/SRE owner 固定，不能在切流后再调整以“通过”。

### 17.3 删除 PR 的硬要求

每个删除 PR 必须列出：

- 被删生产入口与最后 consumer；
- replacement commit 与验证证据；
- 数据/backup/credential compatibility status；
- flag/adapter 到期记录；
- rollback artifact/version；
- `rg`/dependency graph/binary scan 结果；
- 明确未验证的设备/provider/system surface（若有则不得合并删除）。

### 17.4 回滚

删除只在短小独立 PR 进行，不与 schema/UI 新功能混合。若必须恢复旧 binary，先按 P5 判断其是否能读当前数据；不能通过数据库 downgrade 或删除 vNext 文件强行回滚。

## 18. Phase 11：最终来源、许可证、相似性与发布验收

**目标**：证明当前 release artifact 达到本计划定义的 Track A 独立，而不是只证明源码能编译。

**前置**：P10 完成。

**预计**：2–4 工程周 + 法务/发布审批。

### 18.1 源码相似性 exit gate

在 clean release commit、无 dirty worktree 上使用 P1 同一脚本复跑。先将每个命中分类，再应用对应门：

| Provenance 类别 | 相似性门 | 其他硬证据 |
| --- | --- | --- |
| Rikka-derived、由 Amber 计划替换的 owned source | normalized exact 功能文件 = 0；文件 Jaccard ≥0.80 = 0；模块 shingle operational target ≤5% | replacement commit、spec/fixture、人工 review |
| public protocol/common vocabulary | 不因标准字段机械要求归零；从 owned-source 分数中按 P1 规则剥离 | 官方规格版本、独立实现 review |
| third-party-cleared | 不要求为了不同而重写；不得伪装成 Amber original | 原始 upstream、hash、SPDX、NOTICE、allowed-use |
| generated | 扫 generator/template 来源，不只扫输出 | generator version、input/hash、license |
| legacy importer/migration adapter | 可临时高相似，但必须路径隔离、只读、无新依赖、有 owner/到期版本 | 数据兼容证据和权利 review；窗口结束后删除 |

Rikka 自有/重命名品牌资源的 byte-exact 命中必须为 0；与双方共同依赖的独立第三方标准资产可以保持相同 upstream hash，但必须进入 third-party allowlist，而不是记作 Rikka allowlist。

exact-Rikka production blame 用于证明趋势和找漏项，不作为 Track A 的无条件“必须为 0”门。所有最终仍归因于 shared commits 的活跃行必须逐项落入上述类别并有审查结论；任何未分类命中都会阻塞发布。

这些是工程 release thresholds，不是侵权/不侵权的数学结论。若无关项目校准显示 5% operational target 产生系统性误报，只能由 P1 记录的审查流程调整，并保留前后结果。

### 18.2 静态/依赖 gate

运行时代码、resolved dependency、产品品牌资源和 binary 不再包含未批准：

```text
RikkaHub / rikkahub / rikka-ai
me.rerere.rikkahub
com.github.rikkahub
旧 upstream URL / endpoint / resource hash
旧 MessageChunk/UIMessageChoice/ProviderSetting/ModelDsl contract
```

`me.rerere.hugeicons` 等第三方 namespace 不能仅靠字符串判断；最终必须已经替换，或有独立 upstream/provenance/license allowlist。

`LICENSE`、`NOTICE`、provenance/审计文档、经批准的历史 migration fixture 可能因已批准的许可证/归属要求而必须出现 `RikkaHub`、旧 URL 或 copyright 文本；扫描必须按路径 allowlist 报告，不能为了达到字符串 0 而删除要求保留的 attribution。禁止的是未批准的生产 endpoint、依赖坐标、源码契约、品牌资源和用户可见来源冒充。

### 18.3 Binary/asset gate

- 解包 APK/AAB/IPA，扫描 classes/strings/resources/native libs/framework links。
- Android About 内置正确 NOTICE/SBOM；iOS app 不再链接 `Shared.framework`。
- Gradle/Cargo/Bun/npm/native/assets 的 source/version/hash/SPDX/notice 完整，无 unknown-license blocker。
- 新 Amber logo 与旧 hash 不同，所有 launcher/debug/notification/Graphite 变体一致。

### 18.4 行为/release gate

- Android JVM/compile/assemble、instrumented/设备安装启动；
- iOS native tests/build-for-testing、Simulator/真机；
- 两端真实 provider family：官方 OpenAI、OpenAI-compatible、Claude、Gemini/Vertex/OAuth，以及 released image generation/edit、TTS 的正常/取消/错误；
- MCP OAuth/approval、backup/restore、credential rehydrate；
- foreground/background、notification/deep link、expiration、kill/relaunch；
- vNext migration、old archive import、rollback/recovery exercise。

### 18.5 发布记录

每个 release 保存：

```text
release_id
source_root_commit
spec_revision
fixture_manifest_hash
provenance_ledger_revision
dependency_sbom_hash
license_review
migration_version
feature_flag_defaults
rollback_binary
verification_matrix
known_unverified_surfaces
```

### 18.6 完成声明

只有工程、数据、依赖/资产、binary、验证和权利 review 全部通过，才能将 Track A 标记 `Released`。`Implemented`、`Compiled`、`Tested in Simulator`、`Verified on device`、`Verified with real provider` 和 `Released` 必须分开记录。

## 19. 平台与目录所有权

| 内容 | Android owner | iOS owner | 可进入 root `core/` 的条件 |
| --- | --- | --- | --- |
| Chat UI/navigation | Compose/ViewModel/routes | SwiftUI/UIKit/ViewModel/routes | 不进入 |
| Provider transport/auth | OkHttp/Ktor/Android OAuth/Keystore | URLSession/Ktor bridge/Swift OAuth/Keychain | 不进入；wire semantic contract 可候选 |
| Stream merge | Android consumer/reducer | Swift/KMP consumer/reducer | 同 fixtures + 双生产消费者后可进入 |
| Conversation storage | Room/files/projection | files/index/actor owner | 不进入；wire model可候选 |
| Run persistence | Room/foreground/notification | durable files/BGTask/Live Activity | 不进入；terminal/event contract可候选 |
| Settings/credentials | DataStore/Keystore | UserDefaults/Keychain | 不进入；redacted wire 可候选 |
| Tool execution | Android function/tools/MCP transport | Swift tools/MCP transport | declaration/outcome/approval contract 可候选 |
| Background | WorkManager/FGS/notification | BGTask/UIKit expiration/ActivityKit | 不进入 |
| Memory/Sync/Council | Android domain implementation | iOS domain implementation | 两端语义稳定前不进入 |
| Fixtures | Android runner | iOS runner | schema/fixture files优先共享 |

根 `core/` 不是“暂存所有重复代码”的目录；若一个 contract 只有一个生产消费者，就留在平台孵化层。

## 20. PR/里程碑拆分

### 20.1 里程碑

| 里程碑 | 达成条件 | 可对外表述 |
| --- | --- | --- |
| M0 Baseline frozen | P0/P1 快照、ledger、WIP、决策齐全 | 已完成脱离基线，不代表代码已脱离 |
| M1 Behavior protected | P2 specs/fixtures/可靠性门通过 | 关键行为可回归 |
| M2 Neutral seam consumed | P3 两端各一真实 consumer | 中立 contract 已被验证，不代表旧路径已删 |
| M3 Providers independent | P4 各 provider family 通过 | Provider/stream 实现独立 |
| M4 Data independent | P5 vNext canonical + migration gate | 新数据写入独立，legacy 仍在兼容 |
| M5 Android cut over | P6 前台 Chat 与全入口 manifest 均完成 owner 迁移/处置 | Android 生产控制链独立 |
| M6 Android release boundary | P7/P8 依赖/叶子/品牌/SBOM 通过 | Android candidate 可进入独立发布验证 |
| M7 iOS cut over | I0–I8，final binary 无 Shared | iOS 派生共享面已退出 |
| M8 Legacy removed | P10 稳定窗口和删除完成 | 当前 production 无旧实现 |
| M9 Released | P11 全部 gates + 权利/发布批准 | 可使用 Track A 完成声明 |

### 20.2 推荐 PR 序列

```text
PR-00 baseline-freeze
      SHAs/status/WIP/decision/access matrix

PR-01 provenance-tooling
      ledger schema/source manifest/fingerprint/asset/dependency scanners

PR-02 behavior-contracts
      spec format + approved golden fixture schema

PR-03 reliability-gates
      only missing focused verification/fixes for secret/ledger/terminal/run/recovery

PR-04 neutral-contract-incubator
      minimal provider/message/stream/conversation contracts + two fixture runners

PR-05 provider-openai-chat
PR-06 provider-openai-responses
PR-07 provider-claude
PR-08 provider-gemini

PR-09 data-vnext-codec
PR-10 android-shadow-migration
PR-11 android-vnext-owner

PR-12 android-chat-facade
PR-13 android-conversation-run-owner
PR-14 android-generation-cutover

PR-15 android-domain-<mcp|search|memory|skills|workspace|backup>
PR-16 android-leaf-<tts|common|document|search>
PR-17 android-ui-shell
PR-18 android-dependency-assets-notice

PR-19 ios-core-facade
PR-20 ios-leaf-consumers
PR-21 ios-provider-runtime
PR-22 ios-settings-vnext
PR-23 ios-conversation-vnext
PR-24 ios-shared-removal

PR-25 legacy-writer-removal
PR-26 legacy-reader-adapter-removal
PR-27 final-provenance-release
```

序号表达依赖关系，不要求每项只对应一个实际 PR。每个 provider/domain 可进一步拆小，但不得把 schema、UI redesign、runtime owner 和大量删除混进同一不可回退 PR。

### 20.3 每个 PR 的必填模板

```text
phase / milestone:
production entrypoint and owner:
reference/spec IDs:
fixture manifest/hash:
source-access and provenance statement:
files/modules changed:
data/schema/credential impact:
focused verification performed:
device/provider/background evidence:
known unverified surfaces:
feature flag / rollback procedure:
legacy deletion condition and date:
unrelated WIP protected:
```

## 21. 验证矩阵

### 21.1 层级

| 层级 | 目的 | 何时运行 | 不能证明 |
| --- | --- | --- | --- |
| Static/provenance | namespace、坐标、依赖方向、hash、license | 每 PR/发布 | 行为正确 |
| Pure fixtures | contract/codec/reducer 语义 | P2 起每 PR | 平台生命周期/网络 |
| JVM/native unit | platform adapter 的定点行为 | 每切片 | 真实 provider/设备 |
| Compile/link | 依赖和生成 API 可用 | 每切片 | 运行时正确 |
| Integration/canary | 默认生产链与 owner | owner 切换前 | OS 后台/真实服务全部情况 |
| Simulator/emulator | UI/安装/基本 lifecycle | candidate | 真机/系统调度 |
| Device | 真实 Keystore/Keychain/notification/files | 切流/发布 | 所有 provider |
| Real provider/service | wire/auth/error/tool | provider/domain gate | kill/relaunch/OS adopt |
| Background/kill | submit/adopt/expiration/recovery | runtime/release gate | 许可证/来源 |
| Binary/SBOM | 最终包无旧依赖/素材且 notices齐 | release | 产品行为 |

### 21.2 Android 命令基线

从 `apps/android` 运行，按改动范围选择最小集合：

```bash
./gradlew :ai:testDebugUnitTest
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugKotlin
./gradlew :app:assembleDebug
git diff --check
```

具体 domain 使用其现有 module/test task；如果 task 名与当前 Gradle 配置不同，在 PR 中记录实际可运行命令，不创建重复测试 task 只为满足文档。

### 21.3 iOS 命令基线

从 `apps/ios` 运行相关 Gradle tasks：

```bash
./gradlew :ai-core:jvmTest
./gradlew :ai-provider-openai:jvmTest
./gradlew :ai-provider-claude:jvmTest
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

随后使用项目当前生成/构建流程运行 focused XCTest 和 `xcodebuild build-for-testing`。删除 Shared 后，上述 shared link task 应从“必须通过”转为“目标不再存在/不被 app 调用”，并以 native build/link 和 `otool -L` 取代。

### 21.4 故障注入点

- effect Prepared/Started/Finished 每个落盘边界；
- approval 已显示但 response 未落盘；
- provider cursor/terminal 落盘前后；
- Conversation checkpoint 与 delete/branch change 并发；
- migration staging 完成、marker 写入前；
- backup decrypt/verify/apply 各阶段；
- Keychain/Keystore 不可用或 key invalidated；
- background submitted 未 adopted、adopted expiration、process killed；
- notification deep link 到已结束/不存在 run。

只为真实高风险边界补最少测试；已有 canary/fixture 能稳定防回归时优先复用。

## 22. Canary、发布与回滚策略

### 22.1 推荐 rollout

每个可独立关闭的生产切片采用：local/internal → 受控测试账号 → 1% → 5% → 25% → 100%。百分比只是默认建议，真实样本量和停留时间由 P0/SRE owner 固定。

任何阶段出现以下情况立即停扩：

- 数据/branch/attachment 丢失；
- 重复 tool/provider/付费/通知副作用；
- terminal 或 owner 错配；
- migration 不可恢复；
- credential 泄露；
- crash/ANR 或 latency 超过预设预算；
- provenance/license blocker。

### 22.2 Feature flag 约束

每个 flag 必须有组件作用域、safe default、kill switch、owner、创建/过期版本和删除 issue。flag service 不可用时，不得默认打开未经验证的新 writer/side-effect path。

### 22.3 数据回滚

- canonical owner 切换前保存不可变 legacy snapshot。
- rollback binary/version 与 `minReaderVersion` 一起记录。
- 不支持 schema downgrade；用 reader compatibility、reverse export 或恢复 snapshot。
- 外部不可逆 side effect 用 idempotency key/补偿记录，不靠重放旧 run。

## 23. 风险登记

| 风险 | 影响 | 预防/侦测 | owner gate |
| --- | --- | --- | --- |
| 把 rebrand 当重写 | 过早宣称独立 | provenance + normalized fingerprint | P1/P11 |
| Track B 的 source-access 隔离不足 | 最终流程表述/证据不被批准 | access matrix、独立人员/仓库、法务 review | P0 |
| 许可证被误删或误换 | 发布/合规风险 | rights-chain review/NOTICE/SBOM | P0/P11 |
| 双 writer/双副作用 | 数据分叉、重复执行/付费 | single owner、pure shadow only、ledger | P5/P6 |
| 未知 JSON/subtype 丢失 | 配置/会话不可逆损坏 | raw merge/opaque/golden fixtures | P5 |
| variants/select 丢失 | 会话分支数据丢失 | raw `message_node` migration | P5 |
| 旧版本不能读 vNext | 回滚后用户看不到新数据 | marker/min reader/snapshot/reverse export | P5/P10 |
| Provider 协议漂移 | stream/tool/error 回归 | per-provider fixtures + real smoke | P4 |
| iOS 删除 Shared 误伤 native link | app link/build/runtime 失败 | symbol inventory/otool/staged removal | I0/I8 |
| Dormant Kernel 被误当主链 | 验证假阳性 | production manifest/default flags | P2/P6 |
| 当前 Novel WIP 冲突 | 覆盖用户工作、基线污染 | exclusion manifest/Novel last | 全程 |
| 大规模一次性重写 | 无法定位/回滚 | vertical slices/single owner/short PR | 全程 |
| 第三方资源被误算 Amber 原创 | 商标/许可证风险 | per-asset origin/hash/allowed use | P8/P11 |

## 24. 粗略工作量与团队建议

这是容量估算，不是发布日期承诺：

- Track A：约 40–60 engineer-weeks；4–6 名熟悉两端和数据迁移的工程师并行，通常仍需约 4–6 个月，加上真实发布观察窗口和权利审查。
- Track B：需独立 specification/implementation/compliance 团队和新仓，通常至少 6–9 个月；若同包原位迁移和多 provider 全保留，时间更长。
- 最昂贵的部分不是 UI 重画，而是 provider/tool/terminal 行为、无损数据迁移、两端真实设备/后台证据和来源/资产清单。

建议角色：Program/architecture owner、Android runtime/data owner、Android leaf/release owner、iOS façade/runtime owner、QA/device/provider owner、provenance/license reviewer。Track B 默认实施人员与可访问旧源码的规格人员隔离；人员资格和例外由法务/权利 owner 依据 source-access evidence 批准。

## 25. 与既有 Android 能力齐平计划的关系

本计划不重复既有 capability-parity 工作：

- 已实现的 SecretStore、Tool Effect Ledger、typed terminal、run ownership、token fit、production canaries 和 domain features 是迁移输入，不因“脱离”而重写。
- 既有计划标记 `Implemented` 的内容仍需按 P2/P11 补真实 provider、设备、后台、kill/relaunch 证据，不能自动视为 `Verified`。
- 旧计划的 P0 fixture/canary、Phase 1 reliability、schema work 可直接服务本计划 P2/P3/P5。
- 本计划新增的是来源/相似性门、Amber-owned seam、strangler、vNext 数据 owner、Rikka dependencies/assets 退出、iOS Shared 收缩和最终删除 gates。

## 26. 立即可执行的前三个批次

在不触碰现有 Novel WIP 的前提下，下一步只做：

1. **PR-00 Baseline freeze**：提交本计划、三个 SHA、完整 similarity/provenance/asset manifests、WIP exclusion 和 P0 决策表；不改产品代码。
2. **PR-01 Fixture/spec seed**：只建立 Chat stream、tool terminal、Conversation variants、settings unknown-field 的首组 canonical fixtures 和两端只读 runners；不切生产路径。
3. **PR-02 Provider seam canary**：让旧 Android provider 与 iOS `IOSAgentTextProvider` 通过最窄 neutral event/reducer seam，各接一条无副作用 canary；不改 canonical storage、不删旧实现。

前三批通过后再决定先进入 OpenAI adapter 还是 vNext data codec。若 P0 尚未决定 Track A/B，则停在 PR-01，避免新增实现改变 Track B 所需的 source-access/人员隔离证据。

## 27. 最终 Definition of Done

- [ ] P0 的 Track/分发/许可证/视觉决策已批准。
- [ ] 固定 SHA、ledger、source/asset/dependency manifests 可复现。
- [ ] 当前快照发现的 71 个 normalized-exact 功能文件、125 个高 Jaccard 文件已进入初始 ledger；最终以 P1 可复现报告为准，所有命中均有 provenance 分类、replacement/clearance 与 reviewer。
- [ ] Android 的 Rikka-derived owned source 达到 P11 operational threshold；public protocol、third-party、generated、legacy importer 分别满足其证据门，未分类命中为 0。
- [ ] Android 默认生产链由 Amber ChatFacade/RunCoordinator/Stores/ProviderGateway 所有。
- [ ] Android 全入口 manifest 无未知或绕行 legacy owner；每项已迁移、明确移除或证明 partial/dead。
- [ ] Android 运行时 Rikka Maven/endpoint/About 冒充/brand residuals 清零；必要 LICENSE/NOTICE/provenance 文本和已清权第三方项进入明确路径 allowlist。
- [ ] vNext 数据完整保留 variants/unknown fields/attachments/credential refs，迁移与回滚已演练。
- [ ] iOS 93 个 production Swift Shared-import 文件全部完成符号级分类和迁移，final binary 不链接 `Shared.framework`；全 iOS 167 个 import 在 app/tests/build 边界均有结论。
- [ ] 两端同一 semantic fixtures 通过，各自 platform lifecycle 由各自测试证明。
- [ ] 模拟器、真机、真实 provider、后台、kill/relaunch 分开验证并归档。
- [ ] Gradle/Cargo/Bun/native/assets SBOM/NOTICE 无 blocker。
- [ ] 所有临时 flag、shadow path、legacy writer/reader/adapter 已按窗口删除。
- [ ] final release record 与权利/发布 review 通过。

完成这份 checklist 后，才能把计划状态从 `Draft` 依次更新为 `Implemented`、`Verified`、`Released`；不得一次跳级。
