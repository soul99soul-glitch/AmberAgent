# P8 完成与交付

本轮8个phase顺序收口，所有确认产品缺陷已经修复，均有独立subagent检查真实逻辑、调用链与UI；P8完整产品壳/生成/签名产物独立审核PASS，精确设备冷启动记录单列，不以编译代替运行。

| 最终验证 | 结果 / 证据 |
| --- | --- |
| 全域回归 | DeepRead1444、Chat2848、Entry241、prepare6，共4539/4539，0失败/取消/跳过；双领域tsc exit0：[报告](p8-regression.md) |
| ArkTS lint | 各阶段实际改动文件0error；存在既有性能/纯TS规则warn，非warning-free声明 |
| 当前构建 / 调试签名 | /tmp/amber-p8-final-build.log两BUILD SUCCESSFUL及verify-app success；本地开发离线签名，不是AGC正式分发 |
| 生产与生成一致 | 804个当前生产TS/ETS与两副本逐项SHA全部相同，AppScope/module/routes六配置摘要及两实际signedHAP摘要均核实：[指纹](device/p8/final-artifact-fingerprints.json) |
| 覆盖安装 / 冷启动 | 两最终HAP install bundle successfully；实际force-stop成功后start、保留作品/阅读库和模板，见[设备记录](p8-device-cases.md) |
| 独立产品和宿主 | Novel app.amber.novel UID20020074；DR app.amber.deepread.reader UID20020075；宿主app.amber.deepread UID20020073仍在。当前context独立沙盒，模型配置名字也实际各自保留；无宿主密钥迁移 |
| 独立最终审查 | [integration review](p8-final-integration-review.md)，当前来源、构建、路径、产物、逐项表与设备证据逐项核实；无新增确认阻塞 |
| 对照 | [最终80项小说/11项深读表](final-parity.md)，逐项列两平台生产位置、实际能力、等价差异与证据层级 |

已交付的独立产品：小说完整项目/种子/资料继承/历史分支、上下文预览与选择、专属Agent提案工具、章节合同与后续走向、结构化状态/人物经历/身份澄清、连续性专项改稿与原子救援、作者返修、普通创作恢复、批量代笔与逐章润色/失败重试、公开工作区和本端完整备份及作品导出；深读20发现来源/分类、热点来源快照、模型/搜索选择、非tool模型四段写作、活动阶段编辑室与随机发现阅读、实时阅读库、逐篇不可变模板、资料浏览、20MiB文件、HTMLstarter/编辑预览、TXT/Markdown/PDF原生保存分享。具体行为及平台差异以91项表为准，不把总项数当完全同形界面或私有容器字节兼容。

重建当前两产品：

```bash
node scripts/harmony-standalone/build.mjs --product all --sign
```

产物：`.harmony-standalone/novel/entry/build/default/outputs/default/signed/entry-default-signed.hap` 和对应`deepread`目录。Novel SHA256 `8c47fa423d12975aa96c88c5f7208ca47ee6d36b2a5188b12ac00036757f6f8e`；DR `23109b82fe0429f1ce853a5e1456b634f3b3f8b6e760953eb5b8576bd2ae932e`。

源码仍位于当前dirty工作树，保留原dirty/untracked；初始1278个记录文件无消失，未reset/stash/clean、提交、推送或发布。最终深浅色检查后恢复暗色，深读100%阅读字号；测试项目与阅读库保留自己创建的验证内容，模拟器中的模型配置只是合成localhost验证服务，真实使用应在各应用配置自己的可用提供商。

未验证边界：真实手机验收/正式分发、外部云模型品质、官方Responses云恢复、Google外网成功（实际宿主已挂但deadline超时）、各公共源长期在线、无限后台、全部主题/字号组合、PDF逐页印刷和设备损坏恢复造样。现有DATATRANSFER有系统时限，公开工作区与私有native备份职责不同；格式和对端parser限制在表中明确，不新增复杂兜底去隐藏这些边界。
