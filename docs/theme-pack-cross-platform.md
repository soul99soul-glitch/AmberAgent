# Android / iOS 主题配方互通

状态：2026-09-26 Android 编译、23 项定点测试和 Swift 数据协议往返检查通过；完整 iOS 模拟器、视觉、真机及真实 provider 生成未验收。本说明以 iOS 已有 `amber.theme.pack` v1 文件为协议基准；Android 保留旧版 token 主题包读取路径。

## 文件协议

主题文件使用 JSON，顶层 `format` 为 `amber.theme.pack`、`version` 为 `1`。文件字段使用 camelCase；Agent 工具参数使用 snake_case。`base_id`、`action` 和 `candidate_digest` 是工具控制字段，不写入主题文件。

必需顶层字段为 `format`、`version`、`id`、`displayName`、`paper`、`accentHex`、`inkHex`、`canvasStyle`、`brandMark`、`shortcutIconStyle`、`chromeTypeface`。可选表面槽为 `canvasScope`、`bubbleChrome`、`glassChrome`、`emptyArt`、`settingsChrome`、`launchBrand`、`assetMode`、`immersivePolicy`，另有可选 `design`。

当前两端接受的槽值如下。`paper` 中的沉浸色只在 iOS 运行时枚举存在，iOS 导入校验会拒绝；Android 校验器也只接受表中五种非沉浸画布。

| 字段 | v1 值 |
| --- | --- |
| `paper` | `paper`、`neutral`、`white`、`pi`、`notion` |
| `canvasStyle` | `flat`、`dotGrid`、`lineGrid`、`paperGrain` |
| `brandMark` | `systemWordmark`、`paintAMBER`、`serifWordmark` |
| `shortcutIconStyle` | `phosphorFill`、`pixelSit`、`systemOutline` |
| `chromeTypeface` | `system`、`rounded`、`serif`、`monospace` |
| `canvasScope` | `homeOnly`、`shell`、`appWide` |
| `bubbleChrome` | `standard`、`soft`、`crisp` |
| `glassChrome` | `standard`、`quieter`、`solid` |
| `emptyArt` | `none`、`character` |
| `launchBrand` | `none`、`matchBrand` |
| `assetMode` | `builtinOnly` |
| `immersivePolicy` | `hidden` |

所有颜色都是不带 alpha 的 24-bit RGB，可写成 `#RRGGBB` 或 `0xRRGGBB`。`design` 可包含浅色和深色 palette、gradient、patterns、components：

- `light` / `dark` palette 都含 `background`、`surface`、`foreground`、`mutedForeground`、`border`。正文对 background/surface 的对比度至少为 4.5:1，弱化文字至少为 3:1。
- `gradient` 含 `colors`、`darkColors`、`angle`。渐变要求浅、深 palette 都存在，两组颜色各有 2–4 个 stop；每个 stop 与对应正文色对比度至少为 4.5:1，`angle` 必须是有限数。
- `patterns` 是最多三层的数组。`kind` 可选 `dots`、`grid`、`diagonal`、`crosses`、`waves`、`rings`；`opacity` 为 0–0.3，`spacing` 为 12–120，`size` 为 0.5–8。
- `components` 的可选数值范围为：`cardRadius` 0–32、`bubbleRadius` / `controlRadius` 0–28、`borderWidth` 0–3、`shadowOpacity` 0–0.35、`shadowRadius` 0–24、`brandSize` 20–40、`brandTracking` -2–6。`brandText` 去掉首尾空白后为 1–16 个字符。
- 顶层 `accentHex` 与 `inkHex` 的对比度至少为 3:1。alpha 不受支持。

`design` 对象存在时，iOS `Codable` 要求其中含 `patterns` 数组；Android 解码器允许省略并按空数组处理。序列化时两端都会带出该数组。空的可选属性在文件中可省略或写 `null`；两端模型均把它们解为 nil，导出时省略 nil。

## Agent 创建与局部修改

Android 与 iOS 的主题工具都支持先读取状态，再创建或局部编辑 recipe。创建时 Android 默认 `canvasScope=shell`、`assetMode=builtinOnly`、`immersivePolicy=hidden`，与 iOS 工具默认一致。局部修改时省略字段会保留原值，修改已有自定义主题保留原 `id`；修改内置主题会生成可编辑副本。

局部 patch 对对象递归合并，对数组整体替换。`design.patterns`、渐变的 `colors` / `darkColors` 都按新数组替换。工具参数中的顶层槽不接受 `null`；design 内可空 palette、gradient、components 或可选 component 值可用 `null` 清除，`patterns` 本身不能写 `null`，清空时传 `[]`。Android 主题工具定点测试已通过；iOS 模拟器回归仍 **pending**。

主题文件中的 `id` 是普通字符串：两端按其原值读写，局部编辑自定义主题时保持原 id。`current` 是 `status` / `base_id` 参数中的当前主题别名，不是文件字段；两端都在工具定位路径中优先解析当前 try-on 或当前主题。工具控制字段与文件 id 分开传递。

Android 通过 `ThemePackageValidator` 保留无 `format` 的旧 `ThemePackage`（`schemaVersion=1`）读取。带 `format` 的 JSON 走新的 `amber.theme.pack` v1 校验；旧 token 包不要求迁移到新协议。

## Android 的可视消费范围

