# iOS Codex OAuth 功能 — 交接文档

> 仓库:`/Users/arquiel/Downloads/AI/amberagent-ios`(KMP + 原生 SwiftUI)
> 目标:把 Android 已有的「用 ChatGPT 账号登录(Codex OAuth)→ 聊天 + 生图」移植到 iOS。
> 状态:**绝大部分已实现并在真机跑通到「请求能发出」;唯一卡住的是 codex 聊天返回 HTTP 404 `{"detail":"Not Found"}`,正在用刚加的诊断抓真实请求来定位。**

---

## 0. 当前最紧要的未决 BUG(请先读这一节)

**症状**:iOS 用 Codex OAuth、选模型 `gpt-5.5` 发消息 → 报错
```
模型不可用、模型不存在，或当前 Base URL 不支持这个聊天路径。当前 Model ID: gpt-5.5。
原始错误：HTTP 404: {"detail":"Not Found"}
```
`{"detail":"Not Found"}` 是 chatgpt.com/backend-api(FastAPI)的格式,说明请求**到达了 codex 后端**但被 404。

**已验证为「正确」的部分(两个 subagent 交叉核实 + 我亲自读码)**:
- iOS 聊天经 KMP `OpenAIKmpProvider.responsesStreamText`,URL = `${providerSetting.baseUrl}/responses`。
- iOS 侧 `IOSCodexProviderResolver.resolved()` 在 `authMode == CODEX_OAUTH` 时,把 provider 的 `apiKey` 换成 **OAuth access token**、`baseUrl` 设为 `https://chatgpt.com/backend-api/codex`、`useResponseApi=true`。
- `IOSCodexProviderResolver.augmentParamsForCodex()` 给 `params.customHeaders` 注入 `OpenAI-Beta: responses=experimental`、`originator`、`ChatGPT-Account-Id`。
- `ChatGenerationCoordinator.start` 确实调用了上面两个,并把改造后的 provider/params 传给 KMP。
- 请求体(model/stream/store=false/instructions/input/reasoning/tools)与 Android `ResponseAPI.buildRequestBody` 逐字段一致。
- **结论:URL / token / 头 / 请求体在代码层面都对得上 Android。**

**我犯过的错(别重蹈)**:我一度断言「gpt-5.5 是假模型名」。**用户明确否认,codex 是支持 gpt-5.5 的。不要再往「模型名假」这个方向带。**

**仍存的假设(待诊断确认)**:
1. **`authMode` 在请求时可能不是 `CODEX_OAUTH`** → 那样 resolver/augment 全被跳过,请求会用空/错 apiKey、缺头(但 baseUrl 可能仍是 codex backend,因为 login 时 `setOpenAIAuthMode` 同时设了 baseUrl)。这是最可疑的「静默失效」点。
2. 某个 Android 在别处加、而 iOS 没加的请求差异(header 或 body 字段)。
3. 该账号/计划对 `gpt-5.5` 在 codex 后端的可用性,或 model id 需要某种后缀。

**我刚加了诊断(还没来得及抓)**:
- `IOSCodexProviderResolver.writeRequestDiagnostic(...)` 会把**实际发出的 codex 请求形态**写到设备 `Library/Caches/codex-debug.log`:`original.authMode`、`resolved.baseUrl`、最终 `url`、`bearer`(EMPTY / MASK / `JWT(len=..)` / other)、`model`、`headers=[...]`。
- 调用点在 `ChatGenerationCoordinator.start`(`startStreaming` 之前)。
- 诊断版**已装到真机**(见下方设备信息)。

**接手后第一步 = 抓诊断、看真实请求**:
1. 让用户(或你)在 codex provider 下用 gpt-5.5 发一条消息(触发 404)。
2. 拉日志:
   ```bash
   xcrun devicectl device copy from \
     --device 94918570-0680-5B93-8E38-7E6B355D4426 \
     --domain-type appDataContainer --domain-identifier app.amber.ios \
     --source Library/Caches/codex-debug.log \
     --destination /tmp/codex-debug.log
   cat /tmp/codex-debug.log
   ```
3. 看最后一行:
   - 若 `bearer=EMPTY` 或 `MASK`、或 `headers` 里没有 `OpenAI-Beta` → **authMode 不是 CODEX_OAUTH**(resolver/augment 被跳过)→ 去查为什么该 provider 的 authMode 不是 codex(登录是否真完成?`setOpenAIAuthMode` 是否被某路径重置?gpt-5.5 是否挂在另一个非 codex provider 上?)。
   - 若 `bearer=JWT(...)`、`resolved.baseUrl=https://chatgpt.com/backend-api/codex`、`headers` 含 `OpenAI-Beta` → 请求**确实**和 Android 一致 → 404 是后端/账号/model 层面的,需进一步抓**完整请求体 + 响应体**(可在 KMP `responsesStreamText` 临时打日志,或上一个 HTTP 抓包代理),并与官方 Codex CLI / Android 真机抓到的请求逐字节对比。

