# Harmony 全链路 Review — 2026-10-18

审查基线：`harmony-preview`，`e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55`。审查当前 Harmony 产品的生产调用链，重点检查 `3bf267a1f..HEAD` 的近期变化；不限于该差异。未覆盖 Android/Web 全量实现，也未覆盖已经迁至相邻仓库的独立 DeepRead 产品。

初次审查确认 **22 项问题，5 项 P1、17 项 P2**。按评审轴分别为：运行与数据链路 17 项（5 P1、12 P2），Standards 1 项（P2），Smart UI Spec 4 项（P2）。另有 1 项既有备份范围缺口，未混入新发现。下文保留修复前的复现记录；后续修复及验收见文末“修复结果”。改动保留在工作区，未提交或推送。

本轮采用 `code-review` 和 `harmony-arkts`。各分区先核生产可达性及设计契约，再执行源码 probe；主审重跑关键复现并对高影响资源竞争独立交叉复核。下述“复现”除明确说明外，均为真实域函数或转译后的实际 ArkTS 方法配合测试端口，**不是设备或真实供应商验收**。

## 运行与数据链路

### R01 · P1 · Assistant 工具再生失败会覆盖原答案及后续对话

位置：[tool_loop.ts:652](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts:652)，重点 646–674 行。

对中间 assistant 节点再生时，工具循环先把“原节点之前的 prefix + 新 placeholder”保存到真实 store；只有整个循环成功返回，才把原节点 alternatives 和后续节点合并回来。第一轮工具成功、第二轮 Provider 失败时，合并不执行，Service 错误 checkpoint 也已使用截断的会话。

复现：`U1,A1,U2,A2` 对 A1 再生，第一轮 `file_read` 成功，第二轮模拟网络失败，最终存储仅剩 `U1+新placeholder`。旧 A1、U2、A2 消失。user 节点主动截断是设计；本项是本来应保留后续历史的 assistant 再生。相同位置正常取消的负向对照会保留四个节点，故不把所有取消归入缺陷。

建议：每次中间保存都投影回包含原 target/suffix 的完整会话，不能只在成功终点恢复。证据：`/tmp/amber-runtime-review.cjs`，对照 `/tmp/amber-runtime-cancel-review.cjs`。

### R02 · P1 · 失败媒体下载关闭 fd 后仍有排队写入，可能污染另一个文件

位置：[MediaCache.ets:273](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/MediaCache.ets:273)；关联 231–238、258–260 行。

视频 chunk 被加入异步 writes 链；HTTP 失败或大小限制失败时，finally 在队列结束前关闭文件。排队回调持有裸 fd，执行时也不重新核验终止状态。另一个文件复用该 fd 后，残余视频 chunk 会写入新文件。

实际源码转译配合延迟写入和 fd 复用复现：404 视频的 VIDEO1 尚未完成便 close(fd=1)；新图片复用 fd=1；旧 VIDEO2/VIDEO3 随后写进新图片，最终 PNG 缓存内容为 `IMAGEVIDEO2VIDEO3`。媒体审查、独立二审及主审均复现。未据此宣称真实设备或数据库已经损坏。

建议：失败后停止接收 chunk，并安全终止或耗尽写入队列，最后关闭文件。证据：`/tmp/media-cache-fd-race-probe.cjs`。

### R03 · P1 · 快速判断模型的共用 codec 与供应商协议不兼容

新增入口：[jev_models.ts:54](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/jev_models.ts:54)；错误实现：[jev_client.ts:61](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/jev_client.ts:61)、[jev_client.ts:148](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/jev_client.ts:148)。

新注册的 Perplexity / Liquid 成功响应使用 `noul` 或 `choice`；当前 decoder 只接受 `probability/value/selected`。choice 请求还将官方的 `criteria` 写成 `options`。因此即使配置和服务器应答正常，仍会落入 `transport_or_decode` 并冷却。

使用官方最小成功响应调用当前 decoder，Perplexity 的 noul、choice 及 Liquid 的 noul 分别报 `invalid noul probability` / `invalid choice selection`。TypeSafe 共用协议也受影响，属于既有问题；Vercel 旧 evaluation-model 的 choice 同受影响，但其 boolean probability 可解码，不能说四家所有请求都失败。没有依据仅凭当前 SDK 名称变化判定旧 endpoint 失效。

