# AmberAgent iOS Autonomous Questions

## Q-1 · conversations · 会话数据接入时机

- **疑问**：`conversations` 首页的真实会话数据源/Repository 在当前 iOS 骨架里尚未接到此屏；原型基准要求先还原视觉和信息层级。
- **默认做法**：先使用 `index.html` 里的样例会话内容实现像素方向和导航骨架，不重写或猜测业务数据层；真实会话列表接入放到 Phase 3。

## Q-2 · 验证环境 · iOS simulator/runtime 缺失

- **疑问**：当前机器 `xcrun simctl list runtimes` / `devices` 为空，`xcodebuild -showdestinations` 只返回不可用目的地；`xcodebuild -showsdks` 能看到 iOS/iOS Simulator 26.5 SDK，但 scheme 仍提示无 destination，完整 build/simulator screenshot 无法完成。
- **默认做法**：不下载/安装 Xcode platform，不杀 Chrome，不阻塞；当前屏先用 `swift -frontend -parse`、scoped Swift checks 和原型 `renders/conversations.png` 进行静态/视觉方向验证，等本机 runtime/destination 可用后补完整构建与截图比对。

## Q-3 · search · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py search` 返回 `✗ renders/search.png`，脚本没有产出截图；原因需后续打开脚本或 Chrome 输出进一步排查。
- **默认做法**：继续按 `index.html` 中 `#search` section 的源码结构实现搜索覆盖层，先不阻塞；等渲染脚本可产出该屏后补视觉基准比对。

## Q-4 · permissions · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py permissions` 返回 `✗ renders/permissions.png`，脚本没有产出截图；当前没有进一步 Chrome 输出可定位原因。
- **默认做法**：继续按 `index.html` 中 `#permissions` section 的源码结构实现「权限与批准」入口屏，先用 Swift parse 与 diff check 验证；等渲染脚本可产出该屏后补视觉基准比对。

## Q-5 · capabilities · 原型基准图未生成

- **疑问**：执行 `python3 render_screen.py capabilities` 返回 `✗ renders/capabilities.png`，脚本没有产出截图；当前表现与 `search` / `permissions` 一致。
- **默认做法**：继续按 `index.html` 中 `#capabilities` section 与 CD-7 的最终能力模型实现能力门控页，保留现有 Swift 业务逻辑；等渲染脚本可产出该屏后补视觉基准比对。
