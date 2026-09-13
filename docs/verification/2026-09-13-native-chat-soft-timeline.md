# Android 原生聊天细化

## 实现范围

- 用户气泡圆角为左上/右上/右下/左下 23/23/14/23dp，沿用原有 userBg/userInk。
- 思考步骤拥有连续的 18dp 柔和外框，覆盖收起和展开、纯思考和混合工具路径；普通工具胶囊仍在框外。保留流式窗口、文本缓存和跟随滚动。
- Pending 工具使用独立的 20dp 审批卡片；“查看详情”打开既有脱敏预览，“批准”仍批准这次 toolCallId，“拒绝”仍允许填写理由。普通工具不变。
- 上下文弹层为 260dp 宽、20dp 圆角、轻阴影，真实 token/cache/speed 数据不变；单一 Compose transition 实现 380ms 进入、240ms 退出，淡入淡出、0.92 到 1 缩放和 -10dp 位移，可中断开合。
- 输入区上方接入全局子代理状态栏：166×48dp 胶囊，28dp 头像、13sp/11sp 两行文字；默认横向滚动，可展开两列；键盘出现时自动折回。显示真实状态和耗时，不伪造阶段或百分比。终态保留到用户收起，收起不删除任务或取消工作；来源会话在存在性检查后跳转。
- 全局状态观察独立于 ChatVM。续跑沿用 thread id，但 TaskStore 的 createdAtMs 标识本次 activation，thread startedAtMs 不变；启动基线区分旧历史终态与本进程极短任务，避免 StateFlow 合并后漏显示。

首页、现有配色、顶栏、输入控件样式和已缩小字号保持原样；并发数设置不变。首页文件 SHA-256：`5ba6a7b84a433b7c6f36b859a6c49005e3965b0259a8b9fc1b9ded7acb689dac`。

## 验证

- `:app:assembleGraphite`、`:app:assembleGraphiteAndroidTest` 通过（现有 Graphite testBuildType init script 与 `-PuiSmokeTest=true`）。
- `:app:testDebugUnitTest` 四个定点测试类全部通过，共 36 项：SubAgentDockStateTest 4、SubAgentThreadGraphIntegrationTest 20、ChatMessageReasoningTest 6、ContextMeterTest 6。
- 界面验收使用模拟器临时会话和元数据任务，不调用真实 provider、SSH 或子代理，不点击真实工具批准按钮；结束后删除测试拥有的会话/任务，恢复临时偏好。
- `ChatSoftTimelineSmokeTest` 在 390dp / 字号 1.0 和 320dp / 字号 1.3 两种配置均通过：实际点击思考展开、审批详情、上下文弹层，使用来源会话入口双向切换，检查键盘触发折回与终态逐项收起，并确认收起后任务记录仍是完成状态。
- 默认胶囊高 48dp；系统大字号时按 18sp/16sp 两行行高与内边距增加必要高度。窄屏展开网格中的较长状态可省略，详情仍显示完整状态与耗时。
- 模拟器显示已恢复 `780×1688 / density 320 / font_scale 1.0`。正常截图：`/tmp/amber-native-chat-qa/final-390/chat-soft-timeline-smoke/`；窄屏截图：`/tmp/amber-native-chat-qa/final-320-large/chat-soft-timeline-smoke/`。
- 最终 APK：`app/build/outputs/apk/graphite/app-arm64-v8a-graphite.apk`；SHA-256 为 `0ee93f6c9dafc53352db3e4cab14fe2ee6d2acce109c618189b9f68feeca03dd`。模拟器安装的 `base.apk` 哈希一致，RouteActivity 启动返回 `Status: ok`，主包进程存在。
- 最终 `git diff --check` 通过。此轮未执行提交或推送。

## 真机交付

2026-09-13 经用户确认无线调试已连接，通过 AndroMeld 与 Bonjour 找到小米 M610BB（506e0b25），完成 `adb install -r`，返回 `Success`。使用保留数据覆盖安装，未卸载或清除应用数据。

真机 `base.apk` SHA-256 与上述最终 APK 完全一致。通过 AndroMeld 启动 Amber 后，系统 `topResumedActivity` 为 `app.amber.agent/.RouteActivity`，主进程存在，近期 crash buffer 未出现 Amber。AndroMeld 的结构化界面读取暂不可用，未计作真机完整视觉回归；上述界面交互验收仍是模拟器证据，也未计作真实 provider、SSH 或真实子代理生成验收。
