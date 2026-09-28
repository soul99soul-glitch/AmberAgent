# E5-F 插件中心与手动会话入口

2026-09-28，源码与责任范围必要验证已完成，统一编译/签名/设备验收由 root 负责。UI owner独占 PluginsPage.ets、PluginJSONEditor.ets、PluginPageProjection.ets、PluginPageLaunch.ets；root负责DI、routes、SettingSandbox、catalog、Chat manual loop/shared files。未修改其他owner文件、未操作设备/build、未执行全suite。

## 已核对真实参考

- 已读 `E5-plugin-design.md`、harmony-arkts skill，以及实际iOS `RecipesView.swift` 中 PluginsView/PluginImportPreviewSheet/PluginDetailView（不是不存在的IOSDynamicPlugin文件）。预览有候选包、基线、信任、权限diff、启用意图；详情包含工具/runtime、能力、后台限制、隔离/诊断、启停删除及一槽rollback/restore。
- 已读iOS `IOSPluginDevelopment.swift` 的真实test expected_result与candidate_hash/registered=false规则：null必须与未提供区分，失败测试不伪装通过；不复制iOS专用AmberShell/iSH runtime名到鸿蒙SDK。
- 已读当前 `RecipePageLaunch.ets` 的真实流程：同Adapter.prepare生成固定parent checkpoint→当前真实助手会话→await repository.save→导航Chat精确toolCallId→manual continuation，页面不执行工具或让模型猜工具。

## 已冻结接线

- 手动路由 `pages/ChatPage {conversationId,pluginManualToolCallId}`；parent另有 `plugin_manual=true`，固定内容只使用模型owner单键 `plugin_v1`。普通工具不能借此入口执行。
- 最小launch依赖为真实当前 assistant、同 PluginLoopAdapter、许可过滤后真实 primitives、ConversationRepository（save barrier）。helper 不导 DI、不创建模型、不执行 backend，root 先验证实际助手/model配置。helper 在 Adapter.prepare 后再固定 plugin_manual=true，保留已有 plugin_v1/其它 metadata。
- root DI 提供 async getPluginStore/getPluginPrimitives、startPluginFromPage(toolName,input,title,expectedPackageHash=null)、exportPluginFromPage(id,hash,workspacePath)。页面已安装运行传实际展示的 package.hash；root 用单次 installed snapshot 核 enabled/member/hash，再在同 snapshot 构造 adapter。
- 目录/归档 source 直接消费模型 `{kind,workspacePath}`；plugin_test/import 使用 expected_candidate_hash，测试成员 name/inputs，expected_result 未设置不传、明确 null 仍传。执行 owner 已提供 archive_path 与 workspace_directory 恰一源支持。
- 页面 load 使用真实默认助手 primitive snapshot → listInstalled(primitives)，不以空 oracle 校验已安装包。SDK 直接消费 domain getPluginSDK()；输入直接消费 normalizePluginInputs，显示真实 path/message。
- 显式启停/删除/回退/解除隔离/发布者 trust 使用 Store 和产品确认（root 授权，与 E4 一致）；run/test/import 走持久化父工具/Chat adapter。导出由 Store bytes 经 root 真实 Workspace 保存出口，不猜文件系统路径或写入 API。

## 页面实际行为

- 安装目录/归档双入口：固定预览 candidate hash、baseline hash、权限 diff、信任及启用意图；验证只说明结构有效。进入对话的测试/导入显示真实最终输出及回执，不把验证成功当真实试跑。
- 列表区分 enabled、disabled、quarantined、invalid，并保留坏包错误。详情显示全部能力、runtime、输入/输出 Schema、完整包和文件 hash、固定源码/Recipe/endpoint、后台申请与宿主限制、健康和有限诊断。
- 坏包 `currentHash` 来自严格 Store 指针，用于真实 CAS 删除/回退；pointer 坏而 hash=null 明确不可确认版本，不能运行/导出/猜 hash。无虚构 canRollback 状态；Store no_previous 明确反馈。
- JSON editor 不做新表单框架，保持 JSON 对象/嵌套/可选字段的实际类型，按共享 Schema 校验；可展开并复制 Schema。预期结果 null 与未填写分离。
- 可信发布者添加前使用平台已有 public-key fingerprint helper 计算完整 SHA-256 并确认，冻结公钥/备注；列表及签名包详情完整指纹可复制，短 keyId 不代替信任。有效签名和已信任发布者展示分开。
- 主题采用 V1 @StorageProp + @Watch 色板状态，页面 Builder 直接读取可变 state。CardGroup lambda 仅调用 @Builder；44vp 按钮、52vp 输入、16vp gutter/底部 + navBarHeight、Scroll Top、JSON/hash 源码换行复制。新 page 少于1000行。
- JSVM/Python 明确实际取消/运行环境边界：JS 无限循环可能留 CPU 与唯一槽，不称强杀；Python 取消等待实际 settle/C 阻塞不能立即停止。

## 必要验证

- `node --test .workflow/harmony-remaining/fixtures/E5-plugin-page-launch.cjs`：**2/2 PASS**。实际 package validation/PluginLoopAdapter/Conversation/MemoryRepository，只注入 SDK router 和不应被调用的执行端口。保存悬挂前无导航；同 prepared checkpoint 固定旧 hash/输入，plugin_manual 覆盖 false 且保留 metadata；save failure、普通 tool 和 stale candidate 不导航；明确 null/未提供预期结果不同；0 backend/primitive 执行。
- 同一窄 fixture 的纯 projection 检查：invalid/quarantined/disabled/enabled 四态以及 signed-untrusted/signed-trusted 分开。未写镜像 UI 测试，未重复 backend/store 全 suite。
- `harmony/scripts/lint-arkts.sh` 仅上述四个新增 .ets：**0 error / 0 warning**；`/tmp/e5-plugin-ui-lint.log`。

## 统一验收门

页面不声称 build/签名/安装或真实 UI 已通过。C 设备离线；root 保留编译回喂、亮暗布局及 SDK→Workspace→真实测试/结果→批准导入→真实调用→停用/重启设备门。

### 固定已展示基线的精准修复

root 已冻结可选 `expected_base_hash:string|null`：页面仅在 plugin_import 启动输入加 `preview.baseHash`，新包明确 null；plugin_test 输入不变。runner 在 Adapter.prepare 重新准备后、该字段 present 时比较 baseHash，变化明确 stale_base；最终 applyImport 双 CAS 保持。避免进入对话前 installed 变化替换页面已展示的 baseline/diff。此单行按 root 授权不重复已过 2/2 fixture/四文件 lint，等待统一 compile。

### 编译回喂精准修复

`/tmp/e5-release-build.log` 的自有 UI 诊断：PluginJSONEditor 的 `enabled:boolean` 与继承 CommonAttribute.enabled() 冲突，已改 `editable`。父页面两输入原本均是 @State；实际错误是 `$this` 未定义，改为明确 @Prop value + onTextChange 更新父 @State，编辑时清除该字段的上次校验错误。未扩展其它字段/执行协议，不重复已过 launch fixture。

仅这两个变化 .ets 窄 lint：**0 error / 0 warning**，`/tmp/e5-plugin-editor-buildfix-lint.log`；源码稳定等待 root 下一轮 compile。
