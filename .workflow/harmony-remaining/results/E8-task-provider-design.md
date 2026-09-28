# E8 任务 Provider 绑定与配置引导：只读实施设计

2026-09-28。已复用 harmony-arkts skill、读取当前范围裁定/执行计划、state、E6 与 E7 合同及实际消费者。`state.json` 当前 E7=in_progress、E8=pending；E3–E6 的设备 C gate 尚未全部完成。本报告不修改产品、state/index，不执行测试、构建或设备操作，不代表 E8 已实施。正式修改等待 root 阶段 gate。

## 当前事实与需要纠正的前提

- 旧范围裁定 `docs/plans/2026-09-28-harmony-full-parity-plan.md:41/94` 将“用途 Provider Override”登记为任务 UUID → Provider/模型对的独立批次。现执行计划 `2026-09-28-harmony-remaining-execution.md:41` 已纳入 E8，同时要求配置说明及真实复用入口。
- **小说已复用 Chat。** `AppContainer.ets:1796` 的 `resolveNovelInteractiveRuntime`：global→resolveChatRuntime；fixed→resolveChatRuntime(undefined, model UUID, provider ID)，固定失效返回 null。`NovelSettingsPane.ets:123/130` picker 已用 providerId+modelId，旧 ModelRegistry 不参与小说解析。不能继续写“小说也要重新填写深读 API 配置”。
- 实际双轨是 Chat `providers`/助手/任务槽，与 DeepRead `ai_base_url/ai_api_key/ai_model` + `model_configs`。`buildContainer:155` 给 DeepRead 创建 OpenAiCompatibleAiClient 和 ModelRegistry；`getDeepReadScheduler:270` 捕获 deepread_model_id 或 ai_model。深读、议会仍消费旧配置；E8 不合并两套生成引擎。
- `ProviderModel.id` 是配置 UUID，`ProviderModel.modelId` 是发送给服务商的 API 模型名；Chat 全局 `{providerId, modelId}` 保存的是 API 名，小说 fixed 的 modelId 是 UUID。新任务对必须明确保存 **ProviderSetting.id + ProviderModel.id**，不能直接套用全局 ChatModelSelection。
- 当前任务 picker 本来就列出所有可用 Provider 的模型，因此独特 UUID 已能选跨 Provider 模型。E8 的具体增量是保存和解析显式 Provider 身份、处理重复 UUID/同名模型，不是再建一套 Provider。

## 真实存储与消费者

以下 Entry 路径前缀为 `harmony/entry/src/main/ets/`；域路径为 `harmony/chat/src/main/ets/chat/`。

