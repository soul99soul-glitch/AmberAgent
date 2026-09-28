# E1 gate — 原生 tokenizer / Context 消费闭环

2026-09-28。结果 PASS；阶段提交由主 agent 执行，接续 E0。独立审查见 E1-native-core-review.md。

## 实现与验证

- tiktoken-rs 0.6 固定依赖，经 OHOS API12 arm64 静态库、C++ Node-API 异步 countBatch，进入正式 Chat Context 指示；没有 JNI。
- 已知模型按官方 tokenizer 映射；未知模型/原生失败保留原估算。压缩摘要与分组下界保留；模型解析和异步计数均校验当前会话/节点/压缩/模型 identity，历史分页不重复计数。供应商 usage、发送预算不被原生计数改写。
- Rust host 6/6；真实 host Node-API addon 12/12（并发32 jobs/4 worker contexts、参数错误、UTF8/NUL、恢复）；消费 helper 10/10。
- Debug 模拟器与真机 cold/warm 各18项：四轮72/72。日志 /tmp/e1-emu-native-{cold,warm}.log、/tmp/e1-device-native-{cold,warm}.log；独立解析全部成功。
- 最终 Release 两设备安装/启动并传相同固定夹具 selector；生成 BuildProfile.DEBUG=false，两指定 release-blocked runId 无夹具日志。日志 /tmp/e1-{emu,device}-native-release.log。
- 正常 Chat UI native1430→1.4K，未知模型旧estimate1601→1.6K；本次provider110tok保留。亮暗截图 /tmp/e1-context-native-{light,dark}-fixed.jpeg，fallback /tmp/e1-context-fallback.jpeg；独立视觉审查通过。实测provider为本机可控SSE夹具，未扩称外部服务验收。
- 相关类型检查、精确lint均0error；consumer此前37warning为已有/工具限制诊断，最终ChatPage窄lint仅4条既有性能warn。git diff check通过。
- 最终Release /tmp/harmony-remaining-E1-release-build.log SUCCESS 17.176s；已有AGC signing配置生成entry-default-signed.hap，模拟器/连接真机都安装启动。ELF AArch64，无动态JNI/Android依赖。

## 已修审查问题及边界

1. 原model resolver多个迟到callback可覆盖历史助手当前模型：收敛统一resolver+seq/conversation/assistant guard，独立源码与helper审查通过。
2. Context数值/caption暗色低对比：仅两处INK4→INK2，亮暗复拍通过，尺寸布局保持。
3. 当前CLI SDK Ninja为x86_64，在ARM Mac exec errno-86：构建wrapper仅该真实失败使用可运行Studio ARM Ninja；CMake与Hvigor同一AMBER_NATIVE_NINJA路径，未换SDK/sysroot或改SDK。Hvigor6.26.2运行时公开导出的hook缺少公开类型/文档，版本绑定风险明确记录在research报告。

API12编译/链接通过，不等于API12实机已运行；当前模拟器API24，连接真机HOP-AL00的const.ohos.apiversion=26（本次HDC读值），两者已运行。符号zip的既有EEXIST/ENOENT警告未阻止HAP构建签名；未因此删除缓存。Native计数用于显示，不声称非OpenAI近似tokenizer与真实供应商计费完全一致。

E0最后PasswordField交互修复后的融合Release再次构建SUCCESS6.527s（/tmp/harmony-remaining-E0-final-build.log），两设备再次安装成功，HOP-AL00再次aa start成功；未改变E1原生代码，既有计数/Debug/Release隔离证据维持。
