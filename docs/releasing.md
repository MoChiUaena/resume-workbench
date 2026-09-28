# 验证与发布流程

GitHub Actions 在 `main`、当前交付分支、PR 和手动运行时检查前端构建、30 项 Java 测试、Docker 镜像、浏览器流程、离线使用、全新实例备份恢复、中文 PDF 和容器重建持久化。测试只在合成数据的独立 PostgreSQL schema / Compose 项目执行。

推送 `v<版本>` 标签会运行同一套检查。全部通过后，将**刚测试的同一份镜像**推送到 `ghcr.io/mochiuaena/resume-workbench:<版本>`；失败不会发布。工作流使用仓库自带 `GITHUB_TOKEN` 的 packages 写入权限，不需要用户提供 Token。Action 与基础镜像均固定到 commit / digest。

首个候选版本为 `v0.1.0-rc.1`，当前容器平台为 Linux amd64。候选版通过评审后，再更新 Compose 默认版本并发布正式标签。已发布版本不复用标签覆盖；需要修改时使用新版本。

发布前检查：

1. 分支中没有 `.env`、真实简历、备份 ZIP 或其他项目文件，CI 有实际通过记录。
2. README、`.env.example` 与 Compose 默认镜像版本一致，许可证和第三方声明完整。
3. 在 GitHub 新建版本标签 / Release，附上该提交的 `compose.yml` 与 `.env.example`；将首个候选 Release 标为 prerelease。
4. 标签触发的验证与镜像推送成功后，确认 GHCR 包可以匿名拉取。首次发布的包可能默认为 private，需要在 GitHub Packages 设置中改为 public；工作流成功并不等于已验证公开拉取。
5. 在新目录下载配置，使用已发布镜像启动两个服务，验证健康检查和 PDF。之后在 Release 记录实际镜像 digest 与 Actions 链接。

普通用户只下载发布配置与镜像即可启动。源码构建仍可用 `docker compose up -d --build --wait`，无需宿主机 Node/JDK/Maven。

发布版本升级时保留 `.env` 和两个数据卷，先下载完整备份。数据库迁移自动执行；备份恢复以新增记录为默认行为。不要在升级说明中安排删除用户数据卷。
