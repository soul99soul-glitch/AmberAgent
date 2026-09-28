# E4 Recipes 最小执行合约（只读调查，2026-09-28）

本轮只读当前源代码；没有运行测试、构建、签名或设备操作。E4 计划要求 manifest/存储/runner/动态工具/列表及运行结果 UI（`docs/plans/2026-09-28-harmony-remaining-execution.md:37`）。下面是建议实施合约，不是已完成能力。

路径前缀：H=`/Users/arquiel/Downloads/AI/amber-harmony-preview/`；I=`/Users/arquiel/Downloads/AI/amberagent-ios/iosApp/iosApp/`。文中 H/I 引用均指本轮真实读取的文件及一基行号。

## 1. 真实 iOS 合约与 E5 边界

- 单包是 **`recipe.json` JSON**，schema=`amber.recipe.v1`；字段为 `schema/name/version/description/inputs/steps/outputs`（I`IOSRecipeModels.swift:250`）。没有 `runtime` 字段，没有包级 `tools` 权限声明，也没有代码文件/依赖安装字段。
- `inputs` 是名称→`string|number|boolean`，全部必填；调用缺失、额外输入、类型错误均失败（I`IOSRecipeRunner.swift:423`）。`version` 仅非空且无空白，不执行 semver 解析；`description` 去空白后非空（I`IOSRecipeModels.swift:409`）。
- Recipe 名称实际正则 `^[a-z][a-z0-9_]{1,31}$`，2–32 字符；input/step/output 名称实际校验允许 **1–32** 字符（I`IOSRecipeModels.swift:56`、`:65`）。部分错误文案写 2–32，与实现不一致；Harmony 应按实际代码，不复制错误文案。
- `steps` 为 1–8 项：`{id,tool,arguments,timeoutSeconds?}`；id 唯一；timeout 为整数 1–600 秒，默认 60 秒（I`IOSRecipeModels.swift:44`、`:240`、`:425`）。tool 必须存在于宿主 primitive 目录，无空白；`recipe__*`/`plugin__*` 引用被拒绝（`:445`）。
- 顶层 argument 值可为 JSON literal，或完整字符串 `${input.name}` / `${step.id.output.field}`；只引用前序 step，field 只是一层 JSON 对象字段。`outputs` 每项必须引用 step 输出，不能是 literal/input（`:471`、`:486`、`:545`）。
- **不是模板语言**：不拼接字符串、不支持对象路径/数组索引；嵌套 literal 内的合法绑定字符串原样保留，只有顶层 argument 值会解析。嵌套 `${` 开头的非法绑定会被校验报错（I`IOSRecipeModels.swift:99`、`:209`；I`IOSRecipeRunner.swift:229`）。勿擅自增加递归绑定语义。
- 顺序执行、失败即停止，返回 failedStep/completedSteps/structured error；成功后统一解析 outputs。不补偿已产生的副作用，不自动重试整个 Recipe（I`IOSRecipeRunner.swift:9`、`:176`、`:363`）。
- iOS 后半文件混入 Plugin 类型及 UI，E4 不移植这些：`IOSRecipeModels.swift:601` 起、`IOSRecipeTools.swift:729` 起、`RecipesView.swift:689` 起属于 E5。试运行、expected_result、command/runtime、插件信任/市场不进入 E4。

实际 iOS 最小样例在 `iosApp/iosAppTests/IOSRecipeIntegrationTests.swift:2663`：tools_list → `${step.list.output.total}`。**Harmony tools_list 输出没有 total**，应按本端实际字段绑定（H`harmony/chat/src/main/ets/chat/builtin_introspection_tools.ts:205` 有 enabled_count）。不能直接把 iOS 工具名/输出字段当成本端兼容接口。

建议本端闭环样例（宿主须开启 workspace_files 和 python）：

```json
{
  "schema": "amber.recipe.v1",
  "name": "normalize_json",
  "version": "1.0.0",
  "description": "读取 JSON，使用本机 Python 排序，再写入工作区。",
  "inputs": { "input_path": "string", "output_path": "string" },
  "steps": [
    { "id": "read", "tool": "file_read", "arguments": { "path": "${input.input_path}" } },
    { "id": "compute", "tool": "python_execute", "arguments": {
      "code": "import json;print(json.dumps(json.loads(stdin),ensure_ascii=False,sort_keys=True))",
      "stdin": "${step.read.output.content}"
    } },
    { "id": "save", "tool": "file_write", "arguments": {
      "path": "${input.output_path}", "content": "${step.compute.output.stdout}"
    } }
  ],
  "outputs": { "saved_path": "${step.save.output.path}" }
}
```

## 2. 可复用的实际模块

