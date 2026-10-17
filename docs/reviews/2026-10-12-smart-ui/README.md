# 智能 UI 模拟器验收

参考会话 `51f305ac-68c8-4e80-bdc1-310edf8d0c9f`；范围为 HarmonyOS 默认聊天智能 UI。截图使用本地 Debug 回放，不请求模型、不写默认会话数据库。

## 环境与证据

- 模拟器：`127.0.0.1:5555`，API 24，1256×2760。
- 应用：`app.amber.deepread`（Amber Agent，非独立深读产品）。覆盖安装保留已有数据。
- 最终 HAP SHA256：`739bb768ba1397e1940681dc188c8e745cc8879da7937f356268009013e2eb1b`。
- 已恢复原外观模式“跟随系统”。

| 截图 | 实际观察 |
| --- | --- |
| [浅色布局](layout-light.jpeg) | accent / danger filled 容器及正常 surface 子内容可读，网格与卡片正确显示 |
| [深色布局](layout-dark.jpeg) | 同一布局在深色下保持文字与子内容对比 |
| [快速输入](input-focused-dark.jpeg) | 原生 TextArea 聚焦、键盘仍打开，自动快速输入 `offline` 完整保留 |

## 交互与回归

最终包的实际 [草稿文本](prepared-draft.txt) 与 [界面](prepared-draft.jpeg) 已保存；[重置观察](reset-observation.json) 确认字段清空、继续按钮禁用，先前准备的草稿保持原快照。

任务卡实际操作覆盖：空必填禁用继续、方案切换、预算滑块 50→80、输入约束、准备包含当前字段的草稿、重置后继续按钮禁用。原生文本回灌曾产生 `THREAD_BLOCK_6S`，修复后持续输入与操作、组件画廊长时间运行正常；快速输入丢字另有复现并修复。

80 种组件全部通过生产协议解析与默认聊天回放，交互验收覆盖代表性控件；没有逐项手工验收 80 种组件，也不宣称 iOS 完全视觉一致。

最终全套回归 4712/4712（167 suites，失败、取消、跳过均为 0），整应用 Debug SDK 构建成功（25 s 333 ms）；受影响 ArkTS lint 0 error，既有警告保留。完整范围和已知边界见 [实施记录](../../plans/2026-10-12-harmony-smart-ui-experience.md)。

设备没有配置模型，无真实 provider 往返；没有连接物理设备，API 12 真机兼容验收未取得。改动未提交或推送。
