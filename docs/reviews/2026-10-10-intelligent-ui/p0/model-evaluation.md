# P0 模型生成评估

评估索引：[`model-evaluation-index.json`](./model-evaluation-index.json)。评估脚本：[`evaluate-intelligent-ui.mjs`](../../../harmony/scripts/evaluate-intelligent-ui.mjs)。

固定 fixture 共 20 条，每个模型有独立完整 artifact，包含 20 个稳定 case ID、实际 `userText`、SHA-256 prompt hash、请求参数和脱敏 raw output；没有发送 fixture 的 golden body。评估使用当前 `protocol.ts` 的 `parseSmartUi` 重放，SVG 检查闭合根节点与脚本/事件/外链禁项，Three 检查既有 `normalizeThreeScene` 的对象类型、维度和边界。

## 结果

- Qwen `qwen3.8-max`：pilot 返回 HTTP 401，错误码为 `invalid_api_key`；没有 finish reason，也没有 `[DONE]`。本地已有 key 未修改、未重试，20 条样例跳过。
- GLM `glm-5.3`：历史 16 KiB harness probe 触发了捕获上限；随后用 1 MiB wire cap、64 KiB assistant content cap 做了唯一一次真实 stream pilot，10.6 秒返回 `finish_reason=stop` 且收到 `[DONE]`。流式 pilot 证据在 [`glm-stream-pilot.json`](./glm-stream-pilot.json)。
- Mify `zhipuai/glm-5.3`：已有 endpoint 返回 HTTP 200，但受限 pilot 以 `finish_reason=length` 结束且没有可见协议内容；没有继续跑 20 条，避免把无内容响应算成可用模型。
- GLM 非流式 20 条全部拿到终态 `stop`，本次固定评估 18/20 通过完整性和交互断言，可用率 90%。SVG/Three 根结构检查 19/20 通过；失败集中在节点协议字段和图形边界。
- Mify `moonshot/kimi-k3` 的完整证据在 [`mify-model-evaluation.json`](./mify-model-evaluation.json)，20 条中 18 条通过，可用率 90%。失败分别是 `choice-01` 漏写 node 的 `op`，以及 `asset-04` 的 cylinder `size` 维度不符合既有 normalizer。

两份 artifact 都已用当前协议重新 replay，GLM 与 Mify 的最终结果均为 18/20；早先 GLM 的 19/20 是旧脚本版本的中间结果，不作为最终成绩。

## 取舍与边界

- 组合题的 `userText` 明确要求 choice/input 原生操作区和独立图示；独立 asset 题只要求 SVG 或 Three 围栏，避免把普通图形任务强判成智能 UI。
- 模型定义的状态、节点和图形 ID按块本地使用；只校验状态数量、引用闭合、选项、可见条件和 draft 占位符，不要求 fixture 内部 ID。
- GLM 的 90% 是非流式内容验收结果，同时独立 stream pilot 已确认终态收口正常；两者不能合并成“20 条流式全通过”。Mify 的 90% 同样是非流式验收。Qwen 没有生成成绩，不能写成格式失败或通过。
- 评分脚本的 `EVAL_SELF_CHECK=1` 自检通过 3 项，覆盖合法智能 UI、缺失 `op` 的拒绝和安全闭合 SVG。
