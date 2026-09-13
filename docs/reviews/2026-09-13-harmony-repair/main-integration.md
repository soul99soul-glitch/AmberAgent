# 主代理集成与边界

## 已接
- ChatPage独占集成R19单次视觉变换、R27编辑前置token/存活门；三种生成、编辑、append、自动标题/建议在ConversationRunGuard持有到各自写完成，恢复同步占用同id；最终save等待checkpointTail。
- 备份成功发布conversationRestoreVersion；仍存活ChatPage在onPageShow及任何ensureFullConversationForMutation前重载已恢复会话，避免恢复结束后旧页面覆盖新快照。
- R08三种消息行(历史/再生/live)传ToolApprovalPartLocator；空id拒绝弹窗使用独立boolean，不再因空id打不开；失配原引用返回时给用户错误。
- UI-F16六项显示设置传到全部消息行；sendOnEnter/长文本转文件明确不支持，未假保存；UI-F19 Slides无消费者，入口下线和页内明确不支持。
- F44真实data/file文档预览回调+sheet+序号/存活守卫；非支持格式显示真实错误，不扩PDF引擎或音视频。
- ChatPage空态等待初载；数据库不可用显示重试提示且禁发，不以memory成功冒充持久化。
- greetTimer show/hide对称；自绘model/context/deny层优先返回，顶栏同路径，系统sheet保留系统处理。
- ChatPage有效模型全部消费resolveEffectiveChatChoice，选择saveEffectiveChatChoice；绑定助手和全局使用既有resolver；reasoning/技能开关改mutateAssistant锁内最新快照。
- Cron AppContainer传createEntryAbortController、signal和会话运行guard；先initMcpManager；现有time/workspace/search/MCP工具经profile/registry进入runChatTurnWithTools；auto审批全关，pending/unexecuted报告未完成而非成功。无页面工具不暴露，系统唤醒不扩展。
- WebMount bindHooks按owner持有和unbind，工具第三参数保留global||assistant原语义；安全修复先于挂载闭环。

## 独立复核回修
- 批01：消息id/time/重复校验，降级delete先失败，GC加聊天库健康检查/文件mutation锁/逐条最新登记/安全路径；GCM init authTag改16B占位。
- 批23：approval_required仍可取消但cleanup不得清除live callback；normal terminal回调退役；locator miss返回原对象并检查id。
- 批45：站点表写入集中刷新gate，revoke无需下一tool；全局off不能永久覆盖助手独立启用原语义；继续深读只补非READY段，严格完成通知publish失败不误置published。
- 终集成：显式assistant不存在/损坏model内部ID拒绝，不静默改全局；不照搬Android总绑定语义；完整/半参数/普通激活Want区别处理；恢复版本失效旧页面。

## 验证状态
全套chat曾跑2124/2124、deepread779/779；深读复核新增后782/782，最终再执行记录为准。CompileArkTS和PackageHap通过；配置内原签名storeFile指向本机不存在文件，未改签名配置。使用DevEco自带JRE（单命令env）和现有sign-hap.sh完成离线签名/verify-app。无hdc设备，未安装/截图/真实SDK GCM运行。没有真实模型/服务请求。

批次agent部分运行了多次全套测试，最终不把中间跨agent文件变动导致失败当产品失败或成功结论，统一收尾结果。
