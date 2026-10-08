# Tools / search / subagents 审查

日期：2026-10-02。基线 HEAD：`5ea51f7a`，审查的是当前工作区（含未提交和 untracked 文件），没有修改产品源码。

检查范围：chat search services/orchestrator/tools、skills、subagent manager/runner/tools/scope/report、tool exposure/dispatcher/permission/policy，以及 entry ConversationRunTools/Auxiliary、RunToolCatalog、SearchRegistry、AppContainer 子代理装配、CouncilChatSupport。已加载 harmony-arkts skill。

## 确认的问题

### T1 · P2 · 搜索工具停止只结束上层等待，没有停止实际 HTTP

- 位置：`harmony/chat/src/main/ets/search/search_tools.ts:209-215,256-259`；`search_orchestrator.ts:601-612,621-626,672-685`。
- 触发：聊天或子代理执行 `search_web` / `scrape_web` 时停止其所在运行。
- 调用链：ConversationRunTools → createSearchTools → searchOrchestratorSearch → defaultExecutor → getSearchService/runFreeEngine → HTTP。入口接收 signal，但 orchestrator 和 scrape 外呼没有收到它；raceAbort 只提前拒绝工具 Promise。
- 影响：界面已经停止，实际 API 请求还在执行；不会收到有效 abort，免费引擎也可能继续处理响应、更新共享熔断状态。HTTP 自身超时限制损失长度，但不能代替取消。
- 二次复核：服务层 SearchRequestContext 已支持 per-call signal，现有 `search_cancellation.test.ts` 只验证直接调用服务的取消，没有覆盖用户实际经过的工具入口；旧注释确实注明“后续接线”，因此这是功能缺口而非底层不具备能力。
- 最小修复：给 orchestrator 的 executor 增加可选 per-call context，把同一个 signal 传给 configured/free service；scrape 直接传 context。取消不归一成普通来源失败；保留已有并发、查询策略、快照设置。
- 测试：从 createSearchTools 执行 search_web/scrape_web，HTTP mock 必须拿到原 signal；abort 时 mock 被终止。覆盖已取消的 signal 不发请求，以及两次并发请求只取消其中之一。

### T2 · P2 · 技能管理成功后，同一轮技能目录和权限状态仍为旧快照

- 位置：`harmony/entry/src/main/ets/platform_impl/ConversationRunTools.ets:288-295,339-342,354-359`；`harmony/chat/src/main/ets/chat/skills.ts:943-952,1162-1177`。
- 触发：同一工具循环执行 `skill_enable` / `skill_import` 后立即 `skills_list` / `use_skill`，或 disable 后再次 use_skill。
- 现象：管理操作已经保存到助手和磁盘，但 createSkillTools 的 enabledSkills/allSkills 固定于 run 开始；refreshTools 只重建 recipes/plugins 包装，primitives 仍是旧 profiledTools。enable/import 后 use_skill 可能根本没注册；disable 后旧 use_skill 仍将其视为 enabled。
- 二次复核：`setSkillEnabled` 通过 mutateAssistant 操作当前 rt.assistant.id；管理工具不是禁用/占位工具，工具描述明确表示 import 默认 enabled。问题不是运行中切换 provider/模型的快照要求，应只刷新技能目录及该助手的技能启用状态。
- 最小修复：catalog 刷新时重建技能工具部分，读取同一助手 ID 的最新 enabledSkills 和 SkillManager.listSkills；其余 primitives/provider/model 参数继续原快照。避免刷新整套运行依赖或新造 registry。
- 测试：enable→下一步目录包含 use_skill 且加载成功；import→下一步 list/use 可见新技能；disable→下一步禁止加载。验证另一个 assistant/provider/model 不被切换。

### T3 · P2 · 补交 subagent_report 的重试遇到审批，会被误报 completed

- 位置：`harmony/chat/src/main/ets/chat/subagent_runner.ts:398-419,421-427`。
- 触发：子代理首轮输出正常可见文本但没有 report，runner 发内部 reminder；第二轮仍能调用全部 childTools，模型请求审批后停住。
- 影响：第一轮之后有 pendingToolName 检查，第二轮之后没有；resultOrFallback 将未完成的子代理报告为 completed，任务板和父模型均看不到 approval_required。
- 二次复核：报告重试实际包含全部 childTools，pending 可由真实共享 tool loop 产生，不是假设不可达分支。已有 subagent_runner 测试覆盖首轮 pending 和重试，缺少两者组合。
- 最小修复：最后一次 generation 结束后统一复查最终 pending tool，返回已有 approval_required 结构；保持第一轮可见内容和 report fallback 语义，勿增加重试。
- 测试：首轮 plain answer、第二轮 pending session_read（或 memory_write），断言 approval_required，保留原可见答案，manager/task 对应 interrupted 并仍可 cancel。

