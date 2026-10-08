# Amber 测试必要性审计与精简落实

日期：2026-10-05。仓库：`amber-harmony-preview`，分支：`harmony-preview`，基准 HEAD：`b2d054bd7`。

使用 [OpenClaw test-audit](https://github.com/openclaw/openclaw/blob/main/.agents/skills/test-audit/SKILL.md) 的行为、回归、独立合同和重复覆盖标准。OpenClaw 专用 Vitest/changed 命令在这里使用 Amber 的 tsx/Gradle 入口替换；最终复审由独立只读子 agent 完成。

## 第一批目标和范围

完成统一测试入口、修复已确认失效的夹具、精简证据明确的低价值测试。没有修改业务源码，没有改页面、持久化或协议实现，没有提交或推送。

开始时已有 154 个 tracked 改动和 178 个 untracked 路径。验证了本批授权路径之外的 tracked diff 和 untracked 路径与开始时一致。本报告和行数统计均按进入本轮前的工作树计算，避免把此前 WIP 算成本批改动。

## 统一入口

仓库根目录执行：

```sh
# 依赖未安装时执行；使用各领域包已有的 lockfile。
npm ci --prefix harmony/chat
npm ci --prefix harmony/deepread

npm run test:harmony
```

`scripts/test-harmony.mjs` 递归选择以下四个目录中的 `.test.ts` 和 `.test.cjs`：

- `harmony/chat/src/test`
- `harmony/deepread/src/test`
- `harmony/entry/src/test`
- `harmony/tests`

另执行 `scripts/harmony-standalone/prepare.test.mjs` 和 `harmony/scripts/test-lint-arkts.mjs`。固定使用 Chat 安装的 tsx 与 Chat tsconfig，使跨域别名可以解析；子进程失败时入口返回失败。并发文件数为 4。

两个领域包的 `npm test` 和 `test:watch` 同时包含 TS/CJS。DeepRead 明确使用 Chat tsconfig 的跨域路径映射。根入口还包含 Entry 和跨模块脚本，因此根入口是完整的 Node 回归命令。

新增 `.github/workflows/harmony-tests.yml`：PR、`main`/`harmony-preview` 相关路径的 push、手动触发；Node 22、两个 lockfile 的 `npm ci`、只读 contents 权限，执行同一根入口。仅完成本地 YAML/配置检查和独立复审；尚未在远端执行该 workflow。

不包含需要设备的 Python UI 验收脚本、Harmony SDK 构建、签名和安装。现有需要专用编译流程的 native host 套件也不在这条领域/Entry 入口内。

## 删除依据

本批移除 15 个低价值测试和 2 条冗余断言；保留真实 owner 和相邻行为测试。

| 删除项 | 真正能检测什么 | 生产调用、剩余证明和历史 | 风险与验证 |
| --- | --- | --- | --- |
| `ProjectorPropertyTest` 4 个 property，以及同文件 toy 数据类和 `project()` | 检查测试自写 Map projector；未经过生产 ChatEventProjector/Conversation/Room。“保留候选”仅检查结果 ID 唯一，不能发现候选丢失 | toy 无非测试调用。`849afaf63` 引入这组 TC.6 测试及 Kotest/JUnit 5 配置。真实 `InterruptedRunProjectionTest` 和 checkpoint selection 测试保留，但不构成 finalized/落盘/候选保留的完整证明 | 删除 toy 不损失生产检测能力；原合同缺口仍存在。runtime 编译通过，test 为 NO-SOURCE；App sibling 因依赖缓存缺失未运行 |
| app、ai 的两个 `ExampleUnitTest#addition_isCorrect` | 仅 `2 + 2 == 4`，无产品 owner | 来源为初始模块模板。真实 MessageTest、投影等测试保留 | 低风险。ai MessageTest 13/13 通过 |
| `models.test.ts` 的 JSON round-trip、empty/partial sectionStates、null/absent 共 4 项 | 内建 JSON 行为、fixture 内容；类型断言在运行时无校验，不经过生产 parseOutput/entryBucket | `486f775ad`、`75716ef5f` 引入。保留 factory 默认值测试、helpers 和实际 repository/template consumer 测试 | 低风险。删除仅供首项使用的 fixture 和 import；完整 Harmony 与两个领域 typecheck 通过 |
| `schema_sql.test.ts` 全部 5 项 | 锁定无人使用的旧 DEEP_READ_SCHEMA_SQL 字符串 | 旧 `deep_read_cache`/3 索引，真实 RdbRepository 是 `deepread_cache`/4 索引。`59a45fadf` 为接口阶段，`004d8e3e0` 曾接入旧常量，`8e3e79c52` 删除依赖；后续实际 owner 使用独立 schema | 旧常量和接口未删除。本批没有提供真实 SQL 初始化/迁移的替代证明，缺口明确保留 |
| `chat_reply_actions.test.cjs` 的第二次 `fork: false` 断言 | 与前面的循环检查完全相同 | WIP 在循环中加入 fork 后产生重复。其它 UI 行为目标和源码 guard 保留 | 低风险，仅减 1 条断言 |
| `deepread_template_library.test.cjs` 同函数同参数再比较一次的断言 | 最多验证 renderer 的确定性，不能证明 Reader/PDF 共用快照 | `b2d054bd7` 同批已有实际 Reader 和 PDF consumer 测试。保留 repository 更新、模板删除后快照仍可渲染的证明，测试名改为实际覆盖的合同 | 低风险，仅减 1 条断言；完整套件通过 |

Android runtime 删除两项只为 toy 使用的 Kotest 依赖和 `useJUnitPlatform()`；保留本模块 JUnit、coroutines.test 及共享 version catalog，未扩大依赖清理。

## 夹具和时间控制修复

进入本轮前，再次定向复现原先 8 个失败和 1 个取消：

- Provider topic 用例 mock 仍返回 `applyTopics`，真实 store 已使用 `applyOptimization`。按当前返回合同补齐 mock；OAuth/header/真实协议适配器的原断言保留。该测试没有宣称证明真实记忆写入。
- DeepRead 活动导航、强制重新生成和段落重试夹具漏掉 `clearEditorialMotion` 等页面真实方法。补入真实源码方法、状态初值与产品身份端口，没有把方法改成成功兜底。
- 小说通知分支切换夹具漏掉 `clearStudioFeedback`。补入真实方法及状态，仍验证用户选择 B 后保持 B。
- Jev 的 100ms 外层限制会把 fixture 转译/调度开销算入失败。两个 deadline 场景用 Node mock timers 驱动实际 owner 的计时器，保留 abort、无 cooldown、清理计时器以及迟到 KV 返回后零 HTTP 的断言。没有扩大 timeout。

修复后的定向集合 10/10 通过。

第一次完整入口和 Chat 包入口又复现了 `council_tools.test.ts` 的取消时序竞争：40ms deadline 在 `setImmediate` 恢复前触发，导致期望 AbortError 时得到正常 timeout 结果。首项使用 mock timers 固定“父取消先于 deadline”的场景；保留 40ms 参数、真实 manager/AbortController、AbortError、子任务仍 running 的断言。相邻测试继续覆盖 timeout、完成与监听器清理。

## 实际验证

| 命令或检查 | 结果与边界 |
| --- | --- |
| 根 `npm run test:harmony` 最终运行 | **4753/4753 通过**，184 suites；0 failed/cancelled/skipped。约 56 秒，本机 Node 26.8.1 |
| Chat/DeepRead `tsc --noEmit` | 两包均通过 |
| 两包 `npm test` 入口 | 确认均实际执行 CJS。DeepRead 1447/1447；Chat 初次 2935/2936，唯一取消时序问题随后修复并被最终根套件覆盖 |
| runtime `:core:agent-runtime:test --offline` | BUILD SUCCESSFUL；生产 compileKotlin 成功，test **NO-SOURCE**。不算投影合同通过 |
| `:ai:testDebugUnitTest --tests app.amber.ai.ui.MessageTest --offline` | **13/13**，0 failure/error/skipped |
| App InterruptedRunProjection/CheckpointSelection sibling | 任务 dry-run 成功；实际运行未进入测试，offline 依赖缓存不足，失败于 processDebugNavigationResources。未下载这些依赖 |
| Node 脚本语法、JSON、workflow YAML、scoped diff check | 通过 |
| 独立增量复审 | 审阅本批 patch 和取消时钟修复，未发现 P1/P2/P3 阻塞项 |

Android 命令使用已存在的 `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`，未创建/修改 local.properties。App JVM 验证排除了无关 Cargo 构建和终端资源下载；仍缺 hugeicons-compose、sqlite-android SNAPSHOT、Media3 等离线依赖，不能报告 App 投影测试通过。

本机 Node 验证不等于 Node 22/Linux CI 成功。生产 ArkTS/UI 没有改动，本轮没有执行 SDK build、签名、安装或设备验收。

## 本批行数

相对进入本轮前的工作树，排除此前 WIP 和本报告：

| 类别 | 新增 | 删除 | 净变化 |
| --- | ---: | ---: | ---: |
| 业务源码 | 0 | 0 | 0 |
| 测试与专用 fixture/support | 43 | 251 | -208 |
| 运行入口、CI、package/Gradle 配置 | 82 | 10 | +72 |

## 保留的后续项

- 把聊天复制、长按、结果卡 callback 的源码形状检查迁到真实行为边界；缺少替代证明前保留现有 guard。
- 把备份恢复 URI 在界面重建后的存续检查迁到流程测试，保留 SAF 截断契约。
- 实际 RDB SQL 初始化/迁移执行，以及真实 projector 的 finalized/Room/重生成候选保留，仍需各自 owner 的独立证明。
- `resetMemoryExtractionDebounce` 测试专用接口与未接入产品的 reasoning_family 逻辑，本批未修改；需要独立小批次处理。
- 鸿蒙 Builder 参数形状、权限/配置、流式协议、审批 receipt/CAS、原子文件写入等独立合同测试保留。

## 本地证据

- `/tmp/amber-test-audit-20261005-next.kaOfsI/fixtures-before.log`
- `/tmp/amber-test-audit-20261005-next.kaOfsI/fixtures-after.log`
- `/tmp/amber-test-audit-20261005-next.kaOfsI/harmony-all.log`：首次完整运行的时序失败
- `/tmp/amber-test-audit-20261005-next.kaOfsI/harmony-final.log`：最终 4753/4753
- `/tmp/amber-test-audit-20261005-next.kaOfsI/chat-typecheck.log`
- `/tmp/amber-test-audit-20261005-next.kaOfsI/deepread-typecheck.log`
- `/tmp/amber-test-audit-20261005.ye6hDi/android-app-projection-followup.log`

## 第二批：继续删除低价值测试

用户明确授权进一步精简。按同一 test-audit 标准重新只读核查后，本批移除 **34 个测试、12 个完整测试文件**，另 4 个测试文件局部删减。相对本批开始时的工作树，测试及本地 fixture helper **0 行新增、513 行删除**；业务源码、构建配置和测试入口均 **0 改动**。两批累计移除 **49 个测试**，累计测试/support 净减少 **721 行**。

本批开始时已有 168 个 tracked 改动和 181 个 untracked 路径，第一批成果也按已有 WIP 保护。已核对授权路径外 tracked diff、untracked 路径，以及保留的 App 包名测试、MiMo 测试和生产 owner 文件，均未改变。

### 删除依据与剩余证明

| 删除项和精确位置（本批前） | 实际检测能力 / 删除理由 | 生产调用、剩余证明与历史 | 风险和支持删除 |
| --- | --- | --- | --- |
| common/document/highlight/search/tts 各 `src/test/.../ExampleUnitTest.kt` 的 `addition_isCorrect`，5 项 | 只断言 `2 + 2 == 4`，不调用 Amber 代码 | 没有生产 owner，不需要业务替代证明。分别来自 `a4d353752`、`77a7c68d3`、模块模板迁移、`1967ab139`、`45cc94f83`；`6643f0ae3` 只迁移包名 | 低风险，删除 5 个完整模板文件。tts 两个真实 MiMo 测试保留；其它库清空测试不等于业务已验证 |
| ai/common/document/highlight/search/tts 各 `src/androidTest/.../ExampleInstrumentedTest.kt` 的 `useAppContext`，6 项 | 只检查库测试 APK 自身的 `app.amber.<module>.test` 包名，不能检查发布应用或模块业务 | 六模块都是 `android.library`。读取本机 AGP 9.2.0 的 `DeviceTestImpl.getTestedApplicationId()` 字节码：AAR 分支返回 DeviceTest 自己的 applicationId，非 AAR 才返回 main variant applicationId。来源为初始模块模板及 `6643f0ae3` 更名；ai 初始 `355ed95a9`，highlight 仪器测试初始 `c6f4a669d` | 低风险，删除 6 个完整文件；没有生产 seam。**App 的同名仪器测试保留**，它检查实际 app 包名前缀；`3d9fa3402` 有明确包身份变更历史 |
| `ai/.../ui/MessageTest.kt:75-105`，`limitContext with tool call at start should include corresponding user message`，1 项 | 与 `:44-73` 完全同一 user→call→result→final、同一 limitContext(2)、同一期望全部 4 条；名字没有对应独立输入 | owner `Message.kt:287-329`，由 ConversationContextEngine/Planner 调用。保留第一项即可维持 result→call→user 回溯证明。重复起于 `66d4039a7`，后续 Tool 结构转换未分化场景 | 低风险，只删重复块；保留测试实际 12/12 通过 |
| `harmony/deepread/src/test/append_chunk.test.ts` 全部 18 项 | 验证旧内部 helper 的文本/reasoning/tool 折叠、ID 迟到、index 分流等；这些有行为断言，但被测模块不参与当前产品执行 | `agent/append_chunk.ts` 没有生产 importer、没有 barrel export；领域包 private。当前 `AppContainer.ets` 的 DeepRead 使用 `createChatDeepReadAiClient`，真实 Chat accumulator/tool_merge 负责流处理。备用 OpenAiCompatibleAiClient 也用自己的 accumulateToolCall。保留 actual `deepread_ai_client`、`stream_accumulator`、`tool_merge`、`openai_stream`、agent_loop/tool_execution 测试。历史 `68dc7dfb6` 初移植、`bc34e9925` 加并行 index、`abed07784` 迁 schema | 删除整个测试及两本地 fixture helper；**155 行生产旧模块只标记，未删除**。未来若重新接入，应在新真实入口补协议回归；当前 owner 测试不被描述为旧 helper 的替代证明 |
| `inferred_states.test.ts:6-11`，`withInferredSectionStates is no-op when sectionStates populated`，1 项 | 只冻结返回对象引用；保留值的浅复制也会导致它失败 | run_manager 和 supervisor_loop 消费状态值，不依赖 identity。保留 `run_manager.test.ts` 的 `P2-2: continue reuses READY sections and only generates non-READY stages`：summary 为空但 OVERVIEW 为 READY，实际执行 run 并验证不重写概览。其余 5 项 legacy 推断保留。历史 `8a51ffa31` 要求不改变已有状态，没有引用契约 | 低风险；只取消 implementation identity 约束，没有支持/生产删除 |
| `image_scorer.test.ts:125-128`，`.ico extension → reject (icon_format)`，1 项 | 与 `:81-84` favicon 用例正文完全相同，同一输入、同一 reject 断言 | 保留相同 favicon 证明以及普通 svg/svg?query 两项。实际调用 `source_prefetcher → scoreAndDedup → scoreImageCandidate`。两项均起于 `c79baaf34`，没有不同回归历史 | 无新增检测损失；原有 `.ico` 独立分支缺口仍存在：favicon 同时命中 brand 和 icon_format，任一分支独自失效仍能 reject |
| `streaming_display.test.ts:28-33`，`Clock: stamp 后 progressAt 返回 0(尚未到出现时间)`，1 项 | 首字符在 stamp 起始时 progress=0 | 同文件 `Clock: 多字符 cascade(stagger 错峰)` 使用相同参数、包含完全相同断言，再检查第二字符。owner 仍经 HAR 导出。两项均由 `b3ad2c345a` 同批引入 | 无检测损失；只删重复测试 |
| `streaming_display.test.ts:404-414`，`State:reveal 不劈开 surrogate pair`，1 项 | charAt 返回最多 1 个 UTF-16 单元，但正则需要 2 个；断言始终成立。fixture 首帧只显示 `a`，没有到 emoji 边界 | 保留首帧真实发射测试和 nextCodePointEnd 的精确 emoji offset 预期。只读反例：强制切在 ab😀cd 的 offset=3，孤立 D83D 仍通过原断言；全部 65536 个单单元均不匹配。`bc34e99253` 初始版本即有此无效断言；State 保留 HAR 导出 | 不再产生无效 Unicode 保证；**State 中间帧的 Unicode 安全仍缺可靠证明**，终态完整长度测试也不等于逐帧保证 |

旧 `append_chunk` 的 README 目录说明与 `openai_stream.ts` 注释不是执行引用。`entry/TODO-compile-guide.md:45,103`（末更 2026-07-17）曾规划 SSE 接线，但同文仍把如今已实现的 RDB/HTTP 列为 TODO；当前实际入口已采用另一条路径。未发现该内部文件形成独立 public API/发布合同。`STREAMING_SEMANTICS_MATRIX.csv:18-19` 的规则仍由实际 Chat owner 承担，本批不修改它们。

### 本批保留项

- App 实际安装包名前缀、MiMo 音频解码/结束信号/defaults、协议、存储、迁移等独立合同。
- `topic_id` 两个探针及整套：Title 探针还独立覆盖 lowercase/trim；当前 Node 运行时也不能被宣称为 ArkTS 平台归一化的独立证明。
- `State:可见长度与 safeStreamingDisplayEnd 一致` 虽不能约束 State 发射，但仍能检测直接 helper 测试未覆盖的 ASCII offset 映射差异；本批保留。
- CJS 的真实 Watch、ForEach、Builder/callback 接线 guards：相邻 fixture 直接调用提取的方法，缺少接线本身的替代证明，因此未扩大删除。

### 第二批验证

| 验证 | 结果与边界 |
| --- | --- |
| 删除前 4 个候选文件 | 99/99 通过；说明本批没有通过删除失败测试掩盖异常 |
| 删除前实际流处理和 run owners | 83/83 通过 |
| 删除后候选余项与实际 owners/siblings | **236/236 通过**，0 failed/cancelled/skipped |
| ai MessageTest | **12/12 通过**，0 failure/error/skipped，Gradle offline BUILD SUCCESSFUL |
| Chat/DeepRead tsc --noEmit | 均通过 |
| 根 npm run test:harmony | **4731/4731 通过**，184 suites，0 failed/cancelled/skipped，约 40 秒 |
| 独立第二批复审 | 完成；核对删除依据、AGP 字节码、报告计数与日志，未发现 P1/P2/P3 阻塞项 |

仅删除测试，没有改 Harmony 生产源码；未进行 SDK 构建、签名、安装或设备验收。没有为库测试 APK 自身包名安排设备验证。第一批记录的 App 离线依赖缺口仍然存在，本批没有重试整 App 测试，也没有把 NO-SOURCE 当通过。

第二批证据目录：`/tmp/amber-test-audit-20261005-more.pnPJbU/`，包含 `before/`、`test-only.patch`、`loc-stats.json`、`harmony-focused-before.log`、`harmony-owners-before.log`、`harmony-focused-after.log`、`harmony-final.log`、两个 typecheck log 和 `android-message.log`。改动继续留在工作树，未提交、未推送。
