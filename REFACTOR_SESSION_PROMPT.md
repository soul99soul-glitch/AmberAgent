# AmberAgent 重构推进 — Session Prompt

> 复制以下内容作为新 Claude Code session 的第一条消息。

---

我要推进 AmberAgent 从 Chatbot 架构到 Agent Kernel + Surfaces 架构的重构。项目路径：

```
/Users/arquiel/Downloads/AI/rikkashit/rikkahub-agent
```

## 背景

AmberAgent 是从 rikkahub fork + OpenOmniBot Agent 框架融合 + 大量新功能叠加而来的 Android/Kotlin/Compose 项目。21 万行 Kotlin，947 个文件，9 个 Gradle 模块。核心问题：ChatService 2618 行 god class 是宇宙中心，23 个 agent 子系统全部"贴"在 `:app` 里。

重构计划已有 v1.2.1 版本（2232 行，6 Phase），在这里：

```
/Users/arquiel/Downloads/AI/rikkashit/REFACTOR_PLAN_AGENT_KERNEL.md
```

## 本 session 目标：用 CodeGraph + Superpowers 对 plan 做架构验证和修订

### 你需要使用的工具

**CodeGraph MCP 工具**（已对项目建好索引：25,725 节点、66,636 边）：
- `codegraph_search` — 查符号定义
- `codegraph_callers` — 谁调了这个符号
- `codegraph_callees` — 这个符号调了谁
- `codegraph_impact` — 改这个符号会影响什么
- `codegraph_context` — 为某个任务构建上下文
- `codegraph_trace` — 追踪调用链路径

**Superpowers Skills**：
- `/superpowers:brainstorming` — 探索设计前先用
- `/superpowers:writing-plans` — 写/修订 plan 时用
- `/superpowers:systematic-debugging` — 遇到矛盾或不一致时用
- `/superpowers:requesting-code-review` — 完成修订后自审

### 执行步骤

#### Phase 1：架构现状验证（CodeGraph 驱动）

用 CodeGraph 验证 plan 的核心假设是否仍然成立：

1. **ChatService 依赖图**：用 `codegraph_callers` 和 `codegraph_callees` 分析 `ChatService` 的完整调用链。确认 plan 说的"2618 行 god class"到底有多少职责纠缠在一起，哪些依赖是可以干净切断的，哪些会拖泥带水。

2. **Agent 子系统耦合分析**：对 `data/agent/` 下的 23 个子目录，用 `codegraph_impact` 逐个分析：
   - 它们之间有多少交叉引用？
   - 哪些子系统是真正独立的（可以直接拆成 `:feature:*`）？
   - 哪些子系统跟 ChatService 深度耦合（Phase C 之前拆不出去）？

3. **DeepRead 现状快照**：plan v1.2.1 说 DeepRead 已经自演化出"准 Runner"（`DeepReadAgentRunManager`），用 `codegraph_callers`/`codegraph_callees` 画出它当前的完整依赖图——这直接影响 Phase B 是"适配"还是"重写"的决策。

4. **GenerationHandler 调用链**：plan Phase A TA.4 说要把 `GenerationHandler` 改成 `RunScope` 的第一个消费者。用 `codegraph_trace` 追踪从 ChatService → GenerationHandler → AgentToolDispatcher → SpeculativeToolRunner → PermissionDecisionResolver 的完整链路，确认 plan 的拆分边界是否合理。

5. **Legacy 包范围确认**：用 `codegraph_search` 扫描 `me.rerere.rikkahub` 包下的所有符号，确认 plan Phase 0 的 legacy 白名单是否完整（特别是 Room schema 路径和 JNI 符号绑定）。

#### Phase 2：Plan Review（Superpowers 驱动）

用 `/superpowers:brainstorming` 基于 Phase 1 的发现，对 plan 做以下审查：

1. **Phase 优先级是否正确**：plan 的 Phase 顺序是 0→A→A.5→B→C→D→E→F。CodeGraph 发现的耦合关系是否支持这个顺序？有没有隐藏的前置依赖会导致某个 Phase 卡住？

2. **时间估算校准**：plan 给了 6.5-9.5 个月总周期。基于 CodeGraph 揭示的实际耦合复杂度，哪些 Phase 的估时偏乐观？哪些偏保守？

3. **风险盲点**：plan 列了每个 Phase 的 Risk，但基于 CodeGraph 的依赖分析，是否有 plan 没识别到的耦合风险？

4. **Quick Wins 识别**：CodeGraph 是否揭示了一些 plan 没提到但可以低成本先做的解耦？比如某些 agent 子系统实际上已经几乎独立，可以不等 Phase D 就先拆？

#### Phase 3：Plan 修订

用 `/superpowers:writing-plans` 基于 Phase 1-2 的发现，输出一份 plan 修订建议：

- 不重写整个 plan——只输出 delta（需要改的部分）
- 每条修订标注原因（指向 CodeGraph 的具体发现）
- 对于有争议的修订，给出 2-3 个选项和各自的 trade-off

最后用 `/superpowers:requesting-code-review` 对修订建议做自审，确保修订本身不引入新的盲点。

#### Phase 4：输出执行路线图

把验证和修订的结果整理成一份可执行的文档：

- 更新后的 Phase 0 具体 checklist（可以马上开始做的）
- 基于 CodeGraph 数据的模块依赖图（文字描述即可）
- 修订后的时间线和里程碑
- 第一个要动手的 PR 的具体范围

输出写到：`/Users/arquiel/Downloads/AI/rikkashit/REFACTOR_EXECUTION_ROADMAP.md`

## 约束

- 不要修改项目代码，这个 session 纯分析和 plan 修订
- 发现 plan 跟代码现状有矛盾时，以代码为准，更新 plan
- 大的方向改变先列选项让我决定，不要直接覆盖 plan 的已有决策
- 用中文输出（技术术语保留英文）
