# P7 完成记录

两个独立产品当前 UI 集成完成，三条独立 source/device review 均 PASS；所有确认问题已关闭。Novel 动态卡按真实反例修复，Chapter5同mounted卡从写作→失败→重试联合审稿→完成/第5章；第6章单独用于最终44×44vp裁决热区复验，之后同job重试完成，目录共6章。没有通过轮询或重挂卡模拟更新。

Reader 展开查找时仅中间区域用既有 Scroll，正文150vp，footer44vp在IME上方完整；未展开普通编辑保持flex。Compose主题默认40→44vp，确认两动作41.7→44vp，裁决动作宽36.3→44vp，均根据真实AX而局部修改。详细activity通知与boolean准入/终结通知保原API语义，原精确测试断言未弱化。

Newsroom从真正RunManager阶段订阅，来源收集→四段标签→完成活动卡移除实屏可见；长题四行与浅暗色轨道正常。随机阅读从实际发现项选择ArxivAI话题、保留来源标题与链接，实际取消/继续入口/返回卡消失闭环；没有声称随机文章模型内容完成。

原生180%长标题/正文/分析/图解、HTML浅暗180%、资料alias/tag/IME草稿放弃、长讨论/分支header、计划草稿与系统PDF预览/另名保存/469.53KB分享面板均有device/p7 JPEG/AX。字号是深读阅读内容倍率，非全系统字号。系统share只打开面板，未发给任何接收人。极长文件名、全部主题字号组合、损坏恢复造样及逐页PDF印刷未穷尽；没有将这些变成未经证实的产品缺陷。

独立审查：[小说卡](p7-novel-card-review.md)、[深读首页](p7-deepread-ui-independent-review.md)、[跨页UI](p7-cross-product-ui-review.md)。最终源码804个TS/ETS与两generated副本全部一致，最新精确UI包的指纹与构建/sign证据见 [最终指纹](device/p8/final-artifact-fingerprints.json)，本phase此前版本的指纹只代表相应历史构建。两最终包均实际覆盖安装成功。P8进行当前包冷启动与完整交付复核。
