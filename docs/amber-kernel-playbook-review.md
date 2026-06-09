# Review：Codex「Amber = 手机上的主动智能运行时」方案

> 状态：设计评审（reviewer 视角，仅文档，不改代码）
> 输入：Codex 基于 Pi 哲学提出的 Kernel 极小化 / Playbook / PolicyGate / Capability / steering 方案
> 立场：骨架采纳，但加三道刹车（自由度、manifest 抽象度、steering）
> 依据：实读 `core/agent-runtime`（`Agent/RunScope/PermissionBroker/AgentEvent/AgentRegistry/AgentRunner/Surface/
> ModelRouter/TraceRecorder` 等顶层声明）/ `core/agent-runtime-impl`（`InMemoryAgentRegistry/InProcessAgentRunner/
> LegacyRunScope`）/ `feature/tools/api`（`ToolRegistry/ToolMetadata/ToolInvocationPolicy/ToolRisk`）/ `feature/subagent`
> （`SubAgentManager/SubAgentRunner/...`）/ `core/agent-store-room`（`agent_run/agent_event/trace_span/permission_intent`）
> / `AgentLiveStatusNotifier`；以及 Pi 的 README/extensions/session-format（DeepWiki + 官网）、yibie「拖拽式已死」一文。
> 名称均已 grep 核对；下方映射表为实读结果（已纠正初稿中 `ToolGate` 等不存在的臆测名）。

---

## 0. 一句话结论

Codex 把我上一轮的「机制层(写死) / 策略层(动态)」抽象**具体化**为 Kernel + Playbook + PolicyGate +
Capability，这是实质升级、采纳。但它在**两个维度各超调了一档**，不刹车会返工或翻车：

1. **动态自由度**：Codex 让 `TaskRuntime` 全局「由 Agent 动态生成执行计划」——必须锁回**仅前台**；后台扫描保持确定性。
2. **抽象度**：Codex 给 1–2 个一方功能造了 `manifest + package` 插件市场——v1 砍掉（YAGNI，正是我们一路砍的 `CapabilityRegistry` 同味）。

外加一条纪律：**新名词必须先映射到已有类**，否则重蹈 `AgentTask` 撞名覆辙。好消息：Codex 的 Kernel 六件套**五件已存在**。

---

## 1. 采纳的部分（方向对，且和现有代码对得上）

| Codex 提议 | 裁定 | 依据 |
|---|---|---|
| Kernel 极小 + Capability 外置 | ✅ **收敛已有资产，不新建** | `core/agent-runtime` 已有 Agent/RunScope/Surface/AgentDescriptor/AgentRunStore |
| Event Hook 作为扩展点 | ✅ | `AgentEventPayload`(sealed) + `AgentEventStore` / `AgentEventWriter` 已在 |
| PolicyGate 拦截高危动作 | ✅ **已存在，叫 `PermissionBroker`** | `PermissionBroker.kt`：`PermissionBroker` / `PermissionIntent` / `PermissionKind` / `PermissionDecision`(sealed) / `ApprovalChannel`；工具侧另有 `ToolInvocationPolicy` / `ToolRisk` / `ToolPermission` |
| 审计落库 | ✅ 复用 `permission_intent` 表 + `PermissionIntent` 模型，不新设计 | `core/agent-store-room` + `PermissionBroker.kt` |
| BoardTaskEvent 是事实源非辅助表 | ✅ 完全采纳（= 上一轮「任务账本即 agent 外部工作记忆」） | |
| 反向吸取 Pi 安全教训 | ✅ | Pi README 自陈 package 全权限 |
| 不照搬 no-popup / no-background-bash | ✅ Codex 判断比原文清醒 | Amber 是手机无人值守，风险画像不同 |

---

## 2. 三道刹车（不踩会返工 / 翻车）

### P0 — 砍掉 Capability manifest / package 系统（v1 过度工程）

Codex 给 playbook/package 设计了 `manifest{capabilities, required_permissions, risk_level,
allowed_tools, writeback_policy, network_policy}` + package 分发。

- 这与前几轮被我们砍掉的 `CapabilityRegistry` 同味：**为不存在的扩展性付费**。
- v1 只有 1–2 个**内置一方** playbook（meeting_prep / dependency_stale），无第三方分发、无动态安装。
- **裁定**：v1 的 `PlaybookSpec` = 代码内一个 data class / 一段 skill 描述，`riskLevel / allowedTools /
  writebackPolicy` 直接是字段；manifest + package 分发**推迟到真有外部 playbook 时**。

