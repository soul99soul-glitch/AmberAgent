# E0 · D3/D4 补审

审查基准：`HEAD 6840fa551a445e20c21b0b498a5e05796682745e`，读取 `git show fac8ba745`、`git show 203770192`、`git show 91ac1ae87` 及当前消费者。只读业务代码；本报告是唯一写入。遵循 `.agents/skills/harmony-arkts/SKILL.md` 与 `docs/plans/2026-09-28-harmony-full-parity-plan.md` 的 A/B/C 验收。

## 验证证据

- `harmony/chat/node_modules/.bin/tsx --test src/test/provider_settings.test.ts src/test/provider_overwrite.test.ts src/test/openai_responses_api.test.ts src/test/sync_snapshot.test.ts`：53/53 PASS。
- 独立内存复现：默认 `makeAssistant()` 直接入 `buildSyncPayload.settings.assistants` 抛 `unknown LocalToolOption:`；带 `makeProviderModel({ type: 'chat' })` 的 provider 直接入包抛 `unknown ModelType: chat`。经现有 `serializeAssistantList` / `serializeProviderSettingList` 后 `JSON.parse` 入包，两者通过。
- 独立内存复现：`makeProviderModel({ type: 'bogus' as never })` 经现有 `serializeProviderSettingList` 产生 `"type":undefined`，`parseProviderSettingList` 抛 JSON 语法错误。
- 独立内存复现：无模型 provider 的 `apiKey: 'FAKE_REVIEW_SENTINEL'` 进入 `buildSyncPayload` 后，载荷仍含该哨兵值。没有读取或输出真实密钥。
- 本次未构建、安装或获取截图；UI 视觉验收未做。下述 C 项是与仓库已记录运行时故障完全一致的代码路径，不能替代修复后的设备验收。

## A · 逻辑闭环：FAIL

### A1 [P1] 备份页用领域对象组装线格式 settings，常规导出失败

- 位置：`harmony/entry/src/main/ets/pages/SettingBackupPage.ets:99–104`；校验消费者 `harmony/chat/src/main/ets/chat/sync_snapshot.ts:267–271`；模型线格式解析器 `provider_settings_serialize.ts:122`。
- 触发：选中“助手”或有模型的“服务商”导出/上传。`loadAssistants/loadProviders` 返回领域对象，其工具是字符串、模型枚举是小写；`buildSyncPayload` 却使用真实线格式解析器，分别要求 sealed 工具对象与大写模型枚举。默认全选因此不能完成导出。最小复现见上。
- 最小修复：用现有 `serializeAssistantList/serializeProviderSettingList` 转为线格式 JSON 对象，再交给 `createSyncBackup`；覆盖入口组装的 roundtrip 回归。
- 来源：旧导出逻辑已经存在，D4 的共用 `packBackupBytes` 沿用了该缺陷；不是声称由 D4 首次引入。

### A2 [P1] 服务商备份实际携带 API Key，与明确脱敏文案相反

- 位置：`SettingBackupPage.ets:104`，UI 承诺在 `:484`；域层注释 `sync_snapshot.ts:102` 明确调用方过滤密钥，而 `:267–272` 只校验、不脱敏。
- 触发：导出包含无模型服务商的备份可直接复现；修复 A1 后所有服务商备份均会携带原始 `apiKey`。Google 的 `privateKey` 和模型 `providerOverwrite` 也属于同一递归配置结构。加密不会使“备份不含 API Key”成为真。
- 修复裁定：按主 agent 的明确范围，备份保留凭据供恢复，修改页面声明为“包含所选服务商凭证，建议使用密码加密”。脱敏 Provider 配置文件导入仍是新 ID/空凭据，与备份恢复是两条独立语义；不要求从备份剔除凭据。
- 来源：旧载荷问题；D4 新服务商类别文案将其变成明确的行为矛盾。

### A3 [P2] 运行中修改类别会改变已启动操作的范围

- 位置：`SettingBackupPage.ets:89–104`、`:350–393`；始终可交互的类别开关 `:473–486`、`:532–546`。
- 触发：点击只导出会话，在 `repo.list/getById` 等待期间打开“服务商”，`:102` 会把服务商纳入本次导出；恢复时在解密等待或会话事务等待期间改变类别，后续 settings 写入范围也会改变。代码在多个 `await` 前后分别读取实时 `this.*`，没有固定本次选择。
- 最小修复：动作开始时一次捕获类别并传给本次操作，或忙碌期间禁用类别开关；无需额外状态机。

