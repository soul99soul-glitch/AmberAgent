# Harmony 第三轮 Review 修复计划（2026-08-25）

复核基准：Android 同款且刻意 → 假阳性不修；跨端共享真 bug → Harmony 照修并登记双端同步；接线缺口 → 不入本轮。完整发现清单见 `2026-08-25-harmony-review-round3.md`。

## 复核后假阳性清单（26 项，勿修）

approval_required→RUNNING（双端无续跑 API，产品语义）；grant 自动铸造（有界刻意设计）；dynamic 上限分支不可达（Android 同构）；runs Map 保留 terminal（同款）；temperature NaN/Infinity（toFloatOrNull 同款）；cron 手动/定时并发（执行器 AGENT_CRON_EXECUTION_AVAILABLE=false 未接线）；DST 视界差异（低危跳过）；fit 保留超预算 system（Android 同款策略，只修后置块预算）；extraction 去抖/配额顺序（同款）；assistant.enableMemory 无运行时门（Android 同为 UI-only）；touchMemories 失败阻断（同款）；expiresInDays 负数（同款）；中文年月正则边界（同款正则）；EPUB href 拼接（同款）；归档 `..` 单段（normalize 已抛）；技能目录名/zip 覆盖（同款）；editText 无锁/mkdirs/TOCTOU（同款或降级）；MCP start() try 外（SDK 同款）；Streamable SSE EOF（同款）；inputSchema 裁剪（同款）；JSON-RPC envelope 宽松（同款）；recency_days 内置源（同款）；aggregator 'ok' 状态（契约本就不同于 orchestrator，同款）；MiniApp 空串 key（同款）；srcset（validator 本就拒绝，假前提）；wikipedia 编码、reasoning finishedAt now()、rounds=2 无 final 轮（均同款刻意）；UI 50 vs 40（保留 Android 契约）。

## Phases

**P1 数据安全与持久化**
- MemoryStore/ManagedFileStore：临时文件+rename 原子写；解析失败保留 `.bad` 后按空库处理；getPendingCandidates/getAllCandidates 显式 createdAt DESC。
- WorkspaceStore/SkillStore `writeBytes`：按 byteOffset/byteLength slice。
- ChatRdbRepository：PRAGMA table_info 列 diff 迁移（ALTER ADD COLUMN）；会话列表 RDB+内存双实现补 `id` tie-breaker。

**P2 流式核心**
- stream_accumulator 三处：usage-only chunk 先 mergeUsage 再判 choice；新建 image part 不对 `data:` 开头 url 补 PNG 前缀（配 openai_parse 保持任意 data URI 原样）。
- delete_message：按选中 message id 重定位 selectIndex（双端共享 bug，Harmony 先修）。
- ChatPage 发送状态机：appendActive/sending 前置检查+finally 复位+ensureFull catch；sendParts/sendEdit 异步回调后重查页面/会话/发送位。

**P3 agent 运行时**
- subagent：超时→finish(timed_out)（区分用户取消）；launchRun 顶层 catch；controller 在 await 前创建+启动前 abort 检查；grant 创建移到 admission 后；持久化 definition 下限 clamp。
- AgentTaskStore.update：error/lastErrorCode 支持 null 显式清除（审计全部调用点+测试）。
- cron 恢复：检查 enabled。
- tool_activity：listener 逐个隔离；toolCallId 用 newId。
- SubAgentStore/AgentCronStore isPathInside 规范化拒 `..`；readTail 改 offset/length 尾读+UTF-8 字节窗。
- AppContainer：getSubAgentManager Promise 单例；updateSubAgentSetting 后缓存失效。
- SettingCronTasksPage 补 catch。

**P4 context/memory**
- 预算：ChatPage buildPrepareContext 传 overhead（含后置 system 块估算）；chat_turn/regenerate/tool_loop 组装后发送边界二次 fit。
- precompact：会话级 in-flight 门；EntryCompactStore insertCompact/recordEvent 串行 RMW。
- dream：applyPendingPlan 互斥；savePending/saveApplied 合并为单次 enqueueWrite 事务。
- extraction 并发 single-flight。
- floatOrNull 严格整串校验（extractor+dream）；导入去重可变集合；frontmatter scalar 反转义。

