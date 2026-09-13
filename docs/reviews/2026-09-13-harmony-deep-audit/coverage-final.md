# 最终覆盖地图与跨模块追踪

此处“完成”指列明的关键实现审查已完成，不代表逐行审遍所有源码，也不代表设备测试通过。实际运行域测试2,872项通过；平台/页面无设备运行证据。

## 1. 统一架构与范围

entry（HAP，ArkUI页面/适配层）→ chat（HAR，消息/Provider/工具/同步）→ deepread（HAR，通用平台Port及深读/议会/小说）。API compatible/target均12；V1装饰器；router路由。74个注册页、75个页面源码；Index未注册；17个直接无入口的注册页和助手旧编辑子树登记为旧代码，不机械报功能不可用。真实助手编辑活链是Profile→ChatAssistantsPage；记忆设置另有活入口。

## 2. 按风险区域覆盖

|区域|关键文件与真实入口|主要风险/审查方法|最终审查深度|残余盲区|
|---|---|---|---|---|
|A 平台/生命周期|EntryAbility、AppContainer、PermissionStatusPortImpl、module.json5、tokens；启动/Want/权限设置|API基线、初始化fallback、生命周期、主题、资源；SDK声明+官方状态文档+调用链|关键实现已读；构建实跑失败；无V1/V2混用证据|设备回前台/销毁时序、权限撤销、不同API版本、完整高版本API自动扫描（CLI缺失）|
|B 对话/分支|ChatPage、chat_turn、regenerate、kernel_host、message/conversation、checkpoint、edit/delete/stop、pending_queue、timeline|发送→组装→流→持久化→展示；取消/编辑/重试/切会话；真实域probe|关键实现已读+跨模块+隔离验证；checkpoint落盘顺序复核|页面实例重建、IME/触摸状态、畸形第三方网关出现频率|
|B/H 上下文/记忆预算|context_engine/compact/assembly、memory召回与ChatPage prepareContext|超长system、记忆后置、压缩预算、审批续跑缺参数|实现已读+真实fit/assembly probe；最终分级见复核|真实Tokenizer估算误差、不同模型实际窗口|
|C Provider流|OpenAI Chat/Responses、Claude、Google api/request/parse、SseAssembler/openai_stream、RcpHttpClient、key roulette|200非SSE、错误/终止、UTF8/工具增量、请求配置/快照|关键实现全读+多种合成流验证；双域类型/测试完成|线上服务差异；SDK requestInStream/destroy/timeout实际时序；OAuth模型登录未实跑|
|C MCP|mcp_manager/protocol/transports/tools/config、McpHttpPortImpl；MCP设置→工具|初始化/endpoint/协议头/取消/重连/配置世代|关键实现已读+协议fake/历史修复复核|长连接EOF重连和平台资源收尾部分待验证；无真实server|
|D Agent/工具|tool_loop/dispatcher/policy/permission/approval/exposure/registry、speculative、profile/merge、AgentToolCapsule|授权→执行→续跑、终止预算、重复副作用、取消|全链深读+真实dispatcher/loop/permission probe；补审任务六件完成|真实有副作用工具未执行；未移植工具能力不按存在推断|
|D Cron/任务|agent_cron、AgentCronStore、AppContainer执行器、SettingCronTasksPage、agent_task/scheduler/tools|自动/手动重入、删除/取消、配置快照、超长delay|关键实现已读+真实manager/entry scheduler受控时钟验证|进程被杀后不唤醒为已登记限制；25天进程常驻条件未设备实跑|
|D SubAgent|manager/runner/scope/validator/grants/task store/Transcript|取消超时、approval_required、历史授权隔离|关键实现已读+真实runner→manager闭环mock|历史跨会话读的产品授权范围待确认；不读用户真实会话|
|E RDB/KV/files|ChatRdbRepository/RdbRepository/AtomicJsonFile/FileStore、Memory/ManagedFile/Favorite/Compact/Workspace/Skill/SubAgent/MCP store|初始化、原子写、损坏、写队列、删除墓碑与清理|关键实现已读+跨模块；域存储mock，平台静态证据|真实RDB事务并发读、磁盘满/掉电/fsync/文件句柄限制|
|E 同步/恢复|sync_snapshot/webdav/SyncCryptoPort、SettingBackupPage|分支/元数据可逆、schema、部分失败、恢复与生成并发、附件|全链已读+真实备份restore合成crypto probe；独立复核|真实GCM无法运行（编译失败）；WebDAV未外呼；设备picker、附件跨机未验|
|F WebMount/OAuth|WebMountTools/Session/Sites/OAuthSupport、webmount_oauth、设置页/ChatPage|脚本注入、站点URL权限、mount、PKCE/手动回调|关键实现已读+真实源码VM隔离+独立攻击负例验证|mount/编译阻断遮蔽端到端攻击；真实browser/cookies/OAuth未操作|
|F MiniApp|Runner/Bridge/Platform、sandbox/shell/html_validator/network_policy/url_guard/runtime/storage/repository|JS bridge token/授权、跨app、网络/重定向、队列/图片预取|关键实现已读+上下游namespace/CSP反证；现有测试运行|图片重定向与DNS rebinding待受控设备验证；运行时内存峰值/首刷重跑|
|F 归档/技能/附件|workspace_artifacts、skills、zip_archive、ArtifactPorts、EntryDocumentParser、选图/文件入口|Zip Slip/容量/不完整输出/引用生命周期|高风险路径已读+现有归档测试执行；历史长度/byteOffset保护重核|技能总输出Map、大输入全缓冲、文件解析资源上限未压力测|
|扩展 深读/搜索|scheduler/run_manager/supervisor/research article_plan/evidence_pack/prefetch/url_filter、SearchRegistry/search aggregator/orchestrator、DeepReadArticlePage|取消/REPLACE、证据、用户源开关、通知|各域关键实现深读+取消/计划成功失败对照probe；通知跨平台契约复核|真实文章质量/搜索可达性、通知栏最终顺序|
|扩展 Council|manager/runner/validator/semaphore/prompts、ModelCouncilRunner/CouncilRunPage|并发/预算/deadline/监听/结果|关键实现全读+deadline和seat取消probe|真实模型输出成本、长时间监听压力|
|扩展 Novel|repository/creation/polish/ghostwrite/mutations、NovelWorkspaceService/WorkScheduler/WorkAbility/ChatRunAdapter/Page|项目RMW、后台润色、恢复/版本/任务|关键实现深读、历史RMW/TDZ/退订修复重核；部分状态probe|workScheduler设备执行/进程恢复未验；一次polish probe构造错误，未冒充成功|
|G 页面真实流程|设置/Provider/助手/搜索/记忆/MiniApp/深读/小说；12兼容壳|入口→保存→返回刷新、占位成功、孤儿路由、loading/error|关键入口/保存回调已读，路由全量静态地图|纯展示组件未逐行穷读；字号/暗色/触控目标/键盘/安全区未截图|
|H 性能稳定性|ChatPage流式尾、markdown、JSON/KV/RDB、归档、MiniApp队列、prefetch|规模上限、重复解析、全量保存、缓存/队列/监听|热路径与资源限额静态审查+部分时序probe|无实机profiling；不据“大文件/长函数”本身报告性能Bug|

