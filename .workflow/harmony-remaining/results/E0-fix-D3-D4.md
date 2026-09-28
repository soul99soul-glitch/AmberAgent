# E0 · D3/D4 精确修复

所有权：`ChatProviderSettingsPage.ets`、`SettingBackupPage.ets`、新增两个纯领域入口 helper 与对应测试；共享 `chat/index.ts` 只补本人及协同 worker 明确要求的导出。未提交、未回退其他 worker 的修改。已读取 harmony-arkts skill，无新 SDK API。

## 修复结果

- C1：Provider 导入卡内容提为 `@Builder ImportCard()`；`CardGroup.content` 普通 lambda 只调用 builder，消除已知的直接组件构造运行时崩溃模式。
- B1：`providerFromImportJson` 校验 `amber-provider`/v1、三种 Provider 类型、必要字符串与非空 name/baseUrl/modelId、模型数组及 chat/image/embedding 枚举、各变体可选字段的实际类型。生成新 ID 和空凭证。列表页在保存成功后更新 `all`，非法输入不会写库或污染既有 Provider 列表。
- A1：`buildSyncSettingsBlob` 成为本地导出与 WebDAV 上传实际共用的 settings 组装入口；按类别 load 后通过已有 `serializeAssistantList` / `serializeProviderSettingList` 转为 wire JSON，助手工具 sealed 字段、模型大写枚举与嵌套 Provider 均可恢复。
- A2：按主 agent 明确要求保留备份/恢复的完整配置与凭证语义；服务商行改为“打包服务商配置及凭证”，footer 提示“备份包含所选服务商凭证，建议使用密码加密”。脱敏 Provider 配置文件导入仍保持新 ID / 空 Key。两种功能语义独立。
- A3：操作期间类别与密码开关均由既有 `busy` 禁止变更，密码输入禁用；本次操作的类别不会在 await 后漂移，无新增状态机。
- A4：原 `applyRestoreSettings` 等价移入 `applySyncRestoreSettings`，保留写失败回滚语义；新增独立的预读错误反馈，预读任何一个类别失败均不写入、也不把尚未取到的原值回滚。页面的 `convNote + settingsNote` 保留已提交会话数量。
- B2 配合余额 worker：`all` 改 `@State`，使保留实例的 `ProviderBalanceText({ setting: this.settingFor(row.id) })` 订阅数据源变更；余额请求与缓存身份由独立 worker 修复。

## Red → Green 证据

新 helper 起始为原页面逻辑的机械提取，先跑窄回归：9 个测试，4 PASS / 5 FAIL。

失败覆盖：

- v2 导入文件被错误接受；未知模型 type 被错误接受。
- 含助手的备份实际抛 `unknown LocalToolOption:`。
- 仅含带模型 Provider 的备份实际抛 `unknown ModelType: chat`。
- 原值预读拒绝直接抛 `storage unavailable`，没有 settings 阶段反馈。

修复后命令（工作目录 `harmony/chat`）：

```bash
node_modules/.bin/tsx --test src/test/provider_import.test.ts src/test/settings_backup.test.ts src/test/provider_settings.test.ts src/test/sync_snapshot.test.ts
```

结果：29/29 PASS。新测试包含三变体、三模型类型、新 ID/空凭证、25 类非法结构/字段不写库且既有列表可读、所选类别与空列表/缺键语义、真实 AES-GCM 归档创建/恢复、普通和嵌套 Provider 凭证保留、两种原值预读失败零写入/零回滚、后续写失败恢复原值。测试消费者是生产实际使用的纯 helper，无源码提取或 transpile 机制。

独立窄 TypeScript 检查 PASS：临时 `/private/tmp/amber-d3-d4-tsconfig.json` extends 原 `harmony/chat/tsconfig.json`，仅 include 两 helper 与两测试并保留原 paths/type 解析；`node_modules/.bin/tsc --project /private/tmp/amber-d3-d4-tsconfig.json --noEmit` exit 0。

## 尚须集成验证的门槛

- `git diff --check`（本人业务文件）PASS。
- 最初四个窄 lint 均因 sandbox 拒绝写 SDK 的 `codelinter.log` 未启动；一次原命令授权重试虽返回 exit 0 / No defects，却含 `Some error occurred during linting`，不能认定 PASS。主 agent 已接管工具规则修正及统一 lint，按要求停止重复运行。
- 构建/签名/安装/设备截图由主 agent 统一执行。本 worker 未做，不能声称 UI 视觉 PASS。请打开 Provider 页确认导入卡不崩溃、检查回详情后的余额刷新，再检查备份凭证文案、行高/边距以及 busy 时类别保持。
- D3/D4 reviewer 已独立复审当前 diff：A/B PASS；59/59（包含其扩展的 provider_balance 等相关检查）PASS，无新阻断。C 代码修复通过，设备截图仍待主 agent。详见 `E0-review-D3-D4.md` 的复审附录；只有完整集成验证通过后才能关闭 E0。

