# Phase 4：UI12 / P2 提供商输入框加载后仍显示空初值

## 真问题与官方规则

第 41 组真实问题 UI12：root 已安装 AppStorage handoff 修复版本，详情 header 为正确的 OpenAI Codex OAuth，但名称输入框仍实际显示灰色“Provider 名称”placeholder。`device/codex-appstorage.jpeg` 可目看，`codex-appstorage.json` 和 `new-provider-fields.json` 对应 TextInput 均 text='' / hint='Provider 名称'。不是仅 AX 不暴露值，也不是空名 by design；该设备状态为实际 RED。

applyProvider 将正确 provider.name 写入页面 @State name；六处字段调用却经六参数 `@Builder field(label, placeholder, value, onChange, obscure, mono)` 按值传入。初帧空字符串被 builder 闭包捕获，异步加载后的 @State 变化不会刷新内部 FlatTextField。生成 ArkTS TS 的 child paramsGenerator / update 均读取捕获的局部 value。

本地官方文档交叉核对：

- `开发指南/ArkUI_方舟UI框架/UI开发_ArkTS声明式开发范式/学习UI范式基本语法/组件扩展/Builder装饰器_自定义构建函数/arkts-builder`：默认按值传递，状态变化不会刷新 builder；按引用仅在一个参数直接传入对象字面量时生效。落盘 `/tmp/amber-provider-field-builder-doc.log:98-108,194-196`。
- `FAQ/UI框架/组件使用/如何一键清空TextInput_TextArea组件内容/faqs-arkui-80`：TextInput.text 绑定 @State 可程序更新，例示按钮将状态设空后清输入。落盘 `/tmp/amber-provider-field-textinput-doc.log`。

因此问题在 Detail builder 参数，FlatTextField @Prop → TextInput.text 的现有绑定无需修改。其他字段共享 wrapper，但未将它们记作设备逐项验收。

## 最小实现

仅 ChatProviderDetailPage 增加 ProviderFieldOptions 接口，将 field 改为一个 typed 参数，六处调用均直接传对象字面量。覆盖名称、API key、OpenAI base/path、Google base、Claude base；原 labels/placeholders、obscure/mono、分支条件及每个 page draft onChange 回调保持相同。

没有修改共享 FlatTextField，未增加 Watch/controller、整页重挂载或 API20 makeBinding。既有钉固定 base 的只读逻辑保持；root 追加授权同步纠正两条相关注释，改为说明固定官方端点只读意图，去掉 TextInput 初值不能程序刷新的错误说法；没有行为变化。

## 验证与边界

新增 `harmony/entry/src/test/provider_field_binding.test.cjs` 是官方 Builder 源码契约检查，旧签名 6 params != 1 的 RED 日志 `/tmp/amber-phase4-provider-fields-red.log`。改后确认单typed参数、六个直接对象字面量；提取实际对象中的 onChange，验证正常输入继续回写对应 page draft，obscure/mono 保留。

此测试不模拟 ArkUI、不宣称 Node 复现真实 UI 刷新；设备截图是原始 RED，构建后设备字段显示才是最终运行时 GREEN。

- 新 source-contract 1/1 GREEN；连 provider_entry 7 + UI 27 共 35/35 GREEN，`/tmp/amber-phase4-provider-fields-green.log`。
- scoped Detail ArkTS lint 0 defects，exit 0，日志 `/tmp/amber-phase4-provider-fields-lint.log`；owned diff check PASS。
- fix_backup 独立复审已完成，源码、正式 35/35、生成 proxy 与最终设备均 PASS，见 `phase4-provider-fields-review.md`。未发真实登录或保存配置。

## 构建与设备回喂

root 新构建的 release ArkTS TS 已将六个调用生成 `makeBuilderParameterProxy('field', ...)`，value getter读取 __name / __apiKey / __baseUrl / __chatPath 的状态引用，而非旧局部初帧字符串。这是实际编译器产物，源码 contract 未冒充此证据。

已实际读取 root 设备产物并目看浅色初值与输入保留截图：`device/final-provider-name-light.json` 和 `final-provider-name-dark.json` 的名称 TextInput.text 均为 OpenAI Codex OAuth；`final-provider-name-typing.json` 与 `final-provider-name-retained.json` 为 OpenAI Codex OAuthReviewDraft-1002。`final-provider-name-light.jpeg` 确认非 placeholder 正确初值，`final-provider-name-retained.jpeg` 确认键盘退出后保留新增输入。该字段运行时 GREEN；没有真实保存或 OAuth 登录。

设备切 API key 后旧 Codex 错误仍残留，另按 UI09 既有归属问题补初始查询守卫与模式变化清提示，不将它计为新的 UI12 或另增 issue。证据与补修记录在 phase3-ui.md UI09 附记；当前正式 UI33 + entry7 + fieldcontract1 共 41/41 GREEN，Detail lint 0 defects。fix_backup 追加独立 formal41与8项deferred反例均 PASS，并亲看最新最终 HAP 重录 API key 模式图 / JSON：旧错误消失、名称草稿保留。UI12 与 UI09 追加小项均独立闭环；root 整合最终全量、构建签名和设备结论。

最终 root 交付回执：正式包名称初值、输入后模型/config切换保留、API mode可编辑字段均通过；独立 fields/entry及integration复审通过。最新完整4001/4001、906 hash无漂移与HAP签名/install/start见verification.json/completion.md；API模式遗留OAuth提示按同根因UI09追加精准修复，不重复计组。