| 位置 | 当前事实 | E8 必须接到同一选择事实 |
|---|---|---|
| `di/AppContainer.ets:1373–1405` | title_model/suggestion_model/compress_model/image_generation_model 均 string UUID；loadTaskModelIds/saveTaskModelId | 新对的读写；旧 UUID 只作为兼容输入/派生值，运行须携带 providerId |
| `pages/ChatTaskModelPickerPage.ets:28/106/119` | row 有 modelUuid、API modelId，缺 providerId；选中只比 UUID | row 加 providerId；选择和 key 比 Provider+UUID，保存真实对 |
| `pages/ChatTaskModelsPage.ets:100/146/177` | findModelByIdInProviders 决定标题/跟随；clear 写 AUTO；worker clear 写 AUTO+follow=true | 显示与请求共用精确 selector，不能把失效 fixed 对显示成已选另一 Provider |
| `AppContainer.ets:1454/1475/1526` | task UUID 在全 Providers 反查再 makeProviderChoice；Novel fixed 已精确匹配 | 新 task resolver 先按 Provider+UUID 命中，再沿原 makeProviderChoice/auth；原 UUID resolver仍给非用途 caller 使用 |
| `platform_impl/ConversationRunDependencies.ets:58/74/209` | 本轮 compress 解析，并显式 runtime.resolvedChoice fallback | 读取 compress 对；E6 每轮冻结 resolvedChoice 不倒退到全局 Chat |
| `ConversationRunAuxiliary.ets:52/79/105` | title/suggestion 单槽，extraction worker→compress→本轮 chat | title/suggestion/worker/compress 全带 pair，保留字段合并和本轮 fallback |
| `pages/ChatPage.ets:966/1845` | 仍有 compress 展示缓存、列表摘要复用 title 槽 | 同样使用新槽解析，不能只迁新 service、漏残留 UI/辅助入口 |
| `AgentRuntimePrefs.ets:34/377/385` | agent_runtime.memoryWorker 保存 modelId/daydreamModelId、followCompressModel 两旗；RMW 保未知字段并共享锁 | 同一 RMW 保存对应 Provider ID；保留两个 UUID、follow 旗、reasoningLevel及 E7 新字段 |
| `MemoryDreamOps.ets:56/58`、域 `task_model.ts:39/59` | worker/daydream 条件分支与候选链是纯 UUID 数组 | 将同一分支所选引用带上 Provider；不得用 UUID→Provider Map 丢掉同 UUID 的两个用途身份 |
| E7 `MemoryTopicOps`（合同已冻结，当前文件尚未落） | 每轮重读并复用 daydream→worker→compress→actual chat，没有新模型槽 | E7 gate 后核对最终文件；其新消费者必须走同一 pair 候选链，不另加 topic 槽 |
| `OcrSupport.ets:44/180/187/198` | ocr_model UUID；缺失/坏 UUID按原规则；OcrSeed providers+ocrModelId，反查Provider；健康探测同路径 | OCR seed 加 Provider 身份，实际识别与设置健康探测精确同源；保留 IMAGE 输入过滤/缺失不可用 |
| `ImageGenTool.ets:50/67` | image_generation_model 直接 scalar 读取，仅有 auth 的 OpenAI image；execute 实时重新取配置 | 工具注册与 execute 都读 pair；保留 image type/OpenAI门、源图、本地落盘；不改为自动找其它生图模型 |
| `SettingDeepReadPage.ets:114/127/279`、`OpenAiCompatibleAiClient.ets:264` | AI 配置是 legacy ai_*；client 仅 Bearer + Chat Completions，模型库 model_configs给议会 | 不将 deepread_model_id/ModelConfig.id 错迁为 task UUID；复用入口只填可兼容配置 |
| `SettingBackupPage.ets:99`、域 `settings_backup.ts:13` | 当前设置快照仅 assistants/providers；**任务槽/ai_*/agent_runtime未由此包导出** | 不声称升级后这些设置已跨端备份；不借 E8 扩写备份协议 |

## iOS / Android 实际语义

Android `core/settings/.../prefs/ChatPrefs.kt:71–96` 写入各任务 UUID；`PreferencesStore.kt:479` 是 findModelById ?: getCurrentChatModel；`:500` 的 Model.findProvider 在原容器存在时应用 model.providerOverwrite。`SettingModelPage.kt:443/565/601/654` 的 UI 跟随与 worker/daydream clear/select 旗一致。`AiAuxiliaryGenerator.kt:41/87` 通过所选 model.findProvider，再用原 providerHandler；不是任务级 Provider 对。

iOS `ModelDefaultsView.swift:138/224/242/251/280` 也列 Provider 中模型、按 UUID 去重/保存；`ChatViewModel.swift:2987/3011` 先 UUID 或当前 Chat，再 ChatProviderConfiguration.provider；视觉/生图单独能力门。`IOSSharedSettingsStore.swift:1639` 明确 scalar UUID pointers。没有找到任务槽自身保存 Provider/UUID 对的证据，因此 E8 是本计划明确要求的 Harmony 改进，不能称为照抄已有双端 pair schema。

Android 的 `SettingProviderModelSettingsForm.kt:734` “ProviderOverrideSettings”是**模型级 providerOverwrite**；Harmony 已有 `effectiveProvider:1312`。该现有 override 仍由 makeProviderChoice 应用，任务 pin 的 providerId 指模型所在容器。两者不混名、不重复 endpoint/auth 编辑，不为了新 pair 移除模型级 override。

