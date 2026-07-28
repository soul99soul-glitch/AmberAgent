# 等价偏差登记（Parity Debt）

> 鸿蒙实现与 Android 生产语义的已知偏离。每条必须有：Android 基准锚点 / 偏差内容 / 理由 / 消除条件。
> 原则：等价优先级 数据与协议 ＞ 核心闭环 ＞ 错误恢复 ＞ 交互语义 ＞ 视觉。前两档不允许无声偏差。

| ID | 日期 | 领域 | Android 基准 | 鸿蒙现状 | 偏差 | 理由 | 消除条件 | 状态 |
|---|---|---|---|---|---|---|---|---|
| PD-001 | 2026-07-28 | deepread agent | 空 prefetch → hard fail，提示"没有抓到足够的来源"（Android DeepReadSourcePrefetcher 调用方） | 空/异常 prefetch → 用标题构造 fallback source，走 fallbackPlan 产出降级文章 | 错误恢复语义变化（用户可见） | 搜索源全挂时体验：降级文 > 直接失败 | G1 人工确认；若否决则改回 hard fail | open |
| PD-002 | 2026-07-28 | deepread prefetch | SOURCE_COLLECTION_TIMEOUT_MS = 36s（DeepReadSourcePrefetcher.kt:688） | PREFETCH_WALL_BUDGET_MS = 15s | 预算常量减小，可能收集到更少来源 | 移动端等待容忍度；与 HTTP 超时匹配 | 真机 spike 后按实际来源完整度回调 | open |
| PD-003 | 2026-07-28 | 构建目标 | Android targetSdk 37 | harmony targetSdkVersion 5.0.0(12)（API 12 兼容档） | 目标 API 档位远低于 compileSdk 26 | 支持 API 24 模拟器安装（commit 1db3ba8c2 的延续） | 真机设备到位后按真机 API 重新定档 | open |

## 待 Android 侧核实的疑似偏差

（无——G-B 矩阵产出后补充）

## 已消除

（暂无）
