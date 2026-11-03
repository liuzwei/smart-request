# 安全策略 / Security Policy

## 支持的版本

| 版本 | 是否提供安全修复 |
| --- | --- |
| 1.0.x | ✅ |
| < 1.0 | ❌ |

## 报告安全问题

请**不要**通过公开 Issue 报告安全漏洞。请使用 GitHub 的私密漏洞报告：

<https://github.com/liuzwei/smart-request/security/advisories/new>

报告时请尽量包含：

- 受影响的版本、IDE 版本与操作系统；
- 复现步骤或最小复现工程；
- 影响范围（例如是否会导致凭据泄露、任意文件读写、代码执行）；
- 如果你已经有修复思路，也欢迎一并说明。

我会尽量在 7 天内确认收到并给出初步判断。修复发布后会在 Release Notes 中致谢（如果你愿意）。

## 已知的设计取舍（不是漏洞）

- **明文存储**：环境变量、Header、请求 Body、请求历史以明文保存在 `.idea/smart-request.xml`。
  这是有意为之（便于随项目共享），请不要把包含真实凭据的文件提交到 Git。详见
  [DISCLAIMER.md](DISCLAIMER.md)。
- 插件不收集遥测数据，除你配置的请求目标外不会主动发起网络通信。

## 使用建议

- 给插件单独准备测试账号和测试 Token，不要使用生产环境的高权限凭据；
- 在 `.gitignore` 中忽略 `.idea/smart-request.xml`，或提交前手动脱敏；
- 及时升级到最新版本。