**P5 zip/技能/HTTP 上限**
- readZipEntryText/inflateRawEntry 单条目 64MB 上限+平台 expectedSize 拒绝；归档总输出上限（zip 预累加+targz ISIZE 上限）；stored 条目与 CD 解析边界校验；技能非 zip 分支 4MB；HTTP/下载 Content-Length 预检。

**P6 MCP + signal 贯穿**
- endpointPromise 早 EOF/close 统一 reject；protocolVersion 回写 transport；Legacy SSE 补 Accept；openSse 校验 Content-Type；request/postStream per-call timeout 贯穿（120s）；manager generation token 防 await 误删；大小写不敏感 header 合并（协议头优先）；MCP 工具名唯一化。
- AgentTool.execute 可选 signal 参数：dispatcher 传递；MCP（manager/client/transport cancel+notifications/cancelled）与搜索工具消费；RcpHttpClient abort 设 name='AbortError'。

**P7 MiniApp**
- getMiniAppPlatform Promise 单例；storage appId 级互斥（set/remove）；图片预取数量/并发/总预算上限；fetchBytes Content-Length 预检+重定向最终 URL；source[src] 提取；设置串行+失败回滚修正；index.json 结构守卫+坏文件隔离。

**P8 搜索栈**
- html_dom：引号感知 findTagEnd、`<` 文本判定、无引号 URL 尾 `/`、fromCodePoint+`#X`；补测试。
- 去重：保守 title 别名（域非空+题长≥3）、orchestrator 标题合并回写 canonical、空 canonical 不入索引；聚合前滤空 URL。
- canonicalizeUrl 保留端口+IPv6；query trim。
- ChatPage onPageShow 刷新 searchSettings（序号守卫）；search_prefs 按实例队列；FavoriteStore 队列+页面 catch；HotListSourceSettingsPage 改用 NEWSNOW_PRESETS+`source_${id}` 持久化，去掉无消费的间隔控件。

**P9 council/novel/deepread**
- url_filter 全形态 IPv4/IPv4-mapped IPv6 识别+测试。
- CouncilPage 读持久化 setting+enabled 门；validator parseTask fail-closed。
- council：运行级 deadline（timed_out 终态）+seat 子 controller abort；live/listeners finally 清理；snapshot 显式拷贝；seatId 查重；runner 无 key/非 200 抛错+空文本判失败；裁决保守 JSON 解析（失败保原文+warning）。
- novel：repository per-project mutex；FileStore.writeTextAtomic；TDZ/timer 修复；replace 超限；终结退订。
- prefetch：deadline 到期 reject+LRU 命中重排+缓存返回副本；Tavily 多 query 合并去重。
- section_writer：侧字段独立合并；diagnostics 用候选池。

**P10 entry 页面/DI/RMW 统一 + 终审**
- 共享按键 mutation 队列：agent_runtime（AgentRuntimePrefs+PromptConfigStore 共用）、mode_injections/lorebooks（ExtensionPrefs）、MiniApp 设置、favorites。
- chat-domain mutateAssistants(store, transform) + 迁移助手族页面 RMW 写入。
- AssistantDetailPage onPageShow 刷新；AssistantImporter 运行时校验+重入+timer 清理（含 AssistantListPage 剪贴板导入校验）；AssistantPropertyEditor 断链处置；动态编辑行 uid key；"+ 添加 Body" onClick。
- ChatPage 守卫批次：compacts 会话序号、reasoning picker 模型序号、初始化加载 pageAlive、发送准备离页 token。
- Base64ImageSupport ImageSource/ImagePacker 释放。
- 全量回归：chat+deepread 全测、双 tsc、hvigor assembleApp；终审 subagent + UI 细节排查。

每 Phase 收尾：模块测试+tsc（涉及）+hvigor（涉及 .ets）→ 独立 subagent 评审（逻辑闭环/调用链/UI 细节）→ 修完发现 → 进入下一 Phase。
