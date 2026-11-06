# Changelog

本项目的重要变更都会记录在这里，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 模板函数：在 `{{...}}` 中调用函数，对变量做编码、哈希、签名等处理，例如
  `{{base64(token)}}`、`{{urlencode(base64(token))}}`、`{{rsaEncrypt(secret, rsaPublicKey)}}`。
  - URL、Query、Path、Header、Body 和环境变量值都支持；场景运行作用域里的上游出参同样可用。
  - 内置 `base64`/`base64url`/`base64ToHex`/`hexToBase64`/`urlencode`/`urldecode`/`hex`/`unhex`、
    `md5`/`sha1`/`sha256`/`sha512`、`hmacSha256`/`hmacSha256Base64`、`concat`/`lower`/`upper`/`reverse`/
    `trim`/`length`/`replace`/`default`/`substring`/`jsonEscape`。
  - 内置 RSA：`rsaEncrypt`（OAEP-SHA256）、`rsaEncryptPkcs1`、`rsaDecrypt`、`rsaDecryptPkcs1`、
    `rsaSign`（SHA256withRSA）、`rsaVerify`；支持 PKCS#8/X.509 和旧式 PKCS#1 PEM 密钥。
  - 旧版 `OhDave` RSA：`ohDaveRsa(value, exponent, modulus)`，按 f2eweb/`user_ag` 登录的分块
    raw RSA + hex 格式实现；密码反转后用 `ohDaveRsa(reverse(password), exponent, modulus)`，
    不要使用标准 `rsaEncrypt`/`rsaEncryptPkcs1`，也不要用 `encoded` 代替 `exponent`/`modulus`。
  - 纯宏：工具栏 `Functions` 里定义名称、参数和表达式，例如
    `bearer(value) = concat('Bearer ', base64(value))`，请求里用 `{{bearer(token)}}`；
    宏只能使用参数、字面量和其他函数，不能读环境变量；保存时校验语法、未知函数、参数个数和重名，
    递归调用最多 20 层。
  - 场景校验从函数参数中提取真实变量依赖（`{{base64(token)}}` 依赖 `token`），并在运行前报告
    未知函数、参数个数错误和模板语法错误。
  - 函数目录和使用说明见 [`docs/template-functions.md`](docs/template-functions.md)。
- Headers 的 Key 单元格增加下拉选择：内置 31 个常用 HTTP Header（`Authorization`、`Content-Type`、
  `If-None-Match` 等），点 `▾` 选择，也仍然可以直接手写任意 Header 名；环境级的 `Global Headers`
  表格同样支持。
- `Environments` 对话框打开时自动定位到当前活动的环境，不再总是显示第一个。
- 响应提取（Extract）：请求编辑器新增 `Extract` Tab，用声明式路径把响应里的值写入当前环境变量，
  并在响应区新增只读 `Variables` Tab 展示结果。
  - 路径语法：`data.a.b`、`data.items[0].id`、`data.items[-1].id`、`data["a.b"]`、
    `headers.X-Request-Id`、`status`、`statusText`。
  - 每次响应自动执行，也可用 `Run now` 对上一次响应重跑；变量留空时自动取路径末段命名。
  - `Variable` 单元格编辑时直接列出当前环境已有变量，点选即可复用；支持 `Ctrl+Space` / `Alt+↓` / `▾`
    重新打开列表，输入 `{{name}}` 会自动去掉花括号。
  - 同名变量更新（含重新启用），否则追加；`baseUrl` 保留；失败时不覆盖旧值。
  - 失败原因（路径不存在、`null`、非 JSON、截断、二进制）逐行显示。
