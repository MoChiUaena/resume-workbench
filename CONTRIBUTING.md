# 参与开发

欢迎提交问题报告和 Pull Request。开始前请阅读 [README](README.md) 与[架构说明](docs/architecture.md)；项目面向单人本机使用，界面预览与 PDF 导出共用排版逻辑。

## 开发环境

使用 JDK 21、Node.js 24、Docker 与 Compose。Windows 源码启动方式见 README；Linux 可在仓库目录运行 `sh scripts/start.sh`。首次构建会下载依赖和 Chromium。

## 验证修改

前端改动在 `frontend` 目录运行 `npm ci`、`npm run test:unit` 和 `npm run build`。Java 测试使用独立的测试数据库和测试 schema；在确认数据库是可丢弃实例并设置 `RESUME_DB_URL`、`RESUME_DB_USER`、`RESUME_DB_PASSWORD` 后运行 `./mvnw test`（Windows 使用 `./mvnw.cmd test`）。

浏览器、备份恢复和升级测试需要独立的空工作区。不要对日常简历实例运行这些测试；仓库 CI 会在隔离容器中执行完整流程。测试与演示只使用虚构数据，真实简历、证件照、`.env`、模型密钥、备份 ZIP 和本地输出都不要提交。

提交 PR 时请说明触发场景、行为变化、验证结果和数据兼容性。涉及数据库迁移或附件清理时，说明旧数据与备份如何保留；涉及图片或字体时，验证预览、导出及恢复。发布流程见[发布说明](docs/releasing.md)。
