# E4 手动 Recipe 启动平台函数

2026-09-28，owner：e2_platform_secure。依据 E4 frozen contract 与 `E4-manual-run-seam.md`，只新增 `platform_impl/RecipePageLaunch.ets`、一个实域夹具及本报告；不改 AppContainer / ChatPage / shared domain，不操作 build/device，不改变 E3 C 待验结论。

## DI 签名与行为

```ts
export interface RecipePageLaunchDeps {
  store: RecipeStore;
  primitives: AgentTool[];
  assistant: Assistant;
  repository: ConversationRepository;
}
export const startEntryRecipeFromPage = async (
  deps: RecipePageLaunchDeps, name: string, expectedHash: string, inputs: JsonObject,
): Promise<void>
```

root 传实际默认 Assistant、已按其 localTools/toolProfile 过滤的 primitive 集、同一 RecipeStore 和实际 RDB repository；助手/provider hydration 与 ChatPage/tool-loop 的零模型续跑由 root 接线。本文件58行，不含第二执行器。

1. 只 listInstalled 一次，精确选 enabled + name + expectedHash；缺失/变化/停用抛 code=stale_base，不静默选择新版本。
2. 用同一 descriptor 调真实 createRecipeRun，严格 inputs 与宿主 display_title 移除复用 domain；validateRecipe 检查当前 primitive 能力，问题 code=invalid_recipe，尚未保存/导航/执行。
3. wrapper ToolPart 是真正 `recipe__name`、newId、AUTO、output=[]；metadata 为 `recipe_manual=true` 与 JSON 可持久化的 `recipe_v1` checkpoint。input 使用 checkpoint 的 normalized inputs。createRecipeLoopAdapter.prepare 校验这个已有 checkpoint，不重读 live Store 或替换 descriptor。
4. 用真实 makeConversation/newId、assistant.id、`Recipe · name` 标题、seedConversationWithPresets、makeUserMessage/makeUIMessage/toMessageNode 构造新会话；保留真实 presets。父 ToolPart 没有伪 modelId/usage、没有外层 approved，也没有自动批准设置。
5. await repository.save 成功后才 await 已有 router.pushUrl，目标 `pages/ChatPage`，params 为 `conversationId` 与 `recipeManualToolCallId`。保存错误原样传播，无导航。入口不调用 provider/模型、Adapter.advance、primitive.execute，也不建立恢复调度器。

## 一个实域必要夹具

```text
node --test .workflow/harmony-remaining/fixtures/E4-recipe-page-launch.cjs
1/1 pass，0 fail/cancelled/skipped，约0.38s，exit 0
```

fixture 直接 transpile 本平台源及真实 domain createRecipeRun/Adapter、manifest validation、Assistant、Conversation/message 构造器和 createMemoryConversationRepository。该 repository 使用真实 conversation/node 行映射与消息 JSON parse/serialize，验证保存后的 checkpoint 重开结构；router 仅为记录导航的注入边界，不冒充真实页面或 RDB。

- 保存 promise 等待期间 navigation=0，真实 repository 为空；此时把 live 包替换 v2 并改调用者 inputs，保存后仍是 v1/hash/canonical/原 Unicode 输入、phase=ready/nextIndex=0/pendingStep=null。
- 唯一 listInstalled，持久 ToolPart 与 route toolCallId 同一；真实 assistant.id/标题/presets/用户意图/assistant父part可读，AUTO/空output/manual marker、conversation.autoApproveToolCalls=false，modelId/usage=null。
- 对持久 parent 再调用同一个真实 Adapter.prepare，尽管 live 包已 v2，仍保留 v1 checkpoint且不新读 Store。
- actual memory repository 的保存边界注入固定错误，launch 原样 reject，零新增导航/会话；旧 hash 与缺失 primitive 也在 save前拒绝。
- primitive.execute 计数始终0；使用真实 Assistant 构造器且 chatModelId=null，没有构造 dummy model/provider。这里证明**启动平台函数**不需要模型、不执行步骤；完整 ChatPage 首跑/审批续跑零模型仍由 root 的 loop fixture 单独证明。

证据 `/tmp/e4-recipe-page-launch-fixture.log`。未追加排列组合或重跑全 chat/domain suite。

## 静态边界

单文件 `harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/RecipePageLaunch.ets`：exit0、**0 error / 1 warn / 0 suggestion**，日志 `/tmp/e4-recipe-page-launch-lint.txt`。warning 是36:39 `@performance/bad-deep-clone-check`：checkpoint 转 JsonObject 的 JSON序列化；保留与实际可持久化协议相同的转换，没有为消提示引入 API12 缺少仓库先例的 structuredClone。源/夹具 diff-check通过。

root 统一 `/tmp/e4-release-build.log` 真实回喂后，`arkts-no-ctor-prop-decls` 改为显式 readonly code 字段+constructor赋值；首次按诊断位置给 conversation patch 加显式局部 RecipeConversationNodesPatch 接口/typed patch。当前真实 domain signature仍是 Partial<Omit<Conversation,'id'>>，并无 ConversationPatch导出；局部接口只声明 messageNodes，不修改 shared signature/export。两份自有entry精确lint exit0、0error/1warn/0suggestion，原JSON桥接warn移至39:39，`/tmp/e4-recipe-entry-compile-fix-lint.txt`。

第二次统一构建 `/tmp/e4-release-final-build.log` 仍有一个 untyped literal，root 精确定位到 overloaded router.pushUrl 字面量，先前 typed patch 没有消除该实际来源。只补 `const route: router.RouterOptions = {url:'pages/ChatPage',params:params}; await router.pushUrl(route)`，typed nodes局部保留。已查仓内 ChatListPage.ets:586 的 router.RouterOptions先例，并 official docs search/read确认 API9 Promise签名与 url:string / params:Object，缓存 `/tmp/e4-router-official.txt:24,1481`。最终单文件lint exit0、0error/1warn（仍仅39:39 JSON桥接）/0suggestion，`/tmp/e4-recipe-page-launch-route-lint.txt`；diff-check通过。上述皆类型/写法修复，行为不变，按 root 指令未重跑已绿夹具或build/device，修后统一编译结论由 root 报告。

唯一平台 API 为仓库已用的 `@kit.ArkUI.router.pushUrl`；未新增 API 或原生依赖。此结果不声称 Harmony API12 build、实际 RDB 落盘、路由页面或设备 UI 验收通过；root 统一负责薄 DI、ChatPage 消费/manual续跑、构建和视觉门。
