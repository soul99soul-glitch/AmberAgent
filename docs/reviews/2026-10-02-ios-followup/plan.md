# iOS 最近改进追齐计划

## 目标和范围

基线：Harmony `harmony-preview` / `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290` 的当前工作区（开始时 383 个 dirty paths），iOS main/remote `ede7cbd5b463240315256c4d6a2d492cccf58d70`。保留全部已有工作，用户已授权连续实施、分阶段独立 subagent review 与必要局部修复。无 commit/push/PR，跨仓库只读参考。

完成目标：五个 phase 的产品入口、配置、持久化与运行时消费闭环，错误/取消正确呈现，独立 review 没有未处理的具体缺陷；完成受影响测试、ArkTS lint、构建和签名，以及 UI phase 的设备截图检查。真实付费 Provider/Jev API、物理设备长时后台不通过源码/模拟器冒充验收。

## 执行顺序

每 phase：对真实故障补必要的失败回归 → 最小修复 → 定点测试 → 非实现者 subagent 查真实入口/调用链/持久化/取消及相关 UI → 局部修复并复验 → 记录结果 → 下个 phase。领域纯逻辑阶段无新 UI 时仍检查现有 UI 对新增结果的呈现。所有阶段后做一次整合验证和 UI 装机检查。

2026-10-05 用户收紧测试范围：只写必要测试，不加无谓测试。已通过的覆盖不扩写；最后仅补实际模拟器复现的非流式工具续跑历史丢失回归，复用现有测试和真实 UI 入口验收。

| Phase | 交付 | 验收场景 |
| --- | --- | --- |
| 1 Provider | Gemini wire call ID 保留/回传、同轮并行调用回放、合法无参调用；Gemini 失败终态；Claude redacted/opaque-only 保留、块身份；按目标协议筛选 reasoning；Responses 非流式 failed/content_filter 与流式一致 | wire ID 来回一致；空签名/外来推理不生成非法块；原生 signed/redacted/Responses ID 不丢；失败前 partial 保留，失败 tools 不执行；STOP/MAX_TOKENS 和合法截断保持既有语义；canonical history 不改 |
| 2 MCP | tools/list 分页到末页，保留工具 schema/annotations；核对已有 SSE 多事件/匹配 ID/CRLF | 两页工具实际进入同步目录，opaque cursor 正确使用；分页错误不写半份目录；错误/取消不伪造成功；已有 SSE 保护若闭环则只补缺失回归 |
| 3 产物架 | 正文生成式 HTML/widget 自动收录，与 mini_app/图片/文档统一发现；预览、来源、HTML 导出 | 只收录真实可打开的完成产物，补齐默认开启的正文生成式显示开关并与产物架同步；稳定去重/来源定位；预览和导出复用现有净化与沙箱；浅暗色、长标题、空态和底部边距 |
| 4 小说审批 | 提案在原位置呈现 pending/采用/拒绝/失败结果，完成态来源持久化；复用原审批/领域事务 | 审批成功后切分支/重启读回；失败不显示已采用；不能二次执行；计划提案与设定提案走原调用链；卡片标题/状态/按钮/展开内容在浅暗色正常；审稿通过与已收录分开 |
| 5 Jev v2 | 工具结果上下文筛选/同参重读全文保留，压缩保留、自动批准复核、完成校验；各用途 off/shadow/active 与设置接线 | off 零网络；shadow 不改结果；active 只影响对应用途；已取回原文不再次隐藏；自动批准复核只收紧到原审批卡；完成校验用户点击后才续跑；超时/失败沿原行为，取消/旧 run 不串；输入范围和预算复用已有机制 |

## 约束和裁定

- 精准修复实际差异，不增加通用重试/fallback/框架，不删除无关死代码，不改主题体系。
- 复用现有 ArkTS V1、CAS/lease/队列、工具审批、生成式净化、文件导出、AppStorage 设置机制。
- three.js 执行沙箱扩展、Watch、iOS 音频/定位保活不是本轮新增交付；已覆盖能力只验证，不重复实现。
- 小说索引性能、iOS 导入/storage 特有修复只在鸿蒙具体失败或热点被复现时局部处理，不转抄不适用的实现。
- 每 phase 报告记录 RED/GREEN、review 发现与处理、UI 实际检查与待验证项。运行日志放 /tmp，仓库保留精简报告。

## 集成与验证

Worker 写入范围互斥，明确告知不回滚其他人的修改。主 agent 整合并串行运行测试/构建/签名/安装，避免工具链冲突。Reviewer 只读，发现必须给出真实触发、用户影响、调用链和最小修复。最终以全部 phase 完成和实际证据为准，不把开始时已有 WIP 混称本轮工作。

状态：五个 phase 均完成并独立复查；Phase 5 A/B/C 与最后非流式修复已闭环，完成浅暗 UI、草稿追加及精确清理。公共出口由 root 整合。完成记录和验证边界见各 phase 报告及 state.json。

已查明的具体调整：Phase 2 的 inline SSE 旧 ID 改写确有缺陷，与分页同轮修复；完整 schema 必须通过 registry 提示包装及 discovery 输出，不只在 MCP 原始返回中保留。Phase 3 当前没有生成式开关，新增默认开启的单一 DisplaySetting 字段。Phase 4 设定采用失败保留 pending 和现有错误提示，不虚构新的持久化失败状态；本章计划工具失败有原工具结果可呈现。UI 测试数据通过现有聊天备份和小说完整备份导入，不绕过私有目录权限。

## Phase 5 入口与行为裁定

新增四个独立默认关闭用途：tool_context_selection / context_retention / auto_approval / completion_check，对应既有设置模型的四个用途字段；allowToolOutput 独立于工具元数据和任务文本许可。已有 context_selection 近期会话标题选择不被替换。复用既有 noul/choice，不扩展协议题型。

- 结果筛选只针对可重读的只读大文本；结构化错误、审批、不确定执行结果、分页标记保留，明确全文请求不评估；同参合法重读恢复并保持全文。仅改变 prepared projection。
- retention 在近期窗口内异步评估；prepare 不等其网络。出窗口未完成判断就固定沿原清空，不用迟到响应翻回原文；自动/手动压缩都消费同一保留投影。
- 自动批准复核只收紧已有自动 allow。静态普通只读、已有 ask/deny、用户批准的调用跳过；风险≥0.8，明确授权≥0.8免除破坏/离题，外发数据风险仍需原审批卡。前台、子代理、议会与包内 primitive 都在执行前闭环。
- 完成校验仅正常成功终态、本轮真实非文档文件变更且最后变更后无成功检查时评估；terminal 返回 running 不算成功检查。提示绑定 run 与最终消息，用户点击才沿原发送，草稿/附件存在只追加文字。
- 回程检查当前配置和取消；固定短 deadline、200 次预算请求前预留，不新增重试或统一 coordinator。主 agent 负责公共出口和整合，A/B/C 文件范围互斥，review 独立。
