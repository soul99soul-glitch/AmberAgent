# P4 小说 Agent、状态和改稿完成记录

当前域实现与测试完成；最后独立设备审查结论见四份review，不以本记录替代review。

- B06/B09/B10/B11：15项专属工具接真实catalog/生产DI/executor/Creation/CAS提案，共22 novel tools；段落补丁、最近N章、多章删除以及作者确认闭环。
- C03/C11/C13/E03/E04：严格来源协议、事件/人物经历、身份确认与未来抽取关联、持久分块全本救援/重试/原子发布、专用状态推理配置。已有手改仍确定性提交。
- D02–D05：六字段草稿/确认合同、真实模型一键建议、最多八条走向、普通与代笔写作/审核/冻结计划实际消费。
- C16/D07/D10：当前逐字范围与先文章序/摘要核验；修复只修改较后目标；多问题同章倒序合并成一提案，作者批准才写正文与旧版本；Reader定向重生成及暂停/失败代笔作者brief实际请求。

审查中确认并精确修复：generic资料路径归一化与presence、共享base预览、不同分支资料投影；遗漏的代笔arc、结构合同清除/分叉/摘要；全本救援清状态条件、实体无ref事件展示与批量同章preview key、远章证据真实偏移；oneclick固定planner指令被adapter清掉（仅修caller context）；Reader同一原子快照；短页TopStart、同ID状态摘要/别名ForEach刷新、候选attempt误称总稿数的三处文案。无新增重试框架、计数schema或模糊fallback。

验证：最终Novel 480/480、Chat 2617/2617，Entry 57/57；chat/deepread noEmit及改动scope lint 0 errors；/tmp/amber-p4-counter-label-final-build.log两包构建/签名验证；两独立bundle覆盖安装冷启动；11份实际源码与两产品拷贝SHA256一致、最终HAP摘要见device/p4/final-artifact-fingerprints.json。

设备及真实本地HTTP证据见device/p4/device-cases.md、request-receipts.json。合成provider持假key，正确区分模型调用链与真实云质量；没有用领域测试冒充实屏，也没有为private sandbox造shipping debug入口。正式分发签名、真实云质量、系统后台长期存活是外部条件，不妨碍产品已授权范围后续实现；P5/P7/P8继续验证其实际能力边界。
