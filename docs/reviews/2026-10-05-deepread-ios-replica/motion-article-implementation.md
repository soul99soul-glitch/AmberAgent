# 编辑室与付印动效补修

基准为当前 iOS 独立工作树 `DeepReadNewsroomView.swift`、`DeepReadDesign.swift` 的 `DeepReadStampSlam` 与 `DeepReadDetailView.swift`。本轮只改 Harmony 的 `DeepReadNewsroomDesk.ets`、`DeepReadArticlePressMoment.ets`、`DeepReadArticlePage.ets`，以及定向动画测试；未改 TabBar、字体、模板、产品服务或生成 domain。Article 为 994 行。

## 逐项结果

| 动效 | 原缺口 | 当前实现与验证 |
| --- | --- | --- |
| 每字落入活字盘 | 有 -28 位移，但 EaseOut、对称离场、缺小阴影 | 每 300ms 增 1 字；前 10 个非空白非标点字符，留 6 tick 后复位。插入 -28 与 opacity，320ms spring；移除仅 opacity，增加 2vp 阴影。实际 glyphs/placed/transition 方法测试覆盖字符过滤、6 tick 停留与两种方向。 |
| 格言轮换 | 只有 fade；减弱动效时整个时钟停止 | 一般从下方 50 进入、上方 -50 离开，opacity + EaseInOut 500ms，原有两行裁切容器保留。减弱动效只 fade，定时器 4200ms/tick+=14；正常 300ms/tick+=1，均每 4.2 秒换一条。实际 timer 回调、interval 和 noteIndex 测试通过。 |
| rail / 当前环 | 连线无弹簧；环不淡到 0，复位反向动画 | 连线 accent overlay 从左侧展开、节点颜色 600ms spring。当前环有限关键帧 10ms reset 到 scale1/opacity.5，再 EaseOut 1200ms 到 scale1.55/opacity0；1210ms 后才开启下一次 reset。独立 pulse timeout 与活字时钟一起停止，旧 owner/旧 timeout 不得重启。 |
| 阶段图标 / 标题 | 整块淡入，没有独立 replacement | 主图标与完成节点各自保有身份，切换时 .8→1 spring + opacity，旧图标 fade；标题独立 opacity 500ms。主图标保留小幅 breathe，inactive/background/reduced 下回归 1 倍，duration0。 |
| 付印落章 | 2.6 直接到 1、固定 -10° | 新 subtree 挂载后 16ms 开始；有限关键帧 10/180/350ms：scale2.6→.92→1，angle8→-10，ink 在 10ms delay 后 140ms 到 .88。浅色 multiply，深色 normal。实际关键帧闭包采样与 options 测试通过。 |
| 付印说明 / 覆盖层 | 说明立即可见，覆盖层即时消失 | 说明在 300ms 后以 EaseOut 400ms fade，offset8→0；reduced 不做位移。覆盖层 200ms fade 入、400ms fade 出。普通章 1400ms 请求退出，首篇/十篇/百篇 2600ms，可点击退出。 |
| 动态减弱动效 | Press 未 Watch；旧关键帧可能继续落章 | Watch 变化后立即收敛 scale1/angle-10/ink.88/caption 可见，清 enter/caption timer，保留原退出 deadline；回到 normal 不回放旧 owner。初始 reduced 仅 250ms opacity，触感仍保留，与 iOS sensoryFeedback 行为一致。 |
| 完成 / 里程碑 | 已有持久成功门控，本轮继续验证 | Article 原有四段 isComplete、RunResult.ok、实际观察 running→已持久 COMPLETE、可见 cycle 和 run identity 门控保留。旧稿重生成失败、取消、保留旧成功稿、初始 terminal 回放均不盖新章。真实首次稿才计数首篇/十篇/百篇。 |
| 取消 / 离页 / 后台 | 定时器已有部分守卫，本轮增加关键帧闭包 | Desk 停止 interval 与 pulse timeout；Press 停止 enter/caption/dismiss timeout。旧 timer identity 与 motion owner 检查阻止过期回调修改新组件或新章。返回页面不重放旧成功触感。 |
| 正文进入 | 首次生成完成立即进入正文 | 原生 Scroll 与 Web 同用 opacity + 24vp 上移，EaseOut 500ms；reduced 仅 fade，inactive duration0，宿主 IDENTITY。新稿保持编辑室至实际终态的布尔门控保留。 |

`DeepReadHaptics.impact()` 使用主 agent 新建 helper，产品、后台、硬件能力与失败处理由 helper 持有；本组件只在实际新 completion trigger 的 play 中调用，setting 变化不额外触发。

## 官方 API 与曲线证据

- 本地官方 `API参考/ArkUI_方舟UI框架/ArkTS组件/动画/关键帧动画_keyframeAnimateTo/ts-keyframeanimateto`：`UIContext.keyframeAnimateTo` 从 API11 提供，支持每段 duration/curve/event 与 finite iterations；不用 API19 expectedFrameRateRange。当前 SDK `@ohos.arkui.UIContext.d.ts` / common.d.ts 核准签名。
- 本地官方 `API参考/ArkUI_方舟UI框架/ArkTS组件/动画/组件内转场_transition/ts-transition-animation-component`：asymmetric、translate、scale 与 opacity 转场均为 API10 范围；本轮用原有 `.transition(effect)`，没有新增较高版本回调参数。
- `blendMode` 本仓已有 NativeMarkdownText / DeepReadPaperBackground 先例，当前 SDK 核准 `BlendMode.MULTIPLY` 与 blendMode 为 API11。
- 本地官方 `API参考/ArkUI_方舟UI框架/ArkTS_API/UI界面/ohos_curves_插值计算_/js-apis-curve` 与 SDK 核准 `curves.springCurve(velocity, mass, stiffness, damping)`，curve 受弹簧参数影响、duration 仍由 animation/animateTo 控制；本仓已有先例，API9 起提供。按单质量弹簧 `k=(2π/response)²`、`c=2*damping*sqrt(k)` 映射：活字 iOS .32/.58 → k386/c23；rail .6/.8 → k110/c17。主图标 replacement 使用 k400/c30 与 500ms；落章回弹 k220/c16 与 350ms。这些是跨平台物理参数映射，不能当作 SF Symbols breathe/replace 或 SwiftUI bouncy 的逐帧相同证据。

系统 reduceMotion 桥接由主 agent 持有，组件消费现有 AppStorage key 并动态响应。主 agent 当前公开读取 API23 后 capability/version 守卫接线；API12 没有该公开读取能力，默认 normal。本轮未宣称 API12 的系统减弱配置读取已接通。

## 验证与证据范围

`node --test harmony/entry/src/test/deepread_press_moment.test.cjs harmony/entry/src/test/deepread_ios_newsroom.test.cjs harmony/entry/src/test/deepread_editorial_motion.test.cjs harmony/deepread/src/test/deepread_reader.test.cjs`：42/42 通过。测试执行生产方法、真实 timer 闭包、关键帧/transition 配置与回调，包含动态 reduced、后台/销毁/旧 timer/旧 keyframe、保存完成与失败保留旧稿门控。测试不替代 compositor 连续帧、触感硬件与真实设备无障碍视觉验收。

三改动 ArkTS 文件 scoped lint：0 error / 2 个 Article 既有 JSON 深拷贝建议 warn；最终 Desk 追加 4200ms 弱动时钟与 1210ms 完整 pulse deadline 后单文件 lint 0 error / 0 warn。`git diff --check` 通过。未自行 build、签名或操作设备；最终统一构建与 API24 模拟器连续帧由主 agent 执行。
