# Phase 4 UI10 — 独立 Provider 入口复审

结论：**UI10 / UI11 当前窄修独立复审通过；AppStorage 版本及最终无日志签名包真实设备 Codex 新增交接已闭环。** 普通 API key 预置覆盖双模块 host 动态测试，设备因预置已存在并被过滤而未直接点击。审查者未参与 picker / settings / draft 修补，本轮只读产品源码、运行 actual-source probes、写报告。

## 规格二次裁定

专门“OpenAI Codex OAuth”新增按钮沿用统一“鸿蒙暂不支持 OAuth”toast 是陈旧入口缺陷：

- `docs/harmony-parity-gap-plan.md:3,81` 的 2026-09-27 初始清单将相同 toast 列为“OAuth 登录全缺”，不是永久不做裁定。
- `docs/plans/2026-09-27-harmony-parity-execution.md:56` C3-2 明确 Codex 设备码→复制/打开→轮询→token→模型目录的端到端目标。
- `docs/plans/2026-09-28-harmony-full-parity-plan.md:8` 声明 C0–C6 已闭环；`:43` 的单独延期项是 Antigravity OAuth。
- `docs/plans/2026-09-28-harmony-remaining-execution.md:14-16` 要求真实入口闭环，占位入口不算实现。
- Git `db9baeead` C3 Codex/Grok 实施只更新详情页，未更新模板/设置旧 gate；设备根 agent 直接发现旧 toast，与源码一致。

这不撤销 UI01 / UI09：既有 OpenAI 详情可选择 Codex，导入配置也可达；UI10 是专门新增按钮断链。

## 实现审查

UI10 仅两产品文件：`ProviderTemplatePickerSheet.ets`、`ChatProviderSettingsPage.ets`。设备回喂 UI11 后窄修增加既有 `ChatProviderDetailPage.ets` 的暂存访问与消费。

- typed OAuthTemplateKind（codex / gemini）由实际行数据传到 picker click，再传 settings callback。
- settings 只为 Codex 用现有 factory + newId 建立独立 draft：OpenAI type、openai brand、codex_oauth、固定 Codex base URL、Responses=true；key/models 使用既有空默认值。
- draft 进入现有 `setPendingProvider` → `ChatProviderDetailPage` `isNew='1'` 路由，未修改既有 Providers、不隐式保存、不自动请求设备码。
- Gemini 原 gate 保留，提示明确仅指 Gemini OAuth。普通 API key 预置 callback 不变。
- 未扩大其他 OAuth 入口或引入新认证实现。

## 独立动态证据

正式回归测试：

```sh
node --test harmony/entry/src/test/provider_template_entry.test.cjs harmony/entry/src/test/ui_send_provider_lifecycle.test.cjs
```

**31/31 通过**，包括实际 picker row-click、settings callbacks、factory、Codex / Gemini 分流、重复添加 draft 独立、既有列表保留及普通预置行为。