> 注:`writeRequestDiagnostic` 是临时调试代码,定位后删除。

---

## 1. 设备 / 构建 / 安装(已验证可用)

- **真机**:iPhone Air。
  - xcodebuild 用的硬件 UDID:`00008150-000A594E0AF8401C`
  - devicectl 用的 identifier:`94918570-0680-5B93-8E38-7E6B355D4426`
- **签名**:个人免费 team `89QRFX9548`(Arthur Lee, Personal Team),证书 `Apple Development: soul99soul@gmail.com`。**免费账号描述文件 7 天过期**,过期重装即可。
- **坑**:`iosApp/project.yml` **没记 `DEVELOPMENT_TEAM`**,而 pbxproj 是 XcodeGen 生成且 gitignore。**每次 `xcodegen generate` 都会把签名 team 冲掉**,所以命令行必须显式传 team。(可以考虑把 `DEVELOPMENT_TEAM: 89QRFX9548` 写进 project.yml 一劳永逸——用户还没拍板要不要加。)

**真机构建 + 安装(默认流程,带内置 iSH)**:
> 当前开发态需要默认安装 `iosAppExperimentalGPL`,否则普通 `iosApp` 会覆盖同一 bundle id
> `app.amber.ios`,导致设置页显示「当前 target 未链接 embedded iSH」。
> 其它 session 打包/安装真机时优先用这个脚本:

```bash
cd /Users/arquiel/Downloads/AI/amberagent-ios/iosApp
./scripts/install-device-experimental-gpl.sh
```

脚本会构建 `iosAppExperimentalGPL`,检查日志里的 `** BUILD SUCCEEDED **`,安装到真机,并用
`--terminate-existing` 启动 `app.amber.ios`。安装后 `devicectl device info apps` 应显示
`Name iosAppExperimentalGPL` / `Path .../iosAppExperimentalGPL.app`。

**普通 stable 构建 + 安装(不带内置 iSH,仅在明确需要 stable 时使用)**:
```bash
cd /Users/arquiel/Downloads/AI/amberagent-ios/iosApp
xcodebuild -project AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS,id=00008150-000A594E0AF8401C' \
  -allowProvisioningUpdates DEVELOPMENT_TEAM=89QRFX9548 CODE_SIGN_STYLE=Automatic \
  -derivedDataPath build/DeviceBuild build
xcrun devicectl device install app --device 94918570-0680-5B93-8E38-7E6B355D4426 \
  build/DeviceBuild/Build/Products/Debug-iphoneos/iosApp.app
```
- **`echo "EXIT=$?"` 不可信**(若用 `xcodebuild ...; echo` 包裹,echo 的退出码会盖掉真实结果)。务必 grep 日志里的 `** BUILD SUCCEEDED **` / `BUILD FAILED`。
- **新增 Swift 文件**必须先 `cd iosApp && xcodegen generate`(否则不进 pbxproj,报 "cannot find X in scope")。改已有文件不用。
- **改了 KMP(commonMain)**:xcodebuild 的 build phase 会跑 `./gradlew :shared:linkDebugFramework...` 重链 `Shared.framework`,所以直接 xcodebuild 即可;KMP 编译错误会让 build phase 以 "BUILD FAILED in Xs" 快速失败。
- 模拟器(更快验证编译,booted iPhone 17 id `293252D5-CCF3-47DD-8736-8A8A26A6788C`):`-destination 'platform=iOS Simulator,id=...'`,不需要签名。

**单测(参考)**:`xcodebuild test -scheme iosApp -only-testing:iosAppTests/IOSGenerativeWidgetParserTests ...`(全量 417 passed)。

---

## 2. 架构:iOS 的 codex 请求是怎么走的(关键)