## 冻结的最小兼容方案

```ts
interface TaskModelPair { providerId: string; modelId: string; } // modelId = 配置 UUID
type TaskModelReference =
  | { kind: 'auto' }
  | { kind: 'legacy'; modelId: string }
  | { kind: 'fixed'; pair: TaskModelPair };
// resolver 的 ModelType/视觉要求按当前调用端传入，不重写 provider 工厂。
readTaskModelReference(key: string): Promise<TaskModelReference>;
saveTaskModelPair(key: string, pair: TaskModelPair): Promise<void>;
resolveTaskModelReference(ref: TaskModelReference,
  fallback?: ChatProviderChoice): Promise<ChatProviderChoice | null>;
```

1. **同键单次写，兼容读，不建第二份配置真相。** title/suggestion/compress/image/ocr 的现有键继续为 Preferences string；旧非空 UUID 保持可读，用户新选时写 JSON 字符串 `{"providerId":"P","modelId":"model UUID"}`。AUTO/清除保留各槽原规则。新的 read 返回 typed reference；旧 loadTaskModelIds/getOcrModelId 如保留，必须从 typed ref 投影 UUID而非把 JSON 文本当 UUID。无需启动时整库重写、migration ledger、UUID重建、双写 sidecar或扫描Provider自动配对。
2. legacy reference 保留原 UUID 查找和原 fallback语义，不在只读加载时猜/落库 Provider；用户点选真实 picker row 才成为 fixed 对。fixed reference只在指定容器中找该 UUID；失败不再遍历别的 Provider。title/suggestion/compress及 memory 候选继续沿**已有** fallback次序；UI显示“所选模型不可用；当前使用 …”，不假称正在用该 fixed。OCR/image没有 Chat fallback，按原不可用/不装配行为。Novel fixed仍严格 null，不借用 task fallback。
3. Memory worker/daydream保留原 `modelId/daydreamModelId:string`，在同一个 agent_runtime RMW 加 `providerId:string|null`、`daydreamProviderId:string|null`；缺失null就是legacyUUID语义。选择一次写 UUID+Provider+follow=false；清除写 AUTO+Provider=null+follow=true。`MemoryWorkerGateSnapshot/TaskModelWorkerGate`最小增加这两个字段，原 UUID不能删除。候选链按原条件生成 TaskModelReference列表，不能只取旧 string[] 后按 UUID 猜用途；纯分支不加入 IO。
4. 公共 task resolver 返回原 ChatProviderChoice，调用方仍使用原 buildProviderApi/asChatStreamProvider/params/transform/tools。任务对的解析得到原有效 Provider（含 model.providerOverwrite），不在 loader重复构建 Provider。原 `resolveTaskChatModelChoice(string, fallback)` 保留非用途 UUID调用（subagent role定义等）；任务 consumer必须显式传ref，禁止按modelUUID全局套用某个用途的 override。
5. 运行 seed 一次保存选择结果；设置页切槽只影响下一轮，E6 A/B 各自 fallback不串。辅助标题/建议当前每次重读槽的语义保留；本轮Chat fallback仍显式传原 resolvedChoice。image execute 现有实时换槽语义保留。E7改完 memory schema 后在其最终源上添加两 Provider 字段，不覆盖 topicWriteEnabled/其它未知字段。

必须保留：ProviderSetting.id、ProviderModel.id、任务 scalar旧 UUID值、AUTO 常量、OCR旧 UUID/提示词、memoryWorker旧两个 UUID及follow旗；Assistant.chatModelId/imageGenerationModelId、SubAgentDefinition/override.modelId、NovelModelTarget fixed UUID、ModelConfig.id、Council seat.modelId、Chat全局 API-name选择键。E8不重命名/重建这些引用，不将同 API modelId 的不同配置合并。

## 首次配置引导的两个真实入口

