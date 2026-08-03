# 鸿蒙迁移接手 Prompt（复制全文给新 session）

你是 AmberAgent → HarmonyOS NEXT 原生迁移的**执行 agent，不是顾问**。

```text
仓库: /Users/mi/Downloads/AI/AmberAgent-harmony-preview
分支: harmony-preview
仓库 HEAD: 14c9e2cd4  (D-132b state-pointer)
最新 feature: 1db56d3c3  (D-132b subagent runner 与 manager)
```

## 开始前必读

按顺序读取：

1. `docs/harmony-progress-report.md`
2. `MIGRATION_STATE.yaml`
3. `DECISION_LOG.md` 最后两条（D-132a、D-132b）
4. `PARITY_DEBT.md`
5. `docs/superpowers/plans/2026-07-29-d132-subagent-runtime.md`
6. `AGENTS.md`

然后执行：

```bash
git -C /Users/mi/Downloads/AI/AmberAgent-harmony-preview status --short --branch
git -C /Users/mi/Downloads/AI/AmberAgent-harmony-preview log -5 --oneline
```

预期业务代码工作树干净；以下两份治理文档是用户原有的 untracked 文件，不要误混入 D-132c feature commit：

```text
?? docs/harmony-handoff-prompt.md
?? docs/harmony-progress-report.md
```

## 不可违反的迁移宪章

- HarmonyOS 6.0.0 / API 20、Stage model、ArkTS、ArkUI V2。
- 一个 entry HAP + `chat`、`deepread` 两个 cohesive HAR。
- **Android 源码只读**：`app/`、`feature/`、`ai/` 等目录是行为基准，禁止修改。
- 等价优先级：数据/协议 > 核心闭环 > 错误恢复 > 交互 > 视觉。
- Domain 只能依赖 Ports，不能直接依赖 entry 平台实现。
- 红线：禁止删除业务逻辑过编译；禁止空实现返回成功；禁止静默 no-op；禁止未登记功能删除；禁止破坏性 DB 迁移；日志禁止 token/key/隐私。
- 只有 L3 决策点（不可逆数据丢失、支付/账户/法务/隐私、生产密钥证书、系统签名、破坏生产协议）才停下问用户。
- `hdc list targets` 为空时不得等待，继续最高优先级未阻塞的 source-only 工作。
- **不要过度防御、过度兜底、过度设计。** 新 Port、codec、缓存、fallback、公共 helper 或抽象层必须同时具备：Android 当前依据、当前阶段真实消费者、行为测试。

## 已完成并提交

### D-131 AgentTaskTools

```text
94f89d897 feat(agent): D-131 AgentTaskTools 六件 + Scheduler 装配
60ed0fd0a chore(state): head 指针更新 → 94f89d897(D-131 AgentTaskTools)
```

已完成 AgentTaskScheduler、六件 `agent_task_*` 工具、共享 AgentTaskStore、AppContainer/ChatPage 装配。

### D-132a Subagent 纯域基础

```text
c8cb1875a feat(agent): D-132a subagent 纯域基础
b8a0ae6a6 chore(state): head 指针更新 → c8cb1875a(D-132a subagent)
```

已完成 runtime models、Validator、`subagent_report`、child tool scope、transcript JSONL/安全 tail reader。

### D-132b Subagent Runner + Manager

```text
1db56d3c3 feat(agent): D-132b subagent runner 与 manager
14c9e2cd4 chore(state): head 指针更新 → 1db56d3c3(D-132b subagent)
```

已完成：

- shared tool-loop core；main 仍 16..512 clamp，child 使用精确 1/2/4 step；
- isolated child assistant 和真实 provider loop；
- selected-model reasoning defaults（GPT/Codex/o-series → medium，DeepSeek → high）；
- model override/current model fallback、assistant resolution；
- generation/tool retry、report retry、approval、abort、窄 provider fallback；
- Manager admission/grant/lifecycle/cancel/wait/terminal race；
- AgentTaskStore 映射、transcript 顺序、live text/parts subscription 和 64 soft cap；
- `SubAgentTranscriptTail` barrel 类型闭包；
- Manager API 收敛为具体 `SubAgentManager` + 最小 `SubAgentManagerDeps`。

最新门禁：

```text
D-132b focused: 93/93
chat: 1542/1542, tsc clean
deepread: 610/610, tsc clean
assembleHar: success
assembleHap: success
stage review: APPROVED
```

D-132b 没有新增 `PARITY_DEBT`。

## 当前最高优先级：D-132c

不要重新审查或重写 D-132a/b。直接从 Task 8 开始，连续完成 Task 8–10。

### Task 8：五件公共 SubAgentTools

新建：

```text
harmony/chat/src/main/ets/chat/subagent_tools.ts
harmony/chat/src/test/subagent_tools.test.ts
```

