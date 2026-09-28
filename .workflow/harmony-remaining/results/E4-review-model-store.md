# E4 model / validation / ports / RecipeStore 独立 review

2026-09-28，reviewer：`/root/e3_native_python`。当前结论：**PASS，无开放 P1/P2**。下述输入保留键 P2 已精准修复并独立只读核对收口。本次只读产品源、冻结合同及已落盘必要证据；只写此报告，未修改源文件、重复运行 suite/lint/build 或操作设备。

## 已修复 P2：宿主保留的 display_title 能作为必需 Recipe 输入登记，但运行时必然丢失

修前位置：`recipes/validation.ts:103–105` 仅检查输入名正则，允许 `display_title`；`:152` 无条件从实际调用键中移除它，`:153–156` 仍将 manifest 声明的同名输入视为必需。`recipes/tools.ts:77,82` 也把所有声明输入放入 schema properties/required；`recipes/runner.ts:69–72` 的实际调用会进入该 normalize helper。现有 `tool_policy.ts:642–668` 给工具注入 `display_title`，并覆盖同名属性为宿主操作标题。

原可执行 trigger：在 otherwise 合法、引用可用 `file_read` 的 manifest 中声明 `inputs: {"display_title":"string"}`，步骤参数 `{"path":"${input.display_title}"}`。该 manifest 的名字/类型/绑定/primitive 校验均通过，并可安装、生成 `recipe__name`。调用传 `{"display_title":"/workspace/input.json"}` 后，该键仍被剥离，因此必然产生 `input_invalid` / `inputs.display_title: 缺少必需输入`；通过同一 runner 的 UI 运行也一样。若声明 number/boolean，schema 还与宿主 string 属性冲突。

最终修复：model owner 在 `validation.ts:105` 的既有输入名循环增加唯一 `else if (name === 'display_title')`，返回 `reservedInputName` / `inputs.display_title` 和明确宿主保留名文案。Store candidate 仍调用同一 validator，wrapper 仍拒绝有 issue 的包，因此该冲突在安装和目录创建前都被阻断。normalize / Store / 宿主批准协议未新增分支。owner 报告明确记录这是本端宿主输入协议要求、相对于 iOS 纯名称规则的必要差异；root 已裁定该最小限制。

## 标准与 spec 核对

| 边界 | 只读审查结果 |
| --- | --- |
| schema / decode | 必需字段与三种输入类型按合同解码，未知 schema 字段不进入 canonical；步骤形状、有限 literal 数值、可选整数 timeout 解码明确失败，JSON null timeout 按 iOS Optional 省略。 |
| 名称 / primitive / 预算 | 全串匹配防止末尾换行绕过；Recipe 名 2–32、成员名 1–32、1–8 步、唯一 id、当前 primitive oracle、禁止 Recipe/插件/六管理工具引用、1–600 秒范围均有对应检查。 |
| 绑定 / 调用输入 | 顶层完整输入或前序步骤绑定；输出只能指向存在步骤字段；嵌套合法绑定保留 literal、非法 `${` 递归报具体路径。宿主保留输入名已在语义校验拒绝，严格 missing/extra/type/有限 number 检查和实际 runner 调用吻合。 |
| canonical / hash | 只重建 schema 字段，递归排序自身 key，数组序保留；直接编码 literal 不丢 `__proto__`，key/value slash 逃逸跟随实际 iOS fixture。Store 只调用这一 canonical 函数；SHA256 域前缀、8 字节大端 UTF-8 长度及实际 UTF-8 内容、补零 hex 实现与合同一致。 |
| 权限摘要 | 从实际 tool metadata / policy / 基础 mutates/risk 聚合；缺少工具保守为高风险。摘要作为预览展示，未授权替代真实逐步审批。 |
| 固定私有存储 | factory 只保存 `context.filesDir/recipes` 和现有 workspace，不初始写盘；包路径 name 全串校验。候选经过 normalizeWorkspacePath 与 256 KiB capped read、fatal UTF-8 decode、同一语义 validator。 |
| 双 CAS / 调用契约 | prepare 无写入，记录 candidate/base/envelope。apply 在第一个 await 前复制名字、路径和两个 hash，在同名 mutex 内重读候选并重新校验，比较 candidate 与当前 base 后才写盘。写入、返回 descriptor 来自同一重读候选；不会接受调用方替换 preview manifest 内容。 |
| 生命周期 / 原子失败 | 单包读取、apply、启停、删除共享同一个路径锁；启停/删除要求当前 expectedHash。新包默认启用，更新保留原启停状态。既有 Atomic helper 完整 UTF-8 短写循环、close 后 rename，不 TRUNC 目标；更新失败保留旧文件。首次安装写失败只清理新包目录并保留原错误。 |
| 坏文件 | 缺 manifest/state、错误 JSON/形状、非 boolean 状态、manifest 名与目录不符以及 IO 错误显式失败；不伪装空库、quarantine 后回填或覆盖坏文件。私有包语义校验由当前 primitive consumer 执行，失效包不会产生运行 wrapper。 |
| ports / 模块规模 | Store、ExecutionPort、LoopAdapter 与冻结签名及当前 consumer 一致；models ↔ ports 仅 type import，无运行时循环。新增源文件规模小，沿用一个原子 helper 和同名锁，无新增缓存、恢复账本、插件框架或重试层。 |

