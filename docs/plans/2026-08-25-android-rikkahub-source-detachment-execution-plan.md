# AmberAgent Android 与 RikkaHub 源码脱离执行计划

> 状态：工程执行完成；构建/设备发布验证受环境阻塞<br>
> 工作树：`/Users/arquiel/Downloads/AI/amberagent-monorepo/apps/android`<br>
> 分支：`main`<br>
> 冻结基线：`bdd1f7d94e561f1ebb967c6736a261babb8ae2e9`<br>
> 原则：只保留 Amber 产品真实能力；不重做已完成能力，不用删除用户数据换取静态相似度，不建立第二套框架。

## 目标

把 RikkaHub 遗留的产品结构和高重合实现替换为 Amber 自己的单一生产路径，同时保留三个月开发形成的模型接入、聊天、Memory、MCP、Skills、Novel、Council、Workspace 和历史数据。

“彻底脱钩”在本计划中的可验证含义是：

- 生产源码不再包含 Rikka namespace、私有端点、依赖坐标、品牌资源和多助手产品模型；
- 高重合的聊天控制、余额表达式、Office parser、搜索 adapter 等有独立实现；
- 旧数据只留在明确的单向迁移边界，不继续参与新写入和生产路由；
- 依法需要的许可证和历史来源继续保留，不伪称不存在历史关系。

## 每个 Phase 的闭环

1. 修改前固定真实入口、直接消费者和数据边界。
2. 只实现当前 Phase 的最短生产路径。
3. 运行定点测试、编译或静态门禁；环境缺失必须如实记录。
4. 由独立 subagent 检查调用链、持久化、异常路径和 UI 的对齐、边距、间距、尺寸。
5. Root 只修复真实问题，复验通过后进入下一 Phase。

## Phase 0：直接 Rikka 产品残留

状态：完成。

- `RikkaConfirmDialog` 改为 Amber 自有 `ConfirmDialog`。
- Rikka Maven 坐标、HugeIcons、搜索端点、About 链接和 `rikkahub.svg` 已从当前生产面退出。
- NOTICE/SBOM 的工程合规 review 已 GO；APK、设备和法务发布判断仍单列。

门禁：生产 namespace、坐标、端点和品牌资产扫描为 0；独立 review GO。

## Phase 1：TTS 整体删除

状态：完成。

- 删除 `:tts` 模块、UI、路由、DI、工具、自动播放、设置、密钥、Manifest 查询和仅供 TTS 使用的 SSE。
- 保留 AI/MCP 使用的 SSE、语音输入权限和生成完成事件。
- 旧 `tts` 本地工具仅保留在 legacy 反序列化模型中，确保含 TTS 的旧助手 JSON 仍可迁移；生产工具集不再暴露它。

门禁：生产链 TTS 命中为 0；Settings/Secret 清理闭环；独立 review GO。

## Phase 2：聊天 Provider 独立小岛

状态：完成。

- 独立重写 `ModelDsl` 和 Provider message grouping。
- Provider wire adapter、gateway 和生成协调链不再依赖旧 owner。
- 连续工具/内容分组保持 O(n) 且顺序稳定。

门禁：OpenAI/Claude/Gemini 生产调用链唯一；独立 review GO。

## Phase 2.5：移除助手配置层

状态：完成；数据与 UI 独立 review GO。

### 2.5A 删除多助手产品表面

- 删除 Assistant 列表、Picker、详情 CRUD、Importer、路由和 VM。
- 默认配置只保留 Amber，不再注入第二个通用助手。
- 删除仅服务于多助手 UI 的 locale 文案。

### 2.5B 设置直接归属产品

- 不再引入 `AmberProfile` 或其他“单助手容器”；模型、Prompt、请求参数、Memory、MCP、Skills、Quick Messages 等直接属于全局 `Settings`。
- 旧 DataStore 只读迁移顺序固定为：已选中 -> Amber 默认 ID -> 第一份 -> 新默认。
- 保留被选中配置的模型、Prompt、请求参数、Memory、MCP、Skills、Local Tools、Quick Messages 和背景设置；不合并其他配置。
- 新写入只写各自直接设置键，不再写 profile 或旧助手列表。

### 2.5C 历史数据归一

- 保留数据库 `assistant_id` 列作为兼容/运行归属字段，统一映射为 Amber canonical ID。
- 历史会话和 Memory 不再因旧 assistant ID 被列表查询过滤。
- Council host、SubAgent 派生配置、Novel 运行参数只改消费名称，不删除真实能力。

### 2.5D 备份、同步和密钥

- 新备份只写直接 Settings 字段。
- 旧 `settings.json` 的 assistant 列表经过同一迁移器恢复。
- Secret redaction/rehydration 直接处理全局 custom headers；旧列表只存在于读取兼容代码。
- Provider rescue、Skills 和 Quick Messages 不再遍历助手列表。
- `SECRET_REFS` 在所有写入、迁移和 orphan sweep 边界严格解析；存在但损坏时显式失败，不覆盖引用、不删除密钥。
- legacy profile/list 迁移失败时保留旧 key、引用和 SecretStore 数据；普通 direct Settings 仍可写，但不会触发旧数据清理。

