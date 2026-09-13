# 验证记录与环境限制

## 范围和安全约束

所有测试采用现有内存fake或本报告目录隔离合成数据；未调用真实模型、上传备份、发送外部内容、操作真实用户数据库或改变设备设置。未安装/升级依赖，未格式化、修改业务代码、提交或推送。本次代理新增材料仅在本目录。结束git status另出现 `docs/audits/2026-09-13-harmony/` 未跟踪材料，非本次已记录工具/代理产出，来源未确认；仅列目录/时间核对，未覆盖、移动或删除，不将其中的验证计入本报告。源码转译探针在VM替换平台端口，验证的是当前目标逻辑，不声称执行了Harmony SDK。

## 环境与基线

- 审查日期标签：2026-09-13；精确采集时间见 `baseline.txt`（宿主时钟可能不同于会话日期，不用于定时推算）。
- 分支 `harmony-preview`；HEAD `bdce551dd373f3d7e4c393b194997cae19289c40`。
- 起始52个跟踪文件已修改（51个Harmony文件 + PARITY_DEBT.md）；完整文件清单和SHA-256见 `baseline.txt` / `baseline-hashes.json`。审查对象为当前工作树，不只diff。
- macOS arm64，node `v26.8.1`，本地tsx/TypeScript已安装；hvigorw可用；API compatible/target为 `5.0.0(12)`，全V1状态管理；entry→chat→deepread。
- 页面清点复核：**74条main_pages注册，75个页面源码文件**。初始探索估算75条已更正；Index为未注册旧页。

## 主代理执行命令

|命令|结果|证据与解释|
|---|---|---|
|`git branch --show-current; git rev-parse HEAD; git status --short; git diff --stat`|完成|分支/基准/52处改动记录；没有checkout/reset/commit|
|`hdc list targets`|`[Empty]`|无在线设备/模拟器，未签名/安装/启动或截图|
|`node --version; command -v hvigorw; java -version`|node与hvigorw可用，系统java无runtime|构建实际已进入CompileArkTS，故不能把系统java缺失当作构建失败原因|
|`npm --prefix harmony/chat test`|exit0；**2097通过，0失败，0跳过**|`tests-chat.log`；约65.2秒|
|`npm --prefix harmony/deepread test`|exit0；**775通过，0失败，0跳过**|`tests-deepread.log`；约10.2秒|
|`harmony/chat/node_modules/.bin/tsc --noEmit -p harmony/chat/tsconfig.json`|exit0，无诊断|`tsc-chat.log`（空日志表示无输出）|
|`harmony/deepread/node_modules/.bin/tsc --noEmit -p harmony/deepread/tsconfig.json`|exit0，无诊断|`tsc-deepread.log`|
|`node scripts/harmony-inventory/build-harmony-app.mjs`|exit255，**BUILD FAILED**|`build.log`，12.938秒；CompileArkTS日志总计ERROR:12 WARN:502，列出11条带路径诊断。计数差原因工具未说明，不推断隐藏错误/聚合规则|
|`harmony/scripts/lint-arkts.sh harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets`|exit0但**结果不完整，不算通过**|`lint-sync-crypto.log`明确提示“Some error occurred during linting. This may cause incomplete report results.”，随后“No defects”不可信；未伪报全仓lint成功|
|`harmony/scripts/devecocli.sh docs search 'generateRandomSync'`|exit1，MODULE_NOT_FOUND|`docs-crypto-search.log`；缺`/opt/homebrew/bin/devecocli`，未修改环境|
|WebFetch Huawei cryptoFramework官方文档|仅返回文档中心壳，不能核API|改读本地官方SDK声明，保留` sdk-crypto-signatures.txt`；未凭记忆给API结论|
|`node …/probe-f-webmount.cjs`|exit0|真实WebMountTools/Sites源码转译；注入及URL解析两项实证，`probe-f-webmount.log`|
|`node …/probe-f-mount.cjs`|exit0|真实WebMountSession class，50个加速时钟tick后reject，loadUrl0次；`probe-f-mount.log`|

## 构建阻断：P1，独立复核完成

准确报错锚点：
- `harmony/entry/src/main/ets/platform_impl/SyncCryptoPort.ets:21,28,31-32,90`：不存在的createSymKeySync/getRandomSync/CipherSpec、initSync参数数目错误及严格类型错误。
- `harmony/entry/src/main/ets/platform_impl/WebMountOAuthSupport.ets:38`：getRandomSync不存在，连带类型错误。
- `harmony/entry/src/main/ets/pages/SettingExperimentalWebMountPage.ets:154,157,182`：匿名对象类型/无声明对象字面量。