### A4 [P2] settings 原值读取失败会隐藏已经完成的会话恢复

- 位置：`SettingBackupPage.ets:293–294` 的 KV 读取在 `try` 外；`:371` 会话事务已提交；`:393` 调用失败直接进入 `:408–409`。
- 触发：同时恢复会话与 settings，会话已写成功后 `kv.get(ASSISTANTS_KEY/PROVIDERS_KEY)` 拒绝。错误越过 `applyRestoreSettings` 的精确反馈逻辑，UI 只显示“恢复失败”，丢失“会话已恢复”的实况。
- 最小修复：将预读取失败也转成 settings 阶段的失败反馈，保留已提交的会话数量；不在无法获得原值时启动 settings 写入。
- 来源：旧第二阶段恢复逻辑沿用的错误路径。

已闭环部分：未选会话时不会执行会话事务、活跃检查或 compact marker 清理；会话恢复用 `beginConversationRestore` 与真实生成占用互斥；空导出 URI 返回“取消”，不会落私有文件或报导出成功。settings 写入失败后的既有回滚分支逐类别执行，未选类别不会覆盖。

## B · 调用链：FAIL

### B1 [P1] Provider 文件导入未校验模型枚举，可以破坏整个 providers KV

- 位置：`harmony/entry/src/main/ets/pages/ChatProviderSettingsPage.ets:150–160`、`:181–182`；最终写入 `harmony/chat/src/main/ets/chat/kv_store.ts:72–73`。
- 触发：JSON 有合法 `format/name/baseUrl/type`，但某模型 `type: 'bogus'`。导入把字符串强转为 `ModelType`，工厂不做运行时校验；serializer 查表得到 `undefined`，仍写入整个 providers 列表。下一次 `loadProviders` JSON 解析失败，已有服务商也无法读取。上述纯逻辑复现验证了确切持久化结果。
- 最小修复：在任何内存列表替换或 KV 写入之前校验导出版本、三变体类型、字符串字段与模型枚举；用既有线格式 parser 对即将保存的列表做最后的结构校验。写入成功后再替换页面列表。

### B2 [P2] 余额组件不响应服务商配置更新，缓存也不区分查询配置

- 位置：`harmony/entry/src/main/ets/components/ui/ProviderBalanceText.ets:11–21`；父页 `ChatProviderSettingsPage.ets:85–86`、`:295`/`:307`；`harmony/entry/src/main/ets/platform_impl/BalanceSupport.ets:66–67`。
- 触发：列表首次显示空 Key 的服务商为 `~`，进入详情补 Key 并返回。父页会重新加载，但 ForEach key 仍为同一 provider id，组件保留；请求只在 `aboutToAppear` 执行，`setting` 没有 `@Watch`，不会因新配置发请求。修改已有 Key/endpoint 也会留下旧值；重新创建组件后，按 provider.id 的缓存又会复用旧账户结果两分钟。
- 证据：本地官方生命周期文档 `arkts-page-custom-components-lifecycle` 确认 `aboutToAppear` 是新实例 build 前事件，`aboutToDisappear` 是析构前事件，不能以页面返回代替配置变化订阅。
- 最小修复：请求入口同时处理初次出现与 setting 变化；缓存按查询配置/凭据身份区分。过期响应不得覆盖变更后的配置结果。

已闭环部分：`ChatModelParamsPage.ets:109–110 → :253–265 → saveProviders → provider_settings_serialize.ts:106–107 → context_assembly.ts:160–166 → openai_responses_request.ts:292–300` 完整；`useResponseApi` 在 `openai_chat_api.ts:95–108` 分流到 Responses。search/image_generation 都有最终请求消费者并被已有测试验证。Google/url_context 的未实现映射已在计划登记，本补审不把登记项冒充已完成。

## C · UI：FAIL；视觉验收未做

### C1 [P1] 服务商页新导入卡直接在普通 lambda 构造组件