## 3. 跨模块关键旅程（状态所有权、失败、取消、落盘）

### J1 发送→上下文→模型→工具→入库→展示
ChatPage捕获conversation/runtime及operationToken；chat_turn先保存含新user的会话，再prepareContext/assemble/provider，stream accumulator持有本轮原始输出；tool_loop按审批策略停/执行，dispatcher返回结果后写回工具part；raw flush做checkpoint，UI预览单独变换；最终队列排在已捕获checkpoint后。已追踪失败/取消路径；发现非SSE空成功、kernel异常分类、预算/工具空ID、预览变换候选，最终裁决见finding索引。

### J2 生成中取消/退出/切会话/切助手
requestStop→operationAbort/kernel cancel；aboutToDisappear置pageAlive=false并递增token，再abort并投影raw snapshot→applyStopGeneration→checkpoint；promise晚到由finishInactiveGeneration把终态排在checkpoint后，不修改销毁页UI。正常发送/再生已有守卫，编辑前置保存的回调需单独审。router.replaceUrl切会话与push设置仅hide不能混为同一种生命周期。没有设备，不把注释“离页停止”当所有push都销毁的证明。

### J3 工具有副作用但响应丢失/审批续跑
审批对象存于assistant工具part；执行前查signal/denied/pending，模型错误重试与工具执行边界分离。空toolCallId子集续跑的写回错配用真实loop验证，合规Provider初始ID补全需反证；自然语言“继续…”自动批准的用户承诺也独立复核。已发生外部副作用没有通用rollback/幂等保证；MCP服务器已执行但客户端断网的事实无法在本次无外呼条件实证，列残余风险而不虚构重复执行。