另自行编写独立 `/tmp/amber-phase4-provider-entry-review.mts`，使用真实 domain factory / `newId` / fixed-base / `openAIAuthHeaders`，执行实际 row click → settings callback → draft，并执行真实详情 `aboutToAppear` / `applyProvider` 的 pending-draft 消费：

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json /tmp/amber-phase4-provider-entry-review.mts
```

额外断言全部通过：

- ID 为真实 UUID v4，两次新增各不相同，均不同于已存在 OpenAI ID。
- settings.all 与既有 provider key 字节化快照完全不变；save/network 调用次数均为 0。
- 真实行 kind 穿过两个 callback，Codex 路由正确，Gemini 不创建新 draft / 不导航。
- actual Detail 优先消费新 pending draft；加载已有 Providers 后仍保持新 UUID、codex_oauth、Responses 与固定 base URL；pending 随消费清空。
- 对此配置用实际 `openAIAuthHeaders` 构造后续认证，得到 Bearer token，不会误用 plan api-key header。

`git diff --check` 通过。本轮验证是受控平台端口上的实际源码/行为验证；真实 OAuth 云端未调用，最后 lint/build/AGC signing/install 和设备 UI 出口由 root 统一验证。

## 设备回喂 UI11/P2：既有新 Provider draft 跨页传递丢失

root 在最新 505 项 CJS / CompileArkTS / AGC 签名安装通过后，真实点击 Codex 新增进入详情，但标题 `--`、名称为空，等待一分钟仍未加载。证据：`device/final-codex-new-light.jpeg`、`device/codex-new-settled.json`。这证明实际新增入口仍断链。

前述独立 probe 将 draft 作为 lexical `PENDING_PROVIDER` 注入 extracted Detail 方法，验证了内容与消费分支，但**没有覆盖 ArkTS 路由模块的实际暂存交接**；该测试不能作为设备成功的替代证据。

独立诊断已确认 release 编译 Settings 从同一 `@bundle:app.amber.deepread/entry/ets/pages/ChatProviderDetailPage` 导入 setter，而 Detail 末尾仅注册 `registerNamedRoute` 工厂，没有 `loadDocument` 即时创建副作用，因此不能凭源码认定“import 提前触发生命周期清空”。

root 随后实获四节点日志：`set pending=true/idNonempty=true` → 3ms 后 `appear isNewParam=1/pending=false/idNonempty=false` → `resolved target=false/pending=false` → 清空。中间没有 setter 清空。这排除了路由参数丢失和先行 clear，也证明 setter 写入与详情直接读取模块可变绑定的交接不一致。底层编译器/加载器如何造成它尚未查明，不将推测当因果结论。

此问题与 UI10 旧 OAuth gate 是不同根因：`git show HEAD:harmony/entry/src/main/ets/pages/ChatProviderSettingsPage.ets` 原第 444–449 行 API key 预置 callback 已使用 `setPendingProvider(p)` → `isNew='1'` → 同 Detail；HEAD Detail 第 45–49、131–144 行已有同一模块变量写/读/apply/clear 链。新 Codex callback 只是复用既有交接，没有引入这份变量或消费逻辑。因此单独归为 UI11/P2，不重复计入旧 gate。当前实测复现的是 Codex 新增，普通预置入口的同路径影响由源码确认；普通预置的修后设备 gate仍需独立执行。

产品源码保持只读；实施者将做单槽 draft 最小可靠交接，复审必须覆盖 Codex 和普通预置两入口及消费清空；修补后仍需要 root 重建安装并验证真实入口。

## UI11 accessor 修补独立复审

实施者采用私有 `pendingProvider` 单槽，保留 setter、新增 `takePendingProvider()` 取即清；Detail 仅在 `isNew` 时于 async providers load 前消费，后续使用局部 snapshot。删除直接读导出可变绑定及 async 完成后的全局 clear；未引入持久草稿、fallback、重试或状态机。

独立复跑正式入口 6 + UI 27，**33/33 通过**。重写前述独立 probe 使 Settings setter 和 Detail take 使用同一份真实源码访问函数，不再将 `PENDING_PROVIDER` 值注入消费方。actual row/callback + 真实 domain factory/newId + actual Detail methods 断言：

- Codex 及原 API key 预置均进入正确新 draft，名称、authMode、key/baseURL 与 Responses 配置一致。
- take 仅消费一次；await 期间后来写入的新 draft 不被旧加载完成清掉。
- 既有 providerId 编辑路径选择原 provider，不消费单槽新 draft。
- 既有列表不修改，隐式 save/network 为 0，Codex 后续认证仍为 Bearer。

上述 Node 动态证据通过，**不能据此证明 ArkTS 编译运行时访问函数已解决设备问题**。最终通过条件仍是重建签名安装后，Codex 与普通 API key 预置两个实际新增出口显示非空正确配置，且不自动登录或污染既有 Providers。

root 重建安装后继续实测，函数版仍 `set pending=true` → 2ms 后 `take pending=false`。因此该局部访问函数版本不足以解决真实交接，当前不通过；共享同一 closure 的 Node probe 也未覆盖此状态隔离。下一轮实施保持 set/take 接口、仅将单槽存放改为进程级 AppStorage，独立测试必须让 Settings 与 Detail 的 slot 源码分别实例化，仅共享 host storage；依然需要设备验证两个入口。

## UI11 AppStorage 修补独立复审

当前实现仅将私有模块变量换成 typed key `chatPendingProviderDraft`：setter `AppStorage.setOrCreate<ProviderSetting>`，take `AppStorage.get<ProviderSetting>` 后 delete，null setter delete；Detail 保持 await 前局部 snapshot 与一次消费。get/setOrCreate/delete 官方 API 均在 API 12 基线内，仓库已有 AppStorage 先例。接口与单槽范围不变，没有增加状态机或备用渠道。

正式入口 7 + UI 27 独立复跑 **34/34 通过**，diff check 通过。额外独立 `/tmp/amber-phase4-provider-host-review.mts` 从真实 slot 源码 evaluate 两份**独立模块上下文**，只共享 AppStorage host；真实 Settings Codex 方法和普通模板 callback 经 sender 写入，再由 consumer Detail actual methods 读取并 apply。两条新增路径、既有 providerId 编辑、不修改既有列表、Gemini gate、一次消费及 await 期间下一份 draft 保留均通过。

该测试明确覆盖之前遗漏的 host 共享契约。

## 当前设备与最终源码结果

root AppStorage 版本重建签名安装后，真实日志 `set pending=true` → `take pending=true/afterClear=false` → `appear isNewParam=1/pending=true` → `resolved target=true`。独立实际读取 `/tmp/amber-provider-draft-evidence.log`、`device/codex-appstorage.json` 并目看 `device/codex-appstorage.jpeg`，确认详情 header 为 `openai` / `OpenAI Codex OAuth`，Responses 打开、Codex 登录选中、固定 base URL 正确、出现“登录 ChatGPT”控件；此前空详情消失。没有自动启动云端登录。

普通 API key 预置在设备上均已存在，由 existingIds 过滤，不能为验证删除用户 Providers；**未宣称该按钮真机点击通过**。其跨上下文实际 callback → host → Detail 及 native form 消费由独立动态测试覆盖；root 另验证新 draft 切换 API key 且不保存，保护既有数据。

临时 hilog import / ProviderDraft boolean log 已移除；冻结最终源码独立再跑正式 **34/34**、独立双模块两入口 probe、diff check 均通过。最终全量测试/build/sign/install 与无日志设备复测由 root 汇总，真实 OAuth 云端仍未调用。本报告不将构建成功、draft 出口成功与真实账户登录混为同一验证。

最终无日志同签名 HAP 安装后，独立目看 `device/final-provider-name-light.jpeg` 与对应 JSON，Codex draft header/auth/Responses/固定端点均保持正确；`final-provider-apikey-mode.jpeg/json` 确認新 draft 可切 API key form、Base URL 为可编辑且显示既有 draft 值，未保存或云端登录。名称初值与回写/切 tab 保留的 UI12 独立证据另见 `phase4-provider-fields-review.md`。
