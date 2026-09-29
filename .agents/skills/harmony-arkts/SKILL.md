---
name: harmony-arkts
description: Use this skill when writing, modifying, or reviewing ArkTS/HarmonyOS (.ets) code in the harmony/ directory, integrating @ohos/@kit APIs, or building/signing/linting/emulator-verifying the HarmonyOS app. 鸿蒙 ArkTS 开发必读 — 铁律、踩坑速查与验证回路。
---

# HarmonyOS ArkTS 开发规则(harmony/ 模块)

通用模型写 ArkTS 的工程级 Pass@1 极低(ArkEval 2026 最强模型 ~3%,片段生成最高 ~23%),
差距靠「官方文档 + 编译回喂」补,不靠模型记忆。本 skill 固化本仓库已踩实 的规则与回路。

## When to use

- 新增/修改 `harmony/` 下任何 `.ets` / `.ts` 代码
- 接入本仓库没用过的 `@ohos.*` / `@kit.*` API
- 构建、签名、lint、模拟器验证

## 铁律(违反必炸)

### 1. 禁止 `export let` 做全局状态/主题

ArkTS 模块级 `export let` 是可变绑定,重新赋值**不触发任何 UI 刷新**,只有页面重建才生效。

- 已踩真 bug:`harmony/entry/src/main/ets/design/tokens.ets` 用 `export let` 色板 +
  `applyTheme(dark)` 整体赋值 → 切深色后栈内页面不刷新(退出重进才变)。
- **现行机制(2026-09-29,勿拆)**:tokens.ets 保留 export let 令牌库,变更收口统一调
  `bumpThemeEpoch()`(递增 AppStorage 'themeEpoch');全部 struct 订阅
  `@StorageProp('themeEpoch')`;全部 @Entry 页面 build() 内容包
  `ForEach([this.themeEpoch], …)` 纪元重挂载,epoch 变 → 整页子树销毁重建全量重读令牌。
- **V1 深层限制(实测证伪两级方案后确认)**:「状态变量不能独立于 UI 存在」——
  只声明 `@StorageProp` 而未在 build() UI 表达式中读取,变化**不触发重建**;
  且 V1 是属性级精准更新,根节点挂 `.opacity(this.themeEpoch >= 0 ? 1 : 1)` 之类的
  no-op 消费只重刷该属性,不带动整页。要全量刷新只有页面级 ForEach 重挂载。
- 新加全局状态(非主题)仍走 `AppStorage.setOrCreate` + `@StorageProp/@StorageLink`,
  且消费端必须在 UI 表达式里真读它。

### 2. API 基线是 12,不是 SDK 26

`harmony/build-profile.json5`:`compatibleSdkVersion` / `targetSdkVersion` = `5.0.0(12)`。

- 用 API 26 SDK 编译,编译器**不拦**高版本 API;模拟器镜像(API 24)也拦不住 API 13–24 的 API。
- 高于 API 12 的 API 一律 `canIUse('SystemCapability.xxx')` 包裹并写降级路径。
- 不确定 API 的版本归属 → 先查文档(见铁律 5),再决定是否 canIUse。

### 3. 状态管理:本仓库全 V1,禁止 V1/V2 混用

- 现有代码全部是 V1 装饰器(@State/@Prop/@Link/@Provide/@Consume + @Observed/@ObjectLink),新代码保持 V1。
- `@ObservedV2`/`@Trace`/`@Local`/`@Param` 不得与 V1 装饰器出现在同一棵组件树。
- @State 的 Array/Object:原地 `arr.push(x)` **不触发刷新**(ArkEval 头号错误「UI 状态不同步」占 42%),
  要整体赋值:`this.arr = [...this.arr, x]`。

### 4. ArkTS 严格模式禁项(编译期报错,不要反复试)

| 禁 | 替代 |
|---|---|
| `(globalThis as ...).__abilityContext` 动态访问 | EntryAbility `onCreate` 把 `this.context` 写入 AppStorage |
| `escape` / `unescape` | `new util.TextDecoder('utf-8').decode(new Uint8Array(buf))` |
| 裸 `as Record<string, object>` 断言 Want.parameters | interface 声明参数结构 / Want 类型化访问 |
| `any`、未类型化对象字面量 | 显式 interface / class |
| 解构赋值、对象展开、运行时 `typeof` 判型等 TS 动态特性 | 逐字段赋值 + `instanceof` |

完整踩坑清单(含 RDB `ValuesBucket`、RCP `createSession` 签名、通知枚举等):
`harmony/entry/TODO-compile-guide.md`。

### 4b. @BuilderParam:裸 lambda 禁止直接构造组件(运行期进程崩溃,编译不拦)

- 已踩真 bug(2026-09-27 设置重排):`CardGroup({ content: (): void => { CardRow(...) } })` — 组件构造发生在
  普通 lambda(非 builder 上下文),真机一打开页面即 `TypeError: class constructor cannot called without 'new'`,
  **RuntimeError 直接杀进程**。hilog 抓 `AppKit: ... about to exit due to RuntimeError`。
- 正确做法(仓库先例 = PressGlowButton):行内容写成组件的 `@Builder XxxCard(): void { CardRow(...) }` 方法,
  lambda 里只调 `this.XxxCard()`:`CardGroup({ content: (): void => { this.XxxCard(); } })`。
- 关联坑:自定义组件**尾随闭包后不能链通用属性**(`CardGroup() { ... } .margin(...)` 被当成新语句,
  编译报 "Cannot find name 'margin'")——要么显式参数传 content 再链属性,要么用外层容器吃边距。

### 5. 新 API 不凭记忆写

