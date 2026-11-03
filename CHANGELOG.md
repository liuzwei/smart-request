# Changelog

本项目的重要变更都会记录在这里，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added

- 开源协议：Apache License 2.0（`LICENSE`），并补充 `NOTICE`、`DISCLAIMER.md`、`SECURITY.md`、
  `CONTRIBUTING.md`、Issue/PR 模板。
- 插件 zip 内置 `LICENSE`、`NOTICE`、`DISCLAIMER.md`。

### Changed

- 插件 vendor 信息更新为 `liuzhaowei`，并补充仓库地址。

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