- 位置：`harmony/entry/src/main/ets/pages/ChatProviderSettingsPage.ets:390–397`。
- 触发：服务商列表加载后渲染“其他”组。`CardGroup({ content: (): void => { CardRow(...) } })` 在普通 lambda 内直接构造 `CardRow`，与 `.agents/skills/harmony-arkts/SKILL.md` 铁律 4b 已记录的 `class constructor cannot called without 'new'` 运行时退出模式相同；编译不拦截此模式。
- 最小修复：提取 `@Builder ImportCard(): void`，`content` lambda 只调用 `this.ImportCard()`；设备打开服务商页验证无 RuntimeError，再检查卡片排列。

源代码规格检查：备份页使用 CardGroup r18/0.5 描边/GUTTER16，类别行复用 CardToggleRow 的 minHeight52、左右14 与 caption12；provider 列表 minHeight52、左右14，分隔线66/14与头像40+间距12后的文本起点一致。没有截图，不能声称错位、对齐、边距、大小已完成视觉 PASS。

结论：4 个 P1、3 个 P2；需主 agent 修复并完成 lint/构建/设备截图验收后再关闭 E0。

## 修复复审 · 当前工作区

复审对象为 `fix_d3_d4` 与 `fix_balance` 的实际当前改动，包括新增 `provider_import.ts`、`settings_backup.ts`、`provider_balance.ts` 与入口接线。没有修改生产代码。以下结论替代上方初审的未关闭状态；原记录保留用于核对触发条件。

复跑：`tsx --test src/test/provider_import.test.ts src/test/settings_backup.test.ts src/test/provider_balance.test.ts src/test/provider_settings.test.ts src/test/openai_responses_api.test.ts src/test/sync_snapshot.test.ts`，**59/59 PASS**。测试覆盖非法导入保留既有 KV、三服务商变体与模型种类、真实 Node AES-GCM 归档还原、所选类别/空列表、凭据与嵌套 overwrite、预读失败零写入、后续写失败回滚、余额请求身份改变与不改变的情况。设备密码学和 UI 生命周期未由这些纯逻辑测试替代。

### A · 逻辑闭环：PASS（代码与纯逻辑测试）

- A1 已关闭：`SettingBackupPage.ets:96 → buildSyncSettingsBlob`；`settings_backup.ts:17–22` 先调用现有 serializers 再 `JSON.parse`，与 `sync_snapshot` 预校验和恢复解析使用相同线格式。默认助手、带模型服务商与嵌套 overwrite 的完整备份→恢复 roundtrip 通过。
- A2 已关闭：恢复语义保留凭据；`SettingBackupPage.ets:433` 明确“打包服务商配置及凭证”，`:384` 明确包含所选凭据并建议密码加密。配置文件导入的空 Key 文案仍准确，未将其套用到备份恢复。对应回归明确验证凭据可还原。
- A3 已关闭：`doExport/doUploadWebDav/doRestore` 在第一个异步步骤前设置 `busy`；所有类别修改入口 `SettingBackupPage.ets:425/:430/:435/:484/:489/:495` 都以 `!this.busy` 门控。密码开关及密码输入也受相同门控。操作内部仍读取成员字段，但已不存在本页可交互入口在 await 期间修改这些范围的路径；无需新增类别状态机。
- A4 已关闭：`settings_backup.ts:36–40` 单独捕获原值预读取错误并返回准确的 settings note，尚未写入时不回滚；写入失败仍恢复已读取原值。页面 `SettingBackupPage.ets:341 → :347–349` 保留 `convNote` 拼接阶段失败，无论 settings 预读取还是写入失败，都不会隐去已完成的会话事务。

### B · 调用链与异步状态：PASS（代码与纯逻辑测试）

- B1 已关闭：`ChatProviderSettingsPage.ets:126 → providerFromImportJson → :128 saveProviders → :129 this.all`。`provider_import.ts:31–57` 在生成 provider 前验证对象、v1、三变体、必填字符串、models 数组和模型枚举，变体可选字段也逐类型校验；工厂只接收已验证枚举。非法 JSON/字段均在 KV 写入前抛错，UI 在保存成功后才替换列表。新增 helpers 在 `index.ts:398/:665/:666` 导出，入口 import 均可解析。
- B2 已关闭：父页 `@State all` (`ChatProviderSettingsPage.ets:55`) 整体赋值后驱动 `settingFor` 消费，余额子组件 `ProviderBalanceText.ets:11` 的 `@Watch('onSettingChange')` 响应新 setting；首次出现同样调用该入口。`:22/:28` 的请求序号拦住旧配置回包，`:32–34` 销毁时隔离回包。`BalanceSupport.ets:68–76` 在请求前捕获 URL/Key/resultPath，用 `providerBalanceRequestIdentity` 比较缓存查询身份；旧请求即使晚写缓存，其 identity 与新配置不符也不会被新查询读取。缓存中无需额外请求取消机制。
- search/image_generation 的既有 UI→持久化→`toChatModel`→Responses 消费链没有被这些修复改动；相应现有测试通过。

