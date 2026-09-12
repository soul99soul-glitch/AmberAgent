# Android 多维度审查与精准修复

日期：2026-09-08。工作目录：`/Users/arquiel/Downloads/AI/AmberAgent/android`。审查起点：`0afce43`。

## 范围与证据

沿用户操作到生产实现、状态归属、持久化和返回结果审查：聊天与通知、小说代笔、任务与 Cron、Provider 配置、文件和备份、搜索、分享与快捷相机、MiniApp/WebView、主题和 Skill 编辑、图片导出、会议入口。对子代理提出的问题再次核对完整调用链，区分实际缺陷、设计选择和证据不足的候选。

本次是源码路径推演、JVM/Robolectric 验证与编译检查。没有连接 Android 设备，未进行真实 Provider 请求、设备生命周期复现、帧率或视觉截图对比，不把静态结论表述为真机验收。

## 确认的问题及修复

| 路径 | 原问题与触发 | 修复及闭环 |
| --- | --- | --- |
| ChatPage → ChatService.regenerateAtMessage → ChatTurnAgent | 重新生成中间回答时，范围生成的前缀和回答可能追加到会话末尾，旧下游仍进入当前分支 | 按范围节点合并，保留目标旧 variant 并选择新 variant；后续流式 chunk 更新同一 variant；按已有分支契约截断目标之后的下游 |
| 通知 Stop → ChatService.stopGeneration | 旧 runId 可能停止当前新 run；远端取消发生在会话归属校验之前 | 本地停止与远端 stored response 取消均先校验 conversationId/runId 归属 |
| 通知工具审批 → handleNotificationApproval → continueGenerationInline | receiver 协程恢复生成后未登记 session owner，UI/Stop 可能认为已空闲 | 将当前 Job 登记到 ConversationSession，沿用已有 identity completion guard 清理 |
| 恢复 pending queue → sendMessage | 重启后发送新消息会越过已持久化的等待消息 | 新消息先入队，从原队首启动 drain，保持 FIFO |
| 小说项目重命名/删除 → NovelWorkspaceProjectRepository | 活动代笔批次仍引用旧目录，项目改名或删除会破坏执行中的路径 | 使用已有活动批次查询拒绝重命名、删除，给出明确错误 |
| NovelProjectsViewModel.deleteProject | workspace 删除后 legacy 删除失败被吞掉，迁移路径可能重新带回项目 | 先完成 legacy 删除，只忽略明确的 ProjectNotFound，再删除 workspace；其他错误继续暴露 |
| NovelTurnLauncher → AgentRunner → NovelTurnAgent | runner 的 CANCELLED 快照早于 handler 回滚完成；首个 Completed/Failed 又可能被 Flow.first 的中止误判为用户取消 | 完成边界等待 handler 收尾；终止事件消费与外部取消区分；未进入 handler 的终态也关闭事件流，避免悬挂 |
| Cron 手动执行/更新 → WorkManager → 旧 Worker.finally | 旧 Worker 的 REPLACE 重排可能覆盖新手动请求、写坏任务状态 | manager 内串行状态与调度操作，等待 WorkManager 入库，用 WorkRequest UUID 校验归属；启动重排使用 KEEP |
| AgentCronWorker | foreground 失败绕过终态处理；普通取消误报失败，内部超时与外部取消混同 | foreground 放入统一 try/finally；外部取消传播，内部超时在确认协程仍有效后登记失败和超时通知 |
| AgentTaskStore.update → Cron/SubAgent/Terminal/ModelCouncil | null 表示保留旧错误，重试成功后仍显示上一轮 error/code | 增加显式 clearError 更新语义，只在对应状态转换调用，联动清除 error 和 lastErrorCode |
| SubAgentManager.sendMessage → 持久化/live mailbox | 两条路径使用不同长度的消息，重放与实时消费不一致 | 两条路径共享同一 4,000 字符截断值 |
| Codex OAuth 登录/获取模型 → 设置 provider.models | 清除 review 模型后可能把可选列表存为空 | 没有已有非 review 选择时，采用获取结果的首个模型；已有选择保持 |
| provider_config_status → Codex OAuth store | 退出登录或没有会话时仍通过通用 hasUsableAuth 报 ready | 读取真实 OAuth 会话状态后构建诊断，沿用现有注入方式 |
| ChatInput/文件上传/粘贴 → FilesManager → managed_files | 返回 URI 早于文件登记；空输入流和登记失败可能留下孤儿文件；部分入口绕过现有大小上限 | 等待登记完成再返回，失败回收本次文件，空输入流明确失败，各入口沿用 128 MiB 上限 |
| StorageVM → SessionCleanupManager | 批量清理误删其他会话共享 upload，批内重复计数；先删物理文件再删数据库，事务失败会损坏仍存会话 | 同一 Room 事务重新选择和删除会话，排除保留会话引用、批内去重；随后删物理文件，仅成功后移除 managed_files 索引 |
| 清理失败 → Files 页面 → FilesManager.delete | File.delete 返回 false 后仍删除 managed_files，用户无法重试 | 删除失败返回 false 并保留索引；清理测试覆盖解除阻塞后重试成功 |
| SessionCleanupManager → FTS | 清理会话只删消息 FTS，留下标题 FTS | 同事务补删标题索引。标题搜索本来 JOIN 会话表，因此这是索引残留，**不是搜索返回已删会话** |
| SearchVM → rebuildAllIndexes → FTS | 清空和逐会话重建不在同一事务，可能形成混合快照 | 读取会话/节点及全部索引重建放进同一 Room 事务 |
| 全量恢复 → staging → restoreTables | manifest 声明表存在，但实际缺失 jsonl 时，可能先清空表再跳过恢复 | 在破坏性恢复前检查实际 staging 表文件，遵循原有全量恢复/preserve 契约 |
| LocalFolder/WebDAV.uploadSnapshot | 归档复制抛错后遗留临时 archive | 用 finally 回收本次创建的 archive |
| Google 备份授权取消 → 后续重新授权 | pendingGoogleUpload 保留，后续无关授权可能自动执行旧上传请求 | 取消授权同时清除等待上传请求 |
| 生成图/聊天导出/Mermaid/Widget → ContextUtil | MediaStore 插入、打开、编码或关闭失败仍可能报成功；失败留半成品图库记录 | Boolean 结果贯穿 UI；检查编码结果、use 关闭流，失败回收本次 URI；PNG/JPEG 参数不变 |
| HTTP 图片保存 → FilesManager.saveMessageImage | 非成功响应和解码失败没有完整传回调用方，连接/流未完整关闭 | 返回实际结果，关闭输入流并 finally disconnect，两处图片 UI 按结果提示 |
| SearchPage → SearchVM | 手动搜索和 debounce 搜索分别运行，旧结果可能覆盖新结果 | 共用可取消 search Job；旧任务不清除新任务 loading；重建防重并完成后刷新 |
| 主题导入/Skill 编辑/Mermaid 保存 | SAF/文件读取、图片解码与写入占用主线程；Skill 读取失败可中断组合 | 将阻塞操作移到 IO；Skill 读完再打开原编辑弹窗，失败提示；取消正常传播 |
| 外部分享 → RouteActivity → ShareHandler | 相同内容重复分享被相同 effect/key 吞掉，重建重复消费，Spanned 文本丢失 | Intent 作为消费触发，保存消费状态，每次投递独立 deliveryId；EXTRA_TEXT 读取 CharSequence |
| 快捷相机 → ShortcutHandlerActivity | Activity 重建丢 photoURI，并再次拉起权限/相机 | 保存恢复 URI，只在首次创建时请求权限；分享 staging 不吞取消 |
| 会议入口 → 占位会话 → openRoom | 失败只有日志，异步占位清理不确定，用户停留原页不知道原因 | 等待清理并显示真实错误，成功才导航；异常路径也处理占位会话 |
| MiniApp host.updateBoardSummary → Room | fire-and-forget 回调立即返回成功，页面退出可能中断实际写入 | 挂起回调等待 DAO 完成后才回复成功 |
| MiniApp sensor/EventBus → close | 注册与关闭交错，监听器可能在清理后重新登记 | 并发容器与注册前后 closed/ownership 检查，关闭后的注册立即回收 |
| Compose WebView → AndroidView.onRelease | 页面退出没有明确 destroy，state 保留 native WebView 引用 | 永久释放时停止加载、移除接口、destroy，并清除对应 state 引用 |
| SessionHandle.loadUrl/callBridge/callPageFn | Main dispatch 前取消或同步异常遗留 LOADING/pending JS；旧清理覆盖新加载 | 状态安装和 load 同 Main 段，pending 清理覆盖 dispatch，旧状态 CAS 清理，异常不吞 fatal Error |