官方依据：[Perplexity Decisions](https://docs.perplexity.ai/docs/decisions/quickstart)、[Liquid d1](https://docs.liquid.ai/lfm/models/d1)、[TypeSafe API](https://docs.typesafe.ai/api)、[Vercel 固定版本 schema](https://github.com/vercel/ai/blob/b67b1b746341ca591bd1551fde809278ae7f9f44/packages/gateway/src/gateway-evaluation-model.ts#L119)。

建议：按各供应商真实 request/response schema 修正适配，并用官方 fixture 验证。证据：`/tmp/harmony-jev-contract-probe.ts`。未使用 API Key 或发起真实计费请求。

### R04 · P1 · OpenAI / Google 请求序列化丢弃工具图片

位置：[openai_request.ts:281](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/openai_request.ts:281)、[openai_responses_request.ts:213](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/openai_responses_request.ts:213)、[google_request.ts:198](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/google_request.ts:198)。属于当前既有缺陷。

三个编码器只保留 tool.output 的 text。生产 `wm_screenshot` 返回唯一 image，已在 ConversationRunTools 注册；input transformers 没有将这些图片提升到顶层，OCR 也不会补回。

真实编码器 probe 在预算保留截图后得到 OpenAI chat `content:""`、Responses `output:""`、Google `result:""`；同输入 Claude 保留 base64 image。截图工具显示成功，模型却无法观察页面；图片型 MCP 工具也受此编码路径影响。

建议：按供应商支持的格式保留工具图片，必要时用关联的 user image 项承载。证据：`/tmp/harmony-provider-images-probe.ts`。

### R05 · P1 · 小说工作区替换中断后，项目从列表和恢复入口消失

位置：[workspace_storage.ts:54](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/workspace_storage.ts:54)；冷启动恢复：[repository.ts:1121](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/repository.ts:1121)。

替换先 live→随机 .retained，再 stage→live；两次 rename 之间进程终止时，冷启动只识别 .trash，却删除 stage。库存既无正常项目也无失败项目，恢复检查返回 null。生产涉及原生备份替换、公开工作区替换、损坏项目恢复。

真实 FileStore 第一次 rename 后保存 crash snapshot，再新建 repository 模拟重启，得到 `liveAfter=false`、`projects=[]`、`failures=[]`、`recovery=null`。**原字节仍藏在 .retained 中，问题是产品内访问/恢复入口缺失，不是物理字节不可恢复。**

建议：让保留目录和 staging 的切换协议能被冷启动恢复识别。证据：`/tmp/novel-restore-crash-probe.ts`，为持久化断点故障注入，未做设备断电测试。

### R06 · P2 · 批准历史回答的再生工具后，续跑却从会话末尾开始

位置：[ConversationRunService.ets:739](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/ConversationRunService.ets:739)、[tool_loop.ts:314](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts:314)。

中间 A1 再生出待审批 file_write 时，A2 仍为会话末尾；状态判断和 continuation 只查末条。审批保存正确命中 A1，但随后把 visualNodeId 设为末尾，将整个会话交给续跑。真实 Service probe 中 `hasPending=true` 却报 `completed/awaitingTools=false`；批准后 `executed=0`，无关新文本附到 A2。

建议：把批准工具所在 message/node 的定位与上下文保留到续跑和写回入口。证据：`/tmp/amber-runtime-approval-review.cjs`。

### R07 · P2 · 工具图片预算在上下文裁剪之后执行，仍会丢用户任务

位置：[context_assembly.ts:97](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/context_assembly.ts:97)；前置调用 [tool_loop.ts:376](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/tool_loop.ts:376)。

prepareContext 先按所有截图计算并裁剪，之后 assemble 才执行“最近两张”预算。真实组合 probe：32K 窗口，user + 正常工具循环合并的 30 张截图，原估计 33,784 tokens；先预算本只需 2,611，实际 user 已被删除，最终只剩 assistant、2,594 tokens，也没有摘要补偿。

建议：将图片预算后的请求副本送入上下文规划。证据：`/tmp/harmony-tool-image-context-probe.ts`。

### R08 · P2 · 原生 Context 显示把图片 token 再除以 4

位置：[native_context_tokens.ts:115](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/native_context_tokens.ts:115)，最终换算第 163 行。

estimateImageTokens 的 token 返回值被并入 fixedChars，再按字符统一 /4。1024×1024 PNG 的当前图片估计为 1484，Context 最终仅为 372；按相同估计加角色开销应为 1485。ChatPage 原生计数链用于 Context 数字、比例和详情。

这项仅确认显示单位错误，不推断自动压缩阈值受影响，也不涉及供应商真实计费估算法的精确性。建议分开累计字符与 token。证据：`/tmp/amber-storage-review-token-probe.ts`。

### R09 · P2 · API 12 默认 HTTP 上限阻止 5–25 MiB 图片持久缓存

位置：[MediaCache.ets:297](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/MediaCache.ets:297)。

模块允许图片 25 MiB，却未设置 request.maxLimit。API 12 默认限制 5 MiB，6 MiB 图片在应用自身大小检查之前就失败并回退远程 URL。在线显示不能证明已缓存，离线或临时 URL 过期后无法依赖该缓存。

依据本机官方 SDK `@ohos.net.http.d.ts:498–512`（maxLimit since 11）及官方 CLI FAQ `FAQ/网络/网络_Network/http请求传输大于5M文件报错2300023/faqs-network-64`。按该 SDK 合同注入 HTTP 端口，真实实现对 6 MiB PNG 返回原 URL、cached=null。不是 API 12 真机实测。

建议显式配置与 IMAGE_MAX_BYTES 一致的 HTTP 接收上限。证据：`/tmp/media-cache-review-probe.cjs`。

### R10 · P2 · 十分钟清理节流连容量检查一起跳过，超额缓存不再收敛

位置：[MediaCache.ets:365](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/MediaCache.ets:365)。

warmUp 更新 lastPruneAt，十分钟内下载完成触发的所有 prune 都提前返回；之后没有定时补偿，缓存命中也不检查容量。源码声明总上限 256 MiB，但 warmUp 后下载三个合法 100 MiB 视频得到 300 MiB；推进十一分钟后只读缓存仍为 300 MiB。继续在节流窗口下载可进一步放大占用。

建议新增文件后的容量检查与低频过期扫描分开。证据：`/tmp/media-cache-review-probe.cjs`。

### R11 · P2 · 图片产物索引与正文使用不同的文本分段规则

位置：[conversation_artifacts.ts:347](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/conversation_artifacts.ts:347)。

正文先 groupMessageParts 合并连续 text，图片索引却逐 part 解析。生产 Google/Claude/Responses 都可能产生连续 text parts。真实解析验证：分两段的图片 Markdown 在正文可见，产物架 images=[]；分三段的 fenced code 中图片示例在正文是代码，产物架却误收图片。

建议沿用正文合并语义，同文件 widget 索引已有先例。证据：`/tmp/amber-media-artifact-review.ts`。

### R12 · P2 · 搜索的独立 recency_days 与 quick 时间范围被丢弃

位置：[search_orchestrator.ts:303](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/search/search_orchestrator.ts:303)，关联 320–321 行。

独立 recency_days 只生成 timeText，却未满足 news/timeRange 分支而不加入 query；quick 又只保留原 query，丢掉携带时间的变体，Tavily 也未收到原生日期过滤。真实 createSearchTools→HTTP probe 对 `recency_days:2` 及 `depth:quick,time_range:day,topic:news` 都只发送原 query 和普通 topic，结果仍回显要求的时间约束。

建议在首个 query 或供应商原生参数中保留有效时间限制。证据：`/tmp/amber-search-review-probe.ts`。

### R13 · P2 · 过期事实仍可通过没有过期时间的记忆主题进入提示词

位置：[MemoryStore.ets:161](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/MemoryStore.ets:161)；主题创建 [memory_topics.ts:195](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/memory_topics.ts:195)。

召回只过滤记录自身 expiresAt；主题默认 long_term、expiresAt=null。主题成员单纯随时间过期时，不一定触发写入或主题维护。真实域 probe 中，两条到期源都已被过滤，但旅行主题 #3 仍被召回；调用现有 pruneMemoryTopics 后正确归档，表明缺口在召回接线。

建议召回取数时按当前时间验证主题成员。证据：`/tmp/amber-memory-review-probe.ts`。

### R14 · P2 · 删除最大记忆 ID 后复用编号，旧引用可命中新记录

位置：[memory_write.ts:67](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/memory_write.ts:67)；主题分配也在 [memory_topics.ts:194](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/memory_topics.ts:194)。

ID 由当前 max(id)+1 生成；删除最大值后新增即复用。memory_list 的历史工具结果和正在生成的 recalledMemoryIds 不随删除消失，而 edit/delete/reinforce 只按 ID 定位。真实 probe 将 #1 香菜偏好删除后新增电话限制，新记录仍为 #1，使用旧工具 ID 更新后覆盖电话限制，旧 citation 也强化了新记录。

建议在持久层保证身份不复用，并覆盖普通记忆与主题分配。证据：`/tmp/amber-memory-review-probe.ts`。

### R15 · P2 · 切换上文助手分支后，后台记忆提炼仍可提交旧上下文事实

位置：[MemoryExtractionCommitGuard.ets:19](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/MemoryExtractionCommitGuard.ets:19)；输入包含助手上下文 [memory_extraction_actions.ts:48](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/memory_extraction_actions.ts:48)。

提炼用上一条 assistant 消解指代，但 live guard 仅比较最后 user。服务释放 busy 后才异步启动提炼，用户可切换 earlier assistant 分支而保留同一 user。真实 guard + domain probe 中，从“第二方案 SQLite”切到“第二方案 PostgreSQL”，相同 user 为“就用第二个方案”，guard 两次返回 true 并写入“用户选用 SQLite”。

建议提交门覆盖实际用于提炼的完整 source fingerprint。证据：`/tmp/amber-memory-context-probe.ts`。

### R16 · P2 · 从 head 恢复损坏正文会覆盖完好的用户 project.md

位置：[repository.ts:1940](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/repository.ts:1940)；用户文档保留约定 [repository.ts:1428](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/repository.ts:1428)。

recoveryCandidate 将 buildSnapshotFiles 的全量输出作为替换项，其中 project.md 是标题占位。只损坏章节再从 head 恢复后，章节正确恢复，但完整用户项目说明变成 `# 小说2\n`。正常更新明确逐字节保留该用户文档，恢复路径遗漏。

建议恢复正文时保留完好的用户维护文件；原目录仍保留，不能夸大为物理字节全丢。证据：`/tmp/novel-project-md-recovery-probe.ts`。

### R17 · P2 · 前台打开项目抢占仍正常运行的后台润色

位置：[creation.ts:2149](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/creation.ts:2149)；后台入口 [creation.ts:660](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/deepread/src/main/ets/novel/creation.ts:660)。

恢复仅用 drivingPolishJobs 判断活跃执行者，后台 drivePolishInBackground 未登记该表。后台正在 writing 时，前台 open 把未过期 claim 当失联执行者 pause/resume，另发同一模型请求；旧请求未取消，结果到达时因 epoch 失效被拒绝。

真实 creation/repository + 可控模型 probe 显示 epoch 1→2、requests=2、cancelled=[]，旧请求最终 owner lease error。建议统一前后台执行者存活判定，不能仅凭前台内存表接管未过期 claim。证据：`/tmp/novel-polish-overlap-probe.cjs`；未做设备 WorkScheduler 回放。

## Standards

### S01 · P2 · Blob 短写被当作完整落盘并永久命中

位置：[ConversationBlobStore.ets:58](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/ConversationBlobStore.ets:58)。

fileIo.write 返回实际字节数，实现未检查就 rename 为正式 blob；RDB 随后保存引用。读取仅检查非空，再次保存只检查 size>0，残缺 blob 不会被修复。

实际源码和短写端口复现：请求 8022 字节，读回 1024；再次写入仍读回 1024，底层 write 共一次。建议循环完成写入或拒绝发布不完整文件，[AtomicJsonFile.ets:26](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/platform_impl/AtomicJsonFile.ets:26) 已有先例。关联工作原则 5/7 的错误处理和高代价失败验证；这是行为问题，非风格启发式。

证据：`/tmp/amber-review-blob-shortwrite-probe.cjs`。尚非真机磁盘不足测试。API 12 专项未确认违规；未以命名、文件长度等偏好添加问题。

## Smart UI Spec

依据 [2026-10-12-harmony-smart-ui-experience.md:11](/Users/arquiel/Downloads/AI/amber-harmony-preview/docs/plans/2026-10-12-harmony-smart-ui-experience.md:11) 及 [2026-10-10-harmony-intelligent-ui-and-visual-artifacts.md:203](/Users/arquiel/Downloads/AI/amber-harmony-preview/docs/plans/2026-10-10-harmony-intelligent-ui-and-visual-artifacts.md:203)。后续需求优先于旧目录数量限制。

### U01 · P2 · 流式完成清掉实际交互次序，“按这个继续”漏带选择

位置：[ChatPage.ets:4359](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/ChatPage.ets:4359)。

fingerprint 从 partial→complete 时只保留完全相同 fingerprint 的 lastInteraction，因而交互次序归零，虽然字段值仍存在。生产页面方法 probe：partial 卡输入“只用离线方案”，完成后 ready/sourceCurrent=true，lastInteraction 1→0，captureSmartUiContinuation 返回 null；有较早卡片时还可能选择旧卡。

违反最近实际修改卡片及流式状态保留要求。建议区分流式完成与来源替换，保留同定义的实际交互身份。证据：`/tmp/smart-ui-review-probe.mts`。

### U02 · P2 · 按钮模板展开后静默截断提交快照

位置：[ChatPage.ets:4483](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/ChatPage.ets:4483)，预算 [continuation.ts:12](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/smart_ui/continuation.ts:12)。

快照只给 action 原文预留 512 字符；模板字段展开后可远超此值，appendSmartUiDraft 最终按旧 4096 上限截断。六个合法 500 字输入、短模板引用前三项时，snapshot=3103 且校验通过，最终草稿=4096，最后字段和选择结束标记丢失，error 为空。

违反提交包含实际输入与选择的要求。建议按展开后的 action 统一核算或显式拒绝超限，不能静默裁剪结构化快照。证据：`/tmp/smart-ui-review-probe.mts`。

### U03 · P2 · 学习翻卡重挂载丢失已揭示答案状态

位置：[study.ts:103](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/smart_ui/study.ts:103)；原生恢复 [NativeSmartUiStudy.ets:41](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/components/NativeSmartUiStudy.ets:41)。

揭示答案存 answered=true，但无选项翻卡 r 仍为“-”；反序列化从 r 重建 answered，忽略保存的 true。真实状态往返得到 true→false，主题重挂载或离开重开会重新隐藏答案。

违反约定的状态恢复。建议保留合法揭示态，并与已评分结果区分。证据：`/tmp/amber-smart-ui-render-review.mjs`。

### U04 · P2 · 学习题重复选项 ID 未被拒绝，错误答案判为正确

位置：[components.ts:312](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/chat/src/main/ets/chat/smart_ui/components.ts:312)。

study.options 校验格式后直接加入，未查同题 ID 唯一性，correctOptionId 仅验证存在。题目 2+2，选项 3 和 4 都使用 id=a，correctOptionId=a，生产解析返回 ready、无诊断；选择错误项 3 后 score=1。

违反真实值校验要求；普通 choice 已有重复 ID 拒绝，不能把此歧义当正常合法题目。建议在协议校验层拒绝同题重复 option ID。证据：`/tmp/amber-smart-ui-render-review.mjs`。

## 既有范围缺口，不计入上述 22 项

**备份不包含文档附件文件树。** [SettingBackupPage.ets:99](/Users/arquiel/Downloads/AI/amber-harmony-preview/harmony/entry/src/main/ets/pages/SettingBackupPage.ets:99) 只把 repo.getById 的会话序列化，文档此前已物化为本机 file://upload 路径。图片 blob 的 hydration 不处理 document，恢复也不复制附件。

主审和独立二审 probe 均确认归档/恢复后仍为原 file URL，故换设备或重装后文档不可读。但 [batch-1-backup.md:78](/Users/arquiel/Downloads/AI/amber-harmony-preview/docs/reviews/2026-09-13-harmony-repair/batch-1-backup.md:78) 已把附件文件树列为范围外；因此这是应明确给用户的已登记缺口，不算近期提交新缺陷。当前 UI 的“打包全部会话内容”并未说明这个边界。证据：`/tmp/amber-review-backup-probe.cjs`。

## 审查阶段的验证与边界

- `npm run test:harmony`：**4743/4743 通过**，0 fail/cancelled/skipped；日志 `/tmp/amber-review-20261018-tests.log`。测试通过不覆盖上列故障注入、官方协议和跨模块组合缺口。
- `node scripts/harmony-inventory/build-harmony-app.mjs`：**BUILD SUCCESSFUL**，约 56.8 秒；SignHap、SignApp 完成。日志 `/tmp/amber-review-20261018-build.log`。
- 构建有现存符号包警告：symbol/release 软链接 EEXIST，app-symbol.zip 打包 ENOENT；没有将符号包标为成功。
- 未执行安装、启动、模拟器 UI、真机、API 12 设备或真实 Provider 验收；没有修改 ArkTS 源码，因此未追加全仓 lint。
- 初始工作区仅有 build-profile 修改和两个 pelican 文件。主审在验证结束核对三个 SHA-256，与初始一致；本轮只新增这份评审报告。
- DeepRead Agent 可达链覆盖资料输入、搜索/预取、阶段生成、取消、旧稿保留、RDB/产物、文章恢复与导出，未确认额外 P1/P2；相邻独立 DeepRead 项目不属于本次范围。
- 不把静态猜测计作缺陷：未接线旧 Dream 代码、未证实的原生跳转私网语义、ArkUI 索引 key 的显示猜测、Google fallback 的总预算取舍、小说 runner 默认 30 分钟（生产已覆盖为 12 小时）、未复现的数字负零输入。
- 已核实并排除的 API 疑点：utimes since 11、同步 void、毫秒参数；Stat.mtime 秒；Video 支持沙箱 file://；新增相关 DatePicker/可见区域回调/requestInStream 等 API 可用于基线 12。

## 复现入口

从仓库根执行 TypeScript/转换脚本：

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json /tmp/<对应 .ts/.mts/.cjs probe>
```

下列脚本可直接 `node /tmp/<文件>`：media-cache-fd-race-probe.cjs、media-cache-review-probe.cjs、amber-review-blob-shortwrite-probe.cjs、novel-polish-overlap-probe.cjs。备份脚本需 `TSX_TSCONFIG_PATH=harmony/chat/tsconfig.json`。临时脚本未加入产品或自动化测试；其输出和真实源码共同构成本轮证据。

## 修复结果

用户随后授权修复上述问题，并要求精准识别、精准下手。22 项均已落实源码修复和相应回归；既有文档附件备份范围缺口保持单独登记。实现复用现有存储、分支 checkpoint、协议编码器及平台端口，没有新增重试、定时器或通用恢复框架。

| 编号 | 修复后的行为 | 主要回归入口 |
| --- | --- | --- |
| R01 | 再生的每次保存均投影回完整会话，失败也保留旧答案、分支和后续节点 | `conversation_run_service.test.cjs` |
| R02 | 下载失败后停止排队写入，等待正在执行的写入结束，再关闭 fd | `media_cache.test.cjs` |
| R03 | Jev 请求与响应按各供应商官方字段编码、解码，移除猜测格式分支 | `jev_client.test.ts`、`jev_approval_lifecycle.test.cjs` |
| R04 | OpenAI Chat、Responses、Google 请求保留工具图片及所属调用顺序 | `openai_request.test.ts`、`openai_responses_api.test.ts`、`google_request.test.ts` |
| R05 | 目录交换沿用 `.trash` 恢复路径，两次 rename 断点均能恢复可访问项目，保留原字节 | `novel_storage_swap_recovery.test.ts` |
| R06 | 审批续跑定位实际目标节点，仅当前分支参与模型请求和工具授权 | `conversation_run_service.test.cjs` |
| R07 | 图片预算先于上下文估算、裁剪和压缩执行，持久会话仍保存原图 | `context_engine.test.ts` |
| R08 | 图片 token 在字符等价累计中正确换算，1024×1024 PNG 的显示回归为 1485 | `native_context_tokens.test.ts` |
| R09 | HTTP 图片接收上限明确设为 25 MiB，6 MiB 图片可缓存并复用 | `media_cache.test.cjs` |
| R10 | 新缓存写入后立即检查容量，十分钟节流只约束过期和临时文件扫描 | `media_cache.test.cjs` |
| R11 | 产物索引与正文复用相邻文本分组，保留附件顺序和来源 part 索引 | `conversation_artifacts.test.ts` |
| R12 | 每条查询变体都携带时间约束，包含 quick 和独立 `recency_days`，避免重复追加 | `search_tools.test.ts`、`search_orchestrator.test.ts` |
| R13 | 召回前按当前时间排除失去有效源记录的记忆主题 | `memory_topic_store.test.cjs` |
| R14 | 原文件原子保存 `{ records, lastId }`，升级后的删除、删空和重启均不复用 ID | `memory_topic_store.test.cjs` |
| R15 | 提炼提交门比较实际输入 source，上文助手分支变化后拒绝旧提炼结果 | `memory_extraction_commit_guard.test.cjs` |
| R16 | head 恢复保留现存 `project.md`，缺失时才生成占位说明 | `novel_storage_swap_recovery.test.ts` |
| R17 | 前后台共享非阻塞执行锁，只有持锁者能回收残留 lease，迟到 stop 回调不能暂停其他执行者 | `novel_polish_creation.test.ts`、`file_store_lock.test.cjs` |
| S01 | Blob 按实际写入字节推进，短写补齐后才发布，零进展拒绝并清理临时文件 | `conversation_blob_store.test.cjs` |
| U01 | 同一卡片流式增长和完成后保留实际交互次序，切换分支仍清除旧意图 | `smart_ui_page_continuation.test.cjs` |
| U02 | 按完整展开内容检查 4096 字符上限；超限提示并保留原输入与附件 | `smart_ui_page_continuation.test.cjs`、`smart_ui_interaction.test.ts` |
| U03 | 已揭示答案的翻卡状态可恢复，已评分题仍从答题结果推导评分 | `smart_ui_study.test.ts` |
| U04 | 协议层拒绝同题重复选项 ID，允许不同题目复用 ID | `smart_ui_components.test.ts` |

R14 的旧数组兼容读取；首次写入时升级为快照。旧格式没有记录已删除的历史编号，只能从当前存量最大 ID 初始化高水位，无法追溯此前已经丢失的编号信息。

R17 使用 API 9 的 `File.tryLock(true)`，仅将官方错误码 `13900034` 视为争用；竞争立即返回，不排队或发起第二次模型调用。操作结束或异常均释放锁，进程死亡由系统释放锁，因此冷启动恢复无须等待 12 小时 lease 到期。进程死亡及目录断点采用源码故障注入验证，尚未在设备上执行断电或杀进程验收。

### 修复阶段验证

- 全量 `npm run test:harmony`：**4793/4793 通过**，0 fail/cancelled/skipped，约 88 秒；日志 `/tmp/amber-fix-all-tests-final.log`。
- SDK 编译发现并修正了 `FileStore.ets` 的 `arkts-limited-throw` 类型限制。改动仅为 `throw error as Error`，保持原异常对象；随后平台锁测试 **2/2 通过**，日志 `/tmp/amber-fix-filestore-tests-final.log`。
- 各责任范围完成一次有限可维护性审查，未追加无关重构；`git diff --check` 通过。
- 受影响生产文件均完成 ArkTS lint，**0 errors**；域 TS 的类型解析等 warnings 保留，没有宣称零告警。最终 `FileStore.ets` 单文件检查为 `No defects found`，日志 `/tmp/amber-fix-filestore-lint-final.log`。分区日志包括 `/tmp/amber-fix-root-lint.log`、`/tmp/amber-runtime-repair-lint.log`、`/tmp/amber-media-fixes-lint.log`、`/tmp/harmony-provider-repairs-lint.log`、`/tmp/amber-memory-fixes-lint.log`、`/tmp/novel-r17-lint.log`、`/tmp/amber-smart-ui-u01-u04-lint.log`。
- 默认 Release `assembleApp`：**BUILD SUCCESSFUL**，26.687 秒；SignHap、SignApp 完成，日志 `/tmp/amber-fix-build-verified.log`。Debug 构建及签名也通过，36.353 秒，日志 `/tmp/amber-fix-debug-build.log`。既有符号包软链接 EEXIST / app-symbol.zip ENOENT 警告仍在，符号包不计作成功。
- Release 与 Debug 均已在模拟器 `127.0.0.1:5555`（API 24）覆盖安装成功。实际回放使用 Debug 本地 fixture，不调用模型，不写默认会话数据库；模拟器交互结果另附在下方。
- 没有真实计费 Provider、物理设备或 API 12 设备验收。Jev 及工具图片的证据为官方协议 fixture 和真实编码器回归；不能据此宣称服务端接受、视觉判断质量或设备故障恢复已验收。
- 最终核对原有 `harmony/build-profile.json5` 和两个 pelican 文件的 SHA-256，均与任务开始时一致。没有提交或推送。

### 并行二次复核的补充与集成

同一工作区的后续二次复核确认并补修 4 处相邻遗漏，详见 [分阶段修复收尾记录](2026-10-18-review-repair-completion.md)。本报告保留原 22 项编号，避免与初次审查结果混淆。

| 编号 | 确认的相邻遗漏 | 已落实的修复 |
| --- | --- | --- |
| R18 | 历史再生接收 STEER 后取消，仅取消原节点，新 assistant 的待执行工具仍阻塞队列 | 在实际活动前缀的末节点收口取消，保留原后缀；普通失败仍允许续跑 |
| R19 | 跨 context 暂停后立即恢复时，新 driver 争锁退出，旧 driver 结束后无人接替 | 用户显式恢复或重试等待旧执行者释放；普通打开和后台调度继续非阻塞 |
| R20 | 系统 stop 早于异步 owner 登记，或发生在模型 validate 阶段，仍可能发起模型请求 | 首次 await 前登记调度身份和停止意图，并在启动、claim、validate 边界检查停止状态 |
| U05 | 同一 assistant 的多个文本组声明重复 block ID，正文、继续与产物索引的歧义判断不一致 | 按整条消息汇总声明 ID，正文与 continuation 复用同一判定，保留原有 SVG 错误 |

补修后的最新全量结果为 **4808/4808 通过**，0 fail/cancelled/skipped，约 92.8 秒；日志 `/tmp/amber-repair-20261018-final-tests.log`。最新 Release 构建及 SignHap / SignApp 成功，104.421 秒；日志 `/tmp/amber-repair-20261018-final-release-build.log`。核对 `.workflow/harmony-review-repair-20261018/final-input-hashes.json` 中 65 个源码和测试文件，与当前内容逐一一致，因此没有把较早的 4793 项结果当作补修后验收。

在原分区 lint 的基础上，又单独检查后续改动的 9 个生产文件：**0 errors / 4695 warnings**，退出码 0；日志 `/tmp/amber-fix-adjacent-final-lint.log`。告警包括跨域 TS 类型解析诊断，没有通过修改规则、删除断言或抑制错误取得通过。

Debug 本地回放的 [任务卡界面](2026-10-18-comprehensive-fixes/task-ui-draft.jpeg) 与 [实际草稿](2026-10-18-comprehensive-fixes/task-ui-draft.txt) 已复核：轻量方案、50 分钟和 `offline` 约束完整进入可编辑输入，包含选择结束标记，等待用户显式发送。该证据来自共享工作流的实际模拟器操作；当前会话保留其操作归属，没有同时点击同一模拟器。它不代表真实模型、设备故障恢复或全部 26 项的逐项设备验收。

最终 Debug 包已覆盖安装并启动；[学习卡截图](2026-10-18-comprehensive-fixes/study-ui-revealed.jpeg) 与实际 AX 文本一致，第四张无选项翻卡揭示 `1368 年`，得分保持 `1 / 3`。本次只据实报告作答与揭示操作，U03 的恢复由序列化及重挂载回归覆盖，未手工验收主题重挂载。共享记录中最终进程的回放日志为 started→finished，同 PID 的 AppKit ERROR/FATAL 查询为空，交互后进程仍存活。
