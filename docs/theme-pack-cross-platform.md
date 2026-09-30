# Android / iOS 主题配方互通

状态：2026-09-26 Android 编译、25 项主题定点测试，以及 USB Android 真机的分享导入、Qwen 试穿/套用、还原、重启保留和导出回读通过。完整 iOS app/模拟器仍未验收；Swift 数据协议检查在 macOS 执行。本说明以 iOS 已有 `amber.theme.pack` v1 文件为协议基准；Android 保留旧版 token 主题包读取路径。

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

Android 与 iOS 的主题工具都支持先读取状态，再创建或局部编辑 recipe。创建时 Android 默认 `canvasScope=shell`、`assetMode=builtinOnly`、`immersivePolicy=hidden`，与 iOS 工具默认一致。Android 工具省略 `brand_mark`、`shortcut_icon_style`、`chrome_typeface` 时分别填充 `systemWordmark`、`systemOutline`、`system`；导出的 v1 文件仍包含完整必需字段。对真机已观测到的 JSON 字符串形式 `design`，先解码一次，再执行同一套严格字段、范围和对比度校验。局部修改时省略字段会保留原值，修改已有自定义主题保留原 `id`；修改内置主题会生成可编辑副本。

局部 patch 对对象递归合并，对数组整体替换。`design.patterns`、渐变的 `colors` / `darkColors` 都按新数组替换。工具参数中的顶层槽不接受 `null`；design 内可空 palette、gradient、components 或可选 component 值可用 `null` 清除，`patterns` 本身不能写 `null`，清空时传 `[]`。Android 主题工具定点测试已通过；iOS 模拟器回归仍 **pending**。

主题文件中的 `id` 是普通字符串：两端按其原值读写，局部编辑自定义主题时保持原 id。`current` 是 `status` / `base_id` 参数中的当前主题别名，不是文件字段；两端都在工具定位路径中优先解析当前 try-on 或当前主题。工具控制字段与文件 id 分开传递。

Android 通过 `ThemePackageValidator` 保留无 `format` 的旧 `ThemePackage`（`schemaVersion=1`）读取。带 `format` 的 JSON 走新的 `amber.theme.pack` v1 校验；旧 token 包不要求迁移到新协议。

## Android 的可视消费范围

当前 Android 的颜色构建使用 `paper`、`accentHex`、`inkHex` 和 `design.light` / `design.dark`。自定义 palette 的 `foreground2` 与 iOS 一样使用 `foreground`，`mutedForeground` 留给弱化文字；有 portable document 时用户气泡使用 `accentHex` 填充和 `inkHex` 文字。无 document 的旧 Android 外观保持原样。

背景的渐变、六种纹理和画布预设采用 v1 的同一几何语义：渐变方向按归一化画布坐标计算；纹理 `size` 是图案尺寸，描边为 `max(0.5, size * 0.32)`，dots 半径为 `size`、rings 半径为 `size * 2`，自定义纹理从原点起铺。`canvasScope` 在每个导航页面的内容边界生效：`homeOnly` 仅首页，`shell` 包含首页与设置，`appWide` 包含已接入 `amberCanvas` 的功能页；缺省文件槽按 iOS 的 `homeOnly` 处理，工具新建仍显式写入 `shell`。

首页消费 `brandMark`、`shortcutIconStyle`、`emptyArt` 和 components 的 `brandText` / `brandSize` / `brandTracking`。Phosphor fill 复用两端同源图标，像素字标和五个快捷图标使用同一位图。`chromeTypeface` 通过首页的 Material typography 与 Amber 自定义文字样式共同生效；设置页仅在 `settingsChrome=true` 时跟随，聊天正文保留用户字体设置。

components 的圆角、边框与阴影进入首页卡片、共享设置卡片及主题支持的共享组件；未提供值时保持各组件默认外观，显式零值可关闭对应效果。气泡优先使用 `bubbleRadius`，否则 portable document 的 `bubbleChrome` 使用 standard=18、soft=22、crisp=14 的预设。`glassChrome` 控制首页控件表面的强度，但 iOS Liquid Glass 的折射由系统实现，Android 使用本地表面效果近似；系统字体和页面布局仍存在平台差异，不能把文件互通等同于逐像素一致。

`launchBrand` 仍只保留用于主题交换，未接到 Android 系统启动屏；`assetMode` / `immersivePolicy` 目前分别只接受 `builtinOnly` / `hidden`，主题文件不携带外部 asset。

有自定义 `design.dark` palette 时，Android 会关闭 AMOLED 纯黑覆盖，让这套深色 palette 生效；没有自定义 dark palette 时，用户开启的 AMOLED 深色模式仍可覆盖基础背景色。

Android Agent 工具的 `prepare` 只建立内存中的 try-on，并将候选 themePack 送入全局主题构建，不写主题库或设置。普通对话中的 `prepare` / `discard` 不再弹通用审批；`apply` 仍必须明确确认，非普通对话的门控不放宽。聊天卡片绑定 `package_id` 与 `candidate_digest`；若模型已提出待审批的 apply，卡片接入原批准/拒绝回调，已批准但未执行完成时禁用操作，避免写完设置仍挂着 Pending。每个候选仅在匹配的聊天工具结果处显示一张卡，不占用全局导航顶端；外部文件导入在外观页显示同一控制卡。Swift 侧对应 `beginTryOn` 与用户选择“套用/还原”的路径，完整 iOS UI 交互仍未验收。

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


