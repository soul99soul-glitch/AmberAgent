# 灵动岛重设计方案：安静的星核

状态：设计提案（未实施）
日期：2026-07-26
范围：会话顶部活动岛 `ChatActivityIslandView` 与系统 Live Activity（`ActivityWidget`）共享状态语言；两者按各自空间重新排版。

## 1. 现状审计

涉及文件：

- `iosApp/iosApp/ChatActivityIslandView.swift`：岛本体（玻璃、翻转过渡、orb、光晕、软光场）。
- `iosApp/iosApp/ChatView.swift` `topIslandState`：六态映射与文案（等待/思考/生成/工具/图片/标题）。
- `iosApp/ActivityWidget/AmberAgentActivityWidget.swift`：锁屏与真灵动岛 compact/expanded/minimal。

现状问题（按层列出）：

1. **运动系统过多且互相竞争**：一次状态切换同时存在 3D 翻转过渡（72°）、orb 永动、旋转 halo、SoftField 染色光斑四套运动。层级职责不清，读起来「忙」而不是「活」。
2. **状态切换的几何不连续**：idle（34pt）与 active（42pt）之间靠翻转内容替换掩盖形变，是「变魔术」而非液态玻璃的 morph。
3. **色彩语言发散**：六种 tint（neutral/accent/amber/cyan/green/red/indigo）各自为政，AI 工作状态与工具语义色混用同一套通道，用户无法从颜色读出「谁在动」。
4. **流式期间标题是静态的**：生成中标题「生成回复」没有任何与「正在产出」对应的视觉信号，缺少 Apple Intelligence 的生成微光（glint）语言。
5. **文案部分泄露内部机制**：「整理图片上下文」是工程语言；「等待首个响应」是协议语言。工具 detail 直接截断 22 字符，可能切出半截无意义文本。
6. **星核只用了一小半**：`ThinkingOrbEngine` 六态只上岗三态（waiting/thinking/generating），`searching`（globe 扫描球）、`solving`（rubik 魔方）、`shaping`（morph 塑形）与 64pt `.large` 预设全部闲置；工具态退回静态 SF Symbol + halo，orb 只是 24pt 角落装饰，与它周围的光之间没有任何光学关系。

## 2. 参照系：iOS 27 Siri 的灵动岛语法

来自 2026 年 5–6 月 Bloomberg 渲染图与 WWDC26 公开资料（MacRumors / 9to5Mac / Macworld 转载）：

- **岛即 Siri**：iOS 27 中 Siri 唤醒时以一枚发光药丸包裹并「吃掉」原灵动岛挖孔（"Siri Uses Pill Shape to Hide the Dynamic Island"，MacRumors 2026-06-16）。岛不再是容器，岛本身就是状态。
- **辉光三层结构**：沿用 Apple Intelligence 的 glow 语法——紧贴边缘的亮核（tight core）、中等模糊的柔光（bloom）、大半径低透明度的环境晕染（wash），锥形渐变缓慢对流旋转；倾听/思考/回应仅改变转速与呼吸，不改变结构。
- **生成微光（glint）**：Writing Tools 与 AI 产出文本时，文字表面有低对比度的高光扫过，表示「正在生成」，不使用 spinner；生成停止微光即停。
- **退让原则（deference）**：AI 的指示物保持安静，终态不庆祝，安静退场；失败用克制的语义色而非动画。

## 3. 设计概念：安静的星核

一句话：**岛是一颗安静悬浮的星核——玻璃是壳，辉光是态，文字微光是产出。**

每层至多一个运动系统，职责唯一：

| 层 | 元素 | 运动 | 表达 |
| --- | --- | --- | --- |
| 核 | ThinkingOrb 六态（20–24pt，灰阶墨点） | 点阵形变（已有引擎） | AI 与工具的全部内部状态 |
| 壳 | 可选胶囊边缘辉光 | 锥形渐变对流 + 呼吸 | 谁在动：AI（光谱）/ 工具（单 hue）/ 终态（静态语义色） |
| 文 | 标题文字 | 生成微光扫过 | 正在产出 |

退场原则：回到 idle 时全岛（含辉光）0.4s 淡出、orb 冻结静帧（settle），不做任何「完成庆祝」动画。

**色彩分层纪律**：orb 永远是灰阶墨点（dark mode 自动反相，引擎内建），全岛的颜色只属于边缘辉光。核不着色，光不带墨。

## 4. 视觉规范

### 4.1 玻璃

- 保持 `.glassEffect(.regular, in: Capsule())` 单层玻璃，辉光层作为玻璃**背后**的 underlay 渲染，让光从玻璃边缘析出；不在玻璃上再叠 tint 层，不做 glass-on-glass。
- iOS 26 以下回退 `ultraThinMaterial + 0.5pt 描边` 不变，辉光照常作为 underlay。
- 尺寸收敛：idle 34pt / active 40pt 固定高度，流式期间高度永不弹簧（遵守 taste gate：token 流不驱动高度动画）。

