# Phase 4：Codex OAuth 新增入口 UI10

## 真实问题与二次裁定

UI10 / P2：提供商模板弹层的 Codex / Gemini OAuth 两行都调用无参 `onOauthPick()`，Settings 无法区分行并统一提示“鸿蒙版暂不支持 OAuth 登录”，Codex 专门新增入口不能抵达已经实现的设备码登录详情。

此项由 root 设备交互发现，证据为 `new-provider-config-light.jpeg`；fix_backup 独立核对调用链与规格，裁定为遗留入口缺陷，而非当前 by design：

- `docs/harmony-parity-gap-plan.md:81` 将旧统一 toast 记录为待补缺口。
- `docs/plans/2026-09-27-harmony-parity-execution.md:56` C3-2 明确 Codex 设备码、轮询、token、模型目录端到端目标。
- `docs/plans/2026-09-28-harmony-full-parity-plan.md:8` 声明 C0–C6 闭环；`:43` 延后的是 Antigravity。
- `docs/plans/2026-09-28-harmony-remaining-execution.md:14-16` 要求真实入口闭环；占位入口不算实现。
- `db9baeead` 实现 Codex/Grok 详情能力时没有同步这两个模板入口文件，旧 gate 留存。

已存在的 OpenAI 详情可切到 Codex，导入配置也可达，故前述 UI01/UI09 调用链仍成立；本项只修独立新增入口。

## 窄修

仅修改 `ProviderTemplatePickerSheet.ets` 与 `ChatProviderSettingsPage.ets`，其余 Phase 3 页面保持冻结。

模板行携带 `OAuthTemplateKind`（codex / gemini）给现有 callback。Codex 使用既有 `makeProviderSettingOpenAIVariant`、`newId`、`openAIAuthModeFixedBaseUrl` 建立独立 draft：OpenAI Codex OAuth 名称、openai 品牌、codex_oauth、Codex 固定 backend、Responses，key/models 沿用工厂空默认值。pending provider 和 `isNew=1` 进入原 Detail；不复用 API key 预置 ID、不改持久提供商列表、不发起授权网络请求。

Gemini 沿用 gate，toast 只说明 Gemini OAuth 暂未支持。API key 预置 callback 保持原行为。没有新增认证流程、自动登录、额外队列或兜底机制。

## 验证

新增 `harmony/entry/src/test/provider_template_entry.test.cjs` 提取真实 ArkTS picker row-click、Settings callback / handler，调用真实 provider 工厂与固定地址 helper，仅 mock UUID 和页面导航边界。

- 修改前：4 项中 3 RED / 1 PASS，真实失败分别是 kind 为 undefined、Codex 不产生 draft、Gemini toast 未限定认证种类。日志 `/tmp/amber-phase4-provider-entry-red.log`。
- 修改后：4/4 GREEN，验证两行区分、Codex 独立 ID / 正确认证协议 / 空 key 与模型 / 不改已有 provider、Gemini gate、原预置导航。
- 连同冻结 Phase 3 UI 测试：31/31 GREEN，日志 `/tmp/amber-phase4-provider-entry-green.log`。
- owned `git diff --check` PASS。

fix_backup 独立复审 PASS，无待修真实问题；其自行运行正式 31/31，并提取实际 row-click → callback → 工厂 / UUID → Detail pending 消费链另做独立 probe，确认 Providers 不变、save=0、network=0、Gemini gate 保留，真实认证 header Bearer 正确。证据见 `phase4-provider-entry-review.md`。

实现源码保持冻结；scoped ArkTS lint、完整构建、签名安装和设备截图由 root 统一执行。当前实际方法测试不宣称真实 OAuth 云端登录验收。

## 设备回喂 UI11：mutable binding 的 draft 交接仍失败

首次入口修复后的 HAP 确已构建、签名并覆盖安装；root 实际点击 Codex 后抵达 Detail，却始终显示空 slug/name 与 api_key。`device/final-codex-new-light.jpeg` 与 `device/codex-new-settled.json` 证实实际 draft 未消费，原 Node probe 不能证明 ArkTS module handoff。

