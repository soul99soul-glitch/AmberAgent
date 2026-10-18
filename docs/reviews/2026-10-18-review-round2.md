# 当前工作区第二轮审查与分阶段修复

Git 交付范围与交付树重新验证见 [交付记录](2026-10-18-review-round2-delivery.md)。下文保留原工作区审查与验收的历史快照。

审查基线：`harmony-preview`，`e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55`，包括接手时已经存在的未提交修复。本轮不重复计算上一轮已闭环的 26 项。初始 73 个修改/未跟踪文件已备份并固定 SHA-256，路径为 `.workflow/comprehensive-review-round2-20261018/baseline/`。

范围：深审当前 Harmony 产品的生产链路，并对仍参与 Gradle 构建的 Android SDK 进行重点审查。历史 Web UI 没有当前服务端或应用入口，所见候选不计为产品缺陷。此处不是对整个仓库零缺陷的保证。

本轮确认 25 项：24 项 P2，1 项 P3。每项有生产可达性、真实函数或实际源码端口复现及第二次复核。计数按平台/入口记录，B07 与 B13 是两平台的同类图片边界缺陷。详细证据链接指向本地审查目录；它们不代表真实供应商、设备或系统回调验收。

## 问题与阶段

| 编号 | Phase | 级别 | 已确认问题 | 主要 owner | 复核证据 | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| B01 | 1 | P2 | FileStore 短读写被当作完整 IO，返回残缺文件或补零数据 | `entry/platform_impl/FileStore.ets` | [phase1-fileio-outcome.md](2026-10-18-review-round2-evidence/results/phase1-fileio-outcome.md) | 已修复并通过独立复核 |
| B02 | 1 | P2 | 代笔在异步模型验证期间暂停或取消，结束验证后仍发起请求 | `deepread/novel/ghostwrite_runner.ts` | [novel-audit-report.md](2026-10-18-review-round2-evidence/results/novel-audit-report.md) | 已修复并通过独立复核 |
| B03 | 1 | P2 | 小说公开工作区往返将支线推理开关替换为主线值 | `deepread/novel/workspace_interop.ts` | [novel-audit-report.md](2026-10-18-review-round2-evidence/results/novel-audit-report.md) | 已修复并通过独立复核 |
| B04 | 1 | P2 | waiting_system 时页面停止观察，系统继续任务后可见进度不更新 | `entry/pages/NovelPolishPage.ets、NovelWorkspacePage.ets` | [novel-audit-report.md](2026-10-18-review-round2-evidence/results/novel-audit-report.md) | 已修复并通过独立复核 |
| B05 | 2 | P2 | OpenAI Chat 与 Responses 完整响应的 refusal 被丢弃，出现空白回答 | `chat/openai_parse.ts、openai_responses_parse.ts` | [chat-protocol-report.md](2026-10-18-review-round2-evidence/results/chat-protocol-report.md) | 已修复并通过独立复核 |
| B06 | 2 | P2 | Responses 非流式 image_generation_call 结果未转成图片 | `chat/openai_responses_parse.ts` | [chat-protocol-report.md](2026-10-18-review-round2-evidence/results/chat-protocol-report.md) | 已修复并通过独立复核 |
| B07 | 2 | P2 | Harmony 将相邻完整图片拼为一个 base64 载荷，忽略图片身份 | `chat/stream_accumulator.ts` | [chat-protocol-report.md](2026-10-18-review-round2-evidence/results/chat-protocol-report.md) | 已修复并通过独立复核 |
| B08 | 2 | P2 | 官方 OpenAI o-series 有限输出上限使用不支持的 max_tokens | `chat/openai_request.ts` | [chat-protocol-report.md](2026-10-18-review-round2-evidence/results/chat-protocol-report.md) | 已修复并通过独立复核 |
| B09 | 2 | P2 | 其他协议的 assistant 图片重放到 Responses 时生成非法 output_image | `chat/openai_responses_request.ts` | [chat-protocol-report.md](2026-10-18-review-round2-evidence/results/chat-protocol-report.md) | 已修复并通过独立复核 |
| B10 | 2 | P2 | Mistral content 数组的正文和多段思考被丢弃 | `chat/openai_parse.ts` | [chat-protocol-report.md](2026-10-18-review-round2-evidence/results/chat-protocol-report.md) | 已修复并通过独立复核 |
| B11 | 2 | P2 | MCP Streamable GET 未声明接受 SSE，严格服务端拒绝事件流 | `chat/mcp_transports.ts` | [MCP 独立复核](2026-10-18-review-round2-evidence/results/phase2-independent-review.md) | 已修复并通过独立复核 |
| B12 | 2 | P2 | MCP 关闭遇 DELETE 失败或 GET 迟到时，旧 SSE 未被取消 | `chat/mcp_transports.ts` | [MCP 独立复核](2026-10-18-review-round2-evidence/results/phase2-independent-review.md) | 已修复并通过独立复核 |
| B13 | 2 | P2 | Android 相邻生成图片同样被拼接，第二张图片身份及内容丢失 | `ai/ui/MessageStreamAccumulator.kt、Message.kt` | [android-web-audit.md](2026-10-18-review-round2-evidence/results/android-web-audit.md) | 已修复并通过独立复核 |
| B14 | 3 | P2 | 增量摘要携带旧摘要，分支切换失效后迟到提交复活旧事实 | `chat/context_engine.ts、EntryCompactStore.ets` | [memory-context-compact-review.md](2026-10-18-review-round2-evidence/results/memory-context-compact-review.md) | 已修复并通过独立复核 |
| B15 | 3 | P2 | 后台 precompact 与 force 并发，重叠历史生成两份摘要并同时注入 | `chat/context_engine.ts` | [memory-context-compact-review.md](2026-10-18-review-round2-evidence/results/memory-context-compact-review.md) | 已修复并通过独立复核 |
| B16 | 3 | P2 | 顺序模板替换重新扫描原文，改写用户粘贴的占位符 | `chat/context_engine.ts` | [memory-context-compact-review.md](2026-10-18-review-round2-evidence/results/memory-context-compact-review.md) | 已修复并通过独立复核 |
| B17 | 3 | P2 | 跨会话记忆提炼先查后记，多个请求能同时占用最后一个每日额度 | `chat/memory_extractor.ts、MemoryStore.ets` | [memory-context-memory-review.md](2026-10-18-review-round2-evidence/results/memory-context-memory-review.md) | 已修复并通过独立复核 |
| B18 | 3 | P3 | 实际召回注入路径不更新时间，lastUsedAt 排名加分失效 | `entry/platform_impl/ConversationRunDependencies.ets` | [memory-context-memory-review.md](2026-10-18-review-round2-evidence/results/memory-context-memory-review.md) | 已修复并通过独立复核 |
| B19 | 3 | P2 | Bing/DDG 将普通网站 u/url/uddg 查询参数误当引擎跳转 | `search/bing_service.ts、duckduckgo_service.ts` | [memory-context-search-report.md](2026-10-18-review-round2-evidence/results/memory-context-search-report.md) | 已修复并通过独立复核 |
| B20 | 3 | P2 | IPv6 去重 domain 丢端口，把不同服务的同标题结果合并 | `search/search_orchestrator.ts` | [memory-context-search-report.md](2026-10-18-review-round2-evidence/results/memory-context-search-report.md) | 已修复并通过独立复核 |
| B21 | 4 | P2 | 远程图片保存/分享仍用 HTTP 默认 5 MiB 上限，6 MiB 图片失败 | `entry/platform_impl/ImageSourceSupport.ets` | [persistence-review.md](2026-10-18-review-round2-evidence/results/persistence-review.md) | 已修复并通过独立复核 |
| B22 | 4 | P2 | 附件读取或大小错误被 pickFiles 静默当成用户取消 | `entry/pages/ChatPage.ets` | [persistence-review.md](2026-10-18-review-round2-evidence/results/persistence-review.md) | 已修复并通过独立复核 |
| B23 | 4 | P2 | WebDAV 目录未逐段编码，#/? 吞掉备份文件路径 | `chat/sync_webdav.ts` | [persistence-review.md](2026-10-18-review-round2-evidence/results/persistence-review.md) | 已修复并通过独立复核 |
| B24 | 4 | P2 | 畸形备份 manifest 进入原生预览后触发 undefined.length | `chat/sync_snapshot.ts` | [persistence-review.md](2026-10-18-review-round2-evidence/results/persistence-review.md) | 已修复并通过独立复核 |
| B25 | 4 | P2 | 3D 损坏终态移除之前已显示的合法对象及封面 | `chat/response_content.ts` | [smart-ui-review.md](2026-10-18-review-round2-evidence/results/smart-ui-review.md) | 已修复并通过独立复核 |