五件顺序：

```text
subagent_list
subagent_start
subagent_read
subagent_wait
subagent_cancel
```

要求：

- 对照 Android `feature/tools/impl/.../SubAgentTools.kt`，先 RED 后最小 GREEN；
- pin schema、required、wait 默认 10000/max 60000、roster/smart prompt、manager delegation；
- `parentToolsProvider` 只返回已经 profile 过的 parent base tools；
- public tools 不能进入 child scope；
- council enabled 状态不要重新塞回 Manager，工具层单独读取既有 council settings；
- 不新建第二套 registry、dispatcher 或 manager service interface。

### Task 9：设置水合与 entry adapters

必须复用：

```text
harmony/entry/src/main/ets/platform_impl/PromptConfigStore.ets
createEntryAgentPromptSettingsPort(getChatKvStore())
```

它已经拥有 `agent_runtime` 的 subAgent/modelCouncil parser、serializer 和 unknown-key-preserving RMW。

禁止：

- 在 `AgentRuntimePrefs.ets` 重写 subagent settings；
- 新建第二套 settings store、JSON codec 或 RMW；
- 复制 D-128 已有设置逻辑。

应做：

1. 新建 `harmony/entry/src/main/ets/platform_impl/SubAgentStore.ets`，实现 D-132a `SubAgentTranscriptPort`：私有 runs 目录、fileIo append/stat/access、canonical containment、bounded tail。
2. AppContainer 复用一个共享 `AgentPromptSettingsPort`。
3. 为 `SubAgentManagerDeps.getSubAgentSetting` 提供**薄 hydrated snapshot bridge**：只缓存/刷新现有共享 port 的当前设置，不负责 parser/serializer/RMW。
4. 缓存 runner/manager/tools Promise 单例。
5. 复用现有 `getAgentTaskStore()`、`getSessionGrantStore()`、provider/model/assistant resolution 和 `GenerationRetrySetting`。

### Task 10：ChatPage 装配

按 Android ChatService 顺序：

1. conversation context/history/queue；
2. D-131 task tools；
3. parent profile filter；
4. base registry + parent discovery；
5. conversation 存在且 subagent enabled 时挂五件 SubAgentTools；
6. council 和其他 supervisor groups；
7. final profile/registry/activity wrapping。

约束：

- `parentToolsProvider` 只能捕获第 4 步 base tools；
- `subagent_*` 不能出现在 child registry；
- 不把 SubAgentTools 加入 LocalTools；
- 不提前实现 `ChatMessageSubAgentStep` 专属 UI，该 UI 是 runtime 闭环后的独立交互片。

## D-132c 执行纪律

- 每个任务先对照 Android 当前源码和 Android 测试。
- 先写 RED，再做最小 GREEN。
- Task 8–10 完成后只做一次阶段级 plan-drift/spec/code-quality/anti-overdesign review。
- 不重复已经通过的 D-132a/b 审查。
- reviewer 建议若无 Android 依据、当前消费者和行为测试，不要实现。
- 设备不可用不能阻止 source-only 提交。

## 提交前门禁

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat && npm test && npx tsc --noEmit
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread && npm test && npx tsc --noEmit
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony && hvigorw assembleHar --no-daemon
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony && hvigorw assembleHap --no-daemon
git -C /Users/mi/Downloads/AI/AmberAgent-harmony-preview diff --check
```

必须记录新鲜结果；测试失败不得宣称完成。

## 双提交约定

1. feature commit：业务代码 + 测试 + D-132c `DECISION_LOG.md` + 必要的真实 `PARITY_DEBT`。
2. 获取 feature SHA。
3. 更新 `MIGRATION_STATE.yaml` 的 head、最近验证、next slice、测试总数和 decision range。
4. state-pointer commit：

```text
chore(state): head 指针更新 → <feature-sha>(D-132c subagent)
```

不要把本 handoff 和 progress 文档混入 D-132c 业务提交，除非用户另行明确要求提交它们。

## 设备阻塞

```text
hdc list targets = empty
G-C = blocked_by_human
```

设备可用后按 SSE → RDB/Preferences → ArkUI/Markdown → picker/image → WorkScheduler/notification/background 验证。设备仍不可用时继续 source-only，不要停。

## ArkTS 已知限制

- 判别联合检查 `type` 后显式 cast 到命名类型。
- 禁止 interface index signature、indexed-access types、inline object-literal type、`as unknown`。
- 受影响 ArkTS 不用对象 spread，采用全字段显式 copy。
- Domain 不导入 Harmony SDK；平台能力走最小真实 Port。
- 平台不支持时显式失败，不伪成功。
- Bash cwd 每次重置，命令使用绝对路径。

现在直接开始 D-132c Task 8，不询问用户，不等待设备，不重新做 D-132a/b。