当前工作树中，Android 主题构建已接入 `paper`、`accentHex`、`inkHex`、`chromeTypeface`、`canvasStyle`、`design.light` / `design.dark`、gradient、patterns，以及 components 的圆角、边框、阴影值。`bubbleRadius` 已接到聊天消息圆角；card/control 几何进入主题 shapes，`borderWidth` 与阴影由 `AmberCard` 消费。配方 apply、Room 保存和重新读取已通过 Android 定点测试；实际界面呈现与手感尚未做设备验收。

以下字段可解析、校验、保存，并在未改动时随 v1 文件和 Agent patch 保留，但当前 Android 没有对应的可视消费：`canvasScope`、`bubbleChrome`、`settingsChrome`、`brandMark`、`shortcutIconStyle`、`glassChrome`、`emptyArt`、`launchBrand`，以及 `design.components.brandText`、`brandSize`、`brandTracking`。`assetMode` / `immersivePolicy` 目前分别只接受 `builtinOnly` / `hidden`；主题文件不携带外部 asset。

有自定义 `design.dark` palette 时，Android 会关闭 AMOLED 纯黑覆盖，让这套深色 palette 生效；没有自定义 dark palette 时，用户开启的 AMOLED 深色模式仍可覆盖基础背景色。

Android Agent 工具的 `prepare` 只建立内存中的 try-on，并将候选 themePack 送入全局主题构建；它不会写入主题库或设置。工具要求前台用户批准，不能自动批准。应用前需用 prepare 返回的主题 `id` 与 `candidate_digest` 精确绑定；用户应用后才落库。Swift 侧对应 `beginTryOn` 与用户选择“套用/还原”的路径。Android 工具/manager 定点测试通过；Swift 侧和视觉应用的跨端闭环验收仍 **pending**。

## 验证记录

- `test-fixtures/themes/cross-platform-v1.json` 包含所有顶层可选槽、浅深 palette、gradient、patterns、全部 component 字段和中文名称；颜色、对比度、枚举和数值范围按两端校验约束选择。
- 已用 JSON 解析检查 fixture 语法，并比较 Swift 回归内嵌 recipe 与 fixture 的 JSON 字段和值树；结果一致。
- iOS `AmberThemePackTests` 新增单条跨平台 recipe 回归，覆盖 decode/encode round-trip、自定义 id patch、pattern 数组替换、未修改槽位保留与顶层 null 拒绝。`swiftc -frontend -parse iosApp/iosAppTests/AmberThemePackTests.swift` 已通过。Xcode 定点测试 **pending**：首次共享 DerivedData 因 build database 被占用失败；隔离 DerivedData 构建在首次编译 iOS 主 target 时按资源协调要求中断，尚未到测试执行，目录保留在 `/tmp/AmberAgentThemeCompatibilityDerivedData`。
- 已在 macOS 用一次性 Swift harness 对共享 fixture 和 Android 导出文件执行 iOS 源码路径：源摘取脚本从 `AmberThemeDesign.swift`、`AmberThemePack.swift` 和 `PlaceholderViews.swift` 提取当前数据模型、校验器、transfer 编解码、颜色对比、原始 enum 值及 `Paper.isImmersive`；只去掉 SwiftUI 渲染适配和无关 palette/runtime 计算，没有替代或 stub 校验规则。两份文件均经 decode、显式 validate、encode、再次 decode 后 `Equatable` 相等；Android 文件中的 `gradient.angle=38.123456789` 保持，导出文件的 `patterns` 为空数组。本机验证命令记录（一次性脚本和可执行文件位于 `/tmp`）：

  ```sh
  python3 /tmp/extract-ios-theme-wire.py
  swiftc -module-cache-path /tmp/ios-theme-protocol-module-cache /tmp/ios-theme-v1-protocol-check.swift -o /tmp/ios-theme-v1-protocol-check
  /tmp/ios-theme-v1-protocol-check /Users/arquiel/Downloads/AI/AmberAgent/android/test-fixtures/themes/cross-platform-v1.json
  /tmp/ios-theme-v1-protocol-check /Users/arquiel/Downloads/AI/AmberAgent/android/app/build/reports/theme-android-export.json /tmp/theme-android-export-ios-encoded.json
  ```

  这是 macOS 上从当前 Swift 源码摘取的数据协议检查，不是完整 iOS app target 或 Simulator 测试。Android 导出重新编码产物在 `/tmp/theme-android-export-ios-encoded.json`。本轮用 Python `json.loads` 对该文件与 Android 原导出的 JSON 做精确字段/值树比较，结果相等；这是编码数据一致性证据，不代表再次调用 Android decoder。
- Android `ThemePackToolsTest` 6 项、`ThemePackageManagerTest` 9 项、`ThemePackageValidatorTest` 5 项及 `ThemeDesignTest` 3 项，共 23 项定点测试全部通过，`:app:compileDebugKotlin` 通过；Android 测试读取共享 fixture 并写出 `app/build/reports/theme-android-export.json`。该导出经 iOS 源摘取 harness decode、validate、encode、decode 后，`json.loads` 值树与原 Android 导出相同，id 为 `cross-platform-v1-garden`，`gradient.angle=38.123456789`，`patterns=[]`。
- Android `ThemeDesign` 与 iOS Codable 的 gradient、pattern、component 数值字段当前均为 `Double`。共享 fixture 与 Android 导出中的高精度角度均经上述路径保留；Android 导出和 iOS 重编码的全 JSON 字段/值树比较 **passed**。

本说明中的消费范围依据当前 Android 工作树源码；最终 UI/行为证据以各自定点测试结果更新。
