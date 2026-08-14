# iOS Agent 自配置 Provider / Model 计划

状态：**P0–P3 已落地**（2026-08-14）  
产品目标：用户先在设置里**手动配通一个**可用的 chat provider + key + 模型，之后可以在对话里让 Amber agent **受控地**补齐其余 provider、拉模型目录、设默认模型与用途模型，而不必逐项点 UI。

本文件保留为设计与验收参考；实现事实以代码与 `docs/PROJECT_STATE.md` 为准。

---

## 1. 结论先行

### 1.1 用户期望

> 「先配置好一个，就可以让 agent 帮我全配置了。」

可拆成四层能力：

| 层 | 用户说法 | 是否合理 | 现状 |
|----|----------|----------|------|
| A | Agent **读**当前有哪些 provider / 哪个在用 / 有没有 key | 合理 | **部分有**：上下文可注入设置摘要；**无**专用只读工具 |
| B | Agent **拉**某 provider 的模型列表并写入设置 | 合理 | **无** agent 工具；UI 路径在 `ProviderDetailView.fetchModels` |
| C | Agent **写** API Key / baseUrl / 启用开关 / 默认 chat 模型 | 合理但高风险 | **无** agent 工具；写路径在 `IOSSharedSettingsStore` / Keychain |
| D | Agent **静默**在后台批量改配置、不经用户确认 | **不合理** | 明确禁止 |

### 1.2 一句话产品立场

**允许「配置助手」式工具：读状态 → 建议 → 经审批写配置 → 可选拉模型 → 验证。**  
**禁止**把 Keychain 密钥明文塞进 tool 结果回灌模型、禁止无审批批量写 key、禁止用 agent 覆盖用户未提及的高风险开关。

### 1.3 分阶段

| Phase | 名称 | 交付 | 开门条件 |
|-------|------|------|----------|
| **P0** | 只读盘点 | `provider_config_status`（脱敏） | 可立即开工 |
| **P1** | 写配置 + 审批 | `provider_config_apply` + 审批卡 + 审计 | P0 绿 + 安全审查过 |
| **P2** | 拉模型 + 设默认 | `provider_refresh_models` + `settings_set_model_slot` | P1 绿 |
| **P3** | 批量编排剧本 | 「配通一个 → 复制到同类 provider」工作流 + 引导 skill | P2 绿 + 产品确认 |

P3 不是默认必做；P0–P2 即可覆盖「先配一个再让 agent 补」的主路径。

---

## 2. 现状事实（2026-08-14 代码核对）

### 2.1 Agent 工具面没有配置写入口

`ai-core/.../Tool.kt` `IOS_TOOL_DECLARATION_PROVIDERS` 现有工具含 workspace / web / MCP / skill / subagent / council / session_search|read 等，**不含**：

- `provider_*`
- `settings_set_*`（配置类）
- 任何写 `apiKey` / `baseUrl` / `chatModelId` 的声明

`settings_open` 仅在 **Android** `IntentAccessTools`：打开系统设置页，与 Amber provider 配置无关。

结论：**模型无法通过 tool_call 直接改配置。**

### 2.2 配置权威与写路径（宿主已有，未接到 agent）

| 能力 | 权威 / API | 落盘 |
|------|------------|------|
| Provider 列表 + 模型目录 + 默认 chat/title/ocr/… | `IOSSharedSettingsStore` + KMP `Settings` | UserDefaults `app.amber.ios.sharedSettingsJson`（key 脱敏） |
| 写 API Key | `updateProviderApiKey`；侧表 `IOSCredentialSideTable` + Keychain | **密钥不进 JSON 明文** |
| 写 baseUrl / path / response API | `updateProviderEndpoint` | 同上 snapshot |
| 启用 / 改名 | `updateProviderBasics` | 同上 |
| 替换 / 合并 chat 模型列表 | `updateProviderChatModels` / `mergeProviderChatModels` | 同上 |
| 默认 chat 模型 | `setCurrentChatModelId` / `setCurrentAssistantChatModelId` | 同上 |
| OCR / 标题 / 压缩 / 生图模型 | `setOcrModelId` 等 | 同上 |
| 旧 registry 投影 | `ProviderRegistryStore.saveKey` / `select` / `project` | Keychain `app.amber.ios.provider.<id>` + SettingsStore 标量 |

