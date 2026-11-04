# Changelog

本项目的重要变更都会记录在这里，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

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
- 场景编排（Scenarios）基础能力：项目文件新增场景模型（活动网络图，`START` / 请求节点 / `END`）、
  静态校验（环、孤立节点、请求已删除、变量数据流）与 DAG 执行引擎（失败即停、并行上限、单节点超时、
  运行期变量作用域）。界面在下一阶段接入，当前版本尚不可见。
- 开源协议：Apache License 2.0（`LICENSE`），并补充 `NOTICE`、`DISCLAIMER.md`、`SECURITY.md`、
  `CONTRIBUTING.md`、Issue/PR 模板。
- 插件 zip 内置 `LICENSE`、`NOTICE`、`DISCLAIMER.md`。

### Changed

- 插件 vendor 信息更新为 `liuzhaowei`，并补充仓库地址。
- 全局 Header 和全局 Params 从项目级迁移到**环境级**：在 `Environments` 对话框新增 `Global Headers` /
  `Global Params` 页签（`Variables` 之后），每个环境各自维护，切换环境即生效。
  - 旧版本 `.idea/smart-request.xml` 里的项目级全局会一次性迁移到当时活动的环境，不会丢失。
  - 请求超时和是否跟随重定向仍为项目级设置，入口由 `Globals` 改为 `Settings`。

### Fixed

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
