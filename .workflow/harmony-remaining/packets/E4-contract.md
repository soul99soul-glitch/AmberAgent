# E4 Recipes 执行合同：冻结 v1

2026-09-28，root 已核对并分发。本 packet 收口跨 worker 边界。依据 [真实实现调查](../results/E4-recipes-design.md) 与当前 `tool_loop/tool_dispatcher/tool_permission/tool_exposure/terminal_approval`。实际实施若要改以下接口，集中通知 root，不各造一套协议；所有 worker 保留别人编辑。E3只剩设备连接故障导致的最终UI门待补，不阻塞本阶段独立源码实施。

## 范围与 ownership

- 单 `recipe.json`、声明式最多八步、宿主 primitive 编排、安装/启停删除、`recipe__name` 目录、真实逐步骤审批和结果 UI。
- 沿用 E2/E3、本端 Workspace、AgentTool/dispatcher、会话存储、AtomicJsonFile。没有通用 ledger、scheduler、插件 runtime、下载代码、expected_result、市场、签名、回退中心或 E6 多会话架构。
- Model owner：新增 `harmony/chat/src/main/ets/chat/recipes/models.ts`、`validation.ts`，schema/严格绑定/canonical 与必要 fixture；不改 shared files。
- Store owner：新增 `recipes/ports.ts`、`entry/src/main/ets/platform_impl/RecipeStore.ets`；包/hash/异步预览/双 CAS/启停删除；不改 SkillStore、AtomicJsonFile、DI。
- Runner/tools owner：新增 `recipes/runner.ts`、`tools.ts`；状态/逐步参数、下列 RecipeLoopAdapter、management schema；不改 tool_loop/dispatcher/permission/ChatPage。
- UI owner：新增 `RecipesPage.ets` 及确需的小组件；固定 store/runner 接口后实现，安装及运行使用同一服务/适配器；不改 DI/routes/ChatPage。
- Root：`chat/src/main/ets/index.ts` 导出、DI、tool_loop、tool_exposure、registry/policy、ChatPage、审批摘要、SkillsPage/main_pages 接线；统一必要验证与 subagent review。

## Manifest v1

```ts
export type RecipeInputType = 'string' | 'number' | 'boolean';
export interface RecipeStep {
  id: string; tool: string; arguments: JsonObject; timeoutSeconds?: number;
}
export interface RecipeManifest {
  schema: string; name: string; version: string; description: string;
  inputs: Record<string, RecipeInputType>; steps: RecipeStep[]; outputs: JsonObject;
}
export interface RecipeIssue { code: string; path: string; message: string; }
export interface RecipeEnvelope {
  tools: string[]; mutates: boolean; needsApproval: boolean;
  risk: 'normal' | 'sensitive' | 'high';
}
export interface RecipeDescriptor {
  hash: string; canonicalJSON: string; manifest: RecipeManifest;
}
export interface InstalledRecipe { descriptor: RecipeDescriptor; enabled: boolean; }
export const decodeRecipe: (text: string) => RecipeManifest;
export const validateRecipe: (manifest: RecipeManifest, primitives: AgentTool[]) => RecipeIssue[];
export const canonicalRecipeJSON: (manifest: RecipeManifest) => string;
export const recipeEnvelope: (manifest: RecipeManifest, primitives: AgentTool[]) => RecipeEnvelope;
```

- schema 精确 `amber.recipe.v1`；所有 schema 字段必需，空 inputs/outputs 允许；不新增 runtime/tools/permissions 字段。
- name=`^[a-z][a-z0-9_]{1,31}$`；input/step/output 名=`^[a-z][a-z0-9_]{0,31}$`；version 非空/无空白，description trim 非空。步骤1–8，id唯一，tool无空白且必须为可用 primitive；不允许 recipe__/plugin__ 或 Recipe 管理工具作为步骤。
- timeoutSeconds 是整数1–600，默认60；入参必须恰好匹配 inputs 三种类型，有限数值。宿主注入的 display_title 在严格输入校验前移除。
- arguments 仅顶层完整 `${input.name}`、`${step.id.output.field}` 绑定；引用已声明输入或前序 step；field=`^[A-Za-z_][A-Za-z0-9_]*$`，只有顶层对象字段。其余为 JSON literal，嵌套合法绑定不展开；`${` 开头的非法绑定递归报错。outputs 每项只能绑定存在的 step 输出。
- canonical 从解码 schema 字段重建并递归排序对象 keys，UTF-8。保留 JSON literal 的值和数组序；iOS 未知字段不保留，本端跟随，不另加拒绝 unknown-key 规则。
- envelope 由当前 primitive metadata/policy 保守聚合，仅用于预览和目录摘要；执行批准仍用当前 step 的解析参数。别凭 Recipe 名猜有无副作用，也不复制 iOS 独立 effectClass 体系。

