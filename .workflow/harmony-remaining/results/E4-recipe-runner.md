# E4 Recipe runner / tools

2026-09-28。实现范围仅 `recipes/runner.ts`、`recipes/tools.ts` 与 `recipe_runner.test.ts`，不修改 dispatcher、tool_loop、policy、ChatPage、DI 或设备。

## 实现和调用链

- `createRecipeLoopAdapter({store, installed})` 保存本轮 installed 快照。`prepare` 固定 descriptor/严格输入或 import preview；已有合法 `recipe_v1` 原样保留，不换 live 包或重新预览。
- `createRecipeRun` 建固定检查点；`nextRecipeStep` 仅解析顶层完整绑定并生成执行 id + index 唯一步骤 id；`acceptRecipeStep` 接受对应 started 步骤，记录结果而不修改旧检查点。绑定只读真实 JSON 顶层字段，缺失字段明确失败。
- `advance` 对当前 primitive 执行 lookup → capture（仅新步骤）→ decide → 保存 pending/started → dispatch → 保存完成步骤。调用真实 port，不复制工具；父批准仅复制到一个 pendingStep，下一步重新 auto/decide。等待批准时父 output 为空，其他 parent metadata（含 `recipe_manual`）保留。
- `ask_user` 仅以父 `answered` 的真实 answer 继续现有 dispatcher；普通 approved 保持等待回答。未批准的 import pending 重入不调用 apply；新 import 尊重真实 decide 的 explicit auto，批准后应用已固定 preview，由同一个 Store 重新验证/CAS。
- dispatch 前 started 保存失败零执行；结果保存失败直接抛出，耐久检查点仍为 started，恢复只报 outcome_unknown，绝不重放。失败/拒绝/Python 非 completed/明确 ok:false/远端非零状态停止后续步骤。
- timeout 从 dispatch 开始计时，不计审批等待；使用步骤 signal 并缩短 JS/Python 原有 deadline，等待真实 dispatch 结算。JS 不消费 signal，未声称硬取消或另建 runtime。
- `terminal_job_start` 可返回 running handle 给后续显式 wait/read；未确认 terminal 状态时 Recipe 不能成功，outcome_unknown 返回已有 handle。无命令结束语义的 interactive session_exec 也不能冒充成功。没有自动 wait/retry。
- `createRecipeTools({store, installed, primitives})` 提供六个 management schema 与有效启用包的 wrapper，wrapper 附真实 envelope。wrapper/import 的普通 execute 明确 `recipe_adapter_required`，不能旁路。启停/删除调用唯一 Store，使用 expected_hash。

## 公开接线

`runner.ts`：`RecipeLoopAdapterDeps`、`createRecipeLoopAdapter`、`createRecipeRun`、`nextRecipeStep`、`acceptRecipeStep`；type re-export `RecipeLoopAdapter` / `RecipeExecutionPort`（实际定义仍在 ports）。

`tools.ts`：`RecipeToolDeps`、`createRecipeTools`。

Root 提供 port 的真实 dispatcher/现有 target guard、retry disabled、空 run-trust、当前完整许可 primitive map、当前父 locator 的 await save/emit。外层支持 import/run 都走 Adapter；现有 management 保持普通工具路线。UI/manual 和目录刷新由 root 接线，本报告不替代其验收。

## 必要验证

- `recipe_runner.test.ts` **6/6 PASS**：真实 dispatcher 三步双批准与 frozen descriptor/capture；拒绝/失败；started 与结果保存故障；实际取消/超时结算；固定 import preview/显式 auto/execute 防旁路；ask_user 真回答。
- 自审先复现 import pending 被 PermissionResolver 的非 auto 分支误允许，断言红后修为 pending 原样返回；ask_user 普通批准错误执行的 focused 断言红后修为 answered 独立续跑。
- `tsc -p harmony/chat/tsconfig.json --noEmit` exit 0。
- 最终窄 Harmony lint exit 0：**0 error / 482 warn**，warnings 为该 linter 对 TS 导入解析的 unsafe、JSON deep clone 等规则；未声称 0 warning。原始输出 `/tmp/e4-recipe-runner-lint.txt`。
- 未重复 full chat suite；初始六个 runner fixture 使用 fake primitive，不能作为原生验收。后续唯一真实本机三步链如下。设备/build/sign 未在本 owner 范围执行。

实现文件分别约 310/85 行，无新增 scheduler、ledger、run trust 或授权 flag。

## 最后一条必要真实三步链

按 root 授权新增 `recipe_native_chain.test.ts`，仅 **1 test / 1 PASS**，不修改产品实现、不重建 native 或重复 native suite。

- 临时专用 `/tmp/amber-recipe-native-chain-*` 目录承载 Workspace/任务/父检查点，结束仅清除此夹具目录，不触碰用户 Workspace。
- `PosixWorkspaceManager/createWorkspaceTools` 使用 Node FS 的既有 `WorkspaceFsPort`；`PythonRuntime/createPythonTool` 的薄 transport 直接转发现有 `harmony/native/python/build/host/amber_python_host.node` 的 pythonExecute/version/cancel，使用现有打包 Python resourceRoot。取消监听保持到实际 native settle。
- 真实 `RecipeLoopAdapter` + `AgentToolDispatcher(defaultToolInvocationHooks)` 跑 file_read → Python `json.loads(stdin)` / `json.dumps(...ensure_ascii=False,sort_keys=True)` → file_write。每次 dispatch 检查实写父文件已为对应 started。
- 首次暂停 Python 时 native 次数 0、无输出文件；批准 Python 后 native 次数 1、真实 status=completed / exit_code=0，写文件再次独立 pending；第二次批准才创建文件。批准名单恰为 Python 和 file_write。
- 最终内容精确 `{"a": 1, "z": ["你好", "😀"], "中文": "值"}\n`，输入原文保留，最终 saved_path=output.json；三个步骤各执行一次。终态再次 advance 保持 native 次数 1、三个 dispatch 不增加。
- 执行：在 `harmony/chat` 下 `node node_modules/tsx/dist/cli.mjs --test src/test/recipe_native_chain.test.ts`，1/1 PASS。仓库根首次直接调用缺少 tsconfig alias 导致 package resolution 失败，改为既有 chat 工作目录后成功；这不是 native/产品失败。

此证据证明本机 Node FS + 现有 CPython addon 的真实组合链，不等同 HarmonyOS 设备或平台 adapter 验收。
