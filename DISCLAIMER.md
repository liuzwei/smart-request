# 免责声明 / Disclaimer

本文件是对 [LICENSE](LICENSE)（Apache License 2.0）第 7 条（免责声明）和第 8 条（责任限制）的补充说明，
不改变许可证本身的条款。如果本文件与 LICENSE 存在冲突，以 LICENSE 为准。

## 1. 软件按“现状”提供

- 本软件按 **“AS IS”** 提供，不附带任何明示或暗示的担保，包括但不限于适销性、特定用途适用性、所有权和不侵权。
- 不保证功能完整、没有缺陷、不会中断，也不承诺任何形式的支持、响应时间或 SLA。

## 2. 使用风险自负

- 本插件会向你配置的 URL **真实发送 HTTP 请求**，可能创建、修改或删除服务端数据。
  请勿在生产环境随意调用写接口（POST / PUT / PATCH / DELETE）。
- 因使用或无法使用本插件造成的任何直接或间接损失（数据丢失、业务中断、利润损失、账号封禁、
  被第三方追责等），作者及贡献者不承担任何责任。

## 3. 敏感信息与数据泄露

- 环境变量、Global Header（例如 `Authorization`、Token）、请求 Body、请求历史快照会以**明文**
  保存在项目目录的 `.idea/smart-request.xml` 中。该文件可能被提交到 Git、随项目共享、被打包备份
  或同步到云端。
- 把该文件纳入版本控制、发给他人、截图、贴日志或上传工单时，可能泄露 Token、Cookie、内网地址等
  敏感信息。这类泄露及其后果由使用者自行承担，作者及贡献者不承担责任。
- 插件本身不收集、不上传遥测数据，除了你配置的请求目标之外不会主动发起网络通信；
  但请求内容会发送到你自己指定的服务器，请确认该服务器和数据的合规性。
- 请不要在 GitHub Issue / PR 中粘贴真实 Token、Cookie、内网地址或生产数据；如需提供，请先脱敏。

## 4. 第三方与商标

- 本项目是独立的第三方插件，与 JetBrains s.r.o. 无任何隶属、合作或背书关系。
- IntelliJ IDEA、JetBrains 等名称与标识是其各自所有者的商标，此处仅用于说明兼容性。
- 你通过本插件访问的第三方服务，适用其各自的服务条款与隐私政策。

## 5. 合规责任

- 你应确保使用本插件向目标系统发起请求的行为已经获得授权，并遵守所在地法律法规以及所在组织的
  安全规范。因未授权访问或违规使用产生的责任由使用者自行承担。

## 6. 责任限制

在法律允许的最大范围内，作者及贡献者不对任何索赔、损害或其他责任负责，无论是基于合同、侵权
还是其他原因引起的，也与本软件或本软件的使用或其他交易无关。

---

## English summary

Smart Request is provided **“AS IS”**, without warranty of any kind, express or implied,
and the authors are **not liable** for any damages or losses arising from its use.

The plugin sends real HTTP requests to the endpoints you configure. Environment variables,
tokens, request bodies and history snapshots are stored **in plain text** in
`.idea/smart-request.xml`; if you commit, share or publish that file, you are responsible
for any resulting credential or data leakage.

This is an independent third-party plugin and is not affiliated with or endorsed by
JetBrains s.r.o. IntelliJ IDEA is a trademark of JetBrains s.r.o.

See [LICENSE](LICENSE) for the full terms.