## Store v1（唯一文件写入服务）

```ts
export interface RecipeImportPreview {
  workspacePath: string; candidate: RecipeDescriptor; baseHash: string | null;
  envelope: RecipeEnvelope;
}
export interface RecipeStore {
  listInstalled(): Promise<InstalledRecipe[]>;
  prepareImport(workspacePath: string, primitives: AgentTool[]): Promise<RecipeImportPreview>;
  applyImport(preview: RecipeImportPreview, primitives: AgentTool[]): Promise<RecipeDescriptor>;
  setEnabled(name: string, expectedHash: string, enabled: boolean): Promise<void>;
  remove(name: string, expectedHash: string): Promise<void>;
}
```

- 存储根固定为应用私有 `filesDir/recipes`，name通过已验证正则；每包 `recipe.json` 与独立 enabled 状态。新包默认启用，更新保持既有启停状态。
- 读取候选走 normalizeWorkspacePath + `PosixWorkspaceManager.readBytesCapped(path, 256*1024)`，不暴露任意绝对路径。prepare 无写入。
- hash=`SHA256(UTF8("amber.recipe.package.v1\0") || uint64BE(canonical UTF8字节数) || canonical UTF8字节)`；现有 CryptoArchitectureKit/ArkTS encoder API已有先例，不增加原生依赖。存储和执行都使用同一 canonical 内容；跨 Swift/JS hash 一致需明确 fixture 后才能声称。
- apply 在同 name 锁内重读候选/已安装包，重新 validate 同一 oracle，比较 candidateHash/baseHash；变化拒绝 stale_candidate/stale_base，无写入。成功用现有 writeTextFileAtomic，不 TRUNC 直写、不建立通用 artifact store。
- Agent/UI同用此 Store，更新/启停/删除立即影响下一轮目录；已固定的执行计划不被替换。解析/IO失败不伪装空库后覆盖原文件。

## Recipe 执行桥（唯一特殊路线）

```ts
export interface RecipeExecutionPort {
  primitive: (name: string) => AgentTool | null;
  capture: (part: UIMessagePartTool) => JsonObject | null;
  decide: (part: UIMessagePartTool, tool: AgentTool) => PermissionDecision;
  dispatch: (part: UIMessagePartTool, tool: AgentTool,
    signal?: AbortSignalLike) => Promise<UIMessagePartTool | null>;
  saveParent: (part: UIMessagePartTool) => Promise<void>;
}
export interface RecipeLoopAdapter {
  supports: (toolName: string) => boolean;
  prepare: (parent: UIMessagePartTool, primitives: AgentTool[]) => Promise<UIMessagePartTool>;
  advance: (parent: UIMessagePartTool, port: RecipeExecutionPort,
    signal?: AbortSignalLike) => Promise<UIMessagePartTool>;
}
export interface RecipeRunCheckpoint {
  kind: 'run'; executionId: string; descriptor: RecipeDescriptor; inputs: JsonObject;
  nextIndex: number; completedSteps: string[]; stepOutputs: Record<string, string>;
  phase: 'ready' | 'awaiting_approval' | 'started' | 'finished';
  pendingStep: UIMessagePartTool | null;
}
export interface RecipeImportCheckpoint { kind: 'import'; preview: RecipeImportPreview; }
// ToolLoopOptions 两个可选新增接线；既有无 Recipe 路线不变。
recipeAdapter?: RecipeLoopAdapter;
refreshTools?: () => Promise<AgentTool[]>;
// ToolExposureState 可选扩展（方法由 root 实施）。
replaceTools: (tools: AgentTool[]) => void;
```