### C · UI：源代码问题关闭；视觉验收待截图

- C1 已关闭：`ChatProviderSettingsPage.ets:257–265` 是明确 `@Builder ImportCard`，`:347` 的 BuilderParam lambda 只调用 builder，不再直接构造组件。
- 页面仍复用既有 CardGroup、CardRow、CardToggleRow；本次改动没有增加新的布局层或修改全局尺寸 token。
- **尚未收到主 agent 的设备截图；未声称视觉 PASS、真实页面返回后的余额刷新已设备验证，或无 RuntimeError 已真机验证。** 构建、lint、安装及设备实况由主 agent 完成后补充证据。

复审结论：初审 7 项在代码层面全部关闭，未发现新增可证明缺陷。A/B PASS；C 的原 BuilderParam 代码缺陷已修，最终视觉验收待设备截图。

## C · 设备截图追加审查

独立目测主 agent 提供的实际当前 signed HAP 截图：

- [服务商导入卡·亮色](/tmp/e0-provider-import-light.jpeg)
- [服务商导入卡·暗色](/tmp/e0-provider-import-dark.jpeg)
- [备份与恢复·暗色](/tmp/e0-backup-dark.jpeg)

### C1 已关闭 · 服务商导入卡与选择器入口

亮暗两张图中，“从文件导入”标题、两行说明、左侧上传图标座、右侧箭头均完整可见，卡片圆角、左右边距与既有分组一致；文本不压箭头，未见错位或截断。列表顶部已有服务商行的部分裁切是当前滚动位置，不作为新布局问题。余额 `~` 与图标在对应行的文字列中排列稳定。

主 agent 实机操作证据：当前生产入口成功展示该卡，无 RuntimeError；点击打开系统 `DocumentViewPicker`，空本机文件列表下 Back 取消并稳定返回。这关闭了原 BuilderParam 的实际入口崩溃问题。

### C2 [P2] 新实况缺陷 · 暗色备份密码显隐图标对比度不足

- 位置：`harmony/entry/src/main/ets/pages/SettingBackupPage.ets:406–412`。
- 触发与证据：打开暗色“备份与恢复”，开启密码加密；`e0-backup-dark.jpeg` 中原生 PasswordIcon 为近黑眼睛，而输入框是深灰 `SURFACE2`，实际图标明显低对比。文字和其他行末控件均有主题色，只有这一内置图标保持近黑。
- 最小建议：仅在本页隐藏原生图标 `.showPasswordIcon(false)`，使用已有 `Icon` 的 `eye/eye_off`（`Icon.ets:129–130`）与 `INK2`，保留 44vp 点击区；一个 V1 `@State` 布尔值切换 `InputType.Password/Normal`，显隐动作继续以 `!busy` 门控。无需新全局密码组件。
- 文档核对：本地官方 `faqs-arkui-1014` 明确说明原生 PasswordIcon 的位置/大小/颜色不能直接修改，并给出隐藏内置图标、在输入框旁放自定义图标的方案；不是凭记忆引入新 API。

暗色备份页其余可见区域：类别标题/描述、开关行、恢复文件行的对齐和分隔线稳定；“打包服务商配置及凭证”真实展示，与保留凭据的实现相符。没有亮色备份截图，尚未补亮色该页目测。

分层验收：设备截图与主 agent 的实机操作证明页面显示、系统选择器入口和取消返回；59/59 helper/serde tests 证明文件内容校验、归档和恢复逻辑。**本轮系统选择器为空，没有实际选中文件，因此不声称设备上真实文件导入落盘、导出归档或文件恢复成功。** 余额真实 HTTP/换 Key 返回后的数字刷新也不由当前 `~` 截图证明。