### 4.2 辉光（Edge Glow）

真机截图复核后，边缘辉光定位为**可选增强且默认关闭**。设置入口位于
「显示与字体 → 活动状态 → 彩色边缘辉光」；关闭时不挂载辉光 renderer，状态表达必须仍由 orb、文案与标题微光独立闭环。

三层锥形渐变，全部在一个自绘 `Canvas`/layer 内完成，≤30fps：

| 层 | 参数 | 透明度 |
| --- | --- | --- |
| core | 1pt 描边，贴胶囊内缘 | 0.85 |
| bloom | 4pt 描边，blur 6 | 0.45 |
| wash | 10pt 描边，blur 14，外扩 6pt | 0.18 |

转速即状态语言（结构不变）：

| 状态 | core 转速 | 呼吸 |
| --- | --- | --- |
| 等待连接 | 8s/rev（急切） | 无 |
| 思考中 | 14s/rev（沉稳） | 无 |
| 生成中 | 20s/rev | 透明度 0.6↔1.0，2.6s 正弦 |
| 工具执行 | 静止 | 无（单 hue 常亮） |
| 失败/完成 | 静止，0.6s 淡入，2s 后淡出退场 | 无 |

### 4.3 色彩

- **AI 状态**（等待/思考/生成）：光谱对流转色，锚定品牌 hue：amber `#FFB847` → cyan → violet → amber，低饱和循环。全 App 只允许这一处出现光谱色。
- **工具状态**：单一语义 hue 常亮（搜索 cyan、图片 indigo、其余 accent），不旋转。
- **终态**：绿=成功、红=失败，静态边光，永不旋转、永不彩虹。
- idle 无任何边光，纯玻璃。

### 4.4 生成微光（Title Glint）

- 仅等待/思考/生成三态开启：标题文字上覆盖 30% 宽度的高光带，峰值亮度 +18%，2.4s 线性从左扫到右，`PhaseAnimator` 或本地 `TimelineView` 驱动，只重绘文字 mask，不触发岛 body 重算。
- 终态（含工具完成、失败、取消）立即停止，不回扫。
- 与 detail 行不同时开启；detail 永不加微光。

### 4.5 星核运用（ThinkingOrb 六态全上岗）

引擎性能已证（UIKit CADisplayLink 24–30fps、零 SwiftUI 帧成本），现状只用了 3/6 态。新映射：

| 岛状态 | orb 态 | 形态 | 说明 |
| --- | --- | --- | --- |
| 等待连接 | `.listening` | wave 呼吸球 | 现状保留：等待对方回应 |
| 思考中 | `.working` | orbits 轨道粒子 | 现状保留 |
| 生成中 | `.composing` | ribbon 缎带 | 现状保留 |
| 搜索/联网工具 | `.searching` | globe 扫描球 | 新：扫描线语义贴合「正在看世界」 |
| 复杂工具（MCP / ish / 议会） | `.solving` | rubik 魔方 | 新：多步求解语义 |
| 图片生成/识别 | `.shaping` | morph 圆→三角→方 | 新：塑形语义 |
| 等待用户（审批 / ask_user） | orb `paused` 静帧 | 静止 + amber 静态边光 | 新（可选）：核停下来等你，全岛唯一「不动」的 active 态 |

配套规则：

- 岛上 glyph 统一为 orb；SF Symbol 与 tint 底圈从岛上退役，工具语义全部由文案表承担。
- **光源一致性**：辉光锥形渐变的起始相位与 orb 投影 yaw 共用同一时钟基准（`CACurrentMediaTime`），边缘光读起来像从核里溢出——这是「岛即 orb」的关键，也是 Siri 药丸的核心手法。
- **`.large` 主角时刻**：64pt 预设只用于真正需要用户注意的场景（审批等待、全屏图片生成），岛上常态保持 20–24pt；一局会话至多出现一次。
- **真灵动岛复用**：引擎是纯函数，可离线渲染各态静帧序列供 Widget/Live Activity 低 fps 轮换，系统岛与 in-app 岛同一语言（Live Activity 无 CADisplayLink）。

### 4.6 形态过渡

- 退役 72° 3D 翻转。idle↔active 改为几何连续 morph：胶囊宽度/高度插值 + 内容交叉淡化（4pt 垂直偏移），spring `response 0.5 / damping 0.86`。
- 状态间切换（active→active）只做内容交叉淡化 + 辉光色/转速过渡，不改变胶囊几何。

## 5. 文案系统

规则：

1. 标题 = 面向用户的动词短语，≤6 字，不出现「上下文 / 投影 / 管道 / 首个响应」等机制词。
2. 副标题 = 可验证的客观事实（模型名、数量、域名、进度），≤10 字；没有增量信息就为 nil，宁缺毋滥。
3. 全表只使用陈述事实的语气，不安慰、不感叹、不使用省略号以外的标点。