| 模块 | 已有能力与 E4 使用方式 |
|---|---|
| H`harmony/chat/src/main/ets/chat/tool.ts:30` | AgentTool/JSON schema；`recipe__name` 参数直接由 inputs 生成 required schema，移除宿主 display_title 后再执行严格输入校验。 |
| H`harmony/chat/src/main/ets/chat/tool_registry.ts:78` | 注册去重、策略、输出预算；每轮 Recipe descriptor 必须与执行 manifest/hash 为同一快照。 |
| H`harmony/chat/src/main/ets/chat/tool_dispatcher.ts:229`、`:347` | primitive 参数级权限决策及真正执行；不复制 native、MCP、Workspace 工具实现。 |
| H`harmony/chat/src/main/ets/chat/tool_approval.ts:119` | 精确 part locator；审批先落库再续跑，ChatPage`:4184` 已接线。Recipe 继续使用这个用户按钮路径。 |
| H`harmony/chat/src/main/ets/chat/workspace.ts:67`、`:188` | normalizeWorkspacePath/readBytesCapped；recipe_import 接工作区路径，沿用 iOS 256 KiB 上限，不读取任意绝对文件路径。 |
| H`harmony/entry/src/main/ets/platform_impl/AtomicJsonFile.ets:12`、`:160` | UTF-8 原子写与 KeyedAsyncMutex；单例 RecipeStore 按 name 串行 CAS/启停/删除。 |
| H`harmony/entry/src/main/ets/platform_impl/EntryLocalToolPorts.ets:36` | 已有 cryptoFramework SHA256、TextEncoder；RecipeStore 可复用同样 API 对 Uint8Array hash，不用新增平台依赖。 |
| H`harmony/chat/src/main/ets/chat/python/tools.ts:27` | 真正本机 `python_execute`；code/stdin 输入，stdout/stderr/status 输出，现有批准与取消。不要改为远程 Python。 |
| H`harmony/entry/src/main/ets/platform_impl/JsSandboxTool.ets:158` | `javascript_execute`；code/timeout_ms，输出 ok/result/console。result 是字符串或 null，JSON 对象结果也已转字符串（`:119`）。 |
| H`harmony/entry/src/main/ets/pages/SkillsPage.ets:189`、PageTemplate | Recipes 的入口与本机列表视觉骨架；不把 SkillManager 抽象成通用 artifact framework。 |

Python 没有直接工作区文件/网络/subprocess/pip；Recipe 用 file_read → stdin → stdout → file_write 组合。本端 helper 把 stdin 字符串直接注入用户 globals，禁用用户导入/引用 sys，示例必须用 json.loads(stdin)，不能用 sys.stdin.read（H`harmony/entry/src/main/resources/rawfile/python/amber_python.py:19`、`:63`、`:88`）。JS 无网络/文件、每次调用无持久状态。本端 runtime 是这些 primitive 工具自身的能力，Recipe 不额外发明 runtime 选择器。

## 3. 存储、安装与动态目录

1. 私有 `filesDir/recipes/<name>/recipe.json` 存 canonical manifest，启用状态单独保存，默认启用；停用仍可查看，不再新声明工具。已有 iOS 对应 I`IOSRecipeFileStore.swift:118`、`:126`、`:286`。
2. canonical 必须从**已解码的 schema 字段**编码，递归固定对象 key 次序、UTF-8，不直接 hash 原始输入文本；存储、hash 和执行基于同一 canonical 内容。iOS JSONDecoder 忽略未知字段，canonical 不保留未知字段（I`IOSRecipeModels.swift:250`、`:318`）；不擅自加入 unknown-key 拒绝规则。
3. iOS hash 精确算法是 SHA256(`UTF8("amber.recipe.package.v1\0") || uint64BE(canonical字节数) || canonical字节`)（I`IOSRecipeFileStore.swift:663`）。已有 UTF-8 string hash 不能替代长度前缀字节算法。跨 Swift/JS 的转义/小数编码相等需要真实 fixture；未经对照不宣称跨平台 hash 一致。
4. `prepareImport(workspace_path)` 无写入，返回 manifest/步骤/输入/效果摘要及 baseHash/candidateHash。批准后重新读取候选、重新校验 oracle、对比双 hash，CAS 成功才原子落盘；候选或已安装包改变，给 stale_candidate/stale_base 并要求新预览。iOS 原路径 I`IOSRecipeTools.swift:289`、`:308`。
5. 工具面最小对齐 `recipes_list/recipe_validate/recipe_import/recipe_enable/recipe_disable/recipe_delete`（I`IOSRecipeTools.swift:124`）。生命周期调用带 name/expected_hash（`:496`）；安装批准独立于运行步骤批准。
6. iOS 还有一份 previous 包与回退（I`IOSRecipeFileStore.swift:122`、`:231`），但 E4 计划未要求回退；本次最小闭环先实现更新 CAS/启停/删除，不引入插件多版本租约/市场/签名机制。
7. Recipe 工具只由已启用、有效 manifest 生成：toolName=`recipe__<name>`，description/inputs 来源同一 descriptor，descriptor 保留 version/hash/canonical manifest。目录 oracle 与真正可执行 primitive 工具集合同源；不以“文件已安装”替代工具可用性。
8. 安装后下一模型轮生效；启停/删除也在轮边界生效。当前助手 localTools/toolProfile 不具备的 primitive 明确显示缺少工具，调用前失败；不通过 Recipe 把关闭的能力重新开启。