## USB 真机修复复核（2026-09-26）

本次直接检查用户设备上的主题会话。日志显示批准已进入 ALLOW/execute，失败原因是 `design` 被模型编码成 JSON 字符串，之后重试又缺少三个平台样式字段；分享文件被旧路由存成聊天附件；原试穿卡则被挂在全局导航外壳顶部。

修复并验证：

- `ACTION_SEND` 单个主题文件和 content/file `ACTION_VIEW` 经有界读取确认 `format=amber.theme.pack` 后进入主题预览；保留来路导航，普通 JSON 仍作为聊天附件。
- 以设备里原有的 `amber-theme-bordeaux-velvet.json` 验证热启动与冷启动导入；不会在导入时保存。
- 当前 `qwen3.8-flash` 真实会话的 prepare 不再要求通用审批，模型输出的完整三层纹理配方试穿成功。用户后续确认时，聊天内单张主题卡通过原工具审批回调完成 apply，随后 status 确认已保存。
- 还原可退回原外观；套用后重新启动应用仍保留主题。真机重新导出的 JSON 与用户原始共享文件逐字段/值树相等；该导出也通过 macOS 上摘取的真实 Swift 数据模型的编解码检查。
- 保存期间取消或替换候选会拒绝旧提交并补偿本次写入；Settings 写入/回滚不再覆盖无关新设置。旧不透明 ARGB 强调色可无损转 RGB，含透明度且无法无损表示的旧颜色会给出错误，不再静默改成陶土色。

验证命令：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/opt/homebrew/share/android-commandlinetools \
./gradlew :app:testDebugUnitTest \
  --tests 'app.amber.core.ai.tools.ThemePackToolsTest' \
  --tests 'app.amber.feature.ui.theme.ThemePackageManagerTest' \
  --tests 'app.amber.feature.ui.theme.ThemePackageValidatorTest' \
  --tests 'app.amber.feature.ui.theme.ThemePackTransferTest' \
  :app:assembleGraphite --offline --console=plain -Pksp.incremental=false --max-workers=2
```

25 项测试通过（6 工具、11 管理器、5 旧包校验、3 跨平台配方转换）。Graphite 包以相同包名 `app.amber.agent` 和相同签名保留数据覆盖安装；原数据库及偏好文件均仍在，原有会话与 provider 配置可继续使用。截图、设备导出、构建日志存于本机 `/tmp/amber-theme-device-20260926/`；本次没有做全应用性能基准或其它 provider 的实机验证。

## 视觉消费补齐（2026-09-26）

本轮以用户分享的「勃艮第 · 暮红」为对照，修复了纹理线宽/半径/原点、归一化渐变、次级文字色及用户气泡配色，并把首页品牌/五个快捷图标、卡片几何/阴影、页面作用范围和界面字体接到实时 try-on 文档。画布绘制顺序与 iOS 一致：底色、渐变、自定义 patterns、canvasStyle。

定点验证 `ThemeDesignRenderingTest` 2 项、`ThemePageChromeTest` 2 项、既有 `HomeCompactLayoutTest` 5 项和 `ThemePackToolsTest` 6 项，共 15 项通过。新增渲染用例直接读取原生 Canvas 输出像素，验证纵向画布的斜向渐变及 dots/crosses/rings 的尺寸；页面范围用例通过真实 Nav3 entryProvider 验证 metadata，没有把字符串 contentKey 错当作 Screen。

子代理只读复核了 manager try-on → Theme.kt → LocalThemePack → 页面 decorator/首页的更新链，未发现依赖持久化才刷新或还原的断链。既有 HDR 工作树改动保留。本轮设备列表没有 USB Android 真机；Android 模拟器视觉结果记录在 `/tmp/amber-theme-parity-20260926/`，不能据此宣称两端真机逐像素一致。iOS 侧本轮仅核对主题渲染源码，没有重建或修改 iOS 应用。

模拟器补充验收：保留数据覆盖安装后，「勃艮第 · 暮红」已保存主题直接呈现衬线字标、实心快捷图标和修正后的纹理；另一份 `homeOnly/settingsChrome=false` 配方在设置页保持纯色，在首页显示自定义品牌和像素图标，点击还原后返回已保存的勃艮第主题。最终 Graphite 构建通过；截图与构建日志同上目录。

顶部沉浸补充：WorkspaceTopBar 将系统 inset 放到 toolbar surface 外侧，有主题画布时展开与滚动状态均透明，避免返回顶部时出现背景过渡色块。首页把 statusBars 避让从整个 Column 移到 LazyColumn 初始 contentPadding，滚动后可延伸至状态栏；Haze 捕获主题背景，顶部渐隐覆盖状态栏加 40dp，自定义配方不再叠纯色遮罩。模拟器对照确认初始首页字标边界不变，滚动后不再出现旧纯色顶带。`HomeCompactLayoutTest` 5 项复跑通过，Graphite 构建通过；展开/折叠设置页和紧凑窗口滚动截图均在本轮目录。