### T4 · P2 · 等待子代理或议会时，聊天 Stop 最多延迟 60 / 180 秒收口

- 位置：`harmony/chat/src/main/ets/chat/subagent_tools.ts:326-329` / `subagent_manager.ts:451-464`；`harmony/chat/src/main/ets/chat/council_tools.ts:71-80` / `harmony/deepread/src/main/ets/council/manager.ts:214-225`。
- 触发：父聊天在 subagent_wait（工具指导首次传 60000ms）或 model_council_wait（允许180000ms）时 Stop。
- 调用链：ConversationRunService.cancel → kernel.cancel / controller.abort；ChatKernelHost.run 仍 await runInteractiveTurn，tool dispatcher 仍 await tool.execute；两种 wait execute 都忽略 signal，必须等子运行结束或等待超时后父运行才能 finally 释放。
- 影响：会话 busy、后台 keepalive、待发队列收口被挂住；用户已停止却不能立即继续同一会话。
- 二次复核：子运行独立于父聊天继续执行是合理设计，本项不要求 Stop 自动取消子代理/议会，只要求终止父方等待。manager 的独立 cancel 功能与此不同。
- 最小修复：wait 接受可选 signal，等待时 AbortError 及时拒绝并清理计时器/监听；不修改子 run 状态，不增加通用 polling/重试框架。
- 测试：执行两种 wait 后 abort 父 signal，应立即拒绝；子 manager cancel 未调用，子任务仍 running。另测等待成功/超时及 already-aborted 不进入长等待。

## 红证据

临时复现脚本（本机 `/tmp/amber-tools-review-proof.mts`，不改仓库）使用实际 createSearchTools、GenerationSubAgentRunner、createSkillTools、SubAgentTools、createCouncilTools，只替换 HTTP/storage/generation/wait 端口。

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json /tmp/amber-tools-review-proof.mts
```

输出：

```text
search tool result AbortError
HTTP calls after tool abort 1 forwarded signals [ false ]
report retry pending -> status completed summary Existing visible answer generations 2
skill persistent enabled [ 'demo' ] catalog enabled_count 0 has use_skill false
subagent wait settled after Stop? false wait args length 2
council wait settled after Stop? false wait args length 2
```

等待测试用可控未完成 Promise，abort 后 25ms 检查仍未收口，随后显式 resolve，避免留下计时器。搜索测试同样结束 mock HTTP。T2 的直接域层测试验证旧快照行为；entry 实际每步 refresh 不会重建技能工具已通过源码调用链复核。

## 排除项与边界

- 自动签发有界 history grant 是既有设计；无 grant 的子代理历史调用仍被敏感风险审批拦住，不能仅根据 copyToolForHistoryGrant(needsApproval=false) 推断绕过审批。
- approval_required 的 manager 仍 live 且可 cancel，但任务板映射 INTERRUPTED 是明确现行设计。
- 停止父聊天不自动取消已经独立启动的子任务/议会，本次不改变。
- terminal subagent runs Map 留存、dynamic admission 冗余分支、技能 ZIP 无条件剥离第一段等已有移植约定，不据此扩大本轮修复。flat ZIP 文件夹失真可另作产品支持范围决定，未计入确认问题。
- SearchRegistry 中历史独立 key adapters 明确不启用，当前配置统一来自搜索设置；没有把死代码当现行 provider。
- Auxiliary 的 title/suggestions 同时 beginConversationRun 不互斥：guard 是活动计数，仅阻止 restore，不是排他锁；未误报并发启动失败。
- SDK/API 行为、真实服务返回格式及设备可见控制未以在线服务或真机验收验证；上述问题均有源码调用链与可控端口复现，不声称 provider 端到端通过。

## 建议 Phase 2 修复顺序

1. 先加 T1/T4 的工具入口红测试，修复 per-call signal 的丢失；运行搜索、子代理、议会相关 focused suites。
2. 加 T2 的管理→刷新→加载闭环测试，局部刷新技能工具，不碰其他 run 配置快照。
3. 加 T3 的第二轮审批复现，复用既有 approval_required 返回结构。
4. Phase 后独立 subagent 审查取消范围、工具可见性、技能权限刷新、误报 completed 四项，补 ArkTS lint / build 证据；生命周期改动按主流程补设备验证。