## 修复计划与完成标准

1. Phase 1：数据完整性和小说生命周期（B01–B04）。复用实际字节数、既有 owner claim、分支元数据和可见性机制；完成聚焦回归后由独立 subagent 审查。
2. Phase 2：协议与图片边界（B05–B13）。按官方字段修正解码和序列化，图片按完整载荷及调用 ID 处理，MCP 按传输生命周期清理；Harmony/Android 分别验证，随后独立审查。
3. Phase 3：上下文、记忆与搜索（B14–B20）。同会话压缩协调、存储提交失效检查、原子额度预约、最终注入记忆 touch，以及精确搜索 URL 处理；随后独立审查。
4. Phase 4：媒体、备份及原生反馈（B21–B25）。修复具体上限/解码/反馈问题，3D 复用既有有界对象提取，错误场景仍禁止 ready/export；随后独立审查。
5. Phase 5：整合。对本轮 diff 做一次有限维护性检查；分别做 Standards 与 Spec 独立审查。运行完整 Harmony 回归、受影响 Android 单测、改动文件 ArkTS lint、SDK 构建/签名及可用模拟器验证；固定最终输入哈希。

独立分区可并行实施，但每组都需要独立审查与验证才能关闭。未授权提交、推送、发布、权限扩展或清理用户数据。

