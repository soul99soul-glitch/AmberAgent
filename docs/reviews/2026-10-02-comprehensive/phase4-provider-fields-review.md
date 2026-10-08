# Phase 4 UI12/P2 — Provider 字段刷新独立复审

结论：**UI12/P2 独立复审通过，源码、官方语义、35 项 focused 回归、最新 CompileArkTS 及最终签名包设备字段显示闭环。** 审查者未参与修补，没有修改产品源码。

## 真实问题与二次裁定

UI11 AppStorage 版设备 `device/codex-appstorage.jpeg` 已显示 `OpenAI Codex OAuth` header、正确认证模式及固定地址，但名称 TextInput 仍显示 `Provider 名称` placeholder。这与 draft 丢失不同：provider 已被 apply，名称状态也按源码赋值，却没有传到初帧已建立的输入控件。

旧 Detail `@Builder field(label, placeholder, value, onChange, obscure, mono)` 有六个按值参数，名称/API key 在初帧空状态下建立。官方本地文档 `arkts-builder`（已落盘 `/tmp/amber-provider-field-builder-doc.log` 第 108、194–196 行）说明：多个参数不支持 builder 内 UI 随状态刷新；单个直接传入对象字面量的引用参数支持刷新。TextInput 官方 FAQ80（`/tmp/amber-provider-field-textinput-doc.log`）明确状态绑定 text 后可程序化更新。这证明问题位于 builder 参数传递，不能笼统归因 TextInput 不支持程序化刷新。

影响是详情异步加载或 OAuth token 等程序化表单更新后，输入框继续显示旧/空值。本次实证为名称空占位，未宣称已发生持久化数据损坏；UI12 与 UI11、UI10 分开计数。

## 窄修与调用审查

仅修改 Detail 的 field builder 及原六处调用：一个 `ProviderFieldOptions` 显式 interface，包含 label/placeholder/value/onChange/obscure/mono；builder 读取 options 字段，不改参数属性；六调用均直接传对象字面量。

| 调用 | value 来源与回写 | obscure | mono |
| --- | --- | --- | --- |
| 名称 | this.name | false | false |
| API Key | this.apiKey | true | true |
| OpenAI Base URL | this.baseUrl | false | true |
| API Path | this.chatPath | false | true |
| Google Base URL | this.baseUrl | false | true |
| Claude Base URL | this.baseUrl | false | true |

各 onChange 均向正确父页字段赋值。Claude API key 的 placeholder 分支、固定 OpenAI endpoint 只读行、Google API key/Vertex 分支及六调用可达条件均保留。未修改 FlatTextField、全局输入组件或引入高版本 UIUtils.makeBinding（API20）、V2 装饰器；方案使用 API12 既有 builder 引用语义与 V1 状态。

## 验证与边界

独立复跑 `provider_field_binding.test.cjs` + `provider_template_entry.test.cjs` + `ui_send_provider_lifecycle.test.cjs`：**35/35 通过**，diff check 通过。新增测试以 TypeScript AST 检查单个 typed object literal 传入条件，并动态执行实际六个 callbacks，验证正确 draft 字段赋值以及 obscure/mono 保留。它验证官方刷新前置条件和回写行为，**没有模拟 ArkUI rendering，不宣称输入框设备刷新已通过**。

root 最新正式 build 通过后，独立读取 release 编译产物：六处均生成 `makeBuilderParameterProxy("field", ...)`，value getter 读取父页 `__name` / `__apiKey` / `__baseUrl` / `__chatPath` 观察属性或对应字段。field 初次创建 FlatTextField、paramsGenerator 和后续 `updateStateVarsOfChildByElmtId` 均读 `options.value`，已从初帧按值快照变为动态取值和子组件更新路径。

编译与实际传参路径得到确认；最终设备验收独立记录如下，不用 Node 或生成源码成功替代渲染证据。

## 最终设备独立目审

root 最终同签名 HAP 安装、force-stop/start 后，审查者亲看三张截图及对应 JSON：

- `device/final-provider-name-light.jpeg/json`：名称 TextInput 为 `OpenAI Codex OAuth`，不再显示初帧 placeholder；Codex auth/Responses/header 正确。
- `device/final-provider-name-retained.jpeg/json`：输入 `ReviewDraft-1002` 后切模型再回配置，TextInput 与 header 保持 `OpenAI Codex OAuthReviewDraft-1002`，证明实际 callback 回写与子组件重建读值闭环。
- `device/final-provider-apikey-mode.jpeg/json`：切 API key 后名称草稿保留、Base URL 变为可编辑 TextInput 并显示现有 draft Codex 地址、Path 正确；不把保留地址的既有认证模式语义误判字段刷新失败。

读到的 TextInput `bounds` 均等于 `origBounds`，该快照无裁切。此证据验证本次名称初始化、回写/切 tab 保留及 base URL 分支刷新；没有实际 OAuth 云端登录、没有保存测试草稿，也不宣称所有 provider/字段组合均经设备遍历。全量 3995 项与最终构建/签名/安装由 root 汇总。