Adapter `supports` **只匹配 `recipe_import` 与 `recipe__*`**，避免泛化所有工具；其余 management 按普通 AgentTool 路线。prepare 固定 import preview 或 Recipe descriptor/inputs，不执行 primitive。外层 Recipe wrapper 的 execute 不执行步骤；错误地走普通执行时明确失败，不偷偷旁路批准。

parent metadata 的单键 `recipe_v1` 保存可 JSON 编码的固定内容：kind=`import|run`；import=preview；run=`executionId/descriptor/inputs/nextIndex/completedSteps/stepOutputs/phase/pendingStep`。phase=`ready|awaiting_approval|started|finished`；pendingStep 是完整 UIMessagePartTool，包含已解析参数及现有 terminal_protocol/terminal_target 等 metadata，凭据/原生句柄/Mosh session key 不放入 checkpoint。

### Root 驱动规则

1. 每次模型轮（生成前）调用 refreshTools：保留该 turn 的现有 primitive 工厂实例，只重建 Recipe wrappers 和 registry/introspection/search；一次工具快照供 model schema、search与执行 map。Recipe 建在 localTools + toolProfile 过滤后的完整 primitive 集上；内部 lookup 不用懒暴露后的 stepDefsMap，避免已声明 Recipe 的合法隐藏依赖查不到。
2. `replaceTools` 更新已知名与工具对象，保留仍存在的 exposedNames；移除已删除名字，保留该 turn 既定 lazyMode。新 Recipe 只在下一模型轮出现；新的 tool_search expanded_tools 必须能够在随后一轮展开真实 schema。不能只改列表或靠未知名字塞 exposedNames。
3. 新调用捕获前先 await adapter.prepare。`recipe_import` preview/hash 已固定且先保存，再走普通 explicit approval 门；批准后 advance 执行 Store.applyImport，不重做一份未固定的 live preview。普通 needsApproval=true + execute读live文件不足以绑定安装内容。
4. `recipe__*` 不做一次外层总批准；prepare 后直接 advance，由当前 primitive 自己的决策控制暂停。Recipe branch 在 generic wrapper resolveDecision/dispatcher batch 之前分开处理；初始调用和 approved/denied continuation 都必须走同一 Adapter。
5. runner 每次根据固定状态解析一个 step，new synthetic part 的 approvalState=auto，唯一 id。capture 在参数解析后且批准前执行；metadata存进 pendingStep，resume不再次 capture 覆盖旧目标。primitive 来自当前许可集合，关掉的能力返回具体 missing_tool。
6. **先 decide，再 dispatch**：现有 dispatcher.execute 在 decision=ask/part=auto时会执行 hooks（`tool_dispatcher.ts:377–391`），它依赖普通 loop 的前置门；Recipe 不得漏掉这一步。ask→parent.output=[]/approvalState=pending，保存后展示；deny→终态失败；allow→dispatch。普通 hook 不具备返回 pending approvalState 的协议，不靠它表达 Recipe 续跑。
7. parent 被批准时，只将其保存的 pendingStep 改为 approved；被拒绝时结束 Recipe，未执行当前步。每步完成后清掉批准，下一步重做 auto+decide。port.decide/dispatch 复用现有全局 autoApprove 配置和 invocationContext，但 **不继承父 Recipe 或上一 primitive 的 run-trust 名单**；仍尊重现有显式无人值守设置。
8. dispatch之前保存 phase=started；保存失败零执行。调用现有 dispatcher + 原生 target guard、传同一 step signal、retry disabled；不能复制工具实现。正常返回后保存 completedSteps/stepOutputs/nextIndex；最多八步，不让内部 step 参与模型 duplicate-signature skip（合法同参连续步骤仍执行）。外层 Recipe 调用继续保留现有模型循环重复调用防护。
9. saveParent 的 root closure 按当前 parent part 身份/唯一 locator改写当前 conversation→await store.save→emit；不得把保存旧会话的闭包带到新会话。每次保存后 root 更新 parent 引用；batch普通工具写回不得覆盖已保存的 Recipe metadata。多 Recipe 与普通工具混合批次按输入序处理；一旦 Recipe ask，落库暂停，不顺带启动后面副作用。
10. 暂停 parent 必须 output=[]，因为 `isToolExecuted` 只看 output.length（`deepread/.../agent/message.ts:166`）；进度放metadata/卡片，不能用普通工具output预览冒充终态。advance返回 pending后 loop必须 break，不能下一轮先去请求模型。最终结果才写非空output。
11. started无终态（冷启/取消窗口/结果保存失败）标记 outcome_unknown/已中断，不能自动重放。awaiting_approval内容可供审阅，任何继续也要先验证固定 checkpoint及当前 primitive能力；不新建 scheduler 或后台恢复服务。
12. timeout用step取消信号+primitive本身deadline，等真实返回后终态，后续步骤零调用；批准等待不计timeout。JS现接口不消费signal，只能用已存在内部超时并停止整个Recipe，不能宣称已硬取消；不为Recipe引入另一套JS runtime。Python timeout_ms遵守本端1–60000ms上限，外层60秒默认不会让它忽略原生限制。

