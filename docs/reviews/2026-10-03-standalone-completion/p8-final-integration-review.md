# P8 最终集成独立审核

本 reviewer 只读核对产品壳、生成项目、数据边界、构建与证据；唯一写入为此报告。未修改 scheduler 或其他生产代码，未构建、签名、运行设备或重新执行全域测试。

## 当前结论

**产品壳、数据隔离、生产入口、source→generated→最终签名产物及最终包冷启动集成 gate PASS。** 未发现本 scope 新生产阻塞。已读取最新 P7 完成与独立跨页 UI PASS、新 P8 指纹并重新计算全部摘要；最后还逐张读取8组P8 JPEG与对应AX，确认冷读保留和三个独立安装身份。最后同步核读 `state.json`、`p8-completion.md` 与 `final-parity.md`：P1–P8 均 complete/passed，计划及逐项表已同步完成，无阶段 pending。

## 产品与数据边界

| 项目 | 实际核对 | 结论 |
| --- | --- | --- |
| 小说身份与入口 | prepare 定义、generated AppScope、product_kind、ProductIdentity、EntryAbility.loadContent | `app.amber.novel` / Amber 小说 → `pages/NovelProjectsPage` |
| 深读身份与入口 | 同上 | `app.amber.deepread.reader` / Amber 深读 → `pages/BoardPage` |
| 宿主边界 | 原 harmony/AppScope 保持 `app.amber.deepread`，非两独立 bundle；ProductIdentity 默认 agent → ChatList | 独立包不会以相同 bundle 覆盖宿主 |
| 资源与路由 | 两 generated 路由 JSON 与 source 逐字一致；80 route 源码全存在；静态 pages URL 均注册；AppScope 与 Entry 名称/描述按产品生成 | 无遗漏入口或失配页面 |
| 权限与扩展 | 两产品仅 INTERNET、GET_NETWORK_INFO、KEEP_BACKGROUND_RUNNING、VIBRATE；Novel 唯一 WorkAbility，DR 无扩展 | 产品裁剪与实际运行需求一致 |
| 配置与作品 | PreferencesStorage / RdbRepository / FileStore 使用当前应用 context；Novel repository 由同 fileStore 构建；通知与 WorkScheduler 取当前 bundle | 每应用自身沙盒，未指向宿主目录或硬编码宿主通知目标 |
| 生成输入安全边界 | prepare 排除 credentials/userdata/user-data/backups/material/build/dependency/native caches、证书/密钥/DB/env/symlink；移除 build-profile signingConfigs 和 signingConfig | 两 generated sourceFiles manifest 没有这些禁止项；不搬运行配置或宿主签名密钥 |
| 构建入口 | build.mjs 调 prepare 当前工作树 → product ohpm → AMBER_HARMONY_DIR build → 当前产品根 signer | 读取实际 dirty/untracked源码；未 reset/stash 源工作树 |

prepare 六项既有回归分别覆盖两产品身份/权限、密钥和用户数据排除、当前 dirty/untracked 更新、保留产品本地缓存/签名身份、拒绝覆盖非生成目录与 CLI 验证。已读取最终6/6日志，不重新运行。

## 源码与构建记录

- 独立遍历 fingerprint 804 项，逐字摘要核对当前生产源与两个 generated 文件，差异0、缺失0。范围含803份 main/ets TS/ETS与1份 native binding声明。生成 manifest 1483项包括资源及构建配置，不把它与生产源数量混用。
- 当前 P8 构建日志 `/tmp/amber-p8-final-build.log` 两产品均 BUILD SUCCESSFUL；signer分别打印正确 bundle，均 `verify-app success`。当前 profile bundle 与 AppScope 一致，类型 debug。此为本地调试签名，不声称正式分发签名。
- 读取当前签名 HAP 摘要：Novel `8c47fa423d12975aa96c88c5f7208ca47ee6d36b2a5188b12ac00036757f6f8e`；DeepRead `23109b82fe0429f1ce853a5e1456b634f3b3f8b6e760953eb5b8576bd2ae932e`。与最新 `device/p8/final-artifact-fingerprints.json` 的 artifacts.sha256 / bytes 逐项一致；两 AppScope/app.json5、module.json5 和 main_pages.json 的 configSha256 也全部一致。
- 首次审读时，P7 指纹记录尚未覆盖 Compose 高度和 ConfirmationDialog 最小高度两项最后尺寸修复。当前 `device/p7/final-artifact-fingerprints.json` 已同步最终 P8 构建的804项来源及两 HAP 摘要，与最新 P8 摘要一致；旧 pre-touch 记录另存为历史，不作为当前产物依据。

## 回归与逐项证据

