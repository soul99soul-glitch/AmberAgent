# 鸿蒙聊天渲染性能：通用策略与 API 边界

研究日期：2026-10-25。已核对当前工作区代码、本地官方文档与官方在线资料。本轮按用户追加要求，将流式展开的思考展示窗口从 6000 字改为 1500 字；其余策略处于研究阶段，没有采集真机性能数据或验证优化收益。

## 当前代码已经做了什么

| 路径 | 已有机制 | 仍可减少的工作 |
| --- | --- | --- |
| 流式发布 | `chat_turn.ts:260-331`、`stream_snapshot_gate.ts`：默认 48ms 合并发布，窗口内不逐 chunk 生成快照，结束时排空 | 应保留一处主发布节奏；优先减轻每次发布的工作，不再叠加多层延迟 |
| 思考正文 | `reasoning_display.ts:22-24`：本轮改为流式展开保留末尾 1500 字；完成后显式重新展开上限仍为 18000 字 | 窗口约束的是展示，不会删除消息原文；字符计数沿用现有 JS 字符串长度语义 |
| 思考旧块 | `ChatMessageReasoning.ets:152-210`：原文 offset 稳定 key、Observed slot 与 ObjectLink，通常只更新首个裁切块和增长尾块 | 每次仍对完整原文 split 并从头分块；收起正文时这段计算也仍运行，应改为追加游标并在收起时暂停正文投影 |
| Markdown | `markdown_cache.ts:245-293`、`MarkdownText.ets:94-114`：稳定 AST 前缀复用、旧 slot 不重复写入 | 全量块数组遍历与增长代码块的重新高亮仍存在；巨型开放段落/围栏仍有排版成本 |
| 文字动效 | `NativeMarkdownText.ets:189-215`：内容改变才 setStyledString，淡入帧只使绘制失效 | 应保留绘制与布局隔离；增量富文本更新不等于底层只测量新增字符 |
| 会话历史 | `ChatTimelineSession.ets:9-10,91-198`：初次尾页 50 节点、按需向前取页并保存阅读锚点 | `ChatTimelineView.ets:129` 仍为 List + ForEach；取过的旧行逐渐积累，没有真正的有限双向渲染窗口 |
| 重预览 | MiniApp、VisualArtifactPreview 已按可见性暂停部分动画和 bridge | 暂停不等于销毁 Web/DOM/GPU 资源；远处组件仍需按内存预算决定是否卸载 |
| 图片 | `MarkdownText.ets:164-168,231-243`：消息出现过一次后开始预取 | 当前是整条消息的图片预取，不是段落附近预取；巨长消息可能提前加载很远的图片 |

还确认了一处无需复杂虚拟化即可处理的重复工作：`ConversationRunService.ets:187-195` 每次快照执行 `contextCompacts.slice()`，而 `ChatModelContextSession.ets:194-236` 用该数组身份判断上下文 token 缓存是否命中。对同一会话、同一模型重复发布 20 次，以现有 fixture 执行真实状态 owner、仅在 native 边界计次数：复用数组时 native 计数与估算各 1 次；每次 slice 时各 20 次。这证明缓存失效导致重复调用，尚未证明耗时占比。建议保留不可变身份或使用明确 revision，而非加一层通用缓存框架。

## 推荐落地次序

1. **先消除无关计算。** 修复上下文统计缓存失效；思考分块只处理新增内容和活动尾，收起时只更新标题/时长。把全文签名、重复 rows.find、每帧结构计数从热点移走前，先确认调用频率。
2. **再完成历史消息的按需创建。** 在现有分页与稳定 ID 基础上使用 API 12 可用的 LazyForEach，配合有限邻近缓存；大量上翻后保留有限双向 UI 窗口，全文数据独立保存。
3. **处理单条巨型消息内部。** 消息级虚拟化不能解决一个巨大 ListItem 内的所有段落；需要稳定逻辑块、尾部可变块、近视口渲染块，卸载远处块时保留高度和阅读锚点。
4. **单独收敛重组件。** 增长代码块复用稳定行高亮；表格避免随每次 token 改整表列宽；公式/图表按安全边界发布；图片附近预取并约束解码尺寸；远处 Web/图表按资源预算卸载。
5. **最后按测量决定后台解析。** 若解析仍占用主线程，再采用单个在途任务 + 最新待处理版本；返回结果校验 conversation/run/message revision。过时显示任务可合并，协议事件、工具审批、错误和最终原文不能丢。

“冻结”首先指数据、AST、布局身份稳定，而不是把历史文字截图。可见活动块正常更新，邻近块缓存静态布局，远处块释放视图并保留可恢复状态；复制、导出、搜索与模型上下文始终读取完整原文。正文块的安全冻结需遵循下文实际语法边界。字体、宽度等影响布局的参数改变时，高度/布局缓存必须失效。

## 建议怎样分层优化

应把“少更新”“少解析”“少创建节点”“少布局绘制”“少保留资源”分开衡量。只隐藏视口外内容，不能证明这些工作都已消失。

