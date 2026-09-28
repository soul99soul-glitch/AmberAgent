# E8 任务模型两页实现

Owner C，仅修改 `ChatTaskModelsPage.ets` / `ChatTaskModelPickerPage.ets`。遵循 harmony-arkts skill 和 E8 packet，保留其他 owner 的纯域、prefs、消费者与 DI 修改。未操作设备、build、sign、git。

## 路由与真实接口

- 路由保持 `pages/ChatTaskModelPickerPage`，参数 `{task}` 七值 title/suggestion/compress/image/worker/daydream/ocr。未增加路由或配置存储。
- title/suggestion/compress/image/ocr 从真实原键 `readTaskModelReference` 读取；新选择调用 `saveTaskModelPair(key,{providerId:ProviderSetting.id,modelId:ProviderModel.id})`。API 模型名只用于展示与既有实际请求，没有拿它保存任务选择。
- worker/daydream 用 `taskModelReferenceFromId` 读取实际 snapshot；选择由原 `updateMemoryWorkerModels` 同一 RMW 写 UUID、容器 Provider ID 和 follow=false；清除同时 AUTO、Provider=null、follow=true。保留 E7 字段及 prefs 未知键由既有 RMW 负责。
- 按槽读取捕获非法新 pair 的真实“任务模型配置无效”错误，一坏槽不遮挡其它任务入口，也不虚构 auto 或第二配置来源。OCR 保留原无清除按钮；其余清除沿原 AUTO 键语义。

## 配置与运行事实

- picker 当前行和 key 基于原容器 Provider ID + 配置 UUID；legacy 名称/当前行使用对应任务实际 choice 的 providerContainerId + model.id，读侧不推断/写回 Provider。刷新批次加入 key 的展示版本，确保从服务商页返回后同一配置的新名称/选中/能力状态实际重建，不重复 E6 已确认的 ForEach 闭包旧对象问题。
- fixed lookup 严格 `findTaskProviderModel` 指定容器，无其它 Provider 同 UUID 替换。失效选择仍显示完整 Provider 配置 ID 与模型 UUID，可复制；明确所选不可用。
- picker 可用凭据按 `model.providerOverwrite ?? provider` 判断。pair 始终保存原容器 ID；覆盖后的请求服务商另行展示，覆盖对象的 id 不参与 pair。image 行还要求实际 effective OpenAI 服务商；OCR 行保留 chat+image input 能力过滤。
- 设置页显示 raw 配置来源与 actual effective 请求服务商；辅助任务失效/auto 显示实际公共解析器所得的设置页回退参考，并说明真正聊天回退使用运行轮次选择，不假称页面全局参考就是当前后台会话。
- memory 回退直接使用 `memoryWorkerModelReferences/daydreamModelReferences` 与 root `taskModelReferenceForChoice/resolveTaskModelReferenceChain`，没有手写第二候选序或 Provider 工厂。worker fixed 失效到聊天、daydream fixed 失效到压缩再聊天沿原实际链。
- OCR `loadOcrSeed/probeEntryOcrHealth` 消费同一实际 choice，移除页面原 raw UUID/provider 反查探测。异步健康结果用页面/加载序列限制，不覆盖返回后新配置。image `resolveImageGenChoice` 直接消费实际工具可用性端口；没有新增网络健康探测。OCR/image 未配置/不支持/不可用没有聊天回退。

## 原设置与 UI

- 标题/建议/压缩/OCR 提示词键、默认内容、变量说明、编辑保存及立即重置保存语义保留。实际保存失败显示原错误，编辑内容保留；不显示虚构保存成功。
- CenteredTitleBar/CardGroup、V1 @State 与主题 StorageProp/watch、全 token 包装 SSHUIColors。builder 仅接静态 task/last 参数；动态 slot/health/theme 在 builder 内直接读取，不按值传可变状态。
- 左右 16vp、Top 起始 Scroll、底部 16vp+真实 navBarHeight。任务主行≥52vp、picker≥64vp、提示词/清除/设置/保存/取消/重置均44vp。长 Provider/模型名换行，API 名和完整 UUID/ID可读；当前名称不再挤在标题与箭头之间。
- 两文件合计约530行，未新增组件、UI测试、额外状态框架或设置真相。

## 验证边界

- 首轮两文件窄 lint `/tmp/e8-task-model-ui-lint.log`：退出0，No defects found。
- 最后来源文案与返回列表展示版本调整仅复验两文件，日志 `/tmp/e8-task-model-ui-final-lint.log`：退出0，No defects found。
- `git diff --check` 0。未运行测试、build、sign、安装或设备；root统一类型/编译和独立review。C设备仍offline pending，不能据lint宣称亮暗/长名/交互已通过。

## 独立 A/B 审查确认的 legacy 名称/高亮修复

同模型 UUID 的原容器 A 无凭据、B 可用时，旧辅助聊天任务实际跳 A 选 B，纯 raw lookup 仍会返回 A。已只在 legacy UI 分支按用途实际 choice 显示和高亮；main 的名称用 choice.providerContainerId 定位真实容器，模型用 choice.model。fixed 保持 strict raw pair；OCR 从 OcrSeed.ocrChoice，image 从 resolveImageGenChoice，未套通用 legacy chat 解析器。没有改变 pure raw helper 或消费者协议。

仅受影响两页 lint：`/tmp/e8-task-model-ui-legacy-fix-lint.log`，退出0，No defects found；未新增测试或重跑已有验证。

## 真实编译回喂机械类型修复

root `/tmp/e8-release-build.log` 回喂两个页面共4条 `arkts-no-untyped-obj-literals`：Record 文案/图标表的对象字面量不接受。已改为明确七字段 TaskTexts + 小 switch selector，不使用动态 index signature；原文案、任务参数白名单、默认 title、选择/存储/解析行为均保留。没有修改共享接口或重跑 fixture。

两受影响文件本轮窄 lint `/tmp/e8-task-model-ui-compile-fix-lint.log`，退出0，No defects found；diff-check 0，下一实际编译由 root 负责。