## E0 追加：暗色密码显隐图标

真实旧截图 `/tmp/e0-backup-dark.jpeg` 已查看：备份密码字段的原生 PasswordIcon 近黑，在 SURFACE2 深灰背景上缺少对比。WebDAV 密码字段使用同一原生机制，本次统一处理两处，业务修改仅 `SettingBackupPage.ets`。

- 读取本地官方文档完整 ID：`FAQ/UI框架/组件使用/如何解决TextInput组件密码模式下图标不能修改的问题/faqs-arkui-1014`。文档确认原生 PasswordIcon 大小/位置/颜色不能修改，推荐关闭原图标并自定义。
- SDK 正式声明 `sdk/default/openharmony/ets/component/text_input.d.ts:1315–1326` 标明 `showPasswordIcon(boolean)` 自 API 9 可用，低于项目 API 12 基线，不需高版本降级。
- 两字段共用本页 `@Builder PasswordField`，原生图标 `.showPasswordIcon(false)`；新增两项 V1 `@State` 显隐布尔值，切 `InputType.Normal / Password`。复用已有 `Icon` 的 eye/eye_off，颜色 INK2，图标20vp、点击区44×44vp。外行保留 SURFACE2/r10/44vp，文本保持左14vp，使用剩余宽度，图标不会覆盖文字。
- 忙碌时整行禁用且眼睛 click 保留 `!busy` 门；备份密码和 WebDAV 密码仍分别写回既有字段。显隐按钮有“显示密码/隐藏密码”的可访问说明。
- `harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/pages/SettingBackupPage.ets`：在主 agent 已修正的规则与错误检查脚本下 PASS，exit 0 / No defects found，输出无内部错误；`git diff --check` PASS。
- 按主 agent 要求未新增镜像 UI 单元测试、未改其他业务文件、未触碰 `/tmp/e0-commit-snapshot`。主 agent 统一重建与暗色截图；本次源改不能替代新设备截图的视觉验收。

## E0 追加复验：修正 PasswordField 按值状态冻结

主 agent 实际交互复验在 `/tmp/e0-backup-eye-light-visible.jpeg` 与 hidden 截图中发现：输入 dummy `e0-visible-check` 后点击眼睛，仍显示16个圆点及 eye_off。两张真实截图已查看。先按要求只读查证、未操作设备，再获得主 agent 同意后精准修复。

这是本 worker 首版显隐 builder 引入的问题：`PasswordField(text, placeholder, visible, onChange, onToggle)` 将状态按值传入，界面捕获初值而不订阅页面显隐状态。

官方证据：

- `开发指南/ArkUI_方舟UI框架/UI开发_ArkTS声明式开发范式/学习UI范式基本语法/组件扩展/Builder装饰器_自定义构建函数/arkts-builder` 的“私有自定义构建函数”建议通过 `this` 访问组件状态；“按值传递参数”明确状态按值传入后变化不会引起 builder 内 UI 刷新。多个参数不能使用按引用刷新。API20的 Binding 回调方案不适合本项目API12基线。
- `FAQ/UI框架/UI界面/Builder装饰器参数传递限制与使用方式介绍/faqs-arkui-1078` 对按值/多参数不刷新给出直接示例。
- 旧真实编译产物 `harmony/entry/build/default/cache/default/default@CompileArkTS/esmodule/debug/entry/src/main/ets/pages/SettingBackupPage.ts:916–927` 的 TextInput observer 仅闭包读取局部 `text/visible`；`:935` 可访问文案和 `:942/:946/:955` Icon 也仅读取局部 `visible`。真正的 `showPassphrase` State setter 在`:213–217`；setter正常但这些 UI observer 不读取该 State。`:1005` 的旧 builder 调用位于 If 的 branch 构建闭包内，状态改变并不会重建仍相同的分支。

最终精确修法：builder 只接常量 `isWebDav: boolean`，两调用为 `false` / `true`。TextInput.text/type、Icon.name、accessibilityText 在 UI 构造表达式内部直接读取各自页面 State；onChange/onClick 直接写回对应 State。保留原 SURFACE2/r10/44vp、INK2/20vp图标、44×44vp点击区和 busy门。没有新增接口/全局组件/兜底逻辑或镜像 UI 测试，未编辑其他业务文件与 commit snapshot。

本次 `git diff --check` PASS；`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/pages/SettingBackupPage.ets` PASS，exit 0 / No defects found、无内部错误。主 agent 统一重建和交互截图验收，须分别验证备份密码与 WebDAV 密码的显隐。
