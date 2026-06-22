# P0 Visual Baseline

> 视觉基线快照（spec line 43）。用于后续 phase 的 `visual_protection` 回归比对：
> HARD-LOCK 文件出现任何 diff → refute 直接 BLOCK；VISUAL-GUARDED 文件改动 → 必须附受影响屏幕的 baseline 比对。

## 采集环境
- **设备**: iPhone 17 模拟器（iOS 26.x，UDID `293252D5-CCF3-47DD-8736-8A8A26A6788C`）
- **配置**: Debug, `derivedDataPath=/tmp/amber-dd`
- **工具**: `xcrun simctl io <udid> screenshot`（非 live-key 依赖）
- **外观**: system/light（`IOSAppearanceMode.system`）
- **采集时间**: 2026-06-22（P0 baseline 建立）
- **采集时工作区状态**: P0 red-lights 已落，pre-existing 失败存在（见 PHASE_LOG.md）

## 复现方法
基线通过临时 launch-arg 路由覆盖采集（`ROUTE=<route>`），该工具代码在采集后**已完整 revert**（AppShell.swift 零 diff，已验证）。复现基线：
```bash
UDID=293252D5-CCF3-47DD-8736-8A8A26A6788C
xcrun simctl io "$UDID" screenshot docs/ios-port/visual-baseline/<name>.png
```
后续 phase 比对：用相同设备/外观/配置重新截图，与基线像素比对（仅 intended-and-approved 差异可通过，spec line 47）。

## 基线清单（5 屏）

| 文件 | 屏幕 | 对应文件（visual_protection 类别） |
|------|------|----------------------------------|
| `01-home-chat.png` | 聊天首页（会话列表，Liquid Glass） | `ChatView.swift` (HARD-LOCK) |
| `02-appearance.png` | Appearance / 主题设置 | `AppearanceSettingsView.swift` (HARD-LOCK) |
| `03-providers.png` | Provider 列表 | `ProvidersView.swift` |
| `04-board.png` | Board（Deep Read 入口/热榜） | `BoardView.swift` (VISUAL-GUARDED) |
| `05-council.png` | Council（模型议会） | `CouncilChatRuntimeView.swift` (VISUAL-GUARDED) |

## 唯一允许的预期视觉变化（spec line 47）
- P4 Deep Read/Council 失败态（failed/partial）展示
- interim_safeguard 的 provider 禁用/提示
- 改动中必须显式标注 intended 并经人工确认。其余一切视觉差异视为回归。

## 注意
- 基线建立时 DeepRead/Council 尚未走真实 provider（P0 只建矩阵），故 Board/Council 截图反映的是**当前 baseline 行为**，非最终态。
- 后续 phase 若改 BoardView/CouncilChatRuntimeView，须重新采集这两屏并与本基线比对。
