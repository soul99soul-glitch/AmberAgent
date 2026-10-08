# Phase 4 MiniApp / composer / provider images 独立复审

复审对象：本轮开始前 `/tmp/amber-android-review-20261002-baseline` 与当前工作树。只读源代码、相关回归夹具；未运行 Gradle、未访问其他产品仓库。生产代码修改由 miniapp_chat 负责，测试最终结果由 root 核验。

## 首轮结论（补修前记录）

MC01–06、MC08 的修复精确，未发现阻塞性新缺陷。MC07 原始协议问题属真问题，方向正确，但初稿存在两个真实阻塞项及一个允许输入边缘缺口，已报 root，待实现者修复后重新核验。

| ID | 二次判定 | 当前修复复核 |
| --- | --- | --- |
| MC01 | 真问题。baseline 跳过引号/注释时已前移 cursor，循环尾又前移，丢掉相邻闭括号。不是全面 JS parser 需求。 | 三类 skipped token 各自 `continue`；不扩展解析范围，合法/错误边界回归覆盖原问题。通过源代码复核。 |
| MC02 | 真问题。源码编辑器捕获 entity，随后保存用旧 entity.copy 会覆盖并发 metadata，删除后 upsert 会复活；新版 HTML 也能被覆盖。 | 事务内重读 current，缺失/版本不符明确拒绝，只更新源码相关字段；恢复版本读/写处于同一事务。通过源代码复核。 |
| MC03 | 真问题。opaque signed URL/base64 被 take(2000) 截断，表面写入成功但附件损坏。 | 仍用原 2000 字符政策，只由静默截断改为明确拒绝；允许 URL 原样保留。附件数量/协议未扩展。通过源代码复核。 |
| MC04 | 真问题。runner factory 捕获 settings snapshot 后，高风险开关变化不生效。 | 实际 createRunnerSendGate 生产 factory 每次决定读当前 SettingsAggregator；实测夹具使用该 factory 和同一 gate true→false→true，无产品重建要求。通过源代码复核。 |
| MC05 | 真问题。effect lookup/create 非原子，两个 bridge 请求能各创建一次。 | 单例 MiniAppWorkspaceWriter 的局部 Mutex 包围 lookup/create，DataSourceModule 用 single。未引入每 key lock registry、未改 ArtifactRepository。通过源代码复核。 |
| MC06 | 真问题。A 发送挂起期间 B 写入，A 成功后无条件 delete 会删除 B。 | DAO WHERE conversation+draftId CAS，writer 使用自身保存 ID；ChatVM 在接受发送时同步捕获已投影草稿身份再异步删。通过源代码复核。 |
| MC07 | 真问题。Claude URL 被塞入 base64 source；Google URL 被塞入 inlineData.data。 | Claude URL source 正确，Google complete/stream 均 prepare 后再 protocol mapping，不修改调用者 parts，失败不会悄悄丢图。初稿两个边界问题待关闭，见下。 |
| MC08 | 真问题。retained ChatVM 从 runner 回到原聊天页，init 不重新执行，持久草稿未入 composer。 | 两个实际 ChatPage 的 LaunchedEffect(vm) 进入 composition 调 onChatVisible；HostDraftComposer 保护手输入/编辑/附件导入/挂起读/重叠刷新，保持原投影的草稿才可替换。消费身份防止 delete 未完成时再次加载 A。通过源代码复核。 |

## 首轮阻塞项（现均已补修关闭）

