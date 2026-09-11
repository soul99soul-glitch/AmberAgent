# 环境配置

## Antigravity OAuth

构建时从仓库根目录的 `local.properties` 读取 `antigravityClientId` 和
`antigravityClientSecret`，也支持同名 Gradle 属性或环境变量
`ANTIGRAVITY_CLIENT_ID`、`ANTIGRAVITY_CLIENT_SECRET`。值通过生成的 BuildConfig
提供，不能提交到版本库。未配置时其余功能可以正常构建，Antigravity 登录会提示客户端未配置。

## 准备环境

- 安装CMake
- 安装NDK，并配置`ANDROID_NDK`环境变量

## git submodule

在项目根目录执行以下命令初始化子模块：

```bash
git submodule update --init --recursive
```

注意：必须在git仓库的根目录（`app.amber.agent/`）执行此命令，不是在 `src/main/cpp/mnn` 目录。

## 构建libMNN.so

进入 `src/main/cpp/mnn` 目录，执行以下命令：

```bash
./build.sh
```
