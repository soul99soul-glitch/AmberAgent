# E4 Recipes 页面消费者

2026-09-28。已读 `harmony-arkts` skill 与冻结 `E4-contract.md`。仅新增 `harmony/entry/src/main/ets/pages/RecipesPage.ets`（455 行以内）；未改 root shared files、routes/DI/SkillsPage/ChatPage、Store/domain，未操作设备、build、stage/commit，保留其他 worker 编辑。

## 同服务安装与管理

- 页面 `getRecipeStore(): Promise<RecipeStore>` 只消费冻结 Store。Workspace 相对路径先 `prepareImport(path, primitives)`，展示实际候选 name/version/description、完整 canonical 清单、涉及工具、风险/修改/审批摘要、候选 SHA-256 与已安装基线 SHA-256。长 hash、路径和清单换行且可本机复制；确认安装/更新按钮在完整清单之后。
- 确认调用同 Store `applyImport(originalPreview, currentPrimitives)`，不重新 prepare 或自行读写文件；preview 的 candidate/baseHash 保持固定。路径变更清旧 preview。Store 双 CAS 或校验异常直接显示，已知 stale_candidate/stale_base 提示重新预览或打开详情，未知 message 保留。
- 列表显示真实启停状态、步骤数与当前缺失 primitive；详情显示完整清单与固定 hash。启停/删除传所选 descriptor 的 name/hash，删除确认也捕获同 descriptor。成功后重读 Store；刷新时所选版本已更改或删除会回列表，不悄悄替换执行目标。
- 读取失败显示真实错误，不把异常伪装成空库，也没有备用文件/代码执行器。

## 同 Adapter 的运行提交

- root 已冻结 `getRecipePrimitives(): Promise<AgentTool[]>` 和 `startRecipeFromPage(name,expectedHash,inputs): Promise<void>`。页面明示采用当前默认助手配置（与既有 SkillsPage 一致），使用默认助手许可过滤后的完整 primitive oracle 做 prepare/apply 和缺失工具展示。
- 输入表单严格按 manifest.inputs 生成 string TextArea、number TextInput、boolean Toggle；数字拒绝空值、NaN/Infinity，字符串不擅自 trim，布尔值始终为 boolean，提交只包含声明字段。V1 字段更新整体替换数组；控件值直接读取当前 state，未把可变字段快照按值交给 @Builder。
- 运行只捕获所选 name/hash 和当前 JsonObject 输入，然后调用 root `startRecipeFromPage`。页面不 dispatch primitive、不直接调用 Python/JS/Workspace，实际固定 checkpoint、逐步审批、进度和终态由 root 的同一 RecipeAdapter/Chat 卡片消费者承担。助手/模型配置和真实提交错误显示原 message。
- 页面未伪造运行成功或结果；运行按钮文案明确“在对话中运行”，说明审批/结果在对话查看。root helper、Chat 消费者和路由仍须统一接线/build/实际验收，不能仅凭页面声称闭环。

## UI 与验证

- V1 状态、StorageProp 主题/accent/navBarHeight；局部 token palette 在 theme watcher 更新。沿 CenteredTitleBar/CardGroup、GUTTER=16、顶部 Scroll 对齐、16+navBarHeight 底部安全边距。按钮 44vp、路径/数值行 52vp，输入墨色/placeholder 显式设置。CardGroup lambda 仅调用 @Builder；详情/预览/表单 builder 直接读取本页 state。
- `harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/pages/RecipesPage.ets`：`/tmp/e4-recipes-ui-lint.log` 为 No defects found，0 error / 0 warning。diff-check 通过。
- 未写镜像 UI tests、未跑全 suite/设备/build。执行时 root 公共 exports/DI helpers 尚未落地；窄 lint 不代替完整 ArkTS type/build。必要亮暗长预览、输入表单、真实逐步审批/失败结果验收由 root 统一执行。

路由入口：`pages/RecipesPage`，无参数。所需公开类型：AgentTool/JsonObject/InstalledRecipe/RecipeDescriptor/RecipeImportPreview/RecipeInputType/RecipeStore；所需 DI helpers 如上，均为 packet/root 冻结名称。

## 共用本地 primitive 机械提取

- 根据 root 后续授权新增 `platform_impl/AssistantLocalPrimitives.ets`，导出同步 `createAssistantLocalPrimitives(deps): AgentTool[]` 与 `AssistantLocalPrimitivesDeps`。依赖固定 assistant/conversationId/workspace/activityStore/terminalRuntime/moshRuntime/sshProfiles/pythonRuntime。
- 从当前 ChatPage.buildToolLoop 最前组原样提取 time_info、SSH/Mosh、Python、ask_user、javascript_engine、Workspace 六件与 artifact 十一件；顺序、同 singleton activityStore/conv ID 参数及平台 artifact/JS ports 完全保持。SSH/Mosh 同样要求 terminal 开关、SSH runtime 与 profiles，Mosh再要求Mosh runtime，不因新增目录解禁能力。
- factory不导 DI、不初始化 runtime、不执行 primitive、不做 profile filter 或额外挂载 JS Host；Chat 的 terminal guard、JS host mounting、其他上下文工具及 profile 过滤仍由 root/原调用者负责。root将让ChatPage与默认助手Recipes本地目录共用此真实factory，未造虚拟工具列表。
- RecipesPage三处说明明确默认助手的“本地工具”目录范围；运行仍通过根helper进入同Adapter。该机械提取不写测试；仅新factory文件lint `/tmp/e4-assistant-local-primitives-lint.log` 为No defects found，0 error / 0 warning，不重跑页面/全suite/build/设备。