UI 拉模型：`ProviderDetailView.fetchModels` → `OpenAIKmpProvider.listModelsOrThrow` / Claude 同类，再 `mergeProviderChatModels`。

### 2.3 安全现状

- JSON 里 `apiKey` 字段持久化为空；真实密钥在 Keychain / credential side-table。
- Agent 若 tool 返回明文 key 会进 transcript → 再上传 → **密钥污染对话**。
- 重装 App 后 Keychain 丢失是真实事故模式；工具设计必须支持「壳在、key 空、models 空」状态。

### 2.4 用户可复现的「半残配置」形态（与事故相关）

真机 plist 可出现：

- providers 壳还在（DeepSeek / OpenRouter / …）
- `models: []`
- `chatModelId` 悬空 UUID
- key 全空

Agent 自配置工具必须**先能诊断**这种状态，再谈写入。

---

## 3. 非目标（刻意不做）

1. **Android 同步实现**（可后补；本计划默认 iOS 先做）。
2. **把整份 Settings JSON 暴露给模型**（过大 + 敏感）。
3. **Agent 代填用户不知的第三方密钥**（密钥只能来自用户当轮输入或已批准粘贴）。
4. **OAuth 完整代登**（Codex / Gemini OAuth 仍走系统浏览器；工具最多 `setOpenAIAuthMode` + 提示用户去登录页）。
5. **改高风险运行时**（`autoApproveHighRiskToolCalls`、exec JS、沙箱 root 等）不在本能力范围。
6. **静默启用全部 bundled provider** 并默认全开联网。
7. **用 `workspace_file_write` 手改 plist** 当正式方案（绕过校验，禁止写进 skill 推荐）。

---

## 4. 威胁模型与安全不变量

### 4.1 威胁

| ID | 威胁 | 缓解 |
|----|------|------|
| T1 | 模型把 API Key 写进 assistant 文本 | Tool 入参 key **永不回显**到 tool result；UI 审批卡只显示 `****后四位` |
| T2 | 恶意/提示注入诱导批量改默认模型到攻击者端点 | `baseUrl` 变更强制审批；未知 host 二次确认 |
| T3 | 工具结果把 key 带回 messages 再上传 | result schema 禁止 `apiKey` 字段；只回 `hasKey: true` |
| T4 | 后台 run 无人看时写配置 | P1 起 **仅前台 Chat** 可写；后台 engine 不注册写工具 |
| T5 | 误删 bundled provider | 删除只允许 user-created；bundled 仅 enable/disable |
| T6 | 污染 polluted 会话 | 成功写入 key 的会话标记 `POLLUTED`（与 MCP/web 同源策略） |

### 4.2 不变量

1. **密钥只走 Keychain / credential side-table**，不进 `sharedSettingsJson` 明文。
2. **任何写工具默认 sideEffect + 审批**（与 `mcp_import_from_skill` 同级或更高）。
3. **读工具永远脱敏**：无 key、无 token、无 Authorization header。
4. **一次 apply 的变更集可审计**：写 `agent_event` 或本地 config-audit 行（providerId、字段名、是否改 key、是否改 endpoint、时间、conversationId）。
5. **写后校验**：`chatModelId` 必须解析到现有 CHAT 模型；否则拒绝 apply 并返回可修复错误。

---

## 5. 产品行为契约

### 5.1 主用户故事

1. 用户在设置里手动配通 **DeepSeek**（key + 拉模型 + 选 chat 模型），确认能对话。
2. 用户对新会话说：「按 DeepSeek 的方式，帮我把 OpenRouter 也配上；key 是 `sk-...`；默认 chat 用 deepseek/xxx。」
3. Agent：
   - 先 `provider_config_status` 看壳与缺项；
   - 再 `provider_config_apply`（带 key）→ 用户点批准；
   - 再 `provider_refresh_models`；
   - 再 `settings_set_model_slot(slot=chat, model_ref=...)`；
   - 最后 `provider_config_status` 复核，用自然语言汇报「OpenRouter 已启用，N 个模型，默认已设」。