1. **ChatProviderSettingsPage**：列表上方复用 FooterNote/正常可换行布局；文案“聊天和小说共用这里的服务商与模型。小说默认跟随聊天，也可在项目设置中指定。标题、建议和压缩可在‘任务模型’中单独选择服务商。深度阅读目前使用独立的兼容 API 配置。”加普通行“深度阅读配置”→SettingDeepReadPage，及已有任务模型路由。不是新增首启向导/每次弹窗/额外持久已读状态。
2. **SettingDeepReadPage 的 AI 配置卡**：文案“这里用于深度阅读；模型库用于议会席位。聊天和小说使用 Chat 服务商配置。可填入当前 Chat 的兼容 API Key 配置，再点击保存。”放实际“填入当前 Chat 配置”按钮：从当前有效全局 Chat choice取 baseUrl/apiKey/API modelId，只填当前页面三个draft字段，不自动保存、不建第二Provider、不改任务/小说选择；复用原保存入口。去 Chat 服务商设置行用于未配置情形。

复用必须按真实客户端限定：DeepRead只支持 OpenAI-compatible Chat Completions + Bearer API Key。当前 choice为 OpenAI且authMode=api_key、useResponseApi=false，并且模型自定义 headers/bodies无需要保留的附加配置时才可填入；Google/Claude/OAuth/SA/Responses不能把凭据当普通 API Key复制。保持原手工填写入口并显示具体不兼容说明，不自动转换凭据/改协议/另建Chat适配器。用真实源判定兼容，完成标准不含虚构任意Provider复用。模型级 override 时取原 effective choice而不是容器凭据。

当前模型库/SettingDeepRead 注释说“供小说”已经与 C5代码不一致；引导 owner只精确改这些相邻面向用户文案为“供议会”，不删旧模型库及其 default迁移，不调整议会生成。

## 窄文件分工与成功标准

- A：新纯域 `task_model_reference.ts` 的 reference decoder/encoder、`task_model.ts` 的 pair候选选择及必要index导出；Entry新 `TaskModelSelectionSupport.ets` 的同键 storage/精确 Provider查询（复用原 makeProviderChoice端口）；不触Page/DI共享装配。
- B：`OcrSupport.ets`、`ImageGenTool.ets`及精确 TaskModels健康探测端口：真实 pair消费者与原能力约束；不改选择页/共享DI。
- C：`ChatTaskModelsPage.ets`、`ChatTaskModelPickerPage.ets`：真实 Provider row/selection/clear/UI显示；两入口引导 `ChatProviderSettingsPage.ets`、`SettingDeepReadPage.ets`及小复用helper同一owner，避免共同编辑Picker（如需并行可把引导两页分给D）。
- root：AppContainer singleton/公共resolver接线、E6 Dependencies/Auxiliary/ChatPage、E7完成后的MemoryDreamOps/MemoryTopicOps/AgentRuntimePrefs、index/routes。先冻结最小公共签名；每位owner只动其文件，保留E7新字段。无需改Native/Recipe/Plugin引擎、DeepRead协议或Novel固定策略。

可验收条件：旧UUID设置仍产生原模型与fallback；新对保存→重进选中→运行Provider同源，重复UUID/同API名不同Provider不串；worker/daydream与compress继承链携带所选Provider并保留两follow旗/E7字段；OCR健康检查=识别实际目标、image装配=执行实际目标；E6各轮实际chat fallback不被另会话替换；Novel fixed失效仍不可替换。两引导入口路由可达、说明小说现状，兼容Chat填入draft后原保存产生真实DeepRead配置，不兼容Provider无凭据转换。

必要验证只计划一组小型旧UUID→新pair roundtrip/精确定位/clear+memory候选案例，及受影响真实消费者的一组调用检查；不铺笛卡尔矩阵、不重复全套已有回归。root统一必要type/lint/实际build及两页亮暗排版/长名称换行与保存返回检查。设备不可用时明确C待验；本只读阶段不执行、不声称PASS。
