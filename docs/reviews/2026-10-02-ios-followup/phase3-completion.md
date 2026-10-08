# Phase 3 完成记录

正文生成式产物已接入产物架，完成态索引与正文共享分组/解析，支持安全预览、原消息定位、HTML 导出和默认开启的显示开关。完整当前分支用于索引，既有图片、文档差分和 mini app 能力保留。

独立 review 与实际设备发现并修复：跨 text-part 漏收录、独立暗色 HTML 透明背景、来源错误被 sheet 遮挡、长 slides 操作滚动不可达、稀疏图表字过小及暗色文字对比度。均限定在对应既有路径；普通 SVG、浅色图表和净化/沙箱未扩大改动。密码为空的真实备份恢复暴露 util.encodeInto 空字符串行为，按官方接口语义转换为空字节，旧备份加密协议不变；真实恢复成功。

最终 worker38/38、root57/57（含备份修复）、独立38/38；chat tsc 通过，定点 lint 0 error。build4、sign-hap、verify-app、原 AGC 包覆盖安装和启动成功。实际设备浅暗预览、固定来源/导出触区、来源返回、两份 HTML 保存并取回检查、开关 OFF/ON、空态关闭均通过。原浅色与开关 ON 已恢复。最新图表浅暗截图由 root 与独立 reviewer 直接检查。

证据：/tmp/amber-followup-phase3-ui-acceptance.md；/tmp/amber-followup-ui/phase3-final-color-chart-dark.jpeg 与 light.jpeg；/tmp/amber-ios-followup-phase3-build4.log、phase3-sign4.log。没有发送付费 Provider 请求；长篇后台、物理设备仍非本阶段证据。