| 层次 | 推荐策略 | 收益与代价 |
| --- | --- | --- |
| UI 发布 | 合并高频流式更新，保留最新待发布快照；结束、错误、审批等语义状态及时提交 | 减少状态投影、序列化和 UI 更新次数；批量间隔需要结合输出延迟实测，不能简单越长越好 |
| 长文本解析 | 复用已稳定块的 AST/行内结果，重算开放容器和活跃尾部 | 避免每次从头解析；必须处理 Markdown 的跨块依赖，不能按固定字符数把任意片段独立解析 |
| UI 依赖 | 已完成块只绑定自己的数据；新 token 不应更新所有旧块的版本或 key | 把刷新范围限制到活跃内容；字体、主题、宽度变化仍要正确失效 |
| 消息列表 | `List + LazyForEach`，稳定的业务 ID，精确的数据增删改通知 | 仅按需创建列表节点；不是把 `ForEach` 放进 `List` 就等价于创建阶段虚拟化。[官方 LazyForEach 指南](https://github.com/openharmony/docs/blob/master/en/application-dev/ui/rendering-control/arkts-rendering-control-lazyforeach.md) |
| 单条超长消息 | 将逻辑消息按段落/代码块等划分为可复用渲染块，必要时对块做窗口化 | 消息级懒加载无法解决“一条消息内部已有几万字”的全部成本；窗口化必须保留全文模型、滚动锚点和选择/复制语义，这是工程推导 |
| 视口附近预取 | 可见范围外保留有限缓冲，远处回收；缓存数量随块大小和设备测量调整 | 缓冲太小可能快速滑动露白，太大增加内存与预布局开销；不要照抄某个固定 cachedCount。[官方 List 参考](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-arkui/arkui-ts/ts-container-list.md#cachedcount) |
| 昂贵子组件 | 视口附近才创建 Web/图表/高亮/大图内容；离远时停动画、取消订阅或释放资源 | 节点虚拟化不会自动保证所有业务计时器和后台订阅停下；按组件生命周期管理，需要单独验证 |
| 后台计算 | 只有确定占用 UI 线程的重解析才移到 TaskPool/Worker；约束在途任务和返回频率 | 后台计算有调度与序列化成本；大量过时任务和密集回调仍会堵塞主线程。[官方 TaskPool](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-arkts/js-apis-taskpool.md) |

“冻结旧内容”推荐首先理解为：旧块数据、解析结果与组件身份稳定，新增内容只流入尾部。它不必等于截图成位图；位图冻结还会引入文字选择、无障碍、内存和缩放清晰度问题。此为设计取舍，尚未判断项目是否需要。

## 鸿蒙 API 12 可以使用什么

本项目技能规定兼容基线为 API 12、状态管理保留 V1。已通过本地官方文档 `docs search/read` 与 OpenHarmony 官方仓库交叉核对以下边界：

| 能力 | 版本及约束 | 对本项目的含义 |
| --- | --- | --- |
| `LazyForEach` | API 7 起；与支持懒加载的滚动容器配合 | 可作为基础方案；数据源必须给出唯一且稳定的 key，并保证通知与数据更新一致 |
| `List.cachedCount(number)` | 基础重载在 API 12 可用 | 控制邻近缓冲；非懒加载仍会创建全部项，不能用它替代懒加载 |
| `cachedCount(count, show)` | API 14 起 | 不能直接作为 API 12 默认路径 |
| `CacheCountInfo` 缓存上下限 | API 22 起 | 属可选高版本能力，不是基础实现前提 |
| `maintainVisibleContentPosition(true)` | API 12 起；针对 LazyForEach 在视口外增删 | 不保证 ForEach 更新、全量 reload 或单个消息内部变高时锚点不跳，需要自己的跟随/锚点策略 |
| `@Reusable`（V1） | API 10 起 | 可减少节点反复创建销毁；应先证实这部分成本，并在复用生命周期重置旧行状态。[官方 V1 复用指南](https://github.com/openharmony/docs/blob/master/en/application-dev/ui/state-management/arkts-reusable.md) |
| `@Component({ freezeWhenInactive: true })` | API 11 起，V1 可用 | 可冻结非激活组件的状态刷新；active/inactive 不等于任意几何不可见，也不代表业务任务自动暂停 |
| V1 组合冻结 | API 17 及以下，对 Tabs/路由/LazyForEach 的组合存在激活范围限制，API 18 改善 | 不能仅在新真机上看到生效就宣称 API 12 组合场景成立 |
| `LazyForEachOptions`、`customComponentFreezeMode`、资源释放/内存策略 | 26.0.0 起 | 与上面的 API 11 V1 冻结不是同一个入口；不能用于 API 12 基础方案 |
| `TaskPool` 基础能力 | API 9 起；并发函数需满足 `@Concurrent` 和序列化约束 | 可研究纯解析工作的卸载；不能把 ArkUI 组件、装饰后的 UI 状态对象直接传进工作线程 |

List 的缓存重载与锚点限制见[官方 List 参考](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-arkui/arkui-ts/ts-container-list.md)。V1 冻结的支持场景、组合限制和恢复刷新行为见[官方冻结指南](https://github.com/openharmony/docs/blob/master/en/application-dev/ui/state-management/arkts-custom-components-freeze.md)。上述日期核对的是文档能力，不代表本项目已接入或已通过 API 12 设备验收。

V1 状态优化应限制依赖传播，不能通过漏掉必要通知来制造“没有重绘”的假象。需要验证深层对象更新是否能被正确观察；具体观察边界见[官方 @State 指南](https://github.com/openharmony/docs/blob/master/en/application-dev/ui/state-management/arkts-state.md)。

## Markdown 旧块能否永久冻结

不能仅凭“已经出现空行”判断整段最终稳定。CommonMark 0.31.2 至少有以下反例：

- 先收到 `[x]`，再追加 `\n\n[x]: /url`，前面的普通文本会变为引用链接；定义可后置并作用于整个文档。[引用定义](https://spec.commonmark.org/0.31.2/#link-reference-definitions)
- `标题\n` 后追加 `---\n`，最后一个段落会改为 Setext 标题。[Setext 标题](https://spec.commonmark.org/0.31.2/#setext-headings)
- 后续列表项或空行可能使整组列表从紧凑变为松散，影响较早的列表项布局。[列表](https://spec.commonmark.org/0.31.2/#lists)
- 未闭合代码围栏可跨任意数量的空行继续增长，固定字符切片会丢失围栏上下文。[围栏代码](https://spec.commonmark.org/0.31.2/#fenced-code-blocks)

据此推导：可以复用稳定块、保留开放容器状态、单独维护引用依赖，并在结束时做一次有界或完整校正；需要按项目实际支持的语法决定失效范围。全文复制、搜索和导出应读取完整数据模型，不能读取屏幕窗口。这些是规范边界下的实现建议，不表示 Amber 的解析器实现了全部 CommonMark 语法。

## 实施前最小测量

用同一设备、同一回放输入，分别覆盖：普通短消息、连续超长思考、一个巨大段落、代码/表格混排、上滑读历史时继续生成、回到底部、结束切静态态。优先观察以下信号：

1. 每次追加后解析了多少字符、多少旧块；更新成本是否随历史总长度持续上升。
2. 常驻组件数、布局节点数、内存是否随全文无限增长；远处 Web/动画是否仍在活动。
3. UI 线程 build/layout/paint 耗时与帧间隔，结合设备实际刷新率；60Hz 的整帧预算约 16.7ms，120Hz 约 8.3ms，不能把全部预算分配给解析。
4. 网络数据到可见文字的延迟、积压长度、结束前后的丢字/重排、用户阅读锚点偏移。
5. 将窗口大小、发布节奏、缓存条数作为少量可测参数；每次只改变一个方向，收益确认后停止扩张。

建议先消除重复全量工作和无关状态更新，再按瓶颈引入更细粒度窗口或后台解析。不要把“所有功能都变成后台任务”或“cachedCount 越大越好”作为通用结论。

## 本地官方文档复查入口

命令入口为 `harmony/scripts/devecocli.sh docs read <docID>`。本次读取的关键 docID：

- `API参考/ArkUI_方舟UI框架/ArkTS组件/状态管理与渲染控制/LazyForEach/ts-rendering-control-lazyforeach`
- `API参考/ArkUI_方舟UI框架/ArkTS组件/滚动与滑动/List/ts-container-list`
- `API参考/ArkUI_方舟UI框架/ArkTS组件/自定义组件/自定义组件参数/ts-custom-component-parameter`
- `开发指南/ArkUI_方舟UI框架/UI开发_ArkTS声明式开发范式/学习UI范式基本语法/自定义组件/自定义组件冻结/自定义组件冻结功能_V1/arkts-custom-components-freeze`
- `开发指南/ArkUI_方舟UI框架/UI开发_ArkTS声明式开发范式/学习UI范式基本语法/自定义组件/自定义组件复用/Reusable装饰器_V1组件复用/arkts-reusable`
- `最佳实践/性能场景优化案例/界面渲染性能优化/长列表加载丢帧优化/bpta-best-practices-long-list`
- `开发指南/ArkTS_方舟编程语言/ArkTS并发/多线程并发/TaskPool使用规范/task-pool-usage-guidelines`

## 最值得保留的原始链接

1. [List API：缓存与保持可见内容位置](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-arkui/arkui-ts/ts-container-list.md)
2. [LazyForEach：按需创建与更新规则](https://github.com/openharmony/docs/blob/master/en/application-dev/ui/rendering-control/arkts-rendering-control-lazyforeach.md)
3. [V1 组件冻结：场景与版本边界](https://github.com/openharmony/docs/blob/master/en/application-dev/ui/state-management/arkts-custom-components-freeze.md)
4. [TaskPool API：后台任务与传递约束](https://github.com/openharmony/docs/blob/master/en/application-dev/reference/apis-arkts/js-apis-taskpool.md)
5. [CommonMark 解析策略：块结构与行内依赖](https://spec.commonmark.org/0.31.2/#appendix-a-parsing-strategy)