独立读取 `/tmp/amber-p8-{deepread-final-tests,chat-tests,entry-final-tests,prepare-tests}.log` 的实际结尾：1444 + 2848 + 241 + 6 = **4539/4539**，0 fail/cancelled/skipped；两最终 tsc 日志均为空且 P8 owner 记录 exit0。最后 Entry 重跑涵盖两处尺寸修改，不把重复运行累加进总数。

逐项表91行（小说80＋深读11）、ID全部唯一；160条源码引用路径均存在。P1–P6 closure及独立报告未把坏项目恢复、公开/native差异、官方Responses云服务、外网Google质量或无限后台这些未实测边界冒充设备通过。P7 Nova动态卡与深读随机/Newsroom有实际设备范围说明，分项结果没有借不同章节/不同包拼出一个虚假的完整运行。

审读初稿时 state/final-parity及P7跨页报告仍有准备稿pending。root随后提供最新P7 completion、独立跨页UI PASS、新P8指纹；state已P7 complete/passed，最终逐项表已更新为P8当前验收。其最后两项“冷启动完整取证/集成review”由下述独立核读关闭；最新 state 与完成报告已登记全部8阶段 complete/passed。具体云质量、全主题字号组合、损坏恢复设备fixture、PDF逐页印刷等未测边界仍保留，不将其转为已确认产品缺陷，也不虚称通过。

最终复核使用最新 P8 指纹，不沿用旧 P7 HAP 摘要：804/804 当前生产及两产品副本逐项一致，两 artifact 与六项配置摘要全部 match。没有遗漏当前 main/ets 生产文件，也没有把源代码查验、受控 HTTP、签名成功和设备结论互相替代。

## 最后独立冷启动与安装身份核读：PASS

读取 `p8-device-cases.md` 的当前最终包执行记录和 root 的成功命令确认：对 `app.amber.novel`、`app.amber.deepread.reader` 分别先 `aa force-stop <bundle>` 成功，再 `aa start -b <bundle> -a EntryAbility`。因此本组证据是明确终止后的启动，不是只从后台返回页面。本 reviewer 未重演命令；下列画面与AX是对root已有设备结果的独立核读。

逐张实际查看8组JPEG，并递归读取对应AX中的bundleName/text/bounds：

- `p8-novel-cold-retained-six` 的AX归属 `app.amber.novel`，小说创作首页保留 `P4-Renamed-Verification` 和“6章”；`p8-novel-cold-chapters` 同bundle进入同项目，显示“共6章”，第4章已完成润色/原子收录报告与第5、6章 `Local draft chapter`（各51字）仍在。第4章“正文已保存·资料分析失败”准确保留既有独立分析结果，不把它写成正文丢失或本轮启动失败。该截图仅覆盖当前目录可见部分，不声称再次逐字检查全部六章。
- `p8-deepread-cold-board` AX归属 `app.amber.deepread.reader`，独立发现首页有实际热点和随机阅读入口，无活动Newsroom冒充正在运行；`p8-deepread-cold-library` 同bundle保留原生稿与 `P6 Standalone Reader Verification` 的完成状态，主动取消的随机项明确“未完成”，旧失败项也仍准确显示失败。
- `p8-cold-captured-template-A` 与 `p8-cold-font-restored` 仍为同深读bundle、同篇 `P6 Standalone Reader Verification`，均显示“本篇版式：P6 Captured Template A”和“完成”。180%图标题自然多行，长引用超出窗口但AX原文完整；还原100%后引用完整可见且时间线可读。未把Scroll窗口裁切当正文丢失，也未声称单组图证明所有页面字号组合或逐页PDF排版。
- `p8-deepread-provider-independent` 是深读bundle，显示“阅读模型设置”和 `P6 Local Verification`；`p8-novel-provider-independent` 是小说bundle，显示“小说模型设置”和 `Local-verification`。两个实际配置名称及产品帮助各自保留，截图不包含密钥值。配置隔离结论同时结合前述context存储源码及下述安装UID，不仅依靠页面标题不同。

`installed-identities.json` 三条实际bm记录：小说UID **20020074** / `/data/app/el1/bundle/public/app.amber.novel/entry.hap`；深读UID **20020075** / `/data/app/el1/bundle/public/app.amber.deepread.reader/entry.hap`；宿主UID **20020073** / `/data/app/el1/bundle/public/app.amber.deepread/entry.hap`。三者UID互异、bundle路径对应、版本0.0.2且provisionType为debug；宿主仍在，没有以相同bundle覆盖。

当前证据完成了本scope冷启动、保留数据/模板、配置隔离和宿主并存核对，未发现新UI或集成finding。所有既有确认finding已关闭；本报告无其他pending gate。最新 `state.json`、计划、`p8-completion.md` 及 `final-parity.md` 已同步8阶段全部完成；此报告判定最终集成 PASS，可交付，保留正式分发签名、官方Responses服务、Google外网质量及有限后台等已明示外部验收边界。本次仅追加已有设备证据与最终状态核读，没有重跑测试、构建或操作设备。
