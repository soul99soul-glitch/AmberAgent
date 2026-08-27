# Android Project Instructions

- 本目录是独立的 Android 构建根；不得通过兄弟目录或绝对路径读取其他产品仓库。
- UI、ViewModel、Room、WorkManager、Keystore、Compose 和 Android DI 留在本项目。
- 状态问题沿 `UI gate -> domain owner/CAS -> durable persistence` 核对，不用 UI 文案代替生产接线。
- 修改 Novel 时优先跑定点 JVM 测试，再按风险扩大到 app compile/assemble。
- 只把稳定、平台无关且已有两端消费者的能力提议迁往版本化的 AmberAgent Core 制品；本仓 `core/` 仍是 Android 平台实现。
- 不把 iOS 过渡实现当作 Android 已验证行为；跨端契约任务必须分别给出两端证据。