状态文案表（in-app 岛）：

| 状态 | 标题 | 副标题 | 说明 |
| --- | --- | --- | --- |
| idle | 会话标题（截断 14 字） | — | 现状保留 |
| 等待连接 | 连接模型 | 模型显示名（如「Claude Sonnet」） | 替换「等待首个响应」 |
| 思考中 | 思考中 | 推理档位（「深度思考」等） | 现状保留 |
| 生成中 | 生成回复 | — | 现状保留，加 glint |
| 识别图片 | 识别图片 | 「第 n 张，共 m 张」 | 替换「整理图片上下文」 |
| 工具执行 | 工具名（截断 18） | 关键参数：域名 / 查询词 / 文件名（截断 20） | 现状保留，截断处保证词边界 |
| 工具失败 | 工具名 | 「未完成」 | 红色静态边光，2s 后退回上行文状态 |
| 生成失败 | 生成未完成 | 「轻点重试」引导在消息区，不在岛上 | 红色静态边光，2s 后回 idle |

Live Activity 对齐（真灵动岛）：

- compact：左侧星核 + 右侧短状态（「连接中 / 思考中 / 回复中」等）。不再显示无界计时器，避免为动态数字预留过长宽度。
- expanded：以当前状态为主标题；普通回复不重复显示「生成回复」任务类型，其他工具任务才保留次级类型。有真实进度时显示进度条/数量，否则显示最后更新时间与「打开对话」，不构造后台继续执行的承诺。
- Chat 与 Live Activity 共享同一组阶段文案；普通回复链路按「连接模型 → 思考中 → 生成回复」更新。同一 delta 仍含 reasoning 时保持思考态；工具完成或审批恢复后继续生成态，不重置为首次连接。工具、确认、重连和终态仍由 `AgentActivityPresentation` 收口。
- 系统岛是共享空间，不做光谱动画；glyph 可用约 1.5s 步进的静帧轮换表示活跃（受 WidgetKit 更新预算约束，真机验证不达标则退静态）。
- `keylineTint` 维持 amber。

## 6. 可达性

- Reduce Motion：glint 关闭；辉光退化为静态 40% 透明度 ring；morph 退化为纯透明度交叉；orb 用既有静态帧。
- VoiceOver：岛本身 `accessibilityHidden(false)` 并聚合为一个元素，label 随状态朗读（「正在生成回复」）；它重复了消息区已有的状态信息，不设交互。
- 对比度：glint 峰值后文字与玻璃底对比不低于 4.5:1。

## 7. 工程护栏

- 岛 body 求值次数不得随 token 增长：glint/glow 全部在本地 `TimelineView`/`Canvas` 内自驱动，state 仍以 `animationKey` 等值门控。
- 辉光实现复用 `ThinkingOrbView` 的 UIKit `CADisplayLink` 路径（30fps、isHidden 暂停门控），并与 orb 共用 `CACurrentMediaTime` 时钟基准做相位同步；禁止用 SwiftUI `TimelineView(.animation)` 驱动全速重绘。
- 退役组件：`ChatActivityIslandHalo`（被辉光取代）、`ChatActivityIslandSoftField`（被 wash 层取代）、`chatIslandFlip` 过渡。删除属行为变更，实施时单列 commit 说明。
- 文案继续集中在 `ChatView.topIslandState` 一处产出；沿用车内现有硬编码中文风格，不扩大翻译范围。
- 不改变 `ChatActivityIslandState` 对外的状态枚举含义，只新增 terminal 停留所需的最小字段（如 `terminalUntil`）。

## 8. 验收标准

1. 六态切换几何连续，无 3D 翻转；idle↔active 形变单 spring 完成。
2. 生成中标题有微光，停止生成即消失；Reduce Motion 下全岛无永动动画。
3. 流式 replay 门禁（`ChatStreamReplayTests`）与 island wiring 测试（`IOSSettingsWiringTests` 内 island source 断言）同步更新后全绿。
4. 真机 120Hz：连续 3 分钟流式，岛区域无掉帧、无高度抖动（目测 + Instruments 二选一留证）。
5. 文案表逐条目检：无机制词，副标题全部为可验证事实或 nil。

## 9. 分期建议

- S1（壳+核）：辉光三层 + 色彩纪律 + morph 过渡退役翻转；orb 六态映射上岗（仅 `ChatView.topIslandState` 与 `orbState` 映射改动）+ settle 缓出；退役 halo/SoftField/岛上 SF Symbol。
- S2（文）：Title Glint + 文案表落地（含图片计数、工具参数词边界截断）。
- S3（岛）：Live Activity 文案对齐与 compact glyph 静帧轮换。

每期独立可验收，S1 即已覆盖「液态玻璃美学」主体观感。