模型语料对 API 12–26 鸿蒙 API 的覆盖极差,猜字段名/枚举名是主要翻车源:

1. 先查仓库先例:`grep -rn "<apiName>" harmony --include="*.ets" | grep -v build`
2. 再查本地官方文档(首选,快且离线):
   `harmony/scripts/devecocli.sh docs search <关键词>`(命中后 `docs read <文档ID>` 读全文)
3. 本地没有 → WebFetch 官方文档:
   `https://developer.huawei.com/consumer/cn/doc/harmonyos-references/js-apis-<name>`
4. 以文档的签名/枚举/字段为准;文档没有的类型不要发明。

## 交付前自查(ArkEval 三错误类,按占比排序)

1. **UI 状态不同步(42%)**:改了数据 UI 不动?→ 是否绕过状态装饰器直改(模块级变量、原地变更)。
2. **严格类型(35%)**:过一遍上面禁项表;`hvigorw` 报的 ArkTS 错逐条修,不要 `as any` 糊。
3. **生命周期(23%)**:`aboutToAppear` 里的初始化在页面复用时是否重入安全?定时器/订阅是否在
   `aboutToDisappear` 清理?

## DevEco CLI(官方工具链,本机已装 @deveco/deveco-cli)

封装入口 `harmony/scripts/devecocli.sh`(必须经它调用:PATH 里的 node 是 DevEco Studio
自带的 Node 18,devecocli 依赖 Node ≥ 20,直呼 `devecocli` 会崩)。

| 能力 | 命令 / 通道 |
|---|---|
| 官方文档检索(离线,随包内置) | `harmony/scripts/devecocli.sh docs search <关键词>` / `docs read <id>` / `docs catalog` |
| ArkTS/C++ 语法诊断 | ZCode 会话内 MCP 工具 `deveco-mcp` → `check(文件列表)`。**本机暂不可用**:ArkTS LSP(ace-server)随 DevEco Studio ≥ 26.0.0.610 发布,本机 6.1.1 调用会报 "Project initialization failed";升级 IDE 后自动可用,无需改配置 |
| 兼容性扫描(API 超基线) | `devecocli check compat --source-version ... --target-version "5.0.0(12)"` — 同样**需 DevEco Studio ≥ 26.0.0.810**,升级后解锁,可自动化铁律 2 |
| build/run/ui/log/signature | 子命令齐全;仓库仍以 build-harmony-app.mjs + sign-hap.sh 为准(已验证离线链路) |

MCP 配置在 `.zcode/config.json`(gitignore 内,机器本地):workspace 打开即自动连接
`deveco-mcp`(服务器本身正常,check 在 IDE 升级前报上述错误属预期)。

## 验证回路(每步都有回喂,命令均在仓库根执行)

```bash
# 0. 静态检查(codelinter;error 级以上退出非 0)
harmony/scripts/lint-arkts.sh <改动文件或子目录>   # 日常:单文件 ~30s,子目录几分钟
#   harmony/scripts/lint-arkts.sh                  # 全仓 ~15-20 分钟(>150 文件自动分批,防 codelinter 挂起)
#   LINT_EXIT_ON=warn harmony/scripts/lint-arkts.sh <target>   # warn 也计入退出码
# (IDE 升级到 26 后,deveco-mcp 的 check 可作更快的单文件诊断,见上文 DevEco CLI 节)

# 1. 构建(封装 hvigorw assembleApp,自动定位 SDK)
node scripts/harmony-inventory/build-harmony-app.mjs

# 2. 签名:两条路线
#   a) AGC 调试签名(真机必用):devecocli auth login 一次 → devecocli signature generate
#      (材料落 ~/.ohos/config,配置写进 build-profile.json5)——之后 hvigorw 构建直接产出
#      已签名的 entry-default-signed.hap,无需 sign-hap.sh
#   b) 离线自建 CA(harmony/scripts/sign-hap.sh):仅模拟器认,零售真机会拒
#      (install 报 9568257 "fail to verify pkcs7 file")

# 3. 模拟器验证(Pura 90 AVD 启动后)
hdc tconn 127.0.0.1:5555
hdc -t 127.0.0.1:5555 install -r \
  harmony/entry/build/default/outputs/default/signed/entry-default-signed.hap
hdc -t 127.0.0.1:5555 shell aa force-stop app.amber.deepread
hdc -t 127.0.0.1:5555 shell aa start -b app.amber.deepread -a EntryAbility
hdc -t 127.0.0.1:5555 shell snapshot_display -f /data/local/tmp/verify.jpeg
hdc -t 127.0.0.1:5555 file recv /data/local/tmp/verify.jpeg /tmp/verify.jpeg
# UI 注入: hdc -t 127.0.0.1:5555 shell "uitest uiInput click 540 1200"
#          hdc -t 127.0.0.1:5555 shell "uitest uiInput swipe 540 1500 540 500 500"

# 4. 真机验证(须先用上面 2a 的 AGC 签名;UDID: hdc -t <serial> shell bm get --udid)
hdc list targets   # 真机 serial 形如 5MT0225B12013017,模拟器是 127.0.0.1:5555
hdc -t <serial> install -r harmony/entry/build/default/outputs/default/entry-default-signed.hap
hdc -t <serial> shell aa start -b app.amber.deepread -a EntryAbility
hdc -t <serial> shell snapshot_display -f /data/local/tmp/v.jpeg   # 真机截图同样可用
```

改了 UI / 主题 / 生命周期逻辑,必须走第 3 步截图目测——暗色 bug 就是只在模拟器上现形的。
学术评测里一轮编译修复能把 Pass@1 从 23% 拉到 37%、多轮把可编译率拉到 66–91%:
**没有回喂的生成代码默认不可信**。