- 场景编排（Scenarios）：把多个请求串成一条活动网络图，一次运行整条链路。
  - 在请求树下方新增 `Scenarios` 列表：新建、重命名、复制、删除、过滤；选中场景后在同一工具窗右侧打开编辑面板，
    和请求的操作方式一致，不再有单独的 `Scenarios` 工具窗或编辑器 Tab。
  - 场景编辑器：步骤表按依赖顺序显示请求、`Runs after`、运行状态、HTTP 状态、耗时和抓取值；`+ Add step`
    从可搜索的请求选择器添加步骤；检查器可改请求、依赖（自动维护 Start/End 连线并拒绝环）、
    `Expect status`（`2xx` / `404` / `200-299` / 列表）、单节点超时和 JSONPath 出参，并可从请求的 Extract
    规则一键同步；`Parameters` Tab 维护场景级默认参数。
  - 校验条：环、不可达/到不了 End、请求已删除、变量数据流、并行写同名变量；点击问题可跳到对应步骤。
  - 运行：有参数或缺失变量时弹出运行对话框（环境、参数、缺失变量）；运行面板实时显示每个步骤的状态、
    状态码、耗时、失败原因和抓取值，结束后列出最终运行变量；失败即停，未开始的步骤标记为 skipped。
  - 运行期变量优先级为“环境 + baseUrl < 运行参数 < 上游出参”，只存在于本次运行，不写回环境；
    每次运行使用一个共享 `HttpClient`，避免逐节点重复 TLS 握手。
  - 数据流校验在发出请求前完成，删除被引用的请求会直接阻止运行并给出明确提示。
  - 图形视图：顶部 `Steps | Graph` 切换；画布支持拖动节点、从右侧端口拖到另一个节点左侧端口建立依赖、
    非法连线（自环、重复、成环、连到 Start/End）在拖动时标红并提示；Delete 删除步骤/依赖，Start/End 受保护；
    Ctrl+滚轮缩放、拖空白处平移、`Fit` 与 `Auto layout`（分层 + barycenter）。
  - 画布显示运行状态（pending/running/passed/failed/skipped）和 HTTP 状态码；点击画布节点与步骤表/检查器同步，
    点击运行面板结果行也会选中画布节点。
  - 撤销/重做：节点和边的深拷贝快照，最多 50 步，覆盖画布拖动、连线、删除、自动布局以及步骤增删；
    节点坐标保存在项目文件中，重启后保持。
  - 节点响应查看器：底部新增 `Response` Tab，显示当前选中步骤在本次运行中保留的响应，切换步骤同步更新；
    不会重新发送请求。
  - 可选出参：每条出参可取消 `Required`，可选规则失败不会让步骤失败，也不会把失败值传给下游。
  - 节点重试：`Retries` 0–5，发送失败、状态不符或必选出参失败时自动重试；耗尽后的错误会标明尝试次数。
  - 请求树 → 画布拖拽：把请求拖到画布上，在松开位置创建步骤，并进入撤销栈。
  - 场景 JSON 导出/导入：`Scenarios` 列表的 `Export…` 会导出场景和它引用的请求（自包含），
    `Import…` 在目标项目里创建新的请求、分组和场景，节点/请求 ID 全部重新映射，不覆盖已有数据。
- 开源协议：Apache License 2.0（`LICENSE`），并补充 `NOTICE`、`DISCLAIMER.md`、`SECURITY.md`、
  `CONTRIBUTING.md`、Issue/PR 模板。
- 插件 zip 内置 `LICENSE`、`NOTICE`、`DISCLAIMER.md`。

### Changed

- 场景不再单独占一个工具窗或编辑器 Tab：`Scenarios` 列表放在请求树下方，选中场景后在同一工具窗右侧编辑；
  切到请求时场景面板保留并隐藏，再次点击场景即可恢复，切到另一个场景会释放上一个面板及其运行。
- 插件 vendor 信息更新为 `liuzhaowei`，并补充仓库地址。
- 全局 Header 和全局 Params 从项目级迁移到**环境级**：在 `Environments` 对话框新增 `Global Headers` /
  `Global Params` 页签（`Variables` 之后），每个环境各自维护，切换环境即生效。
  - 旧版本 `.idea/smart-request.xml` 里的项目级全局会一次性迁移到当时活动的环境，不会丢失。
  - 请求超时和是否跟随重定向仍为项目级设置，入口由 `Globals` 改为 `Settings`。

### Fixed

- 场景 `Response` Tab 连续查看多个步骤的响应时，`ResponsePanel` 在折叠批量事务外移除上一个 JSON 的手动折叠区域，
  IDEA 2026.2 会记录 `Fold regions must be added or removed inside batchFoldProcessing() only.`。
  现在手动折叠的移除也放在 `runBatchFoldingOperation` 内，并加了回归测试（`assertNoErrorLogged`）。
- Params / Path Params 单元格里的变量补全能弹出但“选不中”：弹窗获得焦点时不再被立即关闭；选中后直接
  修改文档，并按当前位置的部分变量名计算插入区间，不再依赖编辑器选区。

## [1.0.1] - 2026-11-03

### Added

- GitHub Actions：推送 `v*` tag 自动构建并发布 Release，附件为 `smart-request-<version>.zip`。

### Fixed

- 响应体较长时无法滚动：`EditorTextField` 会隐藏内嵌编辑器的滚动条，Swing 在没有可见滚动条时
  直接忽略鼠标滚轮事件。现在按需显示滚动条，滚轮、拖动滚动条和 `Shift + 滚轮` 都能正常使用。

## [1.0.0] - 2026-11-03

### Added

- 首个版本：项目级请求树与分组，环境变量与 `{{变量}}` 高亮/补全，Path 参数，Query/Headers 表格，
  URL 与 Params 双向同步，全局 Header/Params，请求历史与响应缓存，复制为 cURL。
- Spring Controller 扫描与一键导入，类/方法 Gutter 图标，基于 DTO 生成 JSON 请求体。
- 响应展示：状态码/耗时/大小、响应 Headers、JSON 语法高亮、折叠、搜索、Pretty JSON、自动换行。
