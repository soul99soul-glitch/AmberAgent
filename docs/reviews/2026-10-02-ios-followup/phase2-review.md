# Phase 2 独立 review

范围：本轮 MCP 分页、完整 schema/annotations、Inline SSE 增量及相应调用链；相对开始时快照识别修改，未评审或改写其他并行工作。本 reviewer 仅写此报告，不改源码、不并发构建。

## 具体发现与处理

1. **显式重新同步失败后状态停在 Connecting**。分页第二页出错时，原 `McpManager.sync` 已设 connecting 后直接传播错误；`addClient` 有外层 error 状态处理，但下拉 `syncAll → sync` 无该处理。独立运行新增真实 manager/transport 回归得到 95/96，唯一 RED 是 status connecting、预期 error。实现者已在 sync 的列表请求失败路径设置当前 client 的 error，目录完整保留。
2. **关闭后的重新连接失败仍停在 Connecting（已修复）**。首次修复只包 listTools；真实 `addClient → client.close → initialize 网络失败 → sync` 探针仍 reject network offline 而 status connecting。实现者已将 sync 的 connect 纳入同一 catch，保留当前 client identity guard。独立复验同一 probe 得到 error 状态及原网络错误，无新增重试。
3. **直接工具发现输出丢完整根 schema（已修复）**。`mcp_list(include_schema=true)` 与全工具 introspection formatter 只输出 properties/required，忽略新增 jsonSchema。包含根 `$defs` 与属性 `$ref` 的工具在自动 Provider 声明路径已完整，但 `mcp_list → mcp_call_tool` 直接调用时看到悬空引用且丢 oneOf 等约束。formatter 已在 jsonSchema 存在时输出完整 JSON，其余旧简单 schema 字符串保持；独立复验 mcp_list 精确 schema 深等及 tools_list/tool_search 根约束回归通过。
4. **实际工具 Registry 包装丢完整根 schema（已修复）**。实现者补充真实 registry 回归后发现 `withDisplayTitleHint` 重新构造 properties/required 时没有带 jsonSchema；此前直接 createMcpServerTools→Provider 的测试未经过这个实际 Entry 边界。实现者已保留完整 JSON 根 schema，注入既有 display_title 时复制 properties，不改服务器 canonical schema。review 已核对实际 `ConversationRunTools → createRunToolCatalog → registry.tools → scopeRunTools → tool_loop` 路径；dispatcher 原有机制会在调用 MCP 前移除 display_title，不向服务器传入额外提示字段。独立 registry→四协议最终请求、根约束、canonical 不变回归均通过。

## 已通过调用链检查

- `McpClient.listTools` 逐页使用原 opaque cursor，不 trim/重编码；每页原 JSON-RPC ID 独立匹配；重复 cursor 精确拒绝。所有页完成前不更新目录；后页错误、缺 tools 数组、client 被移除时保留旧完整目录。
- `McpManager.sync → settings → MCP settings JSON 序列化/读回 → createMcpServerTools → registry.tools → scopeRunTools → toChatToolDefinition → OpenAI/Responses/Claude/Gemini` 参数声明保留完整根 schema。OpenAI、Responses 与 Claude 直接发送完整 JSON；完整 MCP schema 的 Gemini 声明使用 parametersJsonSchema，既有普通工具 parameters 的兼容裁剪保持。发现 4 修复后已增加实际 registry 边界回归。
- annotations 由 list → sync → configuration serialization → mcp_list 完整保存、读回。其 advisory hint 未擅自代替现有用户工具审批设置。
- 修改现有工具 schema 时保留用户 enable/needsApproval 选择；服务器第二页工具进入实际可调用目录，不要求用户额外导入。
- Inline SSE 普通 POST 不改写 unrelated response ID；notification／非匹配结果不能冒充本次 response。CRLF 空行和 EOF 完整帧正确派发；多行 data 保持换行组成合法 JSON；解析错误仍沿原错误链传播。

## UI 与持久化证据边界

无新增 UI 控件。设置实际路由是 ChatMcpSettingsPage；subscribe 状态与目录变更后重赋值，工具页 List/ForEach 可显示第二页工具，已有长标题单行省略、参数 Flex 换行和底部间距复用。下拉刷新结束有 both-path 收口，具体发现 1、2 直接影响其状态 Chip，属于本 phase 应修复的真实 UI 状态问题。未依据源码声称已完成像素对齐／暗色设备截图。

设置页写穿由既有 McpSettingsStore 串行持久化；本轮目录原子性证据指所有分页成功才一次更新，与 JSON 往返完整。底层 KV 写失败／掉电验收不通过内存 fixture 冒充。

## 验证

- 独立 MCP 分页/manager/protocol/transports/tools/config 初轮 95/96：显式重同步失败状态 RED。
- 修复首处状态并完成 Google 根 schema 适配后，加实际 provider request 回归：144/144 GREEN。
- initialize 失败状态探针：修复前 connecting；修复后 error，原错误信息正确传播。
- 全部修复后独立 MCP/Provider 请求/Registry/exposure focused suite：166/166 GREEN。

状态：四处实际发现全部修复并独立复验通过，源码／调用链／目录原子更新／现有 UI 状态 review 完成，没有未处理的具体发现。构建与最终设备检查由主 agent 单独记录。
