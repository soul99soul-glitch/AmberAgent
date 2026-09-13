# 初始覆盖地图（先于专题结论建立）

分支 harmony-preview；HEAD bdce551dd373f3d7e4c393b194997cae19289c40；52 个跟踪文件有未提交改动，51 个在 Harmony 内。API compatible/target 均 5.0.0(12)。entry HAP → chat HAR → deepread HAR；状态管理 V1，router 页面路由。main_pages 注册75页。此地图来自只读定位，不代表完成深审。

|区域/负责人|关键实现|入口与风险|方法/初始状态|盲区|
|---|---|---|---|---|
|A/G 平台与UI|EntryAbility、AppContainer、pages、design、components、PermissionStatusPortImpl|启动/Want/设置/返回/权限；生命周期、设置无效|已读入口，余仅定位；将跨页追踪+官方文档复核|无在线设备，UI实际布局待验|
|B/H 对话与热路径|ChatPage、chat_turn、interactive_turn_runtime、chat_kernel_host、tool_loop、context_*、stream_checkpoint、conversation/message/serialize|发送/取消/分支/切换助手/会话；串写、丢消息、上下文、渲染负载|仅定位；将真实域逻辑probe+跨模块追踪|ArkUI与RDB运行时|
|C Provider/MCP|openai_{chat,responses}_*、claude_*、google_*、sse_assembler、mcp_*、RcpHttpClient、McpHttpPortImpl|模型设置→请求→流→工具；错误协议、断流、取消、重试|仅定位；将多字节/分片/畸形协议合成验证|真实服务兼容性，不调用真实模型|
|D Agent/自动化|tool_dispatcher/policy/permission/approval、agent_cron/run_executor、subagent_*、AgentCronStore|工具审批、并发、cron启动恢复；重复副作用、授权、取消失效|仅定位；将调用链+mock时钟/runner验证|操作系统后台调度|
|E 存储/同步|ChatRdbRepository、RdbRepository、AtomicJsonFile、*Store、sync_snapshot/webdav、SyncCryptoPort、SettingBackupPage|删除/恢复/导入/同步/附件；原子性、旧快照、失败回滚|仅定位；将读写链追踪+mock验证|真实磁盘满、RDB事务、设备crypto|
|F 安全/信任|miniapp/*、MiniAppBridgeImpl、WebMount*、webmount_oauth、session_grant_store、skills/workspace_artifacts、module.json5|远程网页/工具/深链/归档；bridge、路径、SSRF、权限|仅定位；将攻击路径与上游校验复核|不读取真实凭据，不操作真实服务|
|扩展域：深读/搜索/议会|deepread/agent/research/council、SearchRegistry、OpenAiCompatibleAiClient、DeepReadArticlePage、CouncilPage|深读run/检索/取消/议会；预算、错误成功、超时|仅定位；将真实域逻辑与装配审查|无真实模型调用|
|扩展域：小说/记忆/技能|deepread/novel、NovelWorkspaceService/WorkScheduler/WorkAbility、memory_*、skills|项目编辑/润色后台/记忆提取；丢写、幽灵任务、解析负载|仅定位；将事务与异步状态追踪|后台进程恢复|

## 必追跨模块旅程
1. 发消息→上下文→模型流→工具→入库→展示。
2. 生成期间取消/返回/会话与助手切换。
3. 工具已产生副作用而响应丢失/客户端重试。
4. Cron运行中配置更新/删除/后台切换。
5. 导入恢复同步与对话并发。
6. 附件选择解析发送删除及重启。
7. OAuth/Want在冷启动及页面销毁后返回。
8. 深读/议会/小说后台任务的取消与持久化。

## 测试与环境初查
chat 160个测试文件、deepread 61个测试文件；grep估算例数不作为实跑数量。entry无自动化测试。两个npm test入口使用tsx/node:test，搜索确认HTTP/文件/存储为fake，无真实fetch、网络模块或环境凭据依赖，可离线运行。hdc list targets为[Empty]。node v26.8.1，hvigorw存在；系统java无runtime，构建可能使用工具自带JRE，以实跑为准。README仍描述早期Phase0/未安装工具，不能当作现状。

## 历史资料
已读round3审查与修复计划；后续按区域读PARITY_DEBT、parity-backlog、completion-plan、小说.workflow报告。历史中“Android同款”不自动等价于假阳性；当前要找独立的产品授权/上游保护证据。历史修复亦需当前代码复核。
