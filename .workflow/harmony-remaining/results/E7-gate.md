# E7 gate: memory topics and derived documents

2026-09-28。源实现与独立 A/B 审查已收口；最终实际编译、签名回执附下。C 真机/模拟器验收仍 pending，当前 recorded HDC Empty，不重复设备恢复或测试循环。不能据本 gate 宣称整个产品或 E7 设备门完成。

## 已落实的闭环

- 已保存 active 非 core/topic 源 → 当轮真实 daydream/worker/compress/Chat 模型选择 → 严格 JSON grouping → latest guarded writeQueue/upsert → Markdown 投影 → 主题、成员、exact ID 源编辑、来源节点与只读文档页面。
- topic kind/title/memberIds 兼容旧 JSON；同标题稳定 ID、幂等、跨成员冲突拒绝。源内容/分类/移除/归档/到期使旧摘要失效，源 content/ID/provenance 保留。普通写入、提炼、导入导出、旧 Dream/Summary 不处理派生 topic。
- 模型与派生写入有真实开关，默认新写入 false，不混同 recall。取消/后台/超时/队列内 preCommit 核实际 gate；模型 customHeaders/customBodies/reasoning 使用本轮有效 choice。自动仅实际 foreground/idle/charging/每日预算，无虚构后台 scheduler。
- 源 JSON 成功后文档失败独立报 sourceSaved/documentError，可显式刷新，不回滚源；guarded 读失败不清文档。Chat recall touch 不耦合派生 IO。受控文档路径、真实源正文/时间/分类/出处、无变化不改 mtime。
- snapshot 返回 readonly 当前成员可用性视图；不在读入口写 JSON/docs。排队后 eligibility 与 unchanged projection 共用实际 commitNow，过期源不会被旧 enqueue clock 显示为有效。
- 4 个新路由与原核心入口真实接线；topic/doc只读；source 操作后回读真实 ID，文档失败仍显示真实已存/已删。来源定位明确是实际 MessageNode，当前分支可能与原消息不同。revision/返回刷新、44vp 操作、主题 tokens/safearea/长文本布局已源码审查。

## 独立 review 与精准修复

A: results/E7-logic-review.md。实际 foreground 漏接已关闭；queued unchanged document 旧时钟 P2 已关闭。
B: results/E7-ui-call-review.md。核心页 revision、44vp旧图标目标、来源节点文案、仅读主题时到期成员视图共 4 项 P2 已精准关闭。
实现证据：E7-domain-result.md、E7-store-runtime-result.md、E7-memory-ui.md。reviewer 未替 root 运行 tests/build/device，也未把源码 UI 视为截图证据。

## 仅必要验证

- 唯一 domain roundtrip/invalidation group 1 PASS；derived Markdown 内嵌 frontmatter误导入具体 red→局部修复→green。同组覆盖源保留/幂等/失效/投影/普通边界，不铺已有 Memory suites。
- 唯一实际 Entry store/coordinator/atomic FS controlled group 1 PASS。仅 SDK AppStorage/charging/DI/provider 返回受控，实际临时文件/guard/queue/atomic/模型门执行；不证明真实 provider 网络或 OHOS 文件实现。
- 后续两个具体 expiry P2 在该原组末尾少量断言真实红绿：/tmp/e7-commit-clock-red.log→e7-commit-clock-green.log；/tmp/e7-snapshot-expiry-red.log→e7-platform-final-green.log（最终 1 PASS / 0 FAIL，234ms/总447ms）。不新增套件/矩阵/设备循环。
- domain strict types 0；domain 10-file lint 0 error，唯一 frontmatter affected 修后仅重检其文件。平台五文件初 lint、编译诊断类型收口/clock/view后仅 Store最终lint No defects。UI五文件初检、具体回读/审查修后只 affected files No defects。root index+EntryAbility 两文件 lint No defects，/tmp/e7-root-shared-lint.log。
- 实际编译首次 3 条产品诊断（factory context + optional signal listeners），下一次仅剩 method-interface/object literal；按既有 function-property interface 精准修复。实际冻结编译曾 PASS，随后真实 expiry review 回喂改变 Store，因此必须以最后 artifact为准，前一包不称最终。

## 最终 artifact 和未测层

最后实际 Release 编译 /tmp/e7-release-final-build.log 9.680s PASS；generated BuildProfile BUILD_MODE_NAME=release、DEBUG=false。/tmp/e7-release-final-sign.log 本地签名 exit0，Digest verify=true、verify-app success。两 expiry 修复后的 Store 最后 lint No defects、独立 A/B 最终无开放 P1/P2，diff-check 0。
本地 Test-CA 签名只用于离线签名验证，未安装到原 AGC 签名设备。

C pending: 真实 provider grouping、实际平台 FS/charging、foreground/background/timeout与UI亮暗/键盘/长文本/来源返回、最终安装启动。设备已记录离线，无重复 polling/recovery。独立 E8 源工作继续，保留 pending gate 与准确边界。
