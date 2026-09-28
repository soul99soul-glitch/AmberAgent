# E4 Recipe model / validation

2026-09-28，model owner `/root/e3_python_domain`。本域实现完成；E4 的 Store、逐步 runner、审批/目录接线与 UI 验收由对应 owner/root 汇合，不以本报告代替阶段验收。

## 交付与公共接口

- `harmony/chat/src/main/ets/chat/recipes/models.ts`：冻结合同的 manifest/step/input/issue/envelope/descriptor/installed、run/import checkpoint；schema/步骤数/默认及最大 timeout 常量。`RecipeImportPreview` 仅 type import `ports.ts`，不引入运行时循环依赖。
- `recipes/validation.ts`：`decodeRecipe(text):RecipeManifest`、`validateRecipe(manifest,primitives):RecipeIssue[]`、`canonicalRecipeJSON(manifest):string`、`recipeEnvelope(manifest,primitives):RecipeEnvelope`。
- Runner helper `parseRecipeBinding(value:JsonValue):RecipeBinding|null`，binding=`{kind:'input'|'step',name:string,field:string|null}`；input 的 field 为 null，step 的 name 为 id。
- Runner helper `normalizeRecipeInputs(manifest,raw:JsonValue):JsonObject`：先剥离宿主 `display_title`，再严格校验恰好匹配声明输入、string/number/boolean 类型及有限 number；不改原调用对象。
- `RecipeValidationError extends Error`，只公开 `readonly code:string`、`readonly issues:RecipeIssue[]`。解码 JSON/形状错误 code=`invalid_manifest`，调用输入错误 code=`input_invalid`；语义校验返回真实 iOS camelCase issue code/path/message。

本 owner 仅新增上述两份源文件、一份 `src/test/recipe_validation.test.ts` 与本报告，未改 index/DI/policy/Store/runner/UI/shared 文件；保留并读取并行 owner 已落盘的 `ports.ts`。

## 行为证据

依据实际 `amberagent-ios/iosApp/iosApp/IOSRecipeModels.swift` 与 `IOSRecipeRunner.swift` 核对，而非依据过时注释扩展规则：

- schema 字段必需，未知字段忽略且 canonical 重建时不保留；input/step/output 名允许 1–32 字符，Recipe 名要求 2–32 字符。整串匹配，避免 JavaScript `$` 对末尾换行的宽松匹配。
- 1–8 步、id 唯一、当前 primitive 目录存在；拒绝 recipe__/plugin__ 以及六个 Recipe 管理工具。timeout 为整数 1–600；缺省/JSON null 与 iOS Optional 一致，canonical 不新增默认字段。
- 仅 argument 顶层完整字符串展开绑定；嵌套合法绑定保持 literal，不误检其未声明引用；嵌套 `${` 开头的非法绑定递归报出路径。step 只能引用前序，outputs 只能绑定已存在 step 的顶层字段。
- JSON literal 保留数组序、Unicode 与所有自身 key；canonical 直接递归序列化排序 key，避免将 literal `__proto__` 复制到普通对象时丢键。只对原始 JSON 解码及 canonical 做有限数值检查，不加入深度/重试/通用框架。
- 权限摘要按当前 tool metadata、`toolInvocationPolicy`、基础 mutates/risk 聚合，不由 Recipe 名推断；基础高风险不因尚未解析的绑定参数而降低。实际逐步批准仍归现有 runner/permission 链，不由摘要授权。
- Store owner 用真实 Swift canonical fixture 确认 `/` 转义差异：`结果/output.json` 对比 iOS 为 `结果\/output.json`，红证据 `/tmp/e4-recipe-store-slash-red.log`。本域只增加同一个 canonical string 编码用于 key/value，跟随 iOS JSONEncoder.sortedKeys 默认转义行为；完整 hash/实文件证据由 Store 报告记录，本报告不声称任意 JSON 数字编码已跨平台穷举一致。

## 必要验证

一组 fixture、五个相关断言组：合法 schema/literal/canonical/绑定、形状失败及 null timeout、名称/步骤/工具/预算/引用分支、严格调用输入、真实 primitive 权限摘要。

- 初始红：validation 尚未实现，测试导入失败 `ERR_MODULE_NOT_FOUND`（`/tmp/e4-model-validation-red.log`）。这是新功能未实现的基线，不冒充既有 bug 行为复现。
- 绿：`./node_modules/.bin/tsx --test src/test/recipe_validation.test.ts`，**5/5 PASS**；slash 修正后同组复核绿，`/tmp/e4-model-validation-green.log`。
- 类型：`./node_modules/.bin/tsc -p /tmp/e4-model-validation-tsconfig.json`，**exit 0**。只 include 两源与必要 fixture，经 type import 检查现有依赖，不运行全 chat 类型或 suite。
- ArkTS lint：`harmony/scripts/lint-arkts.sh` 指定两源，**0 error / 172 warn / exit 0**，`/tmp/e4-model-validation-lint.log`；warn 为 SDK 单文件分析 import 无法解析导致的 unsafe 诊断，没有放宽规则。CodeLinter 必需的 SDK codelinter.log 写权限已按现有授权请求 escalated，未改 SDK 配置。
- `git diff --check -- <两源及 fixture>`，**exit 0**。
- Store owner 独立执行并回喂的真实 Swift/JS 同一夹具，slash 修正后 **1/1 PASS**（本 owner 读取日志，未重复运行）：Unicode + slash canonical **357 bytes**，hash=`fbc71d9558f002fcaba9b9f74de1b5743de7a6d0f13a38f057d59cf78fd55790`；`/tmp/e4-recipe-store-fixture.log`。范围仅此明确夹具的跨端字节/hash 一致。

未重复全 suite、构建、设备、UI 或并行 owner 的文件 IO 场景；阶段统一检查和独立 subagent review 由 root 负责。

## 独立审查精修：保留输入名

Reviewer `/root/e3_native_python` 确认 P2：`inputs.display_title` 原先符合名称规则并被 wrapper schema 声明为 required，但宿主严格调用输入前始终剥离 `display_title`，因此此 Recipe 通过安装后永远失败。按 root 裁定，`validateRecipe` 的既有 input name 循环只新增单点 `reservedInputName`（path=`inputs.display_title`），直接拒绝这个宿主保留名，不改调用输入、Store 或批准协议。

- 在原域 fixture 文件新增唯一对应断言，使用 `--test-name-pattern='manifest rejects host-reserved display_title'` 只执行这一个回归。
- 修前 **1 failed / exit 1**，真实行为红证据 `/tmp/e4-model-reserved-title-red.log`；修后 **1/1 PASS / exit 0**，`/tmp/e4-model-reserved-title-green.log`。没有重复先前五组域测试或 Store 实文件组。
- 原 scoped types **exit 0**；只对改动 `validation.ts` 的 ArkTS lint **exit 0 / 0 error / 172 warn**，`/tmp/e4-model-reserved-title-lint.log`；diff check **exit 0**。
- 已回喂独立 reviewer/root 复核收口。此限制是本端宿主输入协议要求，明确记录为相对于 iOS 纯 manifest 名称规则的必要本端差异。
