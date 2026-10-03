# P9 成果文件与恢复验收完成

P9实际成果、系统保存、keepBoth恢复及冷启动验收已完成；文件链、PDF逐页和最终出口三项独立subagent review全部通过，P3已关闭，无未修复的确证产品/UI问题。基于P1–P8及R2继续补齐先前明确未测的文件字节/逐页PDF/备份入口，不把已通过的域回归重复计数。

- 小说实际TXT1154bytes、public ZIP38 entries、native ZIP139 entries全部从系统所选Download取回。public/native解析、分支章节及资料、native138payload与真实源树逐字核验通过。
- 两个ZIP经实际导入预览→保留两份，生成各自新ID。冷启动原项目/公开副本/native副本各6章，公开副本新对话，native原19messages/9章版本及完成润色结果保留。两个真实恢复目录逐文件等于生产keepBoth和恢复契约预期结果；原项目138文件没有一个字节变化。
- 深读同篇捕获杂志模板100%两页与180%三页PDF全篇内容一致，严格解析和逐页目测通过，字号180%实测精确1.8，未见裁字、横向溢出、重叠、乱码或整页空白。175%首样本如实保留；阅读已恢复100%，新包冷启动文章仍完成、模板/版式/字号持久一致。
- 精准修复一处P3：用户在系统保存框改名时旧小说toast仍报建议名字，改为通用“文件已导出”。仅一行生产代码；新包改名后的真实文件逐字相等，连续帧03实际显示新提示。
- scoped ArkTS lint exit0/no defects；Entry既有测试241/241。R2全量4554/4554与双tsc继续作为未改域的历史证据，本阶段未冒称重跑。两独立工程再次build/sign/verify成功，均覆盖安装并冷启动；804生产源码与两产品副本SHA全匹配。

证据入口：[真实设备与文件验收](device-validation.md)、[小说交换独立审查](exchange-review.md)、[PDF独立逐页审查](pdf-review.md)、[最终独立审查](final-review.md)、[当前HAP和源码指纹](final-artifact-fingerprints.json)、[实际导出文件指纹](exported-file-fingerprints.json)。

交付仍是模拟器离线签名：Novel `app.amber.novel`，DeepRead `app.amber.deepread.reader`。零售真机签名/实机、真实云模型与非空Responses游标、Google在线成功及所有自定义CSS/远程图片不在本阶段已验证范围。未改宿主数据、未推送或发布。
