# 独立应用再次复审与修复结果

2026-10-03。目标：对当前独立小说／深读应用再次用subagents独立审查逻辑闭环、实际调用链和UI细节，确认反例后精准修复，再交叉复核、回归、新包设备验收。工作树基点 `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290`；实际交付包括既有dirty/untracked源码和本轮局部修复，不将基点SHA当成本轮提交。没有创建提交、push或PR，没有回退既有工作。

## 确认问题及闭环

| ID / 严重度 | 实际问题 | 局部修复 | 独立复核与关闭证据 |
| --- | --- | --- | --- |
| N-L1 / P2 | 未结束代写／润色期间项目重命名推进head，原冻结CAS失效，任务失败且retry无法恢复 | 只在公开rename入口阻止未完成／未取消durable jobs，不全局禁止正文编辑 | actual creation/file repository RED→GREEN；另一reviewer验证暂停／失败、正文保存、取消后rename、分支切换准入；[Novel](novel-logic.md)、[integration](integration.md) |
| N-L2 / P2 | 改别名、标签、自定义类别、注入方式后，旧候选仍可收录 | 既有来源摘要纳入实际注入字段；默认空metadata保持旧摘要 | 另一reviewer通过5组实际system prompt→编辑→collect反例；无metadata legacy固定摘要保持；[Novel](novel-logic.md)、[integration](integration.md) |
| DR-L1 / P2 | 旧部分稿未记录sectionStates，stage选择推断READY但writer没推断，首次继续未收口，下一次重写已有段 | 非force writer初始化采用同一READY推断；force仍空稿 | 真实run/runSection仅生成缺失段、COMPLETE且保留旧正文／模板；[DR](deepread-logic.md)、[交叉复核](deepread-fix-review.md) |
| DR-L2 / P2 | 重新生成抓到了新网页正文，输入合并仍复用旧ready正文 | force仅替换成功重读的web/search，同URL保留输入ID；失败保留旧正文，text/file保留 | 独立整轮规划+4段5次prompt全含新正文；旧完整稿保留至唯一完整save；[DR](deepread-logic.md)、[交叉复核](deepread-fix-review.md) |
| DR-L3 / P2 | 免费搜索已有两条结果，可选Google补充拖过外10秒截止，整桶结果丢空 | 复用现有9秒预算helper，Google只使用聚合剩余时间，耗尽返回已有hits | 实际registry→prefetch→HTTP正文跨层RED→GREEN；原childsignal在既有finally收口；[DR](deepread-logic.md)、[交叉复核](deepread-fix-review.md) |
| INT-01 / P2 | 保活通知缺少文章标题，点击文章页参数校验失败；同一链超512字符标题被当稳定ID清空 | scheduler→activity→Want完整传标题；Entry显示文本保留完整字符串，稳定ID仍限制512 | 独立4字／600字实际activity→Want→Entry→Article链；新包实际点击顶部DATA_TRANSFER卡打开正确文章，随后完成并移除保活卡；[integration](integration.md)、[设备](device-validation.md) |
| INT-02 / P2 | late owner替换、更新被节流合并时，新旧标题前60字符相同导致Want留旧完整标题 | 仅将完整activity.title加入原发布签名 | 独立实际bridge+lease探针：发布2次、Want新title、迟到snapshot不复活、最终stop1；[integration](integration.md)、[UI交叉复核](ui.md) |
| R2-UI-01 / P2 | 项目控制保存完整热区28.3×44vp，文字左侧空白无法触发保存 | 原Text增加minWidth44、保持右对齐 | 新包AX154×154px=44×44vp；原边界外1110,1520点击出现保存回执；UI reviewer实际看图复核；[UI](ui.md)、[设备](device-validation.md) |

上述修复相对本轮审查前804份源码基线仅触及10个生产文件。没有新状态机、通用重试、并行取消控制器、截断文章标题或通用仓库写入拦截。

其余UI检查：实际新键盘帧确认Compose模板／生成footer、项目控制偏好、项目润色偏好footer可达；代表明暗主题、长名称、正文／资料编辑、动态任务卡未确认新的错位、对齐、间距或重叠缺陷。未把正常滚动裁切、旧截图文件名、默认控件图形尺寸机械当成缺陷。普通通知requestCode共享疑点经平台Want匹配实现排除，未修改。

## 验证结果

- [最终回归](regression.md)：DeepRead1456 + Chat2851 + Entry241 + prepare6 = **4554/4554**，0 fail/cancelled/skipped/todo；双模块tsc退出0。focused独立探针不重复计数。
- 首轮唯一失败来自旧audit测试用现在禁止的rename制造CAS漂移。改为真实正文编辑制造漂移，保留原fail/retry/cancel全部断言；仅补跑受影响DeepRead全量和tsc，首轮日志保留。
- 各实际改动文件ArkTS lint均0error。存在纯TS/SDK解析告警和Chat原生vsync测试夹具既有unused-variable warning；未声明warning-free。
- 双产品构建、模拟器debug签名、digest/codesign verification、覆盖安装和启动通过。[804份当前源码与双副本指纹](final-artifact-fingerprints.json) 全部匹配；[安装身份](installed-identities.json) 为Novel `app.amber.novel`、DeepRead `app.amber.deepread.reader`，原host仍安装。
- [新包设备验收](device-validation.md)：保存44×44vp与边缘点击、原6章项目数据、系统DATA_TRANSFER通知点击、原文章保留、完成／导出恢复、保活终态清理均有本轮图片和AX。
- 本轮独立分工：Novel、DeepRead、integration、UI四位reviewer先读源码／验证反例，修复后由不同reviewer交叉复核；第五位agent独立执行最终全量回归。root整合、构建、签名、安装和设备操作。

## 当前交付与边界

最新已安装debug HAP：

- Novel：[entry-default-signed.hap](../../../.harmony-standalone/novel/entry/build/default/outputs/default/signed/entry-default-signed.hap)，SHA256 `acc79f3fbf89da6055ad528415b262227adbce004b1a0d29799e47f833af7667`。
- DeepRead：[entry-default-signed.hap](../../../.harmony-standalone/deepread/entry/build/default/outputs/default/signed/entry-default-signed.hap)，SHA256 `493852710e38653e5a086adad553a4c41df5902c08736915d2fd11d90ba7afa3`。

本报告是P1–P8既有完成工作的新增复审，不覆盖旧P8历史产物指纹；当前最新包以本轮指纹为准。真实云模型、Google在线页面／验证码、零售分发签名、所有系统字号／方向组合没有在本轮全面验收。当前通过不扩大为全iOS功能完全等价或无限后台运行声明；平台边界仍见原[最终能力对照](../2026-10-03-standalone-completion/final-parity.md)。本轮自启fixture与18771 reverse已停止，原18765 reverse保持。
