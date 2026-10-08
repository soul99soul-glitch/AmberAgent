# Standards 轴审查

审查基线：`HEAD 5ea51f7a`，当前工作树的 Harmony 已跟踪改动及未跟踪源码。此次只读源码；仅编写此报告。标准来源：用户提供的行为规范、仓库 `AGENTS.md`、`.editorconfig`、`.agents/skills/harmony-arkts/SKILL.md`。编辑器/编译器可以强制的格式和类型事项不重复冒充业务缺陷。

## 已确认的功能缺陷

此轴未独立确认可达且影响用户的新增功能缺陷。这个结论仅涵盖下面实际核查的项目，不代表整体代码无 bug，也不替代其他领域审查和构建/设备验证。

## 硬标准核查

| 标准 | 一次检查与二次核查 | 结论 |
|---|---|---|
| 全部 V1，禁止 V1/V2 混用 | 对全部 WIP `.ets` 查 `@ObservedV2/@Trace/@Local/@Param/@ComponentV2`；阅读新增原生 Markdown/数学组件 | 未发现混用 |
| 主题真实消费与页面重挂载 | 全部改动 `@Entry` 页面订阅 `themeEpoch`，并存在 `ForEach([this.themeEpoch], ...)`；复核 `MathRichBlock` 的 no-op opacity 在父页重挂载下会重建 | 页面机制保留；不把组件 no-op 单独判为主题故障 |
| Builder 参数不能在普通 lambda 裸构造 | 逐个阅读改动中的 `content`、timeline `rows/activeTail/hostTail` 参数；均委托 `@Builder` 方法 | 未发现裸构造回归 |
| @State 数组避免依靠原地变更刷新 | 扫描改动 `.ets` 的 @State/@Prop/@Link 数组及其 push/splice/sort/pop/shift/unshift，复核正文组件非状态几何缓存 | 未发现匹配到的状态数组原地更新；普通绘制缓存不属于 UI 状态违反 |
| API12 基线 | 使用安装 SDK 的官方声明复核新原生文本、PDF 提取和 VSync；见下表 | 检查到的高版本功能有版本/capability 或动态符号守卫 |
| 新逻辑对应测试 | WIP 同时有领域行为测试与部分平台测试；纯源码正则测试不能证明设备运行正确 | 验证强度限制，应由主执行阶段补足实际构建和设备证据 |

## 二次复核排除的候选

| 候选 | 具体证据 | 判定 |
|---|---|---|
| `NativeMarkdownFadeFrame extends FrameCallback` 在 API12 加载失败 | SDK `@ohos.arkui.UIContext.d.ts:3444-3452` 明确 FrameCallback since12；postFrameCallback 同文件 since12 | 假阳性 |
| `DrawModifier`、`.drawModifier()`、`BlendApplyType` 无高版本保护 | SDK `component/common.d.ts` 对这些 API 标明 since12；`NativeMarkdownText.ets:24-26` 对高级 range fade 限制 sdk>=14；真正的 range 获取发生在 enabled 分支 | 假阳性 |
| PDF `getGraphicsObjects()` 高于目标 API | SDK `@hms.officeservice.pdfservice.d.ts:1942-1949` 标明 since5.0.0(12)，调用前已检查 PDF syscap | 假阳性 |
| 新 C++ VSync API20 静态链接使整个 native 模块无法在 API12 装载 | `stream_frame_rate.cpp:40-43` 用 dlsym 查找 SetExpectedFrameRateRange；直接链接的 Create/RequestFrame/Destroy/GetPeriod 官方头文件分别 since9/9/9/10；ETS `StreamingFrameRate.ets:13` 还有 sdk>=20 与 syscap 检查 | 假阳性 |
| `PENDING_PROVIDER` 是禁用的 export let 导致 UI 不更新 | `ChatProviderDetailPage.ets:46` 是已有临时导航传参，aboutToAppear 读取后清空；不是被 UI 直接消费的可变主题/状态，本次也未新增该设计 | By design，不能因关键词命中当功能 bug |
| Markdown/native 绘制缓存用了 push | `NativeMarkdownText.ets` 的几何缓存、局部 runs/styles 都是普通缓存；绘制明确 invalidate，渲染通过 TextController 发布 | By design |
| 分享后 cache 文件不立即删除 | `ChatExportFiles` 和 `DeepReadExportFiles` 的 show 只是面板打开，接收方还需读取；立即删除反而破坏正常分享 | By design，不能为“清理”制造回归 |

本地 SDK 官方源码位置：`/Applications/DevEco-Studio.app/Contents/sdk/default/{openharmony,hms}/`。仅核查本次涉及的 API，不声称完成整个仓库 API 兼容性扫描。

## 维护性建议（不进入功能 bug 修复队列）

用户明确要求单文件通常少于1000行、1500行为常规极限。本次有继续膨胀的文件，但尺寸本身不能证明运行故障，也没有理由在修 bug 时大范围机械拆分。

| 文件 | HEAD 行数 → WIP 行数 | 判断 |
|---|---:|---|
| `harmony/deepread/src/main/ets/novel/creation.ts` | 1489 → 1680 | 新越过常规极限。以后扩展 creation 时，可按确定职责单独机械拆分，并先后验证无行为变化 |
| `harmony/deepread/src/main/ets/novel/repository.ts` | 2733 → 3035 | 既有大型领域边界继续增长。不能为了消除行数把持久化事务拆散 |
| `harmony/entry/src/main/ets/pages/ChatPage.ets` | 4539 → 4977 | 页面状态协调仍庞大；本 WIP 已提取 recap/archive/native render 等模块，整体改善应围绕真实变化边界 |
| `harmony/entry/src/main/ets/pages/NovelWorkspacePage.ets` | 2976 → 3586 | 多 pane 与操作状态仍集中；后续适合提取独立 pane，但此次真实缺陷修复优先 |
| `harmony/entry/src/main/ets/di/AppContainer.ets` | 1904 → 1904 | 没有尺寸增长；依赖装配根天然聚合服务，不据此追加抽象 |

对 Mysterious Name、Duplicated Code、Feature Envy、Data Clumps、Primitive Obsession、Repeated Switches、Shotgun Surgery、Divergent Change、Speculative Generality、Message Chains、Middle Man、Refused Bequest 的判断仅作为导航：未找到需要立即改动的、证据充分的标准违反。没有为追求“问题数量”列泛化重构、额外兜底、重试层或删除死代码。

## 修复阶段建议

此轴不新增独立代码修复 phase。对其他轴二次确认的真实缺陷做最小修改，并在每个 phase 独立 review 时保持以上状态管理/API/Builder/lifecycle 边界。维护性拆分放在单独无行为变化任务，避免混入功能修复和扩大合并风险。

## Phase 4 设备反证修正

UI-11：原 PENDING_PROVIDER 排除仅说明它不是 UI 直接订阅的主题状态，不能推导真实路由传递正确。已有 onPickPreset → setPendingProvider → Detail isNew 路径在 ArkTS 模拟器实际日志出现写 pending=true、路由参数=1、读取 pending=false，中间无清空调用。该设备证据确证新增 Provider 暂存传递问题。与 UI-10 Codex 旧 gate 是不同根因；不按 export let 关键词改动其他主题机制。修复与二次设备验证见 Phase 4 provider entry 报告。