### 5.2 失败时用户可见

- 审批拒绝 → tool 返回 `status=denied`，不部分写入（P1 事务：单工具单 provider 原子写）。
- 拉模型失败 → 保留 key，返回网络/鉴权错误，不清空已有 models（merge 语义）。
- 悬空 chatModelId → status 工具明确 `chatModelResolved=false`，引导 set_model_slot。

### 5.3 与「先配一个」的关系

「参考 provider」仅用于：

- 复制 **非密钥** 偏好（是否启用 web search 不在此列）；
- 提示 baseUrl 模板（OpenAI-compatible 路径）；
- **不**自动复制 key 到其它 provider（除非用户显式说「同一个 key 也用于 X」且 X 确认是同一账号体系——默认仍要求用户粘贴或明确授权复制）。

默认策略：**不跨 provider 复制 key**。

---

## 6. 工具设计（规范）

### 6.1 分类与暴露

| 工具名 | 类别 | 默认池 | 审批 | pure/sideEffect |
|--------|------|--------|------|-----------------|
| `provider_config_status` | utility | 常驻或 deferred 均可；建议 **deferred** + tool_search | 否 | pure |
| `provider_config_apply` | utility | deferred | **是** | sideEffect |
| `provider_refresh_models` | utility | deferred | 否* | sideEffect（写 models 列表） |
| `settings_set_model_slot` | utility | deferred | 否* | sideEffect |

\* 若 `provider_refresh_models` / `settings_set_model_slot` 会改变**当前会话正在使用的** chat 模型，P1 可要求轻确认；P0 不做写。

全部加入 `iosToolDeclaration` + ToolSearch 中文词条 + `category()` 映射（建议 `utility` 或新建 `settings`）。

### 6.2 `provider_config_status`（P0）

**用途：** 脱敏盘点，支撑 agent 诊断「壳在 / key 空 / models 空 / chat 悬空」。

**输入（可选过滤）：**

```json
{
  "provider_id": "uuid?",
  "provider_name_contains": "string?",
  "include_models": false
}
```

**输出（示例字段）：**

```json
{
  "providers": [
    {
      "id": "f099ad5b-...",
      "name": "DeepSeek",
      "type": "openai",
      "brand": "deepseek",
      "enabled": true,
      "base_url_host": "api.deepseek.com",
      "has_api_key": false,
      "auth_mode": "api_key",
      "chat_model_count": 0,
      "image_model_count": 0
    }
  ],
  "slots": {
    "chat": { "model_id": "2c54455a-...", "resolved": false, "label": null },
    "title": { "model_id": "...", "resolved": true, "label": "..." },
    "ocr": { "...": "..." },
    "compress": { "...": "..." },
    "image_generation": { "...": "..." },
    "suggestion": { "...": "..." }
  },
  "issues": [
    "chat model id does not resolve to any configured CHAT model",
    "provider DeepSeek has no API key",
    "provider DeepSeek has zero chat models"
  ]
}
```

**禁止输出：** apiKey、token、完整 baseUrl 若含 userinfo、任意 header 值。  
`base_url_host` 只给 host（可再加 path 是否自定义的布尔）。

**实现：** 读 `IOSSharedSettingsStore.snapshot` + `hasStoredKey`/credential side-table 是否非空；**不**调用网络。

### 6.3 `provider_config_apply`（P1）

**用途：** 对**单个** provider 做原子配置变更。

**输入：**

```json
{
  "provider_id": "uuid (优先)",
  "provider_name": "string? 用于解析唯一匹配",
  "enabled": true,
  "api_key": "string? 省略=不改；空字符串=明确清除（高风险）",
  "base_url": "string?",
  "chat_completions_path": "string?",
  "use_response_api": false,
  "name": "string? 显示名"
}
```

**校验：**

