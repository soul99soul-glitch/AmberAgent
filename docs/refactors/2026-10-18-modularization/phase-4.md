# Phase 4：配置页与编辑会话

状态：阶段职责、独立代码与已测 UI 限定验收通过；Phase 3 修复包独立 UI 验收通过后启动。完整职责/调用与测试映射见任务证据 `/tmp/amber-modularization-cf0292929/phase4-boundaries.md`，阶段前 5 份相关页面/表单快照在 `phase4-before/`。

- Provider：把 pending-provider 从页面模块移至唯一 AppStorage 交接模块；同步 consume 仍早于异步 load。编辑/保存、登录序号与取消、模型目录及脱敏导出按完整职责迁移，保存时保留最新模型参数和捕获的 token 家族，失败不跳页、不复活已删 provider。
- DeepRead：宿主的模型选择即时保存，独立产品的模型/search/阅读设置保留显式 Save；主题/返回保稿与强调色即时保存仍按当前产品合同。独立草稿由一个 owner 持有，不把三份可写状态互相回读。宿主设置与其内聚视图另行迁移，保留现有 Root 嵌入入口。
- ChatSearch：主设置列表与服务编辑 session 分离；17 类 load/build 成对迁移，保留 identity/enable/selected-index、保存错误保稿和 A/B 编辑会话隔离。独立产品 editor 的数值校验与交接合同不同，不直接合并。

准备阶段发现并用实际页面方法、真实 prefs 队列和内存 KV 复现：只改结果条数时 `draft.searchCommonOptions` 与 current 共用引用，原地赋值使 prefs 的 JSON 等值比较短路，10→草稿11→持久值仍10且 writes=0。证据 `phase4-readonly-resultsize-repro.cjs/.log` 与 `phase4-readonly-hashes.txt`。实施时只在草稿到持久数据映射处复制 common options，保留现有去重与队列，并将期望持久值11的场景放入真实 owner 回归。

三个不重叠实现包分别负责 Provider、DeepRead 设置和 ChatSearch；root 持有共享依赖守卫、公共 fixture 协调、文档及唯一 SDK/签名/设备整合。每包只做一次本次改动维护审查，源码冻结后只处理实际 review/编译/UI 确认问题，不追加无依据的整理。本阶段实现与独立审查已收口，实际验证与未测门如下。

## 当前实现与验证

- ChatSearch 页面 983→380 行，list 134、editor 284、view 232；页持有两具体 observed owner，prefs 仍唯一队列。实现者 focused 37/37（含依赖守卫），4 个生产 `.ets` lint 无 defects；独立 reviewer 在冻结后一次执行同 focused 37/37，前后源码 hash 不变，未留确认缺陷。QuickMessages helper 与其 3 项原用例 byte-identical，未因混合测试迁移改其行为。报告 `phase4-search-{implementation,independent-review}.md`。
- 递归依赖守卫已覆盖 provider、deepread_settings、search_settings 三个新目录，当前检查通过；root 未放宽运行时反向依赖约束。
- Provider 最终 Detail 1638→187 行；编辑/保存、登录序号、目录三个 observed owner，以及 pending 单槽和无状态导出、3 个实际视图均已接线。focused 57/57，10 个修改 `.ets` lint 0 errors，保留 Settings 原 JSON clone warning；pending 消费/UUID/latest merge/取消/脱敏与字段绑定原断言保留。非 provider 混合测试段逐字未变，列表页仅改 pending import。源码已冻结，独立 review 及 SDK 结果见下。
- DeepRead 最终 Page 1506→304 行，独立 SettingsSession 409、HostSettings314、HostView560、Form280。表单直接 ObjectLink 读取第一层字段，草稿与提交 saving/error 同属唯一 SettingsSession；页保留 Root/motion/dock/lifecycle。9 文件 focused 54/54，真实 prefs resultSize 回归确认 10→11 真写入，第二次同值无新增写，原队列保持。5 个修改 `.ets` lint 0 errors/9 条既有 JSON clone 建议，未改公共 fixture。源码冻结，独立 review/SDK 结果见下。
- 旧包设备 UI 基线 `phase4-ui-before/README.md` 已完成：浅/暗服务商列表、既有 OpenAI 配置/模型 tab、host 深读设置/热榜源、搜索主设置/滚动及可取消 Bing editor。未更改字段/业务开关、保存、排序、取模型、OAuth 或联网。旧包模型重复名和暗色低对比图标记为既有外观；已恢复浅色 Home、PID9083 存活。

## 独立审查与整合

三个实现包独立 review 均已完成，无未解决确认问题；Provider 57/57、Search37/37、DeepRead54/54。DeepRead reviewer 另从实际 Form→Session→真实 prefs 验证条数写入/同值零写和并发队列。三个报告为 `phase4-{provider,search,deepread}-independent-review.md`。

SDK 首轮和独立 review 指出的遗漏已定点修正：两份 provider field options 恢复 onChange 声明、阅读表单/登录 overlay 补回 D import、HostView 补 SourceToggle type import，DeepRead appearance port 对齐实际 `Promise<DeepReadReaderAppearance>`。没有 cast 或 adapter；Provider 的 `@Link tab: this.tab` 经本机官方 API9 文档确认可用，不作为问题修改。实际变更仅5份源，原冻结hash另存；类型增量复核/受影响27项/lint已通过，没有扩大检查或新增防御。

root 固定全部 943 份生产 TS/ETS hash；最终 `phase4-build-final.log` CompileArkTS 13.039秒、PackageHap通过，仅既有 SignHap11014003。固定 unsigned SHA256 `59f25ff9c7f86d534abfd9cdfd157e14371b3ca484e8a69d8c7437a4c6b4c3a0`，产物/hash在 `phase4-ui-final/`。943源码构建前后及UI安装前均无差异。

SDK结束后单独完整 Harmony gate **4665/4665、0 fail/cancel/skip、54.254秒**，日志 `phase4-harmony-tests.log`；未更改原超时或断言。既有AGC签名、验签、保数据安装/启动已通过，现有会话及小说继续卡保留。独立 UI 实测浅暗 OpenAI 配置/模型 tab/gpt-4o 参数页返回、host 深读各区滚动、Search 主设置及 Bing 编辑弹层取消；对照旧包未见新增错位或路由阻断，最终恢复浅色 Home、PID22349。详见任务证据 phase4-ui-review.md 与 phase4-ui-final/README.md。

验收保留一次真实 APP_INPUT_BLOCK（16:10:46，PID16025，深读设置滚动期间）及后续 SceneBoard THREAD_BLOCK_6S。抓取时应用主线程在 EventQueue 等待，未见本轮业务 ArkTS 栈；这不足以确定根因，不能称全程无冻结或已证明纯系统问题。普通解锁/重新启动后余下路径完成，全部943份源码末检无差异。独立 DeepRead Root/provider advanced 在此 host 不可达；未测联网、真实 OAuth/provider、保存交互、软键盘、宽屏、API12及真机，不从宿主截图外推。