当前 C 状态：导入卡 PASS；暗色备份页除 C2 外可见布局 PASS。C2 需窄修后再由主 agent 提供实际亮暗截图与显隐交互证据。

### C2 窄修复审 · 暗色缺陷已关闭

独立目测新 signed HAP 的 [暗色修复截图](/tmp/e0-backup-eye-dark-fixed.jpeg)：眼睛图标现为清晰的浅灰 `INK2`，与深灰输入框形成可辨识对比；图标垂直居中，输入提示没有挤压或重叠，输入框宽度、圆角、相邻分隔线与类别行未发生错位。原近黑眼睛的具体对比度缺陷已消失。

源码核对：`SettingBackupPage.ets:399–424` 的 `PasswordField` 是本页 `@Builder`，原生 eye 关闭，已有 `Icon eye/eye_off` 使用 `INK2`，明确 44vp 点击区；`:40/:56` 为独立 V1 显隐状态，`:436–438` 与 `:559–561` 分别回写备份密码/WebDAV 密码。点击与父输入行均继续受 busy 门控，未更改备份载荷、凭据恢复或 WebDAV 请求。该复用只位于同一页的两个真实密码输入，没有新增全局组件。

当前结论：**C2 代码与暗色实况 PASS，已关闭该缺陷。** 修复后的亮色截图待主 agent 后补；截图本身不证明输入真实密码后的显隐交互，待其操作证据。此前系统选择器未选 fixture、设备真实文件导入/导出/恢复尚未验收的边界保持不变。

## 最终 C 验收 · PASS

主 agent 在非空 dummy 密码的交互补验中发现，首版 `PasswordField(text, placeholder, visible, callbacks)` 按值接收 `visible`，点击后两个截屏仍为 masked；这意味着此前空字段截图只能关闭图标对比度，不能证明显隐刷新。修复 worker 已按官方 Builder 参数更新语义与编译产物证据，将本页 Builder 改为直接读取 State。本补审不将首版的源代码静审替代该运行时证据。

已独立读取最终实际源码 `SettingBackupPage.ets:399–437`：Builder 只接收固定的 `isWebDav` 常量；输入文本、输入类型、图标与 accessibility 文案分别直接读取 `this.passphrase/this.showPassphrase` 或 `this.davPass/this.showDavPass`；输入和点击直接写对应独立 V1 State。调用端 `:449` 固定 false，`:570` 固定 true，不再把可变显示状态作为按值参数传入；44vp 点击区、INK2 与 busy 门控保留。

独立对照最终六张非空 dummy 密码截图：

- 备份密码亮色：[明文](/tmp/e0-backup-eye-light-visible-final.jpeg)、[遮蔽](/tmp/e0-backup-eye-light-hidden-final.jpeg)。同一字段从 `e0-visible-check` 变为遮蔽点阵，同时图标从 eye 变 eye_off。
- 备份密码暗色：[明文](/tmp/e0-backup-eye-dark-visible-final.jpeg)、[遮蔽](/tmp/e0-backup-eye-dark-hidden-final.jpeg)。`e0-dark-check` 与点阵切换明确，浅灰图标对比度正常。
- WebDAV 密码亮色：[明文](/tmp/e0-dav-eye-light-visible-final.jpeg)、[遮蔽](/tmp/e0-dav-eye-light-hidden-final.jpeg)。`e0-dav-check` 与点阵切换明确，第二字段的状态消费闭环。

六张最终图均未见文本压图标、点击区侵入相邻行、边距变形、图标错位或切换后布局跳变；两字段使用同页窄 Builder，没有新增全局密码组件或备份/网络行为。

结论：**本补审 A/B 维持 PASS；C 的服务商导入卡、备份页亮暗视觉、备份与 WebDAV 密码显隐交互均 PASS。C2 与首版显隐状态冻结问题全部关闭，E0 D3/D4 补审无待修缺陷。** Release 构建与两设备安装由主 agent 报告和保存证据；本 reviewer 没有操作设备或修改生产源码。

验收边界继续准确保留：系统 DocumentViewPicker 已打开且 Back 取消稳定，但本轮没有选中真实 fixture 文件；helper/serde 测试通过不等于设备真实文件导入落盘、导出归档、文件恢复或 WebDAV 在线读写已成功。余额真实 HTTP/更换 Key 后的数字刷新也未由现有 `~` 截图证明。
