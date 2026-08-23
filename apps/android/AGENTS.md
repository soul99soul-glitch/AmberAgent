# Android Project Instructions

- 本目录是 Android 项目的构建根；普通任务不得进入 `../ios/`。
- UI、ViewModel、Room、WorkManager、Keystore、Compose 和 Android DI 留在本项目。
- 状态问题沿 `UI gate -> domain owner/CAS -> durable persistence` 核对，不用 UI 文案代替生产接线。
- 修改 Novel 时优先跑定点 JVM 测试，再按风险扩大到 app compile/assemble。
- 只把稳定、平台无关且已有两端消费者的能力提议迁往仓库根 `core/`。
- 不把 iOS 过渡实现当作 Android 已验证行为；跨端契约任务必须分别给出两端证据。
