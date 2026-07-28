# 待人工确认队列

> L2 决策按推荐默认已继续执行，在此排队等批量确认（G1 决策包）。
> L3 项目会阻断对应切片并立即上浮——当前无 L3。

## 批量决策包 DECISION_PACKET_001（已产出 docs/DECISION_PACKET_001.md，待 G1 人工确认）

| # | 事项 | 推荐方案（已按此执行） | 风险 | 备选 |
|---|---|---|---|---|
| 1 | PD-001 prefetch 失败降级替代 hard-fail | 维持降级 | 与 Android 错误恢复语义不同；降级文章质量依赖 fallbackPlan | 改回 hard fail |
| 2 | PD-002 prefetch 预算 36s→15s | 维持 15s | 弱网下来源收集变少 | 恢复 36s 或按网络类型自适应 |
| 3 | 三方 SDK 替换总表（plan §5.2 落地） | Room→RDB / DataStore→Preferences / OkHttp→@ohos.net.http / Koin→手工注入+轻量 registry / Coil→自建 image 缓存 / Firebase→华为套件(暂缓,先做无埋点) | Coil/sqlite-vector/MCP 三项硬缺口 | 见 CAPABILITY_MATRIX |
| 4 | ObjectBox 预研关闭 | 关闭（源码零使用） | 极低（若有动态加载未发现的使用会漏） | 重开审计 |
| 5 | 治理文件 4 份轻量制（非 9 份） | 维持 | 与上游提示词字面要求不符 | 补全 9 份 |

## 待设备解锁后才能回答（不阻塞，仅登记）

| # | 事项 | 依赖 |
|---|---|---|
| Q-1 | SSE 流式在真机的稳定性（go/no-go） | hdc target |
| Q-2 | RDB/Preferences 真机持久化与重启恢复 | hdc target |
| Q-3 | ArkUI 50 token/s 流式渲染帧率（≥50fps P95 预算） | hdc target |
| Q-4 | targetSdk 定档（PD-003） | 真机 API 档位 |
