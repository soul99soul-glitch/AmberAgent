# E9 Google OAuth loopback：原生实现与必要证据

2026-09-28。E owner 交付原生接收器；本次仅补报告，核对已有日志/测试脚本及当前源码，没有追加测试、构建或设备操作。共享注册、CMake 和类型声明由 root 集成。

## 独占实现与接口

- `harmony/entry/src/main/cpp/google_loopback_core.h`
- `harmony/entry/src/main/cpp/google_loopback_core.cpp`
- `harmony/entry/src/main/cpp/google_loopback_napi.h`
- `harmony/entry/src/main/cpp/google_loopback_napi.cpp`

Core namespace 为 `amber::googleloopback`；NAPI 注册入口为 `RegisterGoogleLoopback(napi_env, napi_value)`。

```ts
startGoogleLoopback(port, callback): Promise<{ handleId: string; port: number }>
replyGoogleLoopback(handleId, connectionId, status): Promise<void>
closeGoogleLoopback(handleId): Promise<void>
// callback: { connectionId: string; requestTarget: string; errorCode: string | null }
```

仅绑定 IPv4 `127.0.0.1`，返回 `getsockname` 实际端口。非零固定端口 bind 失败返回 `loopback_bind_failed`，不会换端口；显式传 0 才请求临时端口。OAuth 客户端仍须检查注册 redirect 与实际端口一致。

原生层仅提取完整 GET target，不验证 OAuth path/state/code。正常 callback 的 `errorCode=null`；listener 致命错误回调为空 connectionId/target 和固定错误码。target 仅在内存中传递，没有日志输出。最多 4 个连接、8192 字节 header，每个连接 15 秒期限，TSFN 队列最多 8 项。

回复只允许 200/400/404，固定 HTML 不包含请求 target、授权 code、state 或 token。reply 在发送并关闭该连接后完成；随后 close 不会丢弃排队的回复。

## 关闭与环境清理

源码路径：close 先标记 channel closed，唤醒原生 poll 线程；线程退出关闭 listener 和全部连接，调用方 join 后关闭唤醒管道，最后 abort/release TSFN。晚到回调会被 closed 标记抑制；回调数据即使在空 env 下仍由 `unique_ptr` 回收，finalizer 释放 channel holder。`napi_add_env_cleanup_hook` 对该环境的全部 session 执行同一关闭路径。

此段是源码与 API 编译检查结论；host TCP 组直接调用 Core，不等同于设备 NAPI/TSFN 环境清理验收。

## 已有必要检查

唯一 host 临时 TCP 生命周期组：`/tmp/e9-google-loopback-lifecycle.cpp`、`/tmp/e9-google-loopback-lifecycle.sh`，直接编译并执行真实 POSIX Core，没有 SDK mock。

| 项目 | 证据范围 |
| --- | --- |
| GET callback / reply | 完整 query target 精确到达 callback；200 reply 完成并关闭连接；回复不回显 fixture code/state |
| 固定端口占用 | 已监听端口再次 Start 返回 `loopback_bind_failed`，无随机端口替换 |
| 未完成连接取消 | 另一个连接只发送未完成 header；close 少于 1 秒返回，该连接 EOF/reset |
| close / rebind | close 后在同一端口重新 Start 成功，实际端口仍相同 |

日志 `/tmp/e9-google-loopback-lifecycle.log`：`Google loopback lifecycle PASS: GET/reply, fixed bind refusal, unfinished cancel, close/rebind`。

官方 OHOS SDK 的 `clang++ --target=aarch64-linux-ohos12.0.0 --sysroot=…/openharmony/native/sysroot -D__OHOS_API__=12 -std=c++17 -fPIC -Wall -Wextra -Werror -fsyntax-only` 检查两个 owned `.cpp`，执行返回 0；`/tmp/e9-google-loopback-ohos-syntax.log` 为 0 字节、无 diagnostics。此检查验证官方 API12 头文件与 C++ 编译约束，不证明链接或设备运行。

## 验收边界

E owner 没有执行全应用构建/签名、安装、设备浏览器 callback、设备 TSFN/env cleanup、真实 Google 账号授权或 token/流式请求。host Core 生命周期 PASS 与 API12 syntax PASS 独立于这些外部 C gate；OAuth exact path/state/one-use、取消/超时、exchange/onboarding 属 B 的实际客户端链路，不能由本原生组替代。未验证项继续由 root 的阶段记录保留。
