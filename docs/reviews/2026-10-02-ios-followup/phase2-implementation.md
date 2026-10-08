# Phase 2 MCP implementation

## Product behavior and scope

`McpClient.listTools` follows all `nextCursor` pages before returning. The cursor is sent verbatim (including whitespace, Unicode and encoded punctuation), and the first request still omits params. All pages form one discovery result; a later JSON-RPC error, malformed tools array, repeated cursor or transport cancellation rejects discovery and leaves the saved catalog intact. A repeated cursor check prevents a concrete infinite pagination loop rather than adding a page cap or retry.

`McpManager.sync` consumes the complete discovery result once, preserving existing enable/approval settings. Explicit sync failures during initialize or listing now set error status for the current client; old or removed clients do not overwrite a replacement. Removal during a pending second page closes the request and does not install a partial catalog.

Tool schemas preserve root constraints/definitions/composition as an optional complete JSON schema beside the existing local shorthand. Settings serialization writes that complete schema as the original `inputSchema`; reload restores it. Annotations also survive discovery, settings persistence and management inspection. Server annotations do not change the saved approval policy.

The real registry wrapper retains the complete schema while injecting the existing optional `display_title` property into a copy. Saved MCP schemas remain unchanged. `tool_search`, `tools_list` and `mcp_list(include_schema=true)` expose the complete schema, avoiding dangling references that lose their root definitions. The existing dispatcher removes `display_title` before calling the server.

At the provider boundary, OpenAI Chat Completions, OpenAI Responses and Claude receive the complete parameters. Gemini uses the documented `parametersJsonSchema` field for complete schemas, mutually exclusive with the narrower `parameters` field. Ordinary local tools retain the existing Gemini schema projection.

Streamable HTTP POST SSE preserves JSON-RPC IDs instead of rewriting every result to the current request ID. Notifications and unrelated results cannot complete `tools/list`. The line parser joins multiline data using newline, handles residual CR characters, and dispatches the complete final frame at EOF. Entry's existing line decoder already strips CRLF; it was inspected and left unchanged. Existing GET/resumption mechanics were not expanded.

## RED/GREEN and independent review fixes

- Initial discovery regressions: 4/4 RED, covering missing page 2, false success on later-page failure, cursor loop, and missing cancellation path; GREEN after implementation.
- SSE regressions: 3 concrete RED cases for ID rewriting, unrelated multi-event responses and CRLF/EOF; GREEN after the narrow transport fix.
- Explicit resync page failure reproduced a status left at connecting; now error with the prior complete catalog retained.
- Reviewer found initialize failure outside the status catch. A real transport failure regression reproduced RED and passed after connect/list moved into the same identity-guarded catch.
- Reviewer found complete schemas omitted from inspection. The added registry discovery regression then identified the deeper `withDisplayTitleHint` reconstruction loss. Both formatter and registry projection were fixed; the same regression is GREEN and now asserts registry-to-provider behavior for all four protocol boundaries.
- Focused verification: **188/188 passed** in MCP protocol/transports/config/manager/tools/followup, provider request, introspection, tool policy and registry suites. Root also runs the integrated suite separately.
- Scoped ArkTS lint initial nine source files: **0 errors / 820 warnings**, exit 0. Warnings include the tool's unresolved shared TypeScript type diagnostics; no claim that these warnings are new or cleared.
- Review-fix scoped lint for manager/tools/introspection/policy: **0 errors / 819 warnings**, exit 0 (`/tmp/amber-phase2-mcp-review-fixes-lint.log`). Chat `tsc --noEmit --pretty false` passed. Root build results are recorded separately by the phase integration report/logs.

No Entry/UI layout was changed in this phase. Existing MCP settings status and discovered tool list consume the same manager/settings paths. Product UI device inspection belongs to root's phase review and final integrated installation. Fixtures prove protocol, persistence, cancellation and provider request construction, not a real external MCP server or paid Provider request.

## Changed responsibilities

- MCP: `mcp_protocol.ts`, `mcp_manager.ts`, `mcp_config.ts`, `mcp_config_serialize.ts`, `mcp_transports.ts`, `mcp_tools.ts`.
- Actual schema boundary: `tool.ts`, the optional definition field in `provider_model.ts`, Gemini declaration branch in `google_request.ts`, `withDisplayTitleHint` in `tool_policy.ts`, complete-schema formatting in `builtin_introspection_tools.ts`.
- Regressions: new `mcp_followup.test.ts`, corrected protocol assertions/new transport cases in `mcp_transports.test.ts`.

Preexisting and other workers' changes were preserved. No commit, push, framework, extra retry or new approval flow was introduced.

## Official protocol references

- [MCP 2025-06-18 tools and pagination](https://modelcontextprotocol.io/specification/2025-06-18/server/tools)
- [MCP 2025-06-18 transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)
- [Gemini FunctionDeclaration parametersJsonSchema](https://ai.google.dev/api/generate-content?hl=zh-CN)
- [Vertex FunctionDeclaration JSON Schema fields](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/rest/Shared.Types/FunctionDeclaration)
