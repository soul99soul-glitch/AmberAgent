# AmberAgent Core

本目录是稳定共享核心的抽取入口，当前故意不承载生产代码。

现阶段 iOS 有 62 个以上 Swift 文件直接依赖历史 `Shared.framework`，共享工程还统一导出 32 个模块。立即搬迁会同时改变包路径、Swift ABI、Gradle 图和两端行为，风险不可控。

抽取顺序：

1. 用真实 Swift/Android 消费清单定义 façade。
2. 先稳定模型、序列化、事件、流式合并和工具协议。
3. 为同一 fixture 建立两端 Golden Test。
4. 从平台项目迁入一个完整纵向切片。
5. 双端验证通过后删除过渡副本。

禁止直接迁入 UI、Room、DataStore、Keychain/Keystore、后台任务、平台 DI、WebView、文件选择器或具体 Native 链接实现。