## 设计选择与未纳入修复的候选

- 重新生成截断下游遵循现有编辑/variant 切换契约；保留目标回答的旧 variant。没有改成新的分支系统。
- ChatInputSandbox 的 WebView 实际始终按 loadId 创建，READY gate 只控制 overlay，原候选是假阳性。
- Provider `replace_chat` 已保留非 CHAT 模型；Codex Responses resume 对官方 API key 的限制是现有能力边界。
- Google OAuth 生成缺少响应级 401 强刷重试；发送前已有刷新。没有服务端复现实证，也没有把所有 401 解释为可重试过期，未叠加重试。
- 删除模型后的悬空槽位已有 status 诊断；自动清空还是保留待修复涉及设置契约，本次未改变。未为 selector 新增凭证过滤策略。
- FULL 会议预研究和已有席位的关系符合当前可选搜索设计，没有改成每次强制搜索。重复通知导航是否 single-top 同样保留现有行为。
- 快捷相机固定缓存文件在快速连续启动下存在覆盖候选；并发分享 staging 的最终可见会话有顺序竞争。未扩展为新队列或缓存生命周期系统。
- 一般会话删除的异步附件清理、跨 DB/文件的理论并发引用保留为边界；本次没有引入跨资源事务/恢复日志。旧 Android 直接写 Pictures 失败时也回收本次新建文件，既有同名文件不额外删除。
- 全量 v2 备份原本已要求当前非 preserve 表的 manifest 声明；新检查只验证实际文件。v1/无 manifest 的 CONFIG_ONLY 不受影响。没有历史缺表 v2 归档样本，不能宣称它们兼容。
- WebView stale onPageFinished 缺少请求标识，简单按 URL 过滤会误伤重定向/history；未添加猜测性过滤。

