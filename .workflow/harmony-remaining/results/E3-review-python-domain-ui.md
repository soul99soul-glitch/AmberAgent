# E3 独立审查：Python 领域、策略与 UI

2026-09-28。已读 harmony-arkts skill、E3-contract.md 和 Python domain 报告。仅只读产品源码/专项测试，独占本报告；未改产品、构建、签名或操作设备。范围为 chat/python、Python tests、root 的 e3_tool_policy / Activity / policy / profile filter、Python singleton/background、NativePythonTransport / PythonPage 的消费链。CPython 真实执行、隔离和 native 取消由 native reviewer / root 验证，领域 fake port 不证明这些能力。

## 当前结论

本审查 Python 子范围 A/B/C 最终 PASS，无未修复确认缺陷。两张必要最终图已独立目测：亮色本机 JSON/stdin/Unicode 执行完成、暗色真实后台返回后中断，状态和对应结果一致，未发现错位、重叠或水平越界。PythonPage 路由、CMake Python static target、NAPI RegisterPython 已接入；root 报告最终 Debug/Release 构建成功及设备一次11项真实验收通过。构建/原生停止与隔离仍分别依 root/native gate，本报告不宣称独立执行这些检查，也不代替 E3 Mosh 子范围结论。

## 确认发现与闭合证据

| 发现 | 明确触发与影响 | 状态 |
| --- | --- | --- |
| P2：历史 Python 超时/取消/中断被显示成功 | 原 tool_activity 的通用 indicatesFailure 只识别 failed/error/denied、非零 exit_code；Python timed_out/cancelled/interrupted 的 exit_code=null 且只带 error_code，落盘重读后走 succeeded。ChatMessageTools 复用同一 helper，因此影响审批执行后结果卡与历史 Activity。 | root 已在 `tool_activity.ts:449–453` 仅按 Python 真实 status 映射：timed_out→failed，cancelled/interrupted→cancelled。独立实际 tool→registry→conversation→derive 专项与正式 E3 policy 测试通过。此项与 domain owner 发现相同，不重复归责。 |
| P2：历史预览显示整份 JSON，较大结果丢状态 | domain 去掉重复 output 后，通用尾部 helper 回退 raw JSON；原通用 JSON parse 限制也小于 Python 128KiB output 的转义预算。 | root 已仅为 Python 提高 parse limit 到 128KiB×6+2048，并组合 stdout/stderr 取尾，`tool_activity.ts:423–427,477–480`。100000字符失败结果、普通 stdout/stderr、真实注册工具 128KiB 全控制字符 envelope 均通过。未扩大其它工具预算。 |
| P2：stdin 指引使用了禁止的 sys | 原 PythonPage:118 指示 `sys.stdin.read()`；打包 helper `amber_python.py:18–20,38–46` 禁止 sys 导入/反射，用户照提示会被 RejectedSource 拒绝。实际 helper:83/98 提供 StringIO 支持 builtin input() 和全局 stdin 字符串。 | UI owner 已把提示改为“代码通过 stdin 字符串或 input() 读取输入”，`PythonPage.ets:114` 已独立复核；不新增 sys 权限。最终暗色图中完整提示可辨，亮色图用 stdin JSON 真实执行成功，C关闭。 |
| P2：1600字符 Activity 尾从 emoji 中间截断 | 真实可执行输出 `print('😀' + 'x' * 1599, end='')` 的 UTF16 长1601。Python tools 把 full output 交共享 complete/cancel，及 Python 历史分支的 slice(-1600)，会留下首字符0xDE00孤立低代理项；UI/复制出现替代字符。TaskStore 自身已用 terminalTail，没有此问题。 | domain owner与root已只在Python live/history分支复用既有terminalTail；完整工具stdout/stderr不变，共享Store未改。独立原脚本从5绿1红→6/6绿；正式新completed/failed/cancelled三项与root历史emoji回归通过，问题关闭。 |

## A：领域逻辑

- `python/runtime.ts:30–44` 在第一次 await 前校验 source/stdin 的 UTF8 bytes、整数1–60000ms timeout，并复制批准代码/输入/timeout；Map/controller/done 同步登记，后续 caller mutation 不换执行内容。resourceRoot 不在领域/Agent schema 中。
- `runtime.ts:46–60` TaskStore QUEUED 注册真实 cancel 回调，RUNNING 更新前后分别检查 abort。注册等待中的后台中断不会进入 native；没有“Promise超时后实际继续运行”的伪取消。
- `runtime.ts:66–91` 保留 completed/failed/timed_out/cancelled 实际结果；background 的 cancelled 映射 INTERRUPTED/background_interrupted，completed 赢取消则保持完成。cancel 和 interrupt 都等同一 done，全部 live run 会中断；finally 解除 listener、移除 active 并 finish。TaskStore terminal 更新禁取消并注销回调，既有 Store.cancel 再读 owner 终态不会覆盖完成/中断。
- TaskStore spec 仅 timeout，未写 source/stdin；输出 summary 使用两个1600字符 tail，native 任意 reject 文本不进入错误诊断。完整结果保留 stdout/stderr 和真实 nullable exitCode。是否实际停止字节码、是否隔离模块和限制128KiB输出，均属于 native 证据。
- `python/tools.ts:25–52` 独立 python_execute 只有 code/stdin/timeout_ms；缺省stdin为空、timeout15000，needsApproval=true / allowsAutoApproval=false，错误结果仍标明 embedded_python。Activity 与最终 envelope 不混为一套状态：live cancel/failed/completed 正确，旧 activityId 不能覆盖新槽。Envelope 只序列化 stdout/stderr 一份，最大控制字符 JSON 在策略预算内。