### 2.5E 命名和模型清理

- 删除生产设置中的 `Assistant` / `AmberProfile` 类型、身份字段和列表 API；仅保留内部 legacy decoder 承接旧 JSON。
- 保留 canonical Amber ID、Conversation/Memory 的持久化兼容字段，以及运行 ownership 中有现实意义的 `assistantId`。

门禁：产品只有一个 Amber，且没有“助手配置”层；旧选中配置和历史聊天可恢复；新 JSON/DataStore 不包含 profile 或多助手容器；独立数据/UI review GO。

## Phase 3：余额 JSON 表达式替换

状态：完成。

- 删除通用 JSON DSL、JNI 和 Rust crate。
- 使用余额专用路径解析器，只支持生产需要的字段、数组下标和一次减法。
- 设置页和两条余额生产链使用同一解析器。

门禁：旧 DSL/JNI/Rust 命中为 0；两条生产链 review GO。

## Phase 4：Office 文档解析独立重建

状态：完成；Office parser 独立 review GO。

- 从零实现 DOCX/PPTX/EPUB 的最小只读解析，保留当前文本输出契约。
- 删除 JVM/Rust 双实现、随机抽样、Remote Config 和静默 fallback。
- 保留 PDF/MuPDF 和 XLSX/calamine；它们是独立第三方边界或仍有真实消费者。
- 用三份最小 fixture 验证段落、列表、表格、slide 顺序和 EPUB spine 顺序。

门禁：旧 parser 和 `me/rerere` 参考命中为 0；聊天附件、Council、Workspace 三条调用链闭环；独立 review GO。

## Phase 5：搜索 Provider adapter 重写

状态：完成；搜索调用链与 UI 独立 review GO。

- 保留 Amber 自有 Aggregator、Orchestrator 和 DuckDuckGo/Wikipedia/HackerNews 等入口。
- 按高重合顺序逐个重写 Grok、LinkUp、Firecrawl、Jina、Bocha、Tavily，再处理其余需要替换的 adapter。
- 每个 adapter 只依据公开 API 契约实现，不复制对照项目结构；不建立通用网络框架。
- 旧 `amber_agent` 搜索类型只保留单向迁移，不恢复 Rikka 私有端点。

门禁：公开协议 fixture 通过；注册、设置、DeepRead 和 Chat 搜索链闭环；独立 review GO。

## Phase 6：高重合 UI 与品牌资产

状态：完成；静态 UI/品牌 review GO。

- 重做 About、Search、AI icon matcher 和剩余高重合视觉组件。
- 逐屏检查窄屏、字体缩放、深浅主题、触控尺寸、对齐、边距、间距和图标视觉重量。
- 新插图或品牌图必须是 Amber 原创资产，并保留生成/来源记录。

门禁：静态 UI review、资源引用、六语言 XML 和独立 subagent review 已闭环；截图、真机与 TalkBack 因当前环境不可用，保留为发布前设备门禁。

## Phase 7：Common 剩余高重合小岛

状态：完成；OAuth/common 工程 review GO。

- 只处理经调用链证明仍高重合且可独立替换的 OAuth callback、权限或 HTTP helper。
- 公共协议样板和独立第三方代码按 provenance 分类，不为降低百分比重写。
- 每次只替换一个真实消费者闭环，不扩建基础设施。

门禁：旧实现无生产 caller；真实 OAuth/权限流程按可用环境验证；独立 review GO。

## Phase 8：最终验证与重新评估

状态：工程静态验证完成；Android 构建与设备项 unavailable。

- 运行受影响 JVM tests、Kotlin compile、Rust tests、`assembleDebug`、`git diff --check` 和最终残留扫描。
- 重新固定 RikkaHub 对照 SHA，复跑模块/文件/生产 owner 相似度。
- 将静态相似、协议必然相似、第三方边界、历史 provenance 和运行时独立性分开报告。
- Android SDK、APK、设备、真实 provider、后台与 kill/relaunch 证据分别列出，不能互相替代。

最终 Gate：所有已授权工程 Phase 完成且 review GO；环境无法验证的发布项明确为 unavailable，而不是假写通过。

## 最终执行结果

### 产品与生产调用链

- 当前生产源码扫描中，Rikka Maven namespace、HugeIcons、`api.rikka-ai.com`、RikkaHub 品牌入口和 `rikkahub.svg` 均为 0。
- `:tts` 模块、UI、DI、工具和设置生产链已删除；只保留旧 JSON 的 `tts` discriminator 与旧 DataStore key 清理器，不会注册或暴露 TTS 工具。
- 多助手页面、Picker、详情 CRUD、Importer、路由和 VM 已删除；设置直接归属 Amber。数据库 `assistant_id` 和内部 `LegacyAssistantProfile` 仅承担历史数据兼容。
- 默认聊天由 `ChatService -> ChatRunCoordinator -> ProviderCatalog` 持有；旧 `ProviderManager`、`GenerationHandler` 和旧 `Provider` owner 已退出生产源码。
- DOCX/PPTX/EPUB 使用 Amber 当前 JVM parser；PPTX/EPUB 在 `maxChars` 达限时提前停止，损坏 slide/notes 有明确边界；XLSX/calamine 与 PDF/MuPDF 作为独立第三方能力保留。
- Grok、LinkUp、Firecrawl、Jina、Bocha、Tavily adapter 按各自公开 API 契约实现；Chat、DeepRead、MiniApp、设置与 secret 路径已复审闭环。
- Graphite mark 根据应用内 `LocalDarkMode` 选择深/浅资源，不依赖系统 `uiMode`；Provider、MCP、搜索、模型选择、Skills 和聊天输入主操作的 48dp、语义标签、overflow 与六语言资源静态复审 GO。