### J4 Cron运行中配置改变/删除/后台
EntryAbility启动重排→entry timer.fire→prepareTriggeredRun→markStarted→executor按taskId重新读取任务/运行时→runChatTurn→save→markCompleted→scheduleNext。任务与配置非创建时冻结快照；当前无工具单轮、进程存活期timer。重入和超长delay两独立根因已隔离证明。删除清timer但不等于取消在途执行；需按最终review-d-supplement限定效果。

### J5 恢复/导入/同步与在途聊天
SettingBackupPage逐会话读出→exportConversationJson扁平化→加密；恢复先parse归档再逐条repo.save，无整体schema/事务与活跃run协调。保存队列防并行事务但**不**解决旧快照语义覆盖；删除墓碑可挡迟到写却同时挡合法恢复。导入settings仅显式勾选覆盖；WebDAV只有上传，无后台双向同步/冲突合并，不存在的删除传播能力标不适用。并发恢复vs生成的真实页面时序未实跑。

### J6 附件选择→解析→发送→删除→重启
picker→图片压缩/data URL或文档解析/上传引用→消息序列化→Provider编码；已查3张图/10MiB文件等现有限制、ImageKit释放与byteOffset处理。删除入口存在两套编排：ChatList含cleanup，ChatPage抽屉只delete，形成残留；压缩摘要另有KV无级联。备份不携带文件树，file引用跨设备恢复不可保证；选图dataURL避免临时授权过期，但使消息体及备份增大。

### J7 OAuth/Want返回与页面生命周期
WebMount OAuth为打开系统浏览器后手动粘贴code/完整URL，未实现本地loopback listener或后台自动接回调；PKCE保存在页面实例，销毁后需重启流程，不虚构自动回调能力。通知/分享Want只写AppStorage，ChatList的show/appear消费，非根页栈顶收Want不能立即导航。无真实授权兑换，随机数API当前构建失败。

### J8 深读/议会/小说后台
深读scheduler持有topic锁与run token；取消signal覆盖writer但prefetch/plan未贯穿，可能继续网络工作和写RUNNING中间态；完成通知与finally cancel同id且平台publish未await，存在竞态。Council运行级deadline/seat abort已mock验证；Novel服务按project锁和版本管理，后台WorkScheduler流程关键实现已读但未设备运行。

## 4. 测试债务与不适用能力

- entry没有自动化平台/页面测试；领域测试覆盖不了真实ArkTS API错误、RDB/URI/通知/crypto/ArkUI行为。建议优先为平台Port提供可注入mock并增加设备最小旅程，不把“缺测试”计Bug。
- 高风险新增回归：备份分支roundtrip、整体恢复失败原子性、读IO失败不覆盖、长delay不提前fire、并发cron单次执行、审批语句负例、200非SSE与结束帧、取消窗口、工具缺ID身份、真实runtime预算。
- 当前不适用：Android WorkManager、Navigation3、系统级手机控制/短信等未移植能力；Harmony cron系统唤醒、自动双向同步/删除传播、Responses真正续传未实现，记录已接受残余，不假设存在实现。
- 未逐行穷读区域：纯样式/静态展示组件、所有搜索供应商的每个参数映射、全部native/spikes/source-only、Android/iOS非必要模块；本次主目标为可达Harmony应用，不把工具扫描覆盖称审计完备。
