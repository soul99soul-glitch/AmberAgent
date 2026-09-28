# E5 plugin loop integration fixture

Status: **PASS (2 focused tests)**.

Owned changes: `harmony/chat/src/test/plugin_loop.test.ts` only. No shared implementation, DI, ChatPage, native source, build, or device changes were made by this fixture owner.

## Evidence

Command, from `harmony/chat`:

```sh
./node_modules/.bin/tsx --test src/test/plugin_loop.test.ts
```

Final behavioral output: **2 passed, 0 failed**, 144 ms; `/tmp/e5-plugin-loop-fixture-rerun.log`.

After root added the optional plugin branch to the shared child-scope configuration, the second test was extended and only that affected test was run once:

```sh
./node_modules/.bin/tsx --test --test-name-pattern='test/import mixed batch' src/test/plugin_loop.test.ts
```

Result: **1 passed, 0 failed**; `/tmp/e5-plugin-loop-child-scope.log`. The unaffected manual case was not repeated.

Final strict chat TypeScript check, after supplying the existing provider factory required by the child fixture's `ToolLoopOptions`:

```sh
./node_modules/.bin/tsc --noEmit
```

Result: **exit 0**, no diagnostics; `/tmp/e5-plugin-loop-final-tsc.log`. The type-only correction did not repeat the passing behavioral fixture.

The fixture uses real package validation/canonicalization with a Node SHA256 port, plugin and Recipe adapters, ordered mixed batch, tool loop, permission resolver/dispatcher, registry/search/schema refresh, and `McpManager`/JSON-RPC transports/`createMcpServerTools`. Conversation persistence JSON-clones snapshots, so assertions inspect the durable saved value instead of a shared object reference.

1. **One manual plugin parent, two distinct JS host calls.** A fixed checkpoint starts at AUTO, then each `file_write` separately pauses for approval despite ordinary `file_write` run trust. Each effect asserts the most recent saved checkpoint is `started`, with its exact child arguments and captured target. Changing the current capture target does not change the child already approved. Disabling the installed package after the first pause removes its fresh wrapper, while the pinned parent continues under its original package hash/execution ID and existing session. The final counters are **0 model streams, 1 JS start, 2 replies, 2 writes**; the final saved parent matches the returned parent.
2. **Candidate test → mixed Recipe/import/sibling → fresh catalog execution.** The fixed candidate test returns the actual `{doubled:42}`, matching `expected_result` and its content hash, while registering nothing and leaving live health untouched. A normal model batch executes a Recipe read, then pauses at `plugin_import` under **both auto-approval flags enabled**. The later ordinary write has zero effects before approval. Approval imports that exact package hash, then the sibling write executes in order and the completed Recipe checkpoint remains intact. Catalog refresh exposes the installed numeric wrapper in actual model schemas and `tool_search`; its execution uses the fixed MCP server. Disabling and deleting remove the wrapper from subsequent schemas and execution, and the original identical `tool_search` query after deletion returns no expansions. Finally, keeping a valid installed descriptor in the adapter while removing its wrapper from current tools rejects a fresh call with no extra MCP effect.

The MCP identity assertion deliberately uses same-name tools from server IDs `demo-server` and `demo_server`, whose generated suffixes collide. The real tool bridge assigns the latter `mcp__double__demo_server__2`; candidate and installed calls both go through the actual manager to the second server's controlled endpoint. Both assert the exact child alias was saved in a `started` checkpoint before `tools/call`.

The second test also loads the current `ScopedRecipeLoop.ets`, transpiles its ordinary TypeScript module body, and executes `configureEntryScopedRecipeLoop` through the real chat-domain module shim, following the existing subagent fixture pattern. A child initially permits only the second MCP primitive, `file_read`, one plugin wrapper, one Recipe wrapper, and its report tool. A validated refreshed package additionally contains an unpermitted member: refresh retains only the original permitted wrapper, passes the exact child primitive oracle to both Store and adapter factory, and prepares the fresh package hash. It cannot introduce the extra member, global `file_write`, or the first server's MCP tool. Disabling removes the plugin wrapper while preserving Recipe, the exact original report object/schema/executor, and the primitive set. There are no extra MCP effects.

## Limits and focused correction

The JS port is a controlled event fixture; it does **not** evaluate JavaScript or instantiate a JSVM. Its counters prove the domain calls `start` once and replies to the same session, not that native VM stack persistence or cancellation passed. MCP HTTP responses and Store ports are controlled fixtures; this does not claim a live remote server, atomic filesystem installation, platform signing, device UI, or build acceptance. Those remain with their phase owners.

The initial run failed both tests during package validation (`/tmp/e5-plugin-loop-fixture-initial.log`): the fixture declared relative capability prefixes `data`, while the current validator requires canonical `/workspace` paths. The fixture was corrected to `/workspace/data` and its actual host arguments, then only this new file was rerun. This is a fixture correction, not a product-source red/green claim. Two type-only fixture corrections reported by the domain owner's compilation were applied afterward: the mutable server collection is `McpServerConfig[]`, and the JSON-RPC schema is a JSON-cloned wire object. The domain owner confirmed its subsequent chat compilation exited 0. The later child-scope extension passed its focused run on the first attempt; its required provider factory was then supplied and the final strict chat compilation exited 0. Native/platform application builds remain with root.

Only these two tests were introduced; no test matrix, full-suite repetition, native rebuild, or device repetition was added.