### P1 — 动态编排自由度必须锁在「前台 / 后台」轴上（Codex 这版把它丢了）

这是我上一轮的核心 push-back。yibie 文章**自己的数据**就是反证：动态多烧 15–25% token、
方差可达万倍、93% 权限提示被「审批疲劳」点过、Opus 4.8 恶意 computer-use 评分更差。

> 决定自由度的轴，**不是任务复杂度，是「前台有人盯 vs 后台无人值守」。**

- **后台**（每天 9/15/20 自动扫描、Opportunity 闸门）= **确定性、capped、绝不 agent 自由编排、绝不写**。
  一个无人值守、每天自由编排上千子 agent 扫你飞书文档的东西，正是文章警告的成本/安全爆点。
- **前台**（用户已点「派发」、正在看）= edge 放宽，`TaskRuntime` 可让 agent 动态编排「怎么查、读哪些、怎么起草」。
- **裁定**：动态编排只在前台 BoardTask 执行里开放；后台路径写死。

### P2 — steering（通知栏回复注入运行中任务）概念对，但 v1 不做

「通知栏 inline reply = steering message 而非聊天」很漂亮，但它**要求 BoardTask 是长活、可中途打断重规划的运行时**——
有状态并发的硬骨头（跑到一半注入「别写回飞书」→ 安全中断 + 重规划 + 不丢事件）。

- v1 执行是**短时、跑完即 `waiting_user`**，没有「运行中」窗口可 steer。
- **裁定**：v1 降级为「任务完成后用户在卡片/通知上反馈 → 影响下一次」；steering 留到任务真变成长活进程时（v2）。

### P3 — 守住命名收敛（前几轮已为撞名打过两仗）

Codex 新引入 `AmberKernel / PlaybookSpec / TaskRuntime / Capability / PolicyGate / Notification Remote`，
多个与已有类潜在撞名。开工前必须用下表对齐，**能复用的不新建**（android-dev existing-pattern 铁律）。

---

## 3. 新名词 ↔ 已有类 映射表（实读填实）

| Codex 名词 | 现有对应（已存在） | 裁定 |
|---|---|---|
| `AmberKernel`（task runtime / event log / context builder） | `core/agent-runtime`：`Agent` / `RunScope` / `AgentDescriptor` / `AgentRunner` / `AgentRegistry` / `AgentEventStore` / `Surface` / `ModelRouter`；impl：`InProcessAgentRunner` / `InMemoryAgentRegistry` / `LegacyRunScope` | **复用，勿造**。Kernel 已存在，差的只是「把主动场景路由进来」 |
| `PolicyGate` | **`PermissionBroker`**（`PermissionBroker.kt`）+ `PermissionIntent` / `PermissionKind` / `PermissionDecision`(sealed) / `ApprovalChannel`；工具侧 `ToolInvocationPolicy` / `ToolRisk` / `ToolPermission`（`ToolRegistry.kt`） | **直接复用 `PermissionBroker`，别新建 PolicyGate**；高危 playbook 动作走它；写回/发消息映射到一个高 `ToolRisk` 工具 |
| `tool registry` | `feature/tools/api`：`ToolRegistry` + `ToolMetadata` + `ToolInvocationPolicy` + `ToolRisk`（已存在） | 复用 |
| `Capability` | `ToolRegistry` 注册的 Tool + `feature/subagent`：`SubAgentManager` / `SubAgentRunner` / `SubAgentTools` | 复用「Tool」概念，**不要再引入 `Capability` 第三个同义词**（避免重蹈 `CapabilityRegistry`） |
| event log / 事实源 | `AgentEventPayload`(sealed) + `AgentEventRecord` / `AgentRunRecord` / `TraceSpanRecord` + `AgentEventStore` / `AgentEventWriter` + `core/agent-store-room`(`agent_run/agent_event/trace_span`) + 上一轮 `BoardTaskEvent` | 复用；BoardTaskEvent 作业务事实源，`agent_event` 作运行 trace |
| 审计 | `permission_intent` 表 + `PermissionIntent` 模型 | 复用 |
| `Notification Remote` | `AgentLiveStatusNotifier` + `NotificationUtil.XiaomiSuperIslandConfig` | 复用；加 `notifyBoardTask()`（见主文档），别新造通知层 |
| `TaskRuntime`（动态编排） | 新增薄层，**架在 `Agent`/`AgentRunner`/`RunScope` 之上**，仅前台 | 唯一真需要新写的，且要避开与 `agent-runtime` 命名冲突（建议 `BoardTaskRunner`/前台执行器） |
| `PlaybookSpec` | 无（新增） | 新增，但 v1 = data class/skill，非 manifest |