1. provider 必须存在；name 解析必须唯一，否则返回候选列表。
2. `base_url` 若改：必须 https（或明确允许的局域网测试 host，默认否）。
3. `api_key` 长度上下限；拒绝明显 placeholder（`sk-xxx`、`your_key`）。
4. 清除 key（空串）需要审批文案强调「将无法调用该 provider」。

**执行顺序（单 provider 事务）：**

1. 审批通过；
2. 若有 base_url/path/flags → `updateProviderEndpoint` / `updateProviderBasics`；
3. 若有 api_key → `updateProviderApiKey`（进 side-table/Keychain 流程，与 UI 一致）；
4. 返回脱敏状态 + `changed_fields: ["enabled","api_key"]`（key 只报 `api_key=updated|cleared|unchanged`）。

**审批卡文案要素：** provider 名、host、将改字段、key 是否更新（不展示明文）、是否影响当前 chat 模型解析。

### 6.4 `provider_refresh_models`（P2）

**输入：** `provider_id` 或唯一 `provider_name`；`mode`: `merge`（默认）| `replace_chat`。

**执行：**

1. 解析 provider → 注入内存 key（与 UI fetch 相同方式，**不**把 key 放进 tool 参数回传）；
2. 调既有 `listModelsOrThrow`；
3. `mergeProviderChatModels` 或 `updateProviderChatModels`；
4. 返回 `{ added, kept, total_chat_models, sample_labels: [] }`（最多 20 个展示名）。

**失败：** 401/403 → 明确「key 无效」；网络 → 可重试；不删除已有 models（merge 模式）。

### 6.5 `settings_set_model_slot`（P2）

**输入：**

```json
{
  "slot": "chat|assistant_chat|title|ocr|compress|suggestion|image_generation",
  "model_id": "uuid?",
  "model_ref": "string? 显示名/modelId 子串/provider+model 模糊匹配"
}
```

**解析规则：**

1. 若给 `model_id` 且能在 snapshot 中解析为 CHAT/IMAGE 等对应类型 → 使用；
2. 否则 `model_ref` 在**已配置且 enabled** 的 provider 的模型中做唯一匹配；多匹配返回候选；
3. slot 与模型类型不符则拒绝（chat slot 不要 IMAGE-only）。

**写：** 映射到 `setCurrentChatModelId` / `setOcrModelId` / …  
**返回：** 解析后的 provider 名 + model 展示名 + slot。

### 6.6 不做独立 `provider_add` 的 P0/P1？

`IOSSharedSettingsStore.addProvider` / `ProviderRegistryStore.addOpenAICompatibleProvider` 已存在。  
P1 可把「新增 OpenAI-compatible」并入 `provider_config_apply` 的变体：

- `create_if_missing: true` + `name` + `base_url` + `api_key`  
- 仅允许 type=openai-compatible 用户创建；bundled 模板（DeepSeek 等）只更新不创建重复。

P2 再拆 `provider_create` 若 apply 过载。

---

## 7. 接线架构

```
Model tool_call
    → ChatToolRuntime.executors["provider_config_*"]
        → IOSProviderConfigToolService (新，MainActor 或专用 actor)
            → 读/写 IOSSharedSettingsStore
            → 审批：复用既有 tool approval 通道（高风险 sideEffect）
            → 拉模型：复用 OpenAIKmpProvider/ClaudeKmpProvider listModels
    → tool result（脱敏 JSON）
```

### 7.1 文件建议（最小）

| 文件 | 职责 |
|------|------|
| `ai-core/.../Tool.kt` | 四工具声明 |
| `feature/tools/api/.../ToolSearch.kt` | 中文词条 + category |
| `iosApp/.../IOSProviderConfigToolService.swift` | 实现 + 脱敏 + 匹配 |
| `iosApp/.../ChatToolRuntime.swift` | 注册 executor；后台 engine **不**注册写工具 |
| `iosAppTests/IOSProviderConfigToolTests.swift` | 契约测试 |

### 7.2 与双轨 Provider 的关系

当前同时存在：

- **SharedSettings 真源**（Chat 主路径模型选择）
- **ProviderRegistryStore**（部分 UI / 投影到 SettingsStore 标量）

