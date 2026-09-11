# Phase 4–9 独立 review 修复核验

范围：Android main，基线 HEAD ab984f6df1d6b5f9bae2b007ccdcf43f1d467e62 上已有未提交 WIP；不重置、不提交，不修改 iOS，不操作设备。用户要求逐项复核、精准最小修复，不增加依赖或泛化兜底。

上一轮结论：19 项均已修复（fixed）。后续在 Android 工作树复查发现的三处遗漏及本次精准修复见文末；下表保留上一轮记录，UI 接线仍区分源码/编译验证与设备验证。

| Review 项（均为 fixed） | 已实现修复 |
| --- | --- |
| 1 Q08 游标先于消息落盘 | 完成响应优先取完整 GET 输出，消息持久化成功后才结束 run，再清游标；transport 不清终态游标，空 replay 不冒充完整结果 |
| 2 Antigravity 回调路径 | Loopback server 支持精确配置路径，保留其他客户端默认 /callback |
| 3 快捷入口不可解析 | 生产 Shortcut Intent 显式指定 RouteActivity |
| 4 URL 数字/转义主机绕过 | 复用 HttpUrl 规范化主机，再拒绝非标准 IPv4 写法和私有地址 |
| 5 提醒缺生产入口 | 复用工具注册与审批，CRUD 调用 Store/Scheduler，通知进入已有聊天入口 |
| 6 OAuth 迟到刷新 | 同 provider generation 下原子检查与存储/失效，过期结果不返回 token；UI 用 generation 判断会话，不以 token 等值拦截正常刷新 |
| 7 Antigravity 吞取消 | onboarding/模型发现取消继续抛出，取消不触发设置提交 |
| 8 Grok endpoint 备份时机 | 切模式保留原 endpoint，登录成功才提交固定 endpoint |
| 9 haptics 波形数组 | timings/amplitudes 长度及静默段一一对应 |
| 10 MiniApp 总开关 | 系统调用与 capability discovery 同时检查 enabled |
| 11 openURL 二次确认 | 确认结束重读版本/hash/声明/开关/Room grant |
| 12 后台/关闭迟到语音 | 前台状态和初始化 epoch 在实际发声前检查 |
| 13 TTS 语速 | 设置页倍率转为 bridge 0..1 语义 |
| 14 TTS 启动中无法停止 | 启动中保留停止入口并取消启动 job |
| 15 Health 分页 | 首请求无 token；后续不重复设置排序；-1 为终止 |
| 16 brightness 契约 | getBrightness 返回数值而非对象 |
| 17 TTS 自然结束状态 | 监听真实 utterance 结束并忽略旧 utterance 回调 |
| 18 app.info grant 字段 | 输出 Room updatedAt |
| 19 Grok endpoint 展示 | 固定地址取当前 authMode 的 endpoint |

证据边界：上一轮 XML 的 234 项结果是所选回归，不是五模块全量；fake handler 桥接测试只证明桥接与 Room 权限路径。此前 QR“独立解码”没有随台账保留完整命令/输出，本次只按新测试实际结果记录，不补造历史证据。真实账号、SSH 来源、Wear transport、Health 授权 UI/真实记录、真机硬件与 release 门仍未验证。

## 本次验证

- common 8 项、ai 42 项、app 184 项，共 234 项；0 failure/error/skip。这是所选回归，不是模块全量。
- 最后调整 recovery 的“先写 COMPLETED，再清 cursor”顺序后，RunRecoveryServiceResumeTest 13 项再次通过。
- 每个 suite 的数量、时间与 test case 名称见 [测试清单](parity-artifacts/2026-09-10-fix-tests.json)。
- 完整命令与输出：[统一回归日志](/tmp/amber-phase4-9-fix-final.log)、[恢复完成顺序复验](/tmp/amber-phase4-9-fix-recovery-order.log)。
- 识别 CRLF 后的 diff check 通过：git -c core.whitespace=blank-at-eol,blank-at-eof,space-before-tab,cr-at-eol diff --check。RouteActivity 保持 1219 个 CRLF，0 个独立 LF。
- 无新增依赖、数据库 schema、设备操作、iOS 修改或提交；现有 WIP 保留。

初次 common 离线测试因 4 个既有 AAR 未缓存而失败；由 Gradle 下载已声明依赖后，8 项回调测试通过，最终统一回归恢复使用 --offline。初次 app 测试的 import、TTS shadow 初始化回调及 Robolectric SDK/graphics 配置错误均已修正；以最后成功记录为准。

## 关键证据