### 最终静态相似度

冻结对照：

- Amber Git HEAD：`bdd1f7d94e561f1ebb967c6736a261babb8ae2e9`，测量对象为该 HEAD 上的当前 dirty worktree。
- RikkaHub `2.4.11`：`b270766f06671d7456ce3d248622dc667648b6d1`。
- RikkaHub current：`fa0305ba54cda8c7d16a7ce7a6f40b6462da4353`。

同口径源码分母：`1,072 文件 / 252,092 原始行`。以下为 Amber 模块的 7-token union overlap；它衡量词法重合，不是复制率：

| 模块 | 对 `2.4.11` | 对 current | 旧评估约值 | 结果解释 |
|---|---:|---:|---:|---|
| `tts` | 0 | 0 | 85.91% | 模块和生产链已删除 |
| `common` | 66.30% | 66.33% | 76.58% | 剩余为 HTTP/cache/logging/OAuth 等仍有消费者的通用小模块 |
| `search` | 61.11% | 61.09% | 71.76% | adapter 已重写；模块级公共 API 词汇仍高，但 normalized exact 与 k12 文件匹配均为 0 |
| `document` | 15.82% | 15.86% | 65.55% | Office parser 替换带来实质下降 |
| `ai` | 35.01% | 35.15% | 37.65% | 主要是公开 Provider 协议、DTO 与 HTTP transport |
| `app` | 18.54% | 18.53% | 20.27% | Amber 产品控制流占主体 |
| `core` | 15.63% | 15.71% | 18.31% | legacy decoder/持久化兼容仍保留 |
| `feature` | 3.64% | 3.69% | 3.66% | 基本不变且处于低位 |

全源码归一化结果（两个 RikkaHub 对照一致）：

- normalized exact：`69 文件 / 4,549 行`；
- `k=12, Jaccard >= 0.80`：`96 文件 / 11,223 行`；
- `k=7, Jaccard >= 0.80`：`112 文件 / 13,026 行`。

聊天/provider 内核 10 对定点测量：

- 未加权 ordered-token：`33.6%`；
- 未加权 Amber 侧 12-token shingle：`19.4%`；
- 按 Amber 源码行加权 ordered-token：`30.6%`；
- 按 Amber 源码行加权 12-token shingle：`11.5%`；
- 达到 12 行连续块阈值：`3 / 10`，均定位为 OpenAI/Claude/Google 的公开 HTTP/auth/image transport，不是聊天 run-control owner。

资源 byte-exact 仍为 `62 文件 / 8,758,358 bytes`。该口径包含相同的第三方/provider 图标与公共资源；当前扫描没有 Rikka 命名资产，因此不能把这个数字解释为仍有 62 个 Rikka 品牌资源。

### 验证与未验证边界

已完成：

- 每个 Phase 均经过独立 subagent 逻辑/调用链 review；Phase 2.5、4、5、6、7 最终均为工程 GO。
- 六个 locale `strings.xml` 均通过 `xmllint`，重复 key 为 0。
- 最终生产残留扫描通过；`git diff --check` 通过。
- 当前 worktree 的聊天 owner 与静态相似度已用固定 RikkaHub SHA 复算。

未完成且不得冒充通过：

- 使用仓库缓存 JDK 启动 Gradle 后，任务在配置阶段因 `SDK location not found` 失败；当前 JVM tests、Kotlin compile 和 `assembleDebug` 没有新证据。
- 当前 shell 没有 `cargo`，本轮未重新执行 Rust tests；Office Rust 只保留 XLSX 路径。
- 没有当前 APK/AAB、模拟器或真机安装、截图、TalkBack、真实搜索/OAuth/provider、数据库升级、后台或 kill/relaunch 证据。
- 工作树包含大量既有并行 WIP；本轮未 commit、stage、push、reset、clean、stash 或触碰 iOS 文件。

## 仍然保留但不属于产品耦合的内容

- `NOTICE` 与 SBOM 中的历史 provenance 必须保留；工程脱钩不能抹去依法需要的作者、版权和许可证记录。
- `LegacyAssistantProfile`、旧 `assistants`/`amberProfile`/TTS key 和 `LocalToolOption.Tts` 只用于单向兼容或清理旧数据。它们不得重新进入新写入、UI 或生产路由，待跨过明确迁移窗口后再单独评估删除。
- `common`、公开搜索协议、Provider DTO、MuPDF、JetBrains Markdown、Lucide 等有独立消费者或第三方来源；不为了压低百分比重写、删除或伪装来源。
