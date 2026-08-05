# SVG 保存按钮工作进度交接 · 2026-08-05

## 一句话状态

「保存 SVG」源码与 P1 收紧已在工作树；用户改为走主包验收。已于 2026-08-05 19:41 将含该改动的 `iosApp` Debug 覆盖安装并启动 **`app.amber.ios`**（二进制含「保存 SVG」/`IOSGenerativeWidgetSVGExport`）。**Files 导出与真实 SVG 卡可见性仍待真机手测。**

## 目标回顾

在聊天时间线里的生成式 UI 卡片右上角，为完成态、已净化、真正含 SVG 的 widget 提供「保存 SVG」入口，通过系统 Files 导出 `.svg`。

明确边界：

- 不导出 `full_html` / `slides` 的封面预览 SVG
- 不回退导出未净化的原始 `widgetCode`
- 不做过度兜底、不过度设计

## 已完成

### 1. 功能实现

文件：

- `iosApp/iosApp/IOSGenerativeWidgetCard.swift`
- `iosApp/iosAppTests/IOSGenerativeWidgetParserTests.swift`

行为：

- 完成态 + sanitizer `.ready` + 净化 HTML 含完整 `<svg>…</svg>` 时，header 右上角显示「保存 SVG」
- 点击后走 `fileExporter`，失败弹 alert
- 不依赖展开 sheet / ViewModel / `ChatListAction`

### 2. P1 安全收紧（已落地）

- 删除 raw `widgetCode` fallback；只从 `sanitized.html` 抽 SVG
- 显式拒绝 `renderer == "slides"` 与 `renderer == full_html`
- 文件名：`.SVG` 大小写无关；过滤 control characters
- UI：文案「保存」；高度 28 的 accent 小胶囊（与下方 bordered action 区分）；`fixedSize` + 更高 `layoutPriority`；无障碍仍为「保存 SVG 到文件」

### 3. 测试补齐（helper 级）

- 净化 SVG 优先
- raw fallback 改为断言 `nil`
- `full_html` / `slides` 返回 `nil`
- 文件名大小写 / 控制字符

### 4. 真机安装问题定位

用户截图（项羽 vs 刘邦对照卡）看不到「保存 SVG」。

排查结论：

- **主因**：`iosApp/scripts/install-device-experimental-gpl.sh` 安装的是 `iosAppExperimentalGPL.app`（bundle id = `app.amber.ios.experimental-gpl`），但旧脚本启动的是 `app.amber.ios`（旧主包）
- **次要可能**：该卡本身是普通 HTML/`div` 图卡，不含 `<svg>`，按当前契约本就不该显示保存按钮

脚本已改：

```bash
xcrun devicectl device process launch --device "${DEVICECTL_ID}" --terminate-existing app.amber.ios.experimental-gpl
```

并同步把 info apps 查询改成 `app.amber.ios.experimental-gpl`。

## 未完成 / 卡住点

1. **主包已覆盖安装，真机手测未完成**
   - 安装容器：`0693C392-DCFC-4B56-8DE1-EE37945B4DFF/iosApp.app`
   - dylib SHA-256：`e4062b24644f92abc199f50dd84562ec349d868c11113c45df498eb0ba51a8a6`
   - 请打开原来的 Amber（非 experimental-gpl），找完成态 SVG 卡看右上角按钮，并试 Files 导出
2. **「项羽 vs 刘邦」卡若仍无按钮**：按契约查是否含净化后的完整 `<svg>`；无 SVG 则不应显示，另造正例
3. **未 commit**
4. **Xcode unit test 未在本机完整跑通**（历史 EquatableMacros / sandbox 限制）

## 关键改动文件

与本任务直接相关：

- `iosApp/iosApp/IOSGenerativeWidgetCard.swift`
- `iosApp/iosAppTests/IOSGenerativeWidgetParserTests.swift`
- `iosApp/scripts/install-device-experimental-gpl.sh`

注意：工作树里还有大量无关 docs / Novel / AGENTS 变更与删除，**不要整树乱提交**。只挑本任务相关文件。

## 导出门控契约（当前代码）

`IOSGenerativeWidgetSVGExport.artifact` 返回非空，当且仅当：

1. `widget.complete == true`
2. `sanitized.status == .ready`
3. `widget.renderer != "slides"`
4. `widget.renderer != IOSGuizangHtmlDeckValidator.renderer`（即 `full_html`）
5. `extractSVG(from: sanitized.html) != nil`

## 设备信息

- 设备：iPhone Air
- destination id：`00008150-000A594E0AF8401C`
- devicectl id：`94918570-0680-5B93-8E38-7E6B355D4426`
- scheme：`iosAppExperimentalGPL`
- 正确 bundle id：`app.amber.ios.experimental-gpl`
- 错误启动过的旧包：`app.amber.ios`

## 下一步（按优先级）

1. 重新跑：

```bash
cd /Users/mi/Downloads/AI/AmberAgent-iOS/iosApp
./scripts/install-device-experimental-gpl.sh
```

确认日志里 launch / info apps 都是 `app.amber.ios.experimental-gpl`。

2. 在真机打开 **Experimental GPL** 包，回到同一条「项羽 vs 刘邦」消息：
   - 若出现「保存 SVG」：继续验 Files 导出成功/取消/失败 alert
   - 若仍不出现：查该 widget 是否只是 HTML 图卡（无 `<svg>`）。这是当前契约预期，不要为了这张卡放宽成导出 HTML

3. 另造一张明确 SVG widget（`renderer: html/diagram` + 真实 `<svg>`）做正例验收

4. 可选：跑相关 parser tests；不要被既有 EquatableMacros 噪音带偏

5. 仍不要做：
   - SVG 专用 XML allowlist 大重构
   - HTML/PPT 导出协议
   - raw fallback 复活
   - 为无 SVG 的 HTML 卡强行显示保存按钮

## 验收清单

- [ ] 启动的是 `app.amber.ios.experimental-gpl`，不是 `app.amber.ios`
- [ ] 真实 SVG 完成卡右上角有「保存 SVG」
- [ ] `full_html` / `slides` 卡没有该按钮
- [ ] 净化后无 SVG 的内容没有该按钮
- [ ] 导出到 Files 成功；失败有 alert
- [ ] 窄屏/大字号下按钮文案不被挤没

## 用户偏好（续作必须遵守）

- 精准修复，不过度防御 / 过度兜底 / 过度设计
- 子代理默认：`model=gpt-5.6-luna`，`reasoning_effort=max`
- 设置 `model` 或 `reasoning_effort` 时，`fork_turns` 必须是 `none` 或正整数，任务说明自包含