## 二次复核排除及 by design

- numberField 小数输入：方法级强制回灌不能代表真实原生 @Prop 更新；缺少实际输入失败证据，撤回。
- 缺少流式结束标记：没有明确失败/拒绝信号，不能仅据 EOF 收紧全部兼容协议；保留现有契约。
- Android final-only 图片事件：正常官方事件已有 output_item.done；未证明兼容契约要求该缺失事件序列，未计缺陷。
- 历史 Web UI、旧 Dream、未接线记忆导入导出：没有当前生产入口，不进入修复清单。
- 学习翻卡返回正面、展示状态不进入任务快照、静态导出使用默认值、linkCard 不自动打开外链：当前设计边界，不追加功能。
- 既有附件备份文件树、跨平台 KDF 互通范围：已有范围缺口，本轮不另行设计备份格式或迁移。
- 记忆召回漏 touch 只认定使用时间与排名问题，不宣称导致活跃数据丢失或旧 Dream 自动归档故障。

## 实施与验证记录

B01–B25 均已完成实现、针对性回归和独立审查。实现与审查保持分离，首次审查拦截的缺口也保留了红绿证据：

- Phase 1：FileStore 空字符串编码的真实返回契约在独立 review 中被发现；精确修正并复核通过。[阶段复核](2026-10-18-review-round2-evidence/results/phase1-independent-review.md)。
- Phase 2：Mistral 首段 reasoning/tool 次序回归由 peer review 发现；MCP 关闭后仍分发底层缓冲消息由独立 review 发现，原探针从 17 条迟到消息复测为 0。[阶段复核](2026-10-18-review-round2-evidence/results/phase2-independent-review.md)。
- Phase 3：独立审查以真实 Entry adapter 与 domain 组合复现“新 revision 配旧摘要”的时序；修正为等待同会话稳定队列尾部后，原探针确认旧事实不再注入。[阶段复核](2026-10-18-review-round2-evidence/results/phase3-independent-review.md)。
- Phase 4：合法 3D 对象保留、错误状态、禁止 ready/export、来源替换与预算均复核通过；其余媒体/备份分包由未参与实现的 subagent 复核。[阶段复核](2026-10-18-review-round2-evidence/results/phase4-independent-review.md)。

实现收尾按 `code-simplifier` 对本次改动做一次有限维护性检查，没有另建通用重试、兼容层或状态机。整合只补充测试夹具的显式 JSON 类型和 DeepRead 自有包名路径映射，保留输入及全部断言，使独立类型检查恢复可执行。

## Standards

独立审查：0 项硬性违规、0 项需处理的维护性判断项。检查任务所属差异及当前源码，保留 API 12 / V1 机制。Kotlin 两种 accumulator 的局部相似控制流对应不同表示，无需为抽象新增适配层；Harmony 的两条图片路径已共用 owner helper。[原始记录](2026-10-18-review-round2-evidence/results/final-standards-review.md)。

## Spec

独立审查：0 项未完成要求、0 项可复现偏离、0 项不必要范围扩张。逐项对照 B01–B25 和两份原始 Smart UI 计划；坏 3D 场景仍保留安全预览、完整产物才进入索引，符合原始契约。[原始记录](2026-10-18-review-round2-evidence/results/final-spec-review.md)。Standards 与 Spec 分轴均无剩余阻塞。

## 最终验证