iOS **不直接用 Swift 发 OpenAI 请求**,而是经 KMP 共享 provider:
```
ChatViewModel.generateResponse
  → makeProviderSetting()  // = ChatProviderConfiguration.provider(for: currentModel) → 返回 codex provider(authMode=CODEX_OAUTH)
  → ChatGenerationCoordinator.start(providerSetting, params)
      内部 Task:
        effectiveProvider = IOSCodexProviderResolver.resolved(providerSetting)        // 注入 OAuth token + codex baseUrl + useResponseApi=true
        effectiveParams   = IOSCodexProviderResolver.augmentParamsForCodex(params, …)  // 注入 OpenAI-Beta/originator/ChatGPT-Account-Id 到 customHeaders
        writeRequestDiagnostic(...)  // ← 调试:落盘真实请求形态
        startStreaming(effectiveProvider, effectiveParams)
  → KMP OpenAIKmpProvider.streamTextCancellable → streamText
       if (providerSetting.useResponseApi) → responsesStreamText
          POST ${baseUrl}/responses, Authorization: Bearer ${apiKey}, + params.customHeaders, body=buildResponsesRequestBody
```
- **OAuth 全流程在 Swift 原生**(`IOSCodexOAuthClient`,device-auth),token 存 Keychain(`IOSCredentialSideTable`,key=`codex.<providerId>.tokens`)。
- **请求期**:resolver 把 token 当 `apiKey`(bearer)塞进 provider 副本;augment 把 codex 头塞进 params.customHeaders。两者都**只在 `authMode==CODEX_OAUTH` 时生效**。
- **`providerId` 一律用 `provider.id.description()`**(和 Keychain key 一致)。

**Android 权威参照(对照用)**:
- codex 聊天 URL = `https://chatgpt.com/backend-api/codex/responses`(常量 `OPENAI_CODEX_BACKEND_BASE_URL` @ `ai/.../openai/OpenAICodexOAuth.kt`)。
- Android 聊天路径:`OpenAIProvider.streamText`(authMode==CODEX_OAUTH)→ `ResponseAPI.streamCodexText`(`ai/.../openai/ResponseAPI.kt:259`),`buildRequestBody`(322)。注意:**Android 聊天的 `streamCodexText` 只发 Authorization(没 OpenAI-Beta)也能用**;OpenAI-Beta/originator/account-id 出现在 `OpenAIProvider.kt:560-575` 的**生图** codex 路径。
- Android codex 真实模型兜底(`OpenAIProvider.kt:~776`):`gpt-5.4 / gpt-5.3-codex / gpt-5.3-codex-spark / gpt-5.2 / gpt-5.1 / gpt-5.1-codex / gpt-5.1-codex-max`;生图路由模型 `CODEX_IMAGE_ROUTING_MODEL = "gpt-5.4"`。

---

## 3. 已实现的内容(文件清单)

### 新增(Swift,iosApp/iosApp/)
- **`IOSCodexOAuthClient.swift`** — device-auth OAuth(requestDeviceCode / pollDeviceCode / exchange / refresh / getValidAccessToken)、JWT 解析 account_id/plan、Keychain token store、`fetchCodexModels()`(GET codex /models,失败落 `fallbackModels`)、`dataWithRetry`(瞬时网络错误重试,治 "network connection was lost")、常量(clientId/issuer/codexBackendBaseUrl/originator="amberagent_ios"/imageModelId="codex-oauth-image"/imageToolModel="gpt-image-2")。
- **`IOSCodexProviderResolver.swift`** — `resolved()`、`augmentParamsForCodex()`、`writeRequestDiagnostic()`(调试)、`isCodexProvider/isSignedIn/providerKey`。
- **`CodexLoginView.swift`** — 登录 sheet + `CodexLoginModel`(展示登录码、开浏览器到 auth.openai.com/codex/device、轮询、登录态、登出、刷新模型)。

### 改动(KMP)
- **`ai-provider-openai/src/commonMain/.../OpenAIKmpProvider.kt`** — 新增 Responses API(`responsesStreamText`/`responsesGenerateText`/`buildResponsesRequestBody`/`buildResponsesMessages`/`parseResponseDelta`/`parseResponseOutput` 等),按 `useResponseApi` 路由(由前一个 agent 移植自 android `ResponseAPI`;**省略了 image_generation 输出路径**)。另:`listModels` 改为 `runCatching{…}.getOrDefault(emptyList())` **吞错不抛**(修了 iOS 上 listModels 抛异常→Kotlin/Native SIGABRT 闪退的隐患,因为 `Provider.listModels` 接口没标 `@Throws`,不能在 override 单独加)。
- **`ai-provider-claude/src/commonMain/.../ClaudeKmpProvider.kt`** — `listModels` 同样改为吞错返回空。
- **`shared/src/commonMain/.../IosSettingsMutations.kt`** — `setOpenAIAuthMode`(切 CODEX_OAUTH 时 pin codex baseUrl + useResponseApi)、`upsertProviderImageModel`(写 IMAGE 类型模型)、`upsertProviderChatModel` 新增 `modelType` 参数。