- Q08：ResponseAPIResumeStreamTest 验证流结束保留 cursor；RunRecoveryServiceResumeTest 的 completedCursorAtTerminalUsesCompleteGetBeforeClearingCursor 走真实 ResponseAPI 写入终态游标、模拟崩溃、真实 GET interceptor 和 Room 恢复，并在清游标时断言 COMPLETED 已持久化；completedStatusWithoutOutputDoesNotPromoteSeedPartialToFinal 验证空 replay 不把 partial 标为完成。ChatService 强制 checkpoint 的成功标志用于完成状态发布与 cursor 清理。
- OAuth：LoopbackOAuthCallbackServerTest 的真实 socket 回调通过；app 模块 GrokOAuthSessionTest / AntigravityOAuthSessionTest 共 4 项真实 client refresh 竞态测试，覆盖 logout+新 token 保存后的旧成功和 invalid_grant 响应。onboarding/模型发现取消、endpoint 保存与展示由源码和编译核查；未执行真实登录 UI。
- MiniApp：MiniAppSystemCapabilityBridgeTest 驱动实际 postMessage→Room→确认→dispatch，覆盖总开关、二次确认期间版本/hash/声明/删除/grant/开关变化及 app.info updatedAt。MiniAppSystemCapabilityProtocolTest 覆盖数字/转义/全角 IP、控制字符、端口和 IPv6 authority。
- Native：MiniAppAndroidDeviceCapabilitiesTest 调生产 native dispatch 验证波形与亮度，并用 Robolectric native graphics 生成 PNG 后调用现有 ZXing reader 解码成功。MiniAppSpeechEngineTest 真正挂起初始化，pause/close 后手动送入迟到 SUCCESS，并检查没有发声；完成/旧 utterance 回调也通过。
- TTS 设置页的倍率 /2、启动可停与完成状态接线经源码/编译核查；引擎路径有上述回归，未另做 Compose 点击矩阵、设备听感或大字体复测。
- DynamicShortcutIntentTest 根据 Manifest 将生产 shortcut Intent 解析到 RouteActivity。ReminderToolsTest 走真实 Tool.execute→Store→Scheduler；ReminderOwnerTest 改测实际 durable CAS；ReminderNotificationIntentTest 验证已有聊天预填入口。HealthConnectPaginationTest 检查真实 platform request builder 的首/后页参数。
- Health 的 -1 token 语义按 [Android API 文档](https://developer.android.com/reference/android/health/connect/ReadRecordsRequestUsingFilters#getPageToken()) 核对；真实授权/记录互操作仍在原边界内。

## Android 工作树三项定点修复（2026-09-10 后续）

本轮只处理再次 review 确认的三处问题；未提交、未操作设备，未增加依赖、数据库结构或通用重试机制。新增 8 个回归测试方法，关键问题均先复现失败，再修复通过。

| 问题 | 当前修复与证据 |
| --- | --- |
| 恢复沿用旧 run 却新建 POST | `ResponsesResumeRequest.resumeFrom` 明确绑定旧 cursor；`ResponseAPI` 使用既有 GET 重播完整响应并替换 durable partial，流式/非流式均覆盖。`ChatRunCoordinator` 仅首轮携带此 cursor，后续工具轮次继续 POST。`ChatService` 在 begin 前检查 Provider 匹配；重新生成先通过 `StoredResponseStopCancel` 确认旧响应取消并结束旧 run，取消未确认则保留 WAITING_EXTERNAL 和 cursor。传输及 Room 状态回归通过。 |
| Grok 模式切回后实际地址错误 | `OpenAIProvider` 复用原 managed OAuth 地址处理，按 GROK_OAUTH 固定真实请求的 proxy 地址与 Chat Completions 模式；complete/stream 两条真实请求链测试通过，不依赖 UI 展示值。 |
| Grok endpoint backup 未同步 | `SyncSecretSnapshot` 增加 nullable `grokOAuthBackup`，`SyncArchiveManager` 直接接入现有 side-table 导出/恢复方法。真实 FULL archive round-trip 与旧包缺字段兼容通过。`GrokAuthStore.saveBackup` 精确跳过 managed proxy，避免旧包导入后设置页把它误记为原地址；没有原地址时沿用现有退出默认地址。 |

最终定点回归共 **66 项，0 failure/error/skip**：ai 的 ResponseAPIMessage 11、ResumeStream 6、StreamReconcile 19；app 的 SyncArchiveManagerIntegration 6、OpenAIProviderGrokEndpoint 2、GrokOAuthSession 2、RunRecoveryServiceResume 13、StoredResponseStopCancel 7。此前单批结果有重叠，不重复累计。Android app 编译通过，现有编译警告未扩大修改。

验证命令：

```sh
./gradlew :ai:testDebugUnitTest --tests 'app.amber.ai.provider.providers.openai.ResponseAPI*' :app:testDebugUnitTest --tests app.amber.agent.data.sync.SyncArchiveManagerIntegrationTest --tests app.amber.ai.provider.providers.OpenAIProviderGrokEndpointTest --tests app.amber.ai.provider.providers.grok.GrokOAuthSessionTest --tests app.amber.feature.runtime.StoredResponseStopCancelTest --tests app.amber.feature.runtime.RunRecoveryServiceResumeTest --offline --console=plain -Pksp.incremental=false
```

日志：[最终回归](/tmp/amber-three-fixes-verified.log)、[Grok 地址修复前](/tmp/amber-three-fixes-grok-red.log)、[恢复修复前](/tmp/amber-three-fixes-resume-sync-red.log)、[同步修复前](/tmp/amber-three-fixes-sync-red.log)、[重新生成修复前](/tmp/amber-three-fixes-regeneration-red.log)、[旧包入口修复前](/tmp/amber-three-fixes-legacy-red.log)。初次旧包夹具将 String 字段误写为 JSON object，已修正夹具，不为夹具错误修改生产反序列化规则。

独立复核：恢复主链及 Provider 切换前检查、取消后 outcome 刷新均通过；Grok 请求固定与同步接线通过，旧包 proxy 误备份问题已按复核建议定点修复并复验。Provider 切换提示与设置页入口属于源码/编译核查，未运行 Compose 点击或真实账号测试。CRLF-aware `git diff --check` 通过。此三项可以收口，原有真实账号、硬件、SSH/Wear 与发布门边界仍保持不变。