**本计划写工具一律只打 SharedSettings 真源**（`IOSSharedSettingsStore`），与 `ProviderDetailView` 主写路径一致。  
若某 UI 仍只读 Registry，P1 验收清单须列「改 key 后两侧 hasKey 一致」；必要时 apply 后触发一次 registry 同步（若代码仍双写，接到**已有**同步点，不新造第三套）。

### 7.3 前台 / 后台

| 场景 | status | apply | refresh_models | set_model_slot |
|------|--------|-------|----------------|----------------|
| 前台 Chat | ✅ | ✅ | ✅ | ✅ |
| 后台 continued-processing | ✅ 只读可选 | ❌ | ❌ | ❌ |
| SubAgent | ❌ 默认不可见 | ❌ | ❌ | ❌ |

---

## 8. 审批与账本

1. **账本分类：** `provider_config_apply` = sideEffect；`provider_config_status` = pure；refresh/set_model = sideEffect（改持久化设置）。
2. **审批：** apply 必须走用户可见批准；文案本地化中文。
3. **高风险自动批准开关：** 即使全局 high-risk auto-approve 打开，**仍建议对 api_key 写入强制人工批准**（或单独 capability gate `agentCanWriteProviderSecrets`，默认关）。
4. **polluted：** 含 `api_key` 的成功 apply → 标记会话 polluted。
5. **审计：** 最少打日志；P1 建议写 `agent_event` 或 Settings 旁路 `config_audit.jsonl`（无密钥）。

---

## 9. Prompt / 发现引导

1. ToolSearch 词条：「配置提供商」「API Key」「默认模型」「刷新模型列表」。
2. 发现引导补一句：  
   `在声称无法修改模型或 API 设置之前，先 tool_search 设置/provider 相关工具；写密钥必须等用户批准。`
3. **禁止** skill 教模型用 `workspace_file_write` 改 plist。
4. 可选：内置 skill `provider-setup`（只文档，无密钥）描述「先 status → apply → refresh → set_model_slot」。

---

## 10. 分阶段验收标准

### P0 — 只读盘点

- [x] 声明 + executor + 测试：无 key 时 `has_api_key=false`，issues 含悬空 chatModelId。
- [x] tool result JSON 经测试断言 **永不** 含 `sk-` / `apiKey` 字段名带值。
- [x] 前台 Chat 可 tool_search 到并调用。
- [x] 门禁：`IOSProviderConfigToolTests` 13 项 + effect-class pin。

### P1 — 写配置

- [x] 批准后 key 写入与 UI 手动写入同一 side-table/Keychain 路径。
- [x] 拒绝批准 → 不写 key。
- [x] 改 baseUrl 到非法 scheme → 拒绝（且先校验后写，不部分提交）。
- [x] 清除 key 需审批（空串）；结果仅 `api_key_status`。
- [x] 后台 run 调用 apply → denied「仅前台」。
- [ ] 真机：手动批准一张卡，DeepSeek key 写入后 UI 显示已配置（不展示明文）。

### P2 — 拉模型 + 槽位

- [x] refresh_models 接线（OpenAI/Claude listModels → merge/replace）。
- [x] set_model_slot 唯一匹配写入；多匹配返回 candidates。
- [x] 仅 enabled provider 参与 model_ref 解析。
- [ ] 真机：配通 DeepSeek 后，对话指令配置 OpenRouter（用户贴 key）端到端成功。

### P3 — 编排体验（可选）

- [x] 可选出厂 skill `provider-setup`（默认不启用）+ ToolSearch 引导。
- [x] skill 文案：不复制 key、禁止改 plist、禁止开 high-risk。
- [x] 审查修补：落盘/详情 sheet 脱敏；recipe 步骤强制 apply 审批；Claude promptCaching 保留。

---

## 11. 测试计划

| 测试 | 内容 |
|------|------|
| 单元 | 脱敏序列化、model_ref 唯一/多匹配、slot 类型校验 |
| 契约 | apply 审批拒绝 / 通过；refresh merge 不删手建模型 |
| 污染 | apply 带 key 后 conversation memoryMode=polluted |
| 回归 | ToolSearch 暴露、后台无写工具、IOSToolArgumentsFailClosed |
| 真机 | 手工：status 诊断半残配置 → apply → refresh → 发一条 chat |

