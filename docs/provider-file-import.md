# 批量导入 Provider

在「设置 → 提供商 → 导入供应商配置 → 从 JSON / Markdown 文件导入」选择 UTF-8 文件。

文件可以是 JSON 数组，或包含 `providers` 数组的 JSON 对象。Markdown 文件使用下面这样的 `json` 围栏代码块；多个 JSON 块会按顺序合并，其他文字不参与解析。文件最多 100 万字符，任一条目格式错误会中止整个预览，不会部分导入。

```json
{
  "providers": [
    {
      "name": "Qwen",
      "type": "openai",
      "baseUrl": "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1",
      "apiKey": "REPLACE_WITH_YOUR_KEY",
      "models": [
        {
          "modelId": "qwen3.8-flash",
          "displayName": "Qwen3.8-Flash",
          "inputModalities": ["TEXT", "IMAGE"],
          "abilities": ["TOOL", "REASONING"]
        }
      ]
    },
    {
      "name": "Example Provider",
      "baseUrl": "https://example.com/v1",
      "apiKey": "REPLACE_WITH_YOUR_KEY",
      "models": [
        {
          "modelId": "example-model",
          "contextWindowTokens": 1000000,
          "customHeaders": [{"name": "User-Agent", "value": "AmberAgent/2.6.8"}]
        }
      ]
    }
  ]
}
```

- `name` 和 `baseUrl` 必填。`type` 默认为 `openai`，也支持 `claude`、`google`，分别使用应用现有的 OpenAI、Anthropic、Google 配置字段。未知字段会报错，避免密钥或模型字段拼错后被忽略。
- `models` 可省略，稍后在应用中添加。提供模型时，`modelId` 不能为空；`displayName` 省略时使用模型 ID。上下文窗口填写正整数，不使用 `1M` 这样的缩写。
- `apiKey`、`customHeaders`、`customBodies` 等字段沿用应用现有配置格式。账号 OAuth 登录状态不随文件迁移，需要在应用中单独登录。
- 预览仅显示名称、模型数量和同名提示，不显示密钥。同名比较忽略大小写和首尾空格，也检查文件内部的同名项；重复项默认不勾选，手动勾选后作为另一份新配置导入。
- 确认后新增所选 Provider，不覆盖现有配置。Provider 与模型 ID 会重新生成。密钥由应用现有密钥存储流程处理。
- 导入文件本身仍含明文密钥。导入成功后，可以自行删除文件；应用不会删除用户选择的原文件。