status failed/denied/cancelled/interrupted/timed_out、明确ok:false、明确terminal非零终态、Python非completed停止；无法获知完成的副作用不得包装成功。tool返回普通JSON不需强制统一success字段。output.field绑定只解析真正顶层JSON字段，失败给具体step/key。终态输出统一 `{status, recipe, version, hash, completed_steps, failed_step, outputs, error_code, message}`；成功status=succeeded，其余failed/cancelled/outcome_unknown。

## Management/UI 与最小示例

management schema：`recipes_list {}`；`recipe_validate {name? | workspace_path?}`（必须指定一个）；`recipe_import {workspace_path}`；`recipe_enable/recipe_disable/recipe_delete {name,expected_hash}`。list/validate只读；import/启停/删除沿当前explicit approval策略。生命周期自带 expected_hash，安装双hash由 prepare写入metadata，禁止仅凭模型自行声称hash来批准。

列表/详情用现有 PageTemplate + tokens/GUTTER，显示 name/version/description/启停/步骤/输入/hash/缺失工具；安装 UI 路径→同一 prepare→完整摘要→确认→同一 apply；运行 UI 仅三种输入控件，在实际助手上下文通过同一 Adapter，结果与逐步审批用Chat已有卡片/详情。独立直接调用 Python/JS/Workspace 的 UI runner 不符合合约。至少提供安装和真实运行结果可见的可达入口，不做插件“试运行”。

标准三步夹具：file_read(path=input.input_path) → python_execute(code如下, stdin=step.read.output.content) → file_write(path=input.output_path, content=step.compute.output.stdout)；output.saved_path=step.save.output.path。

```python
import json;print(json.dumps(json.loads(stdin),ensure_ascii=False,sort_keys=True))
```

本端 helper 将stdin字符串注入globals（`entry/.../rawfile/python/amber_python.py:88`），用户sys被禁用（`:19`、`:63`）；不使用 sys.stdin.read。测试输入使用小文件，避免 file_read截断的数据被当完整JSON。

## 必要验证最多六类（本轮未执行）

1. 域逻辑 fixture：schema/type/名称/timeout/八步/顶层绑定/input严格校验/失败输出；同一组测试覆盖真正分支，不镜像所有字段实现。
2. 实写Store：Unicode canonical/hash、候选/基线CAS变化拒绝、启停删除、存储失败不覆盖；一个Swift/JS明确hash夹具。
3. 真工具三步：Python+写入分别审批，副作用两步批准各一次；第二步拒绝则已完成一步只执行一次，其余零执行；同参连续声明步骤不被duplicate guard吞掉。
4. Loop+目录：同一turn安装后下一轮 search→schema→调用；删除/停用新轮不可调用；旧pending固定hash/原生target；mixed batch暂停不启动后面副作用。
5. 中断场景：失败/超时/取消/started未terminal均不继续或重放；checkpoint失败零副作用；明确JS取消限制，复用E2/E3真实证据。
6. root/subagent检查全部导出/DI/审批/结果接线，lint/build/sign/install分别记证；最少亮暗列表、长内容安装预览、输入表单、步骤批准与失败结果截图检视对齐/边距/触控尺寸。
