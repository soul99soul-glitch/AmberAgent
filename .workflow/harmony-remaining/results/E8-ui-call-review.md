# E8 独立 B UI / 调用链审查

2026-09-28。基点 `1bbd1dc43`；已读 harmony-arkts skill、E8 合同/设计及当前实际差异（含新增 selection/draft 文件）。审查任务页/Picker、Provider/DeepRead 引导与草稿，以及七槽真实消费者、DI/prefs/OCR/image 路径。只写本报告，没有修改产品或测试，没有执行测试、lint、构建、签名或设备操作。

## 精准发现及修后复核

1. **P1：无关坏槽阻断正常 Chat 与其他任务，已关闭（源码）。** 原 AppContainer `:1403–1407` 聚合严格读取四槽；ConversationRunDependencies 原 `:58` 只为压缩，却读取全部槽。把 `image_generation_model` 写成缺 modelId 的新 JSON 对，即使 Chat/压缩有效且生图关闭，decoder 仍令 prepareConversationSeed 整轮失败；标题、建议、提炼、daydream 同样被无关配置挡住。root 窄修每个实际消费者只读取所需键：Dependencies `:58/74` compress；Auxiliary `:55–57/82–84/108–111` title/suggestion/compress；MemoryDreamOps `:48–50` compress；ChatPage `:966/1845–1847` compress/title。聚合 API 只保留兼容导出，当前 Entry 无实际消费者调用。坏的被消费槽仍抛真实配置错误，没有宽 catch、猜 Provider 或统一 AUTO 回退。
2. **P2：legacy 重复 UUID 的页面显示/高亮与实际 Provider 不同，已关闭（源码）。** 原 TaskModels 优先 rawName 的首个容器，Picker 也用原始首个匹配；disabled A、可用 B 的 chat 模型具有同一 UUID 时，legacy 运行实际选择 B，页上却显示 A，Picker 把 A 过滤后说当前不可用且 B 未选中。C 已加 TaskModels `configuredName :100–105`：仅 legacy 根据实际 choice.providerContainerId 与 chosen model 命名；title/suggestion/compress、memory、OCR 使用该真实显示。Picker `:90–98/112` 的 legacy chat/memory 使用配置 resolver，OCR 使用实际 seed，image 使用实际 image resolver，按实际容器 ID+model UUID 高亮。fixed 仍严格保存/显示原容器对，模型 overwrite 的请求 Provider 独立显示，没有用 effective provider.id 替换容器 ID。
3. **P2：多 API Key 轮换被错误复制成 DeepRead 单个 Bearer token，已关闭（源码）。** 原 helper 将 provider.apiKey 整串复制；真实 Chat 的 AppContainer `:1648–1664` 每请求调用 KeyRoulette，search_service `:389–398` 按空白/逗号 split、trim、distinct，而 DeepRead client `:287` 直接 Bearer ai_api_key。synthetic `key-a,key-b` 在 Chat 单 token 轮换，复制后却成为复合 token。D 窄修 ChatDeepReadDraft `:30–39/50/58–62`：沿相同规则取 distinct keys；多于一个返回具体不兼容说明，不填草稿；一个使用规范化单 key，授权头等价校验也用该 key。没有调用 roulette 或改变其状态，没有加入 DeepRead 轮换、转换凭据或自动保存。

三项均已即时发 root/对应 owner；修后只读复核相关差异。没有提出无证据的兜底或新框架建议。限定当前审查范围无未关闭的具体 P1/P2。

## 七槽与真实消费者

| 用途 | 保存事实 | 读取 / 运行 |
| --- | --- | --- |
| title | 原 title_model string，新选择写 ProviderSetting.id + ProviderModel.id JSON 对 | TaskModels/Picker 与 Auxiliary auto-title、ChatPage 手动总结共用 typed reference；有效 fixed 请求使用原 factory，失效按原本轮 Chat 回退 |
| suggestion | 原 suggestion_model 同键对 | Auxiliary 与设置用同 resolver；回退显式传 detached seed.runtime.resolvedChoice |
| compress | 原 compress_model 同键对 | prepareConversationSeed 只读此槽，冻结本轮 choice；compress provider 保留模型 customHeaders/customBodies；Page 展示读取同槽 |
| image | 原 image_generation_model 同键对 | image 注册与 execute 都用 resolveImageGenChoice，execute 保留原实时换槽；image 类型、有效 OpenAI auth 门；无 Chat 回退 |
| OCR | 原 ocr_model 同键对 | loadOcrSeed → 同一 ocrChoice 供实际 transformer 与 probeEntryOcrHealth；ChatPage 健康门已接 helper，静态兼容 providers 仅包含这一有效目标；无 Chat 回退 |
| worker | 同 agent_runtime.memoryWorker 的 modelId/providerId/followCompressModel | Picker 一次 RMW 保存对+follow=false；clear 一次写 AUTO/null/follow=true；提炼候选传每项 reference 与本轮 Chat fallback |
| daydream | 同 agent_runtime.memoryWorker 的 daydreamModelId/daydreamProviderId/daydreamFollowCompressModel | 同一 RMW 保存/clear；保留既有 daydream→worker 条件选择与 compress/chat 候选次序；E7 TopicOps 复用 MemoryDreamOps 实际 resolver |