以上均在起始未提交改动中；`build-relevant-working-diff.txt`对应差异。**本次审查零业务变更，不是本次引入**。没有在HEAD另建完整构建树，故不声称HEAD曾全量构建成功；只能确认当前错误语句位于工作树修改中。

最小修复方向：按API12可用的官方SDK签名使用SymKeyGenerator.convertKeySync、Random.generateRandomSync、Cipher.initSync(opMode,key,params)，明确GCM参数；匿名对象改显式interface。重新CompileArkTS和crypto往返测试后再签名装机。构建阻断不是P0数据事故；所有其他逻辑缺陷是源码层证据，不代表这个无法构建的快照已经在设备上运行。

## 子代理验证材料

精确命令、断言和限定条件见各 `topic-*.md` 与 `review-*.md`；探针均保留可重跑源码。使用本地已安装tsx，通常需 `--tsconfig harmony/chat/tsconfig.json` 解析HAR别名。不同批次存在重跑/对照断言，**不把探针断言数量累加成独立Bug数量，也不与2872条现有测试混算**。

- B：`probes/probe_tool_pairing.mjs`、`probe_budget_fit.mjs`、`probe_kernel_abort_substring.mjs`、`probe_double_visual_transform.mjs`；157条相关已有测试通过。反证复核结果优先。
- C：`probes/probe-topic-c.ts`；112条相关已有测试通过。fake长流60ms未settle只证明未即时收口，不证明真实设备无限挂死。
- D：`probes-d/topic-d-probe{,2,3}.ts`及`probe-output.log`，15/8/14项断言；scope明确的正反例。
- E：`probes-e/sync_snapshot_probe.mts`、`review-e-probe.mts`；相关6条现有测试通过。合成crypto用于隔离结构/恢复，不验证真实GCM。
- F/D独立：`probes-df/probe-f-independent.cjs`（19断言）、`probe-d-independent.ts`（18断言）；长延时`probe-cron-longdelay.ts`（13断言）。均无需真实网页/等待25天。
- 扩展：`probes-h/`，证据分配、DeepRead取消/REPLACE、council deadline；`probe-novel-state.ts`中polish序列构造错误已明确不是产品Bug，不列成功验证。

## 主代理收尾复跑

`final-probe-recheck.log`记录7个关键探针的完整命令/输出/退出码，**全部exit0**：WebMount注入+URL gate、真实Session挂载、实际Memory/ManagedFile/Favorite读错覆盖、真实Notifier竞态、kernel旧快照抹user、缺ID ask_user续跑错配、远期cron提前fire。前四项用node，后三项使用已安装tsx及chat tsconfig；无网络、设备或真实用户文件副作用。此复跑验证证据可重复，不替代独立审查。

补充材料：`probes-e/probe-e-io.cjs`及`probe-e-io.log`；`probes-xag/probe-h10-real.mjs`为真实源码验证，旧`probe-h10-race.mts`是控制流转写，**仅作说明、不计为复现证据**。

## 未执行及原因

- 未做完整设备UI旅程、深浅色/键盘/安全区/大字体截图、后台/恢复/权限撤销：hdc为空，且当前构建失败；不能把静态代码或编译检查冒充设备验证。
- 未做签名与安装：无可用本轮HAP，签名材料不在本次检查范围。
- 未跑全仓lint：针对性lint已有工具内部错误；可用编译器和双域测试已跑，其余lint覆盖明确未完成。
- 未做真实Provider/Gateway兼容测试、付费模型、OAuth兑换、WebDAV服务读写、真实文件删除和磁盘满测试：安全边界要求，采用mock或静态证明。
- 未执行OOM/极端大文件实机压力测试；性能问题只报告可证的规模/控制缺口，未测实际耗时或内存峰值部分标待验证。

## 终审标签纠正

`probe_b3_chain.mjs`第四例原标“genuine user stop”不准确：没有abort signal，只抛name=AbortError。标签现已改为“without aborting the signal”。`final-probe-recheck.log`为改标签前真实执行输出，保留原始记录但不得据其错误标签推出普通Stop丢消息。终审使用真实signal abort对照保留user，见final-quality-review.md。R05正式范围仍是错误被误分类/未取消却返回旧会话。