## 4. runner 与审批：当前必须补的真实 seam

**不能只在 AgentTool.execute 内写 for-loop。** 它只接 input/signal，拿不到当前 part 的 approvalState/metadata（H`tool.ts:42`）；dispatcher hook 只返回 output/metadata，不能返回新的 pending 状态（H`tool_dispatcher.ts:77`、`:416`）。iOS 是独立 advance/resume 路由，每次 mutation gate 保存 checkpoint 后暂停（I`ChatToolRuntime.swift:4359`、`:4602`）。

勘误补充：dispatcher.execute 当前不拦截 decision=ask 且 part=auto（H`tool_dispatcher.ts:377`、`:380`、`:391`），因为正常路径先由 loop 置 pending；Recipe 路由必须显式先 resolveDecision，ask 时不调用 execute。recipe_import 的 preview 是异步读文件，现有 captureInvocationMetadata 是同步 callback（H`tool_loop.ts:146`），因此必须在 loop 审批前有窄的 async Recipe prepare，固定并落库 preview/hash 后才允许批准。冻结草案详见 `../packets/E4-contract.md`。

建议一个只服务 Recipe 的窄路由，纯 runner 持有不可变计划并提供 next/acceptResult；root 在 tool_loop 驱动下面流程：

1. `recipe__` 调用开始按本模型轮 descriptor 固定 manifest/hash/inputs/执行 id；严格校验输入及当前 primitive 可用性。不要在每次续跑重新读 live store 改用新版。
2. runner 计算当前 step 精确参数，生成 synthetic primitive tool part。实际决策使用已有 dispatcher.resolveDecision；native SSH 等的 captureInvocationMetadata 同样作用到这个 primitive part，目标 hash/host identity 随 pending step 固定。
3. 若 ask：parent Recipe part 的 metadata 保存 pinned manifest/hash、nextIndex/completedSteps、stepOutputs 和 pending primitive part；parent output 保持空、approvalState=pending，**先 await 保存会话再展示卡片**。卡片显示 `Recipe 名 · step id · primitive 名`、已解析参数，原生目标详情保持可审阅。
4. 用户批准 parent 卡只批准保存的一个 primitive 调用；执行 primitive 用既有 dispatcher/hook，完成后记录结果与 nextIndex，再计算下一步；下一步重新决策，清掉上一步批准。拒绝直接结束 Recipe，已完成步骤不再执行。
5. dispatch 前把该步 started 标记落库；失败、取消、超时均停止。进程冷启或中断见到 started 未有 terminal result 时报告 outcome_unknown/已中断，**不自动重放**。这不需要再建一套 scheduler、通用 ledger 或 E6 会话宿主。
6. 拒绝正常化失败为“有返回字符串就成功”：宿主工具返回的 `ok:false`、status failed/cancelled/interrupted、Python 非成功 status、原生 job 非零终态应终止。仅对明确失败字段判断，别把所有 unknown JSON 自行判失败。结果非 JSON 对象仅在需要 output.field 绑定时失败。
7. timeout 应取消同一个 step 的 signal，保持 primitive 原生 deadline/终态；不能 Promise.race 超时后仍启动下一步。不能把用户等待审批的时间算作执行超时。terminal_job_* 若返回 running，是 job handle；后续步骤须显式 wait/read，并尊重其终态，不冒充任务完成。

**目录刷新也是必改点。** H`tool_loop.ts:269` 固定 exposure，H`tool_exposure.ts:56` 固定 allTools，H`ChatPage.ets:3888` 固定 registry。需要 `ToolLoopOptions.refreshTools?` 轮边界 callback + exposure 更新 tools 集合且保留已暴露名字；一次快照同时生成 registry/introspection/tool_search/step dispatch map。单独刷新列表或单独把名字加入 expanded_tools 都不闭环。

现有 policy 根据名字猜 effect，`recipe__read_catalog` 即使内含 write 也会被当 utility/可并行（H`tool_policy.ts:179`、`:243`、`:331`）。root 必须让 Recipe 元数据摘要从 primitive policy 聚合，并显式禁止 Recipe speculative prefetch/并行 dispatcher 路线；副作用批准仍由内部实际 step 参数决定，不靠外层名称或一次总批准放行。无需重建一套 iOS effectClass 序列体系。