仅加不含凭据的临时 ProviderDraft 日志后，设备明确记录：17:17:35.257 `set pending=true idNonempty=true`；.260 `appear isNewParam=1 pending=false idNonempty=false`；.270 `resolved target=false pending=false`；随后才发生 false setter，没有中途清理。路由参数正常，setter 与组件的 mutable export binding 读取不一致；没有扩大归因到底层 loader/compiler。

据此做最小访问函数修复：slot 改为私有 pendingProvider，保留 setPendingProvider，新增 takePendingProvider（取即清）；Detail 在 isNew 下经该函数立即捕获 draft，再交给原异步 load 选择目标。组件不直接读可变导出，异步完成也不再清掉后来写入的 draft。没有 AppStorage cache、TTL、queue 或 fallback。

两项 actual-method 回归覆盖一次消费、await 期间 slot 新值不被旧页面消费；新增 accessor 契约在旧版缺失时 RED，修后 GREEN。entry 6 + UI 27 共 33/33，通过日志 `/tmp/amber-phase4-provider-handoff-green.log`。这两项不冒充底层 runtime 失效的 Node 复现；真正原始 RED 是设备截图与逐阶段日志。

accessor 设备验证版本仍失败：17:20:54.202 setter pending=true；.204 take pending=false / afterClear=false；.204 appear isNewParam=1 / pending=false；.212 resolved=false。由此确认私有 module slot 加函数仍不足以跨实际路由交接，不保留它作为备用路径。

最终实现使用进程 AppStorage 单槽，明确 typed string key `chatPendingProviderDraft`；set/take 接口及 Detail 消费逻辑保持一致，仅存储改为 setOrCreate / get / delete。未使用 PersistentStorage、订阅装饰器、fallback、缓存策略或 TTL。通过本地官方 `ts-state-management` 查询，这三个 API 均 API10+，AppStorage 与应用进程绑定，符合基线12。

新增实际 slot 源码的两个独立 module contexts，只共享 AppStorage host。原私有变量版消费结果为 null，RED；AppStorage 版取出原 draft 且全局清空，GREEN。entry 7 + UI 27 共 34/34，日志 `/tmp/amber-phase4-provider-process-slot-green.log`。这项模拟已观测的模块状态边界，仍不替代设备 gate。

AppStorage 设备验证 GREEN：17:23:36.504 setter pending=true / idNonempty=true；.507 take pending=true / afterClear=false；appear isNewParam=1 / pending=true；.518 resolved target=true。详情真实显示 OpenAI Codex OAuth、openai、Responses、API Key / Codex 模式和登录 ChatGPT 按钮，bounds 无裁剪。日志 `/tmp/amber-provider-draft-evidence.log`，截图与 UI tree `device/codex-appstorage.jpeg` / `device/codex-appstorage.json`。

普通 API key 预置行因设备已有全部预置被过滤，未为验证删除既有提供商。最终正式双 module contexts 回归同时覆盖 codex_oauth 与 api_key draft；独立 actual callback probe 覆盖预置到 Detail。它们记为单元 / 探针，不宣称设备点击了预置。root 后续以新 Codex 草稿切 API key、无保存验证原生详情切换。

所有 ProviderDraft 临时日志及 hilog import 已移除；日志移除后正式 entry 7 + UI 27 共 34/34 再次 GREEN，owned diff check PASS。fix_backup 已对无日志冻结源码独立再跑 34/34、两份独立模块上下文的两入口 probe 和 diff check，通过；并实际读取设备日志 / UI tree、目看 Codex 详情截图。证据见 `phase4-provider-entry-review.md`。

源码冻结；root 统一执行无日志最终 full CJS、三文件 lint、构建、签名安装及设备检查。初次入口 PASS 没有外推到追加变化，最终 handoff 已单独闭环。
