# 验证与发布流程

GitHub Actions 在 `main`、PR 和手动运行时检查 22 项前端逻辑测试与构建、141 项 Java 测试、可执行 JAR / 许可证清单、Docker 镜像、51 项浏览器测试、离线使用、全新实例备份恢复、中文 PDF 和容器重建持久化。自动备份检查覆盖调度、去重、历史恢复、跨实例策略边界和重建保留；脱敏检查覆盖四模板、图片字节移除、原稿保留、过期预览和下载重试；空间检查覆盖引用关系、文件校验、分页清单和读取前后数据保留；暂存恢复检查覆盖移前备份、同请求重试、冲突保留和容器重建后的逐文件字节核对。测试只在合成数据的独立 PostgreSQL schema / Compose 项目执行。

日常迭代通过功能分支、PR、CI 和合并同步 GitHub，变更集中记录在 CHANGELOG 的 Unreleased 区域。小功能完成后不自动创建版本标签或 Release，也不把 Compose 默认镜像改成尚未发布的版本。累积到较完整的里程碑，再统一确定版本、更新部署配置和发布镜像。

推送 `v<版本>` 标签会运行同一套检查。全部通过后，将**刚测试的同一份镜像**推送到 `ghcr.io/mochiuaena/resume-workbench:<版本>`；失败不会发布。工作流使用仓库自带 `GITHUB_TOKEN` 的 packages 写入权限，不需要用户提供 Token。Action 与基础镜像均固定到 commit / digest。

首个候选版本为 `v0.1.0-rc.1`，正式首版为 `v0.1.0`，当前版本为 `v0.7.0`，容器平台为 Linux amd64。发布前更新 POM、前端 package / lockfile、依赖清单的 applicationVersion、Compose、.env.example、README 和 published-image 工作流默认版本。可执行文件固定为 target/resume-workbench.jar，避免每次发布修改启动路径。已发布版本不复用标签覆盖；需要修改时使用新版本。

发布前检查：

1. 分支中没有 `.env`、真实简历、备份 ZIP 或其他项目文件，CI 有实际通过记录。
2. README、`.env.example` 与 Compose 默认镜像版本一致，许可证和第三方声明完整。
3. 在 GitHub 新建版本标签 / Release，附上该提交的部署配置 ZIP、演示与依赖声明；配置 ZIP 内为 `compose.yml` 和 `.env.example`，避免 GitHub 对隐藏文件的重命名。候选 Release 标为 prerelease，正式版本不标。
4. 标签触发的验证与镜像推送成功后，确认 GHCR 包可以匿名拉取。首次发布的包可能默认为 private，需要在 GitHub Packages 设置中改为 public；工作流成功并不等于已验证公开拉取。
5. 在新目录下载配置，使用已发布镜像启动两个服务，验证健康检查和 PDF。之后在 Release 记录实际镜像 digest 与 Actions 链接。

通过 `Verify published image` 手动工作流指定该版本镜像，使用空 Docker 凭据匿名拉取，在两个全新目录启动并执行恢复、普通 / 脱敏 PDF。应从匹配的版本标签运行该工作流，避免用新版接口检查不支持该功能的旧镜像。升级回归须使用独立实例，分别运行 rc.1、v0.2.0、v0.4.0、v0.5.0 与 v0.6.0 创建 schema 2 / 3 / 4 文档与备份，再以保留卷的方式换新版，核验正文 / 样式 / 修订 / 文件完整性和再次导出；另须确认 v0.2.0 拒绝新版 schema 4 备份。

依赖更新时重新解析 runtime dependency:list、运行 generate-notices.py，检查所有许可声明并保留上游文本；运行 verify-distribution.py 逐个核对打包 JAR。字体、上游许可与用户提供的示例图片继续分别声明，不把第三方资源改为项目 MIT。

普通用户只下载发布配置与镜像即可启动。源码构建仍可用 `docker compose up -d --build --wait`，无需宿主机 Node/JDK/Maven。

发布版本升级时保留 `.env` 和两个数据卷，先下载完整备份。数据库迁移自动执行；备份恢复以新增记录为默认行为。不要在升级说明中安排删除用户数据卷。
