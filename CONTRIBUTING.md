# 参与贡献 / Contributing

感谢你愿意改进 Smart Request！提交前请先阅读 [DISCLAIMER.md](DISCLAIMER.md)，不要在 Issue、PR、
测试数据或截图中提交真实 Token、Cookie、内网地址等敏感信息。

## 环境要求

- JDK 25（可以直接用 IntelliJ IDEA 自带的 JBR）
- 使用仓库自带的 Gradle Wrapper，不需要单独安装 Gradle

## 常用命令

```powershell
# 构建插件（首次会自动下载 IntelliJ Platform，约 700 MB）
.\gradlew.bat buildPlugin

# 如果本机已安装 IDEA，可以指定它，避免下载
.\gradlew.bat buildPlugin -PlocalIdePath="C:\Program Files\JetBrains\IntelliJ IDEA OSS"

# 运行测试
.\gradlew.bat test -PlocalIdePath="C:\Program Files\JetBrains\IntelliJ IDEA OSS"

# 启动一个带插件的沙箱 IDE 调试
.\gradlew.bat runIde -PlocalIdePath="C:\Program Files\JetBrains\IntelliJ IDEA OSS"
```

如果 `JAVA_HOME` 不是 JDK 25，需要先设置，例如：

```powershell
$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA OSS\jbr"
```

## 代码约定

- Kotlin，4 空格缩进，风格遵循 `kotlin.code.style=official`；
- 不引入新的运行时依赖（HTTP 层保持 JDK `java.net.http`，JSON 处理不引入 Jackson）；
- UI 代码运行在 EDT，HTTP 请求放在后台线程，回调通过 `invokeLater` 回到 EDT；
- 新增持久化字段时给默认值，并在 `SmartRequestState.normalize()` 中做兼容处理；
- 用户可见文案用英文，`README` 用中文，两者保持一致。

## 提交 PR

1. Fork 仓库并从 `main` 切出分支；
2. 写清楚「为什么改」而不是只写「改了什么」；
3. 行为变更需要补测试（`src/test/kotlin`），UI 相关测试参考现有的 `*UiTest`；
4. 本地跑通 `.\gradlew.bat test`；
5. 如果改了用户可见行为，同步更新 `README.md`，并在 `CHANGELOG.md` 的 `Unreleased` 下补一条。

提交信息建议使用英文祈使句，例如：

```text
Add response body wheel scrolling
Fix release job to check out the repository first
```
