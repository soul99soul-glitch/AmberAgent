## CJK 混合内容

本文件混合使用中文、英文、强调标记和 GFM 表格，用于测试渲染器在 CJK 字符与 Markdown 语法共存时的正确性。

### 中文段落与强调

Kotlin 是一门**静态类型**的编程语言，由 JetBrains 开发，*2016 年* 正式发布 1.0 版本。它与 Java 完全互操作，并且可以编译为 JVM 字节码、JavaScript 或原生二进制代码。

在 Android 开发中，Kotlin 已经成为**首选语言**。谷歌在 2019 年的 Google I/O 大会上宣布"Kotlin 优先"战略，这意味着所有新的 Android API 和示例将*优先*使用 Kotlin 编写。

### 中文加英文混排

使用 `Jetpack Compose` 构建 UI 时，你需要了解以下几个核心概念：

- **可组合函数（Composable）**：用 `@Composable` 注解标记，描述 UI 的一个片段。
- **状态（State）**：驱动 UI 重组（Recomposition）的数据，通常用 `remember { mutableStateOf(...) }` 持有。
- **副作用（Side Effect）**：在可组合函数外部执行的操作，如网络请求，使用 `LaunchedEffect` 启动。

### 带中文内容的 GFM 表格

| 库名 | 用途 | 当前版本 |
| --- | --- | --- |
| Room | SQLite 数据库 ORM | 2.6.1 |
| Koin | 依赖注入框架 | 3.5.3 |
| Coil | 图片异步加载 | 2.6.0 |
| DataStore | 偏好设置持久化 | 1.1.1 |
| Navigation Compose | Compose 导航 | 2.7.7 |

### 日文示例

Kotlin コルーチンは、非同期プログラミングを**シンプル**に記述するための仕組みです。`suspend` キーワードを関数に付けることで、その関数がスレッドをブロックせずに一時停止できるようになります。

### 한국어 예시

코루틴은 **경량 스레드**라고 볼 수 있습니다. 수천 개의 코루틴을 단 몇 개의 스레드 위에서 실행할 수 있으며, 메모리 사용량이 매우 적습니다.

### 中英文混合的代码说明

以下是一个使用 `Flow` 实现的简单仓库示例：

```kotlin
// 用户仓库：先返回缓存，再获取远程数据
class UserRepository(private val api: UserApi, private val dao: UserDao) {
    fun getUser(id: String): Flow<User> = flow {
        dao.getUser(id)?.let { emit(it) }   // 发送缓存值
        emit(api.fetchUser(id))              // 发送远程值
    }.flowOn(Dispatchers.IO)
}
```

这种"先缓存，后网络"的模式在中文社区中常被称为**"离线优先"**策略。