## 复用的必要证据与限度

- 已读 `results/E4-model-validation.md`、`results/E4-recipe-store.md` 与实际两组 fixture 源码；`/tmp/e4-model-validation-green.log` 为 **5/5 PASS**，`/tmp/e4-recipe-store-fixture.log` 为 **1/1 PASS**。本 reviewer 未重复执行。
- Store 实文件组走实际 Store/Atomic/domain 源码，使用临时真实文件与 SDK 等价 shims；覆盖 prepare 零写入、候选/基线变化拒绝、同基线更新互斥、禁用状态保留、旧 hash 生命周期拒绝、rename 失败旧文件保留/tmp 清理、读取失败和坏 JSON 不覆盖。该证据支持 host 调用/文件语义，不能替代 Harmony API12 或设备 IO。
- 同组抽取真实 `IOSRecipeModels.swift` decoder/canonicalJSONData 及 Swift CryptoKit，明确中文/emoji/换行/1.5 literal/array/object/slash 路径/未知 schema 字段 fixture：canonical **357 UTF-8 bytes**，Swift/JS hash **`fbc71d9558f002fcaba9b9f74de1b5743de7a6d0f13a38f057d59cf78fd55790`**。只确认这一实际夹具字节/hash 相等，不外推任意浮点和 Unicode key 排序。
- 已读 Store 最终 lint 0 error/0 warn、model lint 0 error/172 SDK import warn 的已有日志及 owner 窄类型检查记录。统一 HAP 构建、签名、设备与 UI 对齐/边距验收归 root/对应 reviewer，本报告不声称这些已完成。

## 修复后只读收口

已独立读取最终 `validation.ts:103–106`、同文件唯一新增断言及 `E4-model-validation.md:39–46` 的精修记录。已读 `/tmp/e4-model-reserved-title-red.log`：修前保留键 issue 断言真实失败，**1 failed**；已读 `/tmp/e4-model-reserved-title-green.log`：修后同一聚焦断言 **1/1 PASS，0 fail**。owner 用 `--test-name-pattern` 仅执行该失败项，没有重跑前五组或 Store 组；窄类型和单文件 lint 通过记录已在 owner 报告。

该修复只拒绝已确定不能进入真实调用链的宿主保留输入名，源改动一行，无新框架或兜底。此 review 范围现无开放 P1/P2；未扩审冷启、terminal status、child 协议或重复任何测试，后续统一验收由对应 owner/root 处理。