## B：真实消费接线

- `AppContainer.ets:472–481` PythonRuntime Promise singleton；adapter 先部署固定资源，随后复用唯一 AgentTaskStore，失败可重新初始化。`1556` 仅 assistant.python 开关启用依赖；ChatPage:3711–3713 在同门下创建工具，随后沿既有 profile→registry→conversation scope→dispatcher / tool loop 审批链，未借 SSH 默认主机、cwd、target guard。
- `tool_policy.ts:187,300,322,377` 分类 python、明确 sensitive、非并行、输出预算128KiB×6+2048；coding category包含python，workspace_read/minimal不暴露它。实际注册工具在默认与仅普通autoApprove下首轮均 ask，原工具 execute 尚未调用；approved 后才 allow。用户已有全自动/本轮信任语义沿原 resolver，没有新审批例外。
- Activity 注册/title/inputPreview/default runtime 均覆盖 python_execute；ChatMessageTools 的成功/失败胶囊消费共享 toolActivityStatus，历史状态修复可到达真实卡片。审批注册/策略/结果消费有上述真实领域调用链专项证据；本次限定两张手动Python页C，不声称外部模型首轮pending→批准已独立设备验收。
- `NativePythonTransport.ets:12–35` resourceRoot固定在应用私有目录，只部署两个包内文件，以短写循环→close→rename完成写入。没有 Agent 入参文件路径或用户source落地；部署/设备fileIo能力由root最终统一构建与实际本机执行gate证明，不由fake port代替。
- `NativePythonTransport.ets:46–67` native调用前检查signal/background，保持abort listener至 pythonExecute真实return，再finally移除；native.pythonCancel只发控制请求，不本地提前settle。platform declaration符合合同options/result，`napi_init.cpp:180`已调用RegisterPython，CMake已包含两个Python cpp并链接amber_python静态库。是否实际ELF/NAPI执行与隔离属于root/native gate，本次没有运行 native addon。
- `AppContainer.ets:499–507` background只中断已初始化的SSH/Python/Mosh，不因进入后台创建运行时。EntryAbility先置appBackgrounded再调用共享中断；adapter前置foreground检查拦下迟到调用。PythonPage同singleton、页面退出abort自身操作；结果仅alive时回写，取消中保持executing到实际Promise返回，不自动重跑。
- `PythonPage.ets` 全V1，Builder直接读取source/stdin/result/state，CardGroup只调用Builder；亮暗palette由StorageProp Watch更新。页内timeout校验、执行禁输入、44vp运行/取消按钮、Scroll TopStart、底部navBar inset存在。最终两张图用于下列对应状态的实际布局结论，未扩IME/横竖屏/其它UI矩阵。
- SettingSandbox的本机Python入口及 main_pages PythonPage路由已接；assistant本机Python能力开关独立于terminal。中途route未注册已完成补齐，不留为当前缺陷。

## 独立执行证据

- 首轮 Python runtime/tools/E3 policy：22/22 PASS。
- 更新后的 Python + E3 policy + Activity + registry + permission + profile filter：最终91/91 PASS，`/tmp/e3-review-python-domain-policy-tests.log`；包含root历史状态/较大JSON/emoji消费回归、domain控制字符预算及completed/failed/cancelled尾部回归。
- `/tmp/e3-review-python-consumption.mts` 执行实际 domain runtime/tool/registry/conversation/derive，native只提供已知结果：超时/取消状态、stdout/stderr历史预览、最大128KiB控制字符envelope、实际首轮审批/coding过滤5/5 PASS。新增孤立低代理项case后5绿1红，精准回喂后最终6/6 PASS，native envelope仍完整。
- 本reviewer未改产品、全域类型/build/lint/签名，也未操作设备。各owner类型/lint结果保留在原报告；早期native/CMake实施时曾有暂缺Mosh文件，未将其误报为已完成bug，不再作为当前待验状态。

## C 最终结论与边界

仅查看root指定的两张必要最终图，未操作设备、运行测试/构建或扩UI矩阵。

- `/tmp/e3-python-light.jpeg`：本机Python标题与返回按钮分开，CPython 3.14.7版本清晰；代码为 `import json` / `print(json.loads(stdin)['message'])`，stdin JSON含中文与emoji，stdout准确展示“本机 Python 中😀”。执行状态和结果卡均“执行完成”，exitCode为0。代码/stdin/timeout字段、运行按钮、结果标题与stdout有一致内边距，均处父卡内；左右gutter清楚，未见错位、重叠或水平裁切。
- `/tmp/e3-python-dark-background.jpeg`：root真实运行无限字节码循环→Home后台→返回；页内状态及结果卡均“执行已中断”，exitCode显示“—”，保留唯一 `background-start` 输出、运行按钮恢复。没有显示完成/0退出或新重发输出。暗色文字、代码、完整修后stdin提示、timeout、按钮及结果有可辨对比，卡片与输入区边界/内边距一致，未见错位或水平裁切。结果卡下方仍处同一Scroll的后续内容，当前视口边缘不作为整卡被截断的证据。
- root报告最终Debug/Release构建和设备一次11项真实验收PASS；这些是root操作证据，不由领域fake port或本reviewer截图操作替代。native实际停止、隔离、输出上限/限制等由native/root验收单独承担；此处没有扩测或把E2终端图复用为Python。

本Python范围C关闭，A/B既有通过结论保留，没有遗留“等亮暗图”或“stdin指引待验”项。
