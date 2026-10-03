# P6 独立运行与搜索复核

状态：**CLOSED — 当前源码、实际调用测试与持久化边界通过**。本报告不代表签名构建、设备页面、真实云模型质量或原生 Google 页面验收；这些由 root 和入口 reviewer 单独记录。

## 实际 iOS 对照与生产链

对照的是 `iosApp/DeepReadApp/project.yml` 实际引入的共享 DeepReadCore 和搜索实现，以及独立应用 `Sources/Runtime/DeepReadRuntime.swift`。`create` 创建来源与模板记录，`execute` 收集/补齐来源后调用 `IOSDeepReadDraftGenerator.generateViaLLMResult`；该 generator 先规划，再生成 overview / narrative / analysis / extended-reading 四段 JSON。每段失败只纠正一次，保留已完成段；扩展段接受链接、头图或至少两个节点的图解。搜索 `IOSSearchExecutor.searchProviderSelection` 优先一个启用且可执行的首选配置，失效则选一个其他可用配置，再退到免费聚合。`IOSFreeSearchAggregator.weakResultThreshold = 3`，弱结果且开关开启才调用 Google。

Harmony 实际链为 Compose 持久化来源与模板 → Article 显式运行/继续/单段重试 → Scheduler 准入 token 与第四参数 options → AppContainer 每轮捕获模型、认证、搜索与模板 → RunManager 来源/规划 → Supervisor 四段 JSON 或既有 writer-tools → 原有 section writer 校验 → RunRepositoryAdapter → RDB。模型能力由生产 AppContainer 的 `runtime.model.abilities.includes('tool')` 分流；普通/仅 reasoning 模型进入 structured，固定失效模型 UUID 仍真实拒绝，历史浏览不要求配置模型。

## 已验证的闭环

- Structured 四段均以 `tools: []` 调用实际 AiClient；真实 localhost Responses HTTP/SSE 完成五次请求（规划加四段），温度、输出上限、reasoning、customBody、headers 保留，模型预配置 builtin tools 不泄漏到请求。坏分析 JSON 只增加一次纠正请求，不把自由文本 fallback 标成 READY。
- JSON 经 `structured_stage.ts` 交给现有目标 writer。前段正文以独立的 `structuredArticleBody` 提供给后段，目标段不会覆盖前段；实际请求不包含模板 HTML、templateSnapshot 或来源私有 note。解析后的字段仍受原有长度、URL、图片候选池和图解结构校验约束。
- 扩展段确有可达模型提示：`supervisor_loop.ts` 的 structured prompt 明确写“没有真实外部链接时不要编造”，并给出 diagram 的 type/title/nodes/edges。无 URL 的手动原文可以用合法两节点图解完成；独立反例另外证明合法候选 hero 单独可完成，任意图片 URL/无效图解不能完成。这里的链接校验是现有合法 URL 校验，图解校验是结构校验；测试不声称能够自动证明模型每个事实的真实性。
- 冷恢复仅请求非 READY 段；READY 正文保持。HTTP 分析流中途取消保留之前两段，不把未完成分析标成 READY。独立持有模型回包后取消、再放出合法分析 JSON 的反例也通过：没有迟到段写入，没有覆盖强制重做前的旧文章。Scheduler 既有单 topic 准入、信号与 token 回归通过。
- 强制重做的旧稿只在新稿四段完整提交时替换；坏 JSON、新来源失败与取消保留旧稿及模板。首轮无来源失败保留用户输入和已捕获模板；刚冻结的失败输出分支与正常 context 的缓存优先级一致。
- 模板贯穿第四参数、RunManager 与真实 Adapter/RDB 的每次阶段提交。续跑保留原文快照；初次或通用强制调用采用显式快照，其次运行默认。当前全局模板删除或修改不会改变原文快照。
- **两个生产入口语义已区分**：Article“重新生成本篇”显式传本篇捕获快照，保留 A；Compose 新建捕获用户本次选择。通用 `createRunManager(..., { force: true })` 没有显式快照才捕获当前默认 B。曾用“所有 force 都必须取 B”的错误预期检视 Article；核对 iOS `create(settings.templateId)` 与 `retry` 保留同一 task、以及 root 确认的产品语义后，实际 Article → 实际 DI 的测试同时证明本篇 A 保留、通用无显式 force 取 B。这不是生产缺陷。
- 实际 Entry SearchRegistry snapshot → Chat helper → Search SDK HTTP 只执行一个可用配置服务。首选被禁用/缺凭据/移除时选一个其他启用配置；全无可用配置才免费聚合。`Promise.all` 只聚合免费引擎，不并发所有已配置付费服务。一次 snapshot 冻结配置，下一次读取新设置。
- 免费结果去重少于三条且 Google 开关启用时才调用真实注入端口；关闭、已有三条结果、已取消都不调用。Google 失败保留正常结果；原生端口和选定服务 HTTP 分别只响应所属请求信号，取消后不继续其他搜索角度。原生宿主生命周期、聊天宿主隔离和设备 Google 页面归入口 reviewer，不在本报告重复判定。

## 独立验证记录

| 验证 | 当前复跑结果 | 日志 |
| --- | --- | --- |
| structured pipeline、RunManager、Supervisor、writer、Scheduler signal/start failure、actual template Adapter/RDB | 107/107 | `/tmp/amber-p6-independent-runtime-final.log` |
| 实际 Responses HTTP/SSE、AiClient、选定搜索服务/Google port、实际 Entry 模型配置 factory | 30/30 | `/tmp/amber-p6-independent-http-search-final.log` |
| root 新增实际 DI 的 Google topic 归属、writerMode、通用 force 模板 policy | 3/3 独立复跑 | `/tmp/amber-p6-independent-entry-container.log` |
| 独立候选 hero、请求正文排除私有元数据、取消后合法迟到回包不改旧稿 | 3/3 | `/tmp/amber-p6-runtime-independent-probes-final.log` |
| 实际 Article.runNow → 实际 factory：本篇重生保 A，通用无显式 force 取 B | 1/1 | `/tmp/amber-p6-article-force-template-review-final.log` |
| 本 reviewer 获授权更新的旧 Entry fixture | 5/5（包含在 30 项） | `/tmp/amber-p6-independent-entry-refresh.log` |
| 上述唯一改动测试文件的 ArkTS lint | No defects | `/tmp/amber-p6-independent-entry-fixture-lint.log` |

只按 root 授权修改 `harmony/chat/src/test/deepread_runtime_entry.test.cjs`：补新增平台端口 fixture，保留无模型历史、固定 UUID 失效、OAuth 首包与 token 归属的断言；将旧“普通模型文章必须拒绝”的预期更新为实际 structured factory 与 HTTP tools[] 断言，并增加显式/缓存/默认及强制模板优先级测试。没有修改生产源码、执行构建或设备操作。

本 scope 当前没有尚待修复的源码发现。全域测试、实际设备和原生宿主门槛继续以 root 最终日志为准。