## 性能与视觉约束

没有修改主题颜色、布局、阴影、模糊、动画、渲染质量或图片压缩参数。优化集中在主线程 IO、重复搜索取消、WebView/监听器生命周期与资源释放。没有采集真机 frame trace 或内存曲线，因此只确认这些阻塞操作和未释放路径被移除，不报告未经测量的性能增幅。

FTS 全量重建改为单事务以保证一致性；大历史重建期间会更长时间占用数据库写事务，这是明确的正确性取舍，未引入额外索引缓存或后台调度框架。

## 最终复审与验证

实现后分别由子代理复核聊天/小说、数据、Cron 并发、Web/UI 与导航。复审发现并修正了远端 Stop 校验时序、小说 terminal-first 与 no-handler 竞态、清理数据库与物理文件顺序、图库失败回收，以及 Cron 超时/旧 Worker prepare 门禁。小说最终补丁又经过另一名子代理独立只读核对，确认生产 DI 和全部构造调用闭合。

定点回归包含：`ChatTurnAgentTest` 的多 chunk 范围重新生成；`StorageCleanupTest` 的共享引用、物理删除失败保留索引及重试；`ContextImageExportTest` 的图库插入拒绝和关闭输出流失败；`AgentTaskStoreTest` 的显式清错；`ProviderConfigToolsTest` 的无 Codex OAuth session；`NovelTurnLauncherTest` 的 terminal-first 和 gate 未进入 handler；真实 WorkManager 的旧回调与禁用任务手动调度两条交错。

`StoredResponseStopCancelTest` 验证 provider cancel 的结果语义；ChatService 上层归属门禁没有现成可复用集成 fixture，本次以源码调用链核对为证据，没有为布尔判断添加镜像测试，也不将其描述为服务级集成测试通过。

本机 `/usr/bin/java` 没有默认 runtime，但已找到并使用 `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`；SDK 为 `/opt/homebrew/share/android-commandlinetools`。未把早期子代理的 PATH/JDK 报错当作最终阻塞。Gradle 使用现有离线缓存，并关闭此次并行编译中出现问题的增量 KSP。

最终运行 `BUILD SUCCESSFUL`；生产 Kotlin、资源和单元测试编译通过，`git diff --check` 通过。

| 测试范围 | 用例数 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| `feature:novel-workspace` | 107 | 0 | 0 |
| `feature:novel` | 39 | 0 | 0 |
| `ai` | 185 | 0 | 0 |
| `app` | 1,961 | 0 | 13 |
| 合计 | 2,292 | 0 | 13 |

实际通过 2,279 项。`feature:subagent`、`feature:task` 的测试任务为 NO-SOURCE，生产代码随 app 编译；任务状态回归在 app 的 `AgentTaskStoreTest` 中执行。13 个跳过项为 12 个已标注差异的 Markdown native/JVM parity 样本，以及 1 个 Robolectric 不支持 document-start WebView 能力的 SessionHandle 用例；没有通过新增跳过来掩盖本次失败。

最终命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :feature:novel-workspace:testDebugUnitTest :feature:novel:testDebugUnitTest \
  :ai:testDebugUnitTest :feature:subagent:testDebugUnitTest :feature:task:testDebugUnitTest \
  :app:testDebugUnitTest --offline --console=plain -Pksp.incremental=false
```

完整本机日志：`/tmp/amber-review-complete-tests.log`。未执行 APK 打包/安装，编译通过不等同于设备交付。

首次 App 全量运行发现的八个旧断言语言失配，已按当前资源/明确的无 Context 英文 fallback 修正；保留异常类型、数据库未清空、模型不修改等行为断言。新增测试夹具的问题也已修正，没有为测试改生产约束。

边界：设备未连接，未安装 APK、未调用真实 Provider、未做真机 WorkManager 调度或视觉/性能基准。Cron 的新 manual tag 能保护本次版本创建的手动请求；升级前没有该 tag 的旧请求保持原有启动取消语义。小说生产调用都会立即 collect/first；没有扩展“启动后永不订阅事件”的生命周期契约。

## 共享工作树归属

审查期间，同一工作树有独立任务“Review并修复WebMount闭环问题”运行。其 UserSite/userSiteId、登录链接、InlineLoginActivity、WebMountManager、站点适配器与 JS bridge，以及 ToolRegistry.effectClass / DefaultRunKernel / AgentToolDispatcher / RecipeRunner 等改动和对应测试不是本次修复成果；没有回滚或覆盖。ContextUtil.openUrl、SessionHandle 等共享文件按具体 diff 区分。最终构建和 app 测试针对当时的合并工作树，不表示本任务独立验证了另一任务的全部业务行为。

未提交、推送或部署。