## 5. 建议 public 接口与 ownership

共享签名已收敛到 [E4-contract 冻结草案](../packets/E4-contract.md)，以其中 models/RecipeStore/RecipeLoopAdapter 为唯一草案接口，避免同时保留不同 applyImport 或 runner 签名。它们保持现有 JSON、AgentTool、AbortSignalLike 类型，尚未成为已实现 API；以下表格仅列文件责任边界。

| Ownership | 独占文件 / 内容 |
|---|---|
| Model worker | 新 `harmony/chat/src/main/ets/chat/recipes/{models,validation}.ts`；schema/binding/canonical，关键 validator fixture。 |
| Store worker | 新 `recipes/ports.ts`、`entry/.../platform_impl/RecipeStore.ets`；目录/hash/双 CAS/原子写/启停删除；只复用现有 helpers，不改 SkillStore/AtomicJsonFile。 |
| Runner worker | 新 `recipes/{runner,tools}.ts`；纯计划/单步解析/失败输出/management schema；不修改 root 工具循环/策略/ChatPage。 |
| UI worker | 新 `entry/.../pages/RecipesPage.ets`（必要时小组件）；列表/安装预览/运行输入与结果；依赖接口先固定。 |
| Root | `harmony/chat/src/main/ets/index.ts` 导出、`AppContainer.ets` DI、`tool_loop.ts` Recipe route/轮刷新、`tool_exposure.ts` 更新、`tool_registry.ts`/`tool_policy.ts` 聚合策略、`ChatPage.ets` 特殊运行/批准目标捕获、消息详情审批摘要、`SkillsPage.ets` 入口、`main_pages.json` 路由。多 worker 不抢这些 shared files。 |

## 6. UI 的最小产品行为

- iOS Recipe UI 仅列表/详情/启停/删除/回退；空态指导 Agent validate/import，**没有 Recipe 安装按钮或输入表单的独立执行器**（I`RecipesView.swift:157`、`:361`）。计划“运行结果 UI”至少需真实结果可见，不能照搬插件试运行。
- Harmony 页面用现有 CenteredTitleBar/PlainSection/CardGroup/CardRow/FooterNote、GUTTER 与主题 StorageProp；列表显示 name/version/描述/启停/校验问题；详情显示步骤、输入、hash 和权限摘要。缺失 primitive 的包可查看，运行按钮说明具体缺失。
- 安装入口输入工作区 recipe.json 路径，prepare→显示 name/版本/步骤/输入/权限/base+candidate hash→显式确认→同一 applyImport。Agent recipe_import 则用工具批准卡，同一服务 CAS；不能 UI 直接绕过预览或复制安装逻辑。
- 如做手动运行表单，只为声明的三类输入生成控件；选择当前实际助手上下文，提交固定 descriptor/hash/inputs 到 **同一 Recipe route**。可以创建已持久化的 assistant recipe tool part 后走 continuation，在 Chat 中展示逐步批准与终态；禁止另一套直接调用 Python/JS/Workspace 的 UI runner。
- 运行结果显示 succeeded/failed/cancelled/outcome_unknown、completedSteps、failedStep、最终 outputs；失败保留已完成步骤。长工具名/哈希换行或可复制详情；按钮触控≥44vp，输入表单/八步骤/输出区域可滚动，亮暗色用现有 tokens。

## 7. 最多六项必要验收（本轮未执行）

1. 一个纯域 fixture 组覆盖有效 manifest、缺字段/错类型、名称边界、前向绑定/非法绑定、超8步、timeout、输入漏传/多传及实际顶层 literal 绑定行为。
2. 临时私有目录实写验证 Unicode canonical 存储/hash一致、同名更新 CAS、候选变更/基线变更拒绝、启停删除后目录变化；跨平台 hash 用一个明确 fixture 对照，别另建冗余 mock suite。
3. 真正工具链 file_read→本机 python_execute→file_write，逐步批准（Python 与写入分开），输出内容正确；拒绝第二副作用步骤，前一写入恰好一次，后续零调用。
4. 同一聊天用户 turn 内安装/启用→下一模型轮 tool_search 找到 recipe__→真实调用；停用/删除后新轮不可调用；已有 pending/in-flight 调用固定旧内容与原生批准目标，不被 live 更新替换。
5. 一条失败/取消/超时及冷启 started 场景证明不会继续下步/重放副作用，completedSteps 与 outcome_unknown 准确；沿用 E2/E3 native 证据，不重复全产品测试。
6. subagent 检查导出/DI/目录/runner/批准/结果全链，最少亮暗色列表、长内容预览、输入表单、步骤审批和失败结果截图目测；lint→build→sign→install 各层证据分别记。无新/不确定 Harmony API；若实施新增 API，再按 skill 先查 offline 官方 docs。