> 结论：Codex 的 Kernel 六件套 **5/6 已存在**。真正要新写的只有：薄薄的「前台 TaskRuntime」+ `PlaybookSpec` 两个内置实例。其余是**接线**，不是造框架。

---

## 4. 从 Pi 借什么 / 不借什么（已确认）

**借（架构）**：primitives-not-features（核心小、能力外置）；生命周期 hook 作为定制缝
（Pi `tool_call` 可 `block/patch` ↔ 我们的 `PermissionBroker` + `ToolInvocationPolicy/ToolRisk`）；
事件流/trace 一等公民（解「可观测性黑洞」，而我们 `agent_event/trace_span` 已领先）；session/event log 作可恢复事实源。

**不借（风险偏好）**：Pi「no permission popups（靠 container 兜底）」「package 全系统权限、装前自审」——
Amber 动的是手机/通知/飞书/ADB，**必须**细粒度确认 + 审计 + 可撤销 + 后台不黑箱（经任务流/事件/通知/超级岛暴露状态）。
动态编排只在**固定、已审、本地**的工具集上跑，绝不开放任意扩展全权限执行。

---

## 5. 修正后的 v1 形态（净结论）

```
Opportunity Detector   后台 · 确定性 · capped · 绝不派发 · 绝不写
        │ (用户点「派发」——唯一人类闸门)
        ▼
PlaybookSpec           代码内 data class/skill：目标 + 证据要求 + allowedTools + 禁止动作 + 成功标准
        │              （v1：meeting_prep / dependency_stale 两个内置实例，无 manifest/package）
        ▼
前台 TaskRunner        架在已有 Agent/RunScope 上 · agent 动态编排「怎么做」· 仅前台 · 有 token/步数预算
        │
        ├─ Tool（复用 ToolRegistry）— 读类自动
        ├─ PermissionBroker（复用，=PolicyGate）— 写回/发消息/ADB 强制确认 + permission_intent 审计
        ▼
BoardTaskEvent         一等事实源 = agent 外部工作记忆（推理/计划/证据/下一步）
        ▼
waiting_user → 确认 → 写回      （v1 无 steering；完成后反馈影响下次）
        ▼
AgentLiveStatusNotifier.notifyBoardTask()   复用现有超级岛/Live
```

一句话：**Codex 的 Kernel+Playbook+PolicyGate 升级采纳；但把自由度锁回前台、把 manifest 推迟、把 steering 降级，
并复用已存在的 5/6 件套——剩下的 v1 反而更小：只新增「前台 TaskRunner + 两个 PlaybookSpec」。**

---

## 6. 最容易返工的点

1. 把 `PolicyGate` 当新东西造 → 已有 `PermissionBroker`（+ `ToolInvocationPolicy/ToolRisk`），直接用。
2. 后台扫描也开放 agent 动态编排 → 成本/安全爆点，锁前台。
3. v1 上 manifest/package → 为不存在的扩展性付费，砍。
4. v1 上 steering → 给不存在的并发模型写中断逻辑，推迟。
5. 引入 `Capability` 作第三个 Tool 同义词 → 命名熵增，复用 Tool。
6. 新建 `TaskRuntime` 与 `core/agent-runtime` 命名打架 → 用区分名（如 BoardTaskRunner）。

---

## 7. 待你拍板

1. 是否接受「v1 砍 manifest/package、砍 steering、动态编排仅前台」这三道刹车？
2. 「前台 TaskRunner 架在已有 `Agent`/`AgentRunner`/`RunScope` 上」——是否要我下一步实读 `Agent.kt` / `RunScope.kt` /
   `AgentRunner.kt` / `InProcessAgentRunner` 的接口体，确认 PlaybookSpec 能干净接入、`PermissionBroker` 能承接写回确认，再产出落地接线方案？
3. 命名：前台执行器定为 `BoardTaskRunner`，playbook 定为 `PlaybookSpec`，能力沿用 `Tool`——可否？