- 模型配置 UUID 是 ProviderModel.id；API 模型名 ProviderModel.modelId 只用于真实请求。四 scalar 任务键、ocr_model、agent_runtime 都沿现有存储，没有第二份选择真相或读取时迁移写入。
- worker/daydream 更新共用 `sharedKvMutex.withLock(AGENT_RUNTIME_KEY)`，读原 JSON 后只改 patch 字段；topicWriteEnabled、E7 gates 和未知字段保持。正常 title/suggestion/compress/image clear 保留旧 AUTO；OCR 保留旧 allowClear=false（OcrSupport `:58`），没有把不存在的 UI 清除行为声称为已新增。
- fixed 查找只匹配声明容器与 UUID，不在删除/失效时转向同 UUID 的其他 Provider。legacy chat 仍用旧配置 resolver，保留旧凭据过滤次序。原 Novel fixed 解析继续独立 fail-closed，不进入 task fallback。
- makeProviderChoice 应用有效 model.providerOverwrite 一次，并保留 providerContainerId；Picker 以 effective Provider 判 auth/type、展示实际请求标签，选择保存的仍是原容器。OCR health 和执行共享有效 choice；目标 cache prefix 保留 typed reference 与原容器身份，重复 UUID 不复用另一目标结果。这里未重复 owner 的实际 consumer fixture，也不据源码声称远程 OCR/image 成功。

## 草稿、引导与布局边界

- 两说明入口使用实际 routes：Provider → TaskModels/SettingDeepRead；DeepRead → Provider。main_pages 已登记。文案准确区分 Chat/小说共用 providers、DeepRead 独立兼容 API、旧模型库供议会，不称小说必须重填深读配置。
- fillCurrentChatDraft 等本页 pageLoad 完成后解析当前有效全局 Chat choice，并检查 pageAlive/loadToken；仅赋 aiBaseUrl/aiKey/model 草稿。原 Save 仍写真实 ai_base_url/ai_api_key/ai_model，没有自动保存或修改任务/小说选择。
- helper 限定 OpenAI api_key、Chat Completions。真实 Chat endpoint 拼接须与 DeepRead 的移除末尾斜杠及 /vN 判定相同；Responses/Google/Claude/账号模式/不能保留的路径、headers、bodies 返回具体说明。深读模型覆盖优先级在页上明确保留，不错误宣称三草稿字段能改写独立覆盖槽。
- 新任务两页 V1 State/StorageProp、颜色快照和 watch，进入/返回重新读取；seq/alive/visible 拒绝离页旧读取与健康结果。Picker key 编码原 providerId 长度+providerId+模型 UUID，不因不同容器重复 UUID 合并行。
- 新两页 16vp 外侧 gutter，底部 16+navBarHeight，共用安全区标题栏；任务行 minHeight52、Picker minHeight64，实际动作 44vp。长名称/实际 Provider 标签限两行，API 名/ID/错误详情换行；任务 current/identity 使用完整宽度可复制。引导采用原 FooterNote/CardRow/BtnGhost，不新增固定高说明容器，builder lambda 仅调用 @Builder。

## 证据边界

本报告是独立源码/调用链审查。必要测试、lint/真实 ArkTS compile/sign 由现有 owner/root 唯一 gate 负责，本人未执行或据它们声明独立 PASS。设备离线，亮暗主题、窄屏长名称、键盘/安全区和点击实拍仍未验收；**无截图/视觉 PASS**。不把本报告或 controlled host consumer 结果当作真实 Provider/设备 API 接受证据。