Mock：`listModels` 注入；Keychain 用测试 double（与 ProviderRegistry 测试同模式）。

---

## 12. 实施顺序（建议 PR 切片）

1. **PR1 P0：** 声明 + status + 测试 + 发现引导一句。  
2. **PR2 P1：** apply + 审批接线 + polluted + 后台禁用。  
3. **PR3 P2：** refresh_models + set_model_slot + 真机验收清单。  
4. **PR4 P3（可选）：** skill + 批量话术，无新危险原语。

每 PR 必须：`git diff --check`、定点单测、不扩大翻译范围除非加用户文案。

---

## 13. 开工前复核清单（必须重跑）

```text
[ ] iosToolDeclaration 是否仍无 provider_config_*（防止重复做）
[ ] IOSSharedSettingsStore.updateProviderApiKey / mergeProviderChatModels 签名是否变化
[ ] ProviderDetailView.fetchModels 用的 listModels API 是否变化
[ ] 审批通道：前台 tool approval 入口函数名
[ ] 后台 ChatToolRuntime 是否与前台共用注册表（避免误注册写工具）
[ ] Credential side-table 与 Keychain 前缀是否仍为 app.amber.ios.provider.*
[ ] polluted 标记写入点（P2-a 记忆）是否仍在 messagesByFinishingToolCall 附近
[ ] 双轨 Registry vs SharedSettings：当前 Chat 解析模型以哪边为准
```

---

## 14. 风险与回滚

| 风险 | 回滚 |
|------|------|
| 密钥泄漏进历史 | 立即默认关闭写工具声明；会话标记 polluted；用户轮换 key |
| 写坏 chatModelId 无法对话 | set_model_slot 失败不写；提供 status 修复；UI 仍可手选 |
| 与 Registry 双轨不一致 | 文档化真源；热修同步；最坏去掉 agent 写工具保留 UI |

回滚开关建议：`UserDefaults` / agentRuntime 旗标 `agentProviderConfigToolsEnabled`（默认 **P0 开只读 / P1 写默认关或仅 debug**，产品验收后默认开写）。

**推荐默认：**

- P0 status：默认启用（低风险）
- P1 apply：默认启用但**强制审批**
- 清除 key / 改 baseUrl：强制审批且文案加重

---

## 15. 成功标准（产品）

用户在**仅手配一个** DeepSeek 可用的前提下，对 agent 说：

「把 OpenRouter 也配上，key 是 …，默认聊天模型用 …」

在**一次会话、用户点 1 次批准**后：

1. OpenRouter `has_api_key=true`
2. 模型列表非空
3. chat 槽位 resolved
4. 新开对话能真正打到该模型  
5. 工具结果与聊天记录中**无** key 明文

---

## 16. 与事故复盘的关系

2026-08 真机重装导致 Chat 与 Keychain 丢失的经验：

- Agent **不能**假设 key 仍在；status 必须诚实。
- 未来若做「配置导出/导入」备份，是**另一计划**；本计划不解决备份，只解决「有 key 时的受控写入」。
- 真机安装纪律：动设备前备份 Documents + Preferences + Application Support（工程流程，不单属本功能）。

---

## 17. 文档与状态

- 计划本文：`docs/IOS_AGENT_PROVIDER_CONFIG_PLAN.md`
- 入口：`docs/README.md`「仍开放但不是默认任务」
- 开工/完成时更新 `docs/PROJECT_STATE.md` 一小节

---

## 18. 开放决策（实施前需产品拍板）

1. **P1 写工具默认开还是默认关？**（建议：开 + 强制审批）  
2. **是否允许 agent 清除 key？**（建议：允许但审批文案最重）  
3. **是否允许跨 provider 复制 key？**（建议：默认禁止）  
4. **refresh_models 是否算高风险审批？**（建议：否，因不碰 secret 明文）  
5. **P3 是否做？** 还是 P2 足够？

未拍板前按第 1–4 条「建议」实现，并在 PR 描述中写明。