1. **MC07-CANCEL：headers 后下载取消不生效。** `FileEncoder.resolveRemoteImage` 使用 `Call.await()` 后同步 `byteStream.read()`；common await 取消回调只在 suspended continuation 生命周期有效，headers 交付后调用者取消不能 cancel 当前 body 读取，withContext(IO) 也不打断 socket read。需要保持 Call 取消挂钩到 body 完成，回归在 bodyReadStarted 后取消，应及时取消 Call、无 provider API 请求。不得以整体 timeout/retry 掩盖。
2. **MC07-SANDBOX：新本机下载绕过 MiniApp 已有公网图片边界。** `MiniAppUrlGuard`、`MiniAppHttpClient.executeChecked` 已拒绝私有/保留地址并逐重定向检查；`MiniAppImageProxy` 使用同一 guarded downloader。bridge `host.sendToConversation` 只检查 HTTPS scheme，保存 remote Image；Gemini 新逻辑使用普通 client 本机下载，再上传云模型。draft confirm 仅展示文本，send confirm 仅展示 app/会话，不能视为明确同意访问某个内网地址。真实生产链：MiniAppBridge→ConversationWriter→durable draft/ChatService→GoogleProvider→resolveRemoteImage。最小修法：在 MiniApp bridge 确认/发送审批通过之后、持久化或发送之前，复用现有 MiniAppHttpClient.fetchImage 快照 remote Image 成 data URI；沿用 2 MiB/guard/redirect 规则，不新增 fetch 权限（已有图片资源代理同样不 require Network），不全局禁止普通用户的 LAN URL。只入口 DNS 校验随后普通 provider 下载仍可 redirect/DNS 绕过，因此不能算关闭。
3. **MC07-SCHEME：协议大小写。** parseAttachments 明确 ignoreCase 接受 HTTPS://，但 resolver/Claude/Google adapter 初稿只识别小写。允许输入仍失败，需要统一 case-insensitive scheme 识别并保持路径/query 字节，不把整个 URL lower-case。

## 排除的误报 / by design

- SourceChecks 只负责明显括号错误，不因此要求完整 JavaScript/CSS parser。
- MC03 保留既有 2000 字符接受上限，拒绝巨大 inline data 不等于新的截断。
- HostDraftComposer 仅替换空 composer 或未改过的自身旧投影；用户手工修改过内容仍优先，是明确设计。
- ChatVM 注释与原行为规定一旦接受任何发送就消费已恢复草稿；不把这个既有规则偷改成全文相等才消费。
- Gemini tool function response 当前只编码文本，未为其中未消费的图片下载字节是正确的，不算漏接线。
- 普通用户图像 URL 本来是用户显式内容，不能因 MiniApp 沙箱问题给整个 provider 加私网禁用或新的网络框架。

## 验证状态

源代码与 regression fixture 核对完成；禁止 reviewer 跑 Gradle，绿色执行由 root 提供。最终二次复核结果见下。


## 补修后二次复核结论

**MC01–08 均通过源码及真实接线复审，无剩余源码阻塞。** 尚需 root 执行绿色测试与全量验证，不能把源码检查写成测试已通过。

- MC07-CANCEL 已关闭：局部 `Call.awaitRemoteImage` 在 enqueue 之前安装取消挂钩，continuation 直到 `response.use(read)` 完成才恢复，因此 headers 后实际 body 读取仍属于同一 Call 生命周期。取消后晚到 response 会主动 close；读取成功/HTTP/MIME/size 失败均由 use 关闭 body。有限 blocked-source 夹具从实际 Call 的 onResponse 发 headers，等待 bodyReadStarted 后 cancel，验证同一 Call 收到 cancel，失败也不会无限挂起。未添加产品 timeout/retry，未修改公共 HTTP helper。
- MC07-SANDBOX 已关闭：`snapshotConversationImages` 只处理 MiniApp 的 HTTPS Image，在 sendGate 同意/用户确认完成之后调用；draft 的快照处于确认回调内部。既有 MiniAppHttpClient.fetchImage 执行 guarded DNS/redirect/2MiB cap 后，写入与发送均持有 data URI 字节快照。普通用户 provider URL不受沙箱规则限制，Document 不新增未知行为，也不增加 fetch 权限。实际 postMessage 测试覆盖 durable draft 与 real send 回调字节、私网在发请求前拒绝、公网 redirect→私网不发后续 hop、拒绝 send 前零 fetch。
- MC07-SCHEME 已关闭：Gemini resolve/adapter 与 Claude adapter 识别 scheme ignoreCase。正向 provider 回归使用 uppercase scheme；网络请求只允许 OkHttp 规范化 scheme，调用者原始 URL/path/query 保持不改。
- MC04 readiness 夹具只设置测试 Main dispatcher 并等待真实 SettingsAggregator 初始化，再核持久 flag/policy 和同一 factory gate，未改变产品开关/初始化行为。

复审未发现额外防御框架、重试、provider降级、隐式图片丢弃或越界普通用户URL策略修改。