| 层级 | 实际结果 | 证据与边界 |
| --- | --- | --- |
| 全量 Harmony 回归 | 4928/4928 通过，167 suites；fail/cancelled/skipped=0 | `logs/final-harmony-tests.log`，Node、域函数和实际 Entry 转译端口 |
| 类型检查 | Chat、DeepRead `tsc --noEmit` 均通过 | `logs/final-chat-tsc.log`、`final-deepread-tsc.log` |
| Android | 受影响 6 个 suite、84 项离线 Gradle 测试通过 | `logs/phase2-android-final.log`；未构建或安装 APK |
| ArkTS lint | 本轮 28 个生产文件 0 error；后续 MCP/EntryCompactStore 2 文件重查 0 error | 首批 2978 warn，末批 13 warn，存在重叠，不能相加；没有修改前同批 warning 基线，未宣称告警清零或全为旧告警 |
| Release 构建/签名 | `BUILD SUCCESSFUL in 50 s 711 ms`；SignHap / SignApp 完成 | `logs/final-release-build.log` |
| Debug 构建/签名 | `BUILD SUCCESSFUL in 17 s 989 ms`；SignHap / SignApp 完成 | `logs/final-debug-build.log` |
| 安装/运行 | API 24 模拟器覆盖安装成功，最终 Debug 启动及 3D 回放完成 | `logs/simulator-install.log`、`simulator-three-fixture.log` |
| 输入与工作区 | 66 个最终源码/测试/配置输入 SHA-256 与构建后字节一致，`git diff --check` 通过 | `final-input-hashes.json`、`final-verification.json` |

上述日志均位于 `.workflow/comprehensive-review-round2-20261018/`。首次整合全量 4927 项通过；最后补入 MCP 关闭队列回归后，以 4928 项结果为最终完整门槛。SDK 仍有已有构建/符号包警告，未将符号归档作为本轮成功产物。

签名 HAP 已分别保留，避免 Debug 构建覆盖 Release 证据：

- Release SHA-256：`2e9875087b3c3bc408b0a93f094a8ba4adfea08b2e22c34c83608ae2c0c06f4c`。
- Debug SHA-256：`cb7d405d6d430dc33badc00898b61a04d70f44f6de163941569edb5e3d7afdc1`。

模拟器 `127.0.0.1:5555`，API 24，1320×2856，包 `app.amber.deepread`：

- B25 实际回放 `raw-three-error`：流式截图有已完成对象；坏 JSON 尾部闭合后，最终截图仍显示 cube、sphere、torus 与错误提示。PID 31338 的回放日志 started→finished，同 PID 的 AppKit ERROR/FATAL 查询为空，回放后进程仍存活。截图为 `screenshots/three-partial.jpeg`、`three-error-final.jpeg`，各有实际 AX JSON。该 fixture 禁止 Provider 调用，未启用持久化标记。
- 已打开现有 `NovelPhaseCheck-0930` 的工作区和“整章与批量润色”页并截图，未点击生成、保存或开始任务。截图为 `novel-workspace.jpeg`、`novel-polish.jpeg`；相应进程 PID 31471 的 AppKit ERROR/FATAL 查询为空。这仅验证页面出现和可见内容，不等于真实 waiting_system→后台继续的系统验收；该转换由实际页面方法与可控端口回归覆盖。
- 退出临时回放与小说页后，普通启动回到首页，最终截图为 `screenshots/home-after.jpeg`。使用 `install -r`，未卸载、清库或清空全局日志；旧项目可读，但未逐项验收全部历史数据。

## 工作区与未验证边界

接手快照的 73 个路径中，59 个任务外路径保持字节不变；14 个重叠路径在既有 WIP 上叠加本轮授权修复，旧版本完整保留在 baseline。`harmony/build-profile.json5`、两个 pelican 文件和先前报告均保持原始字节。HEAD 与分支未改变，未提交、推送、发布或增加权限。

未取得：真实供应商/计费请求、物理设备、API 12 设备、真实 WorkScheduler 回调、进程断电、外部 WebDAV 服务器、Android APK/设备及所有历史数据的完整性验收。已通过的本地实现门槛不替代这些外部验收；本报告也不保证未审路径没有其他缺陷。

最终交付证据复核：独立 subagent 已通过，核对了 25 项问题、各阶段闭环、测试/编译完成状态、73 份 baseline、66 个最终输入、59 个保留路径、两个签名 HAP、日志及五张截图。[原始记录](2026-10-18-review-round2-evidence/results/final-delivery-review.md)。本轮没有剩余已确认的修复阻塞项。