### 改动(iOS Swift)
- **`IOSSharedSettingsStore.swift`** — `setOpenAIAuthMode`/`upsertProviderImageModel` 的 Swift wrapper;`upsertProviderChatModel` 加 `modelType`;**`restoreSnapshot` 的 provider 循环加了 `!= mask` 守卫 + 去掉重复循环**(修了「真 key 被脱敏占位符 `__MASKED_BY_AMBERAGENT_IOS__` 覆盖」的隐患;已坏的 key 需用户重填)。
- **`ChatProviderConfiguration.swift`** — 新增 `.codexNotSignedIn` issue;`supportsChatStreaming`/`issue(for:)` 对 codex(无 apiKey、按是否登录判定)放行。
- **`ChatGenerationCoordinator.swift`** — `start` 里做 codex resolve + augment + 诊断;抽出 `presentStreamError` 复用。
- **`ChatView.swift`** — `.codexNotSignedIn` 分支补全;**修了输入框死锁**(去掉 TextField 的 `configurationIssue` 禁用条件;`showsComposerMeta` 在有配置问题时也显示模型 chip)。
- **`MessageBubbleView.swift`** — `.codexNotSignedIn` 分支补全;`ChatAssistantMarkdownView` 的 `isStreaming`。
- **`ProviderDetailView.swift`** — codex 登录入口段 + `CodexLoginView` sheet;**模型编辑器加「类型」选择**(`ProviderModelDraft.type`、`modelTypeButton` 自定义分段控件、聊天/生图/嵌入);登录后 `upsertProviderImageModel("codex-oauth-image")`。
- **`ModelDefaultsView.swift`** — 新增 `imageModelOptions`(按 `ModelType.image` 过滤);`auxRow` 加 `options` 参数,**「生图模型」选择器改用 imageModelOptions**(之前错用 chatModelOptions,导致选不到生图模型)。
- **`ChatViewModel.swift`** — `imageGenerationConfigured` 对 codex(无 apiKey,按登录态)放行。
- **`ChatToolRuntime.swift`** — `resolvedCodexImageConfig()`、`dispatchImageToolCall` 的 codex 分支、放开 offer-tool 闸。
- **`IOSImageGenerationRepository.swift`** — `generateViaCodex()`(OAuth bearer 打 codex `/responses` 挂 image_generation 工具,解析 `image_generation_call.result` base64 存图)、补 `OpenAI-Beta` 头。

> **与 codex 无关、但同在工作区的未跟踪文件**:`IOSGenerativeWidget*.swift`、`generative-libs/`、`IOSHealthService.swift`(HealthKit,搁置)。是另一个「聊天内联生成式 UI 卡片」功能,**别动**。

---

## 4. 已知「环境问题」(非代码 bug,会干扰判断)

- **地区封锁**:`auth.openai.com` / codex 后端对部分地区按出口 IP 拒绝,报 `unsupported_country_region_territory`。**必须挂梯子到支持区**。
- **梯子 TLS 干扰**:见过 `NSURLErrorDomain -1200 / -9816 "A TLS error caused the secure connection to fail." interface: utun26`,是梯子破坏了 TLS(换节点/协议)。
- **"network connection was lost" (-1005)**:陈旧连接复用导致,已加 `dataWithRetry` 重试缓解。
- 排查 codex 时务必先确认网络在支持区且 TLS 干净,否则会把环境问题误判成代码 bug。

---

## 5. 用户侧操作要点(给用户的复测清单)
1. 服务商 → 官方 OpenAI provider → 「ChatGPT 登录 (Codex)」→ 完成 device-auth 登录(需支持区网络)。确认登录后显示「已登录」。
2. **刷新模型列表**(登录 sheet 里的按钮)拿真实模型(设备上已存的旧模型不会自动替换)。
3. 聊天页点输入框 → 上方模型 chip → 选模型 → 发消息。
4. 生图:默认模型 → 辅助任务 → 生图模型 → 选「Codex 生图」→ 让它画图。
5. 普通 OpenAI provider 若报 `invalid_api_key`(key 显示 `__MASKED…__`),**重填一次真 API key**(旧 key 可能被脱敏占位符覆盖坏了)。

---

## 6. 还没做完 / 可优化
- **codex 404(头号未决,见第 0 节)**。
- 「点好几次才弹出模型选择 sheet」:聚焦/动画命中问题,未修。
- codex 生图(`generateViaCodex`)未真机验证成功过(被聊天 404 挡着)。
- `writeRequestDiagnostic` 是临时调试,定位后删。
- 是否把 `DEVELOPMENT_TEAM` 写进 project.yml(防 xcodegen 冲签名),待用户拍板。

---

## 7. 行为约束(用户反复强调,务必遵守)
- **全程用中文交流**(技术名词可英文)。
- **先澄清再动手**,暴露矛盾而非绕过;**别臆想、别在没看真实 android/ios 代码时下结论**(我就栽在「gpt-5.5 假名」上)。
- 不擅自删无关代码;只改与任务直接相关的。
- 报错如实说,别粉饰。
