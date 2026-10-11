# P0 原始失败消息取证缺口

## 结论

当前没有拿到截图对应的原始 JSON。模拟器 `MyPhone` 是已存在的空会话，启动前后应用消息列表均为 0；可读的本机和模拟器日志也没有精确匹配 `Unexpected end Text in JSON` 或 `finish_reason` 的记录。

## 已检查的来源

- 模拟器 bundle：`app.amber.deepread`。
- `hdc -t 127.0.0.1:5555 shell bm dump -n app.amber.deepread` 和应用启动结果。
- 当前模拟器 hilog、模拟器历史压缩 hilog，以及工作区内 Harmony 流解析与 SVG 相关日志。
- 工作区的合成 JSON/SSE fixtures，仅用于确认已有测试覆盖，不当作用户消息。

## 能确认与不能确认的内容

能确认：错误文本说明 JSON 解析器在输入末尾仍期待闭合字符；当前代码路径曾把 SVG 作为 `widget_code` 字符串放进外层 JSON，因而外层 JSON 截断会遮蔽图形内容。

不能确认：截断发生在模型输出、输出长度限制、SSE/HTTP 传输、消息 chunk 合并，还是输出转换器；也不能从截图推断 finish reason。

不得把 synthetic `length`、`stop` 或 `unknown` fixture 的结果写成用户原请求原因。
