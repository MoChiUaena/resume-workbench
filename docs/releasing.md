# 稳定版本的部署与发布

当前稳定版本为 0.8.0，镜像为 `ghcr.io/mochiuaena/resume-workbench:0.8.0`，运行平台为 Linux amd64。普通用户的安装、停止、重启和升级命令见 [README](../README.md)。

日常修改通过功能分支和 PR 同步 `main`，累积到完整里程碑再统一发布。已有版本标签和镜像不覆盖重用。

## 交付一个新版本

1. 在发布分支统一 POM、前端 package / lockfile、依赖清单、Compose、`.env.example` 和使用说明中的版本。
2. 完成 PR 的前后端、Docker、浏览器、中文 PDF、备份恢复及升级验证。
3. 给通过验证的提交创建 `v<版本>` 标签。标签工作流会重新验证，并在全部通过后推送刚测试的同一份镜像。
4. 从匹配的标签手动运行 `Verify published image`，指定新镜像，确认匿名拉取、全新目录安装、可编辑备份恢复、报告历史、容器重建和 PDF。
5. 在空目录生成发布包：`python scripts/build-release-assets.py --output-dir output/release`。配置 ZIP 包含 `compose.yml` 和 `.env.example`；另附依赖声明、演示和 SHA256SUMS。
6. 公开镜像通过后发布 Release，再把同一份已验证代码合入 `main`，使默认配置指向已经可用的镜像。Release 记录镜像 digest 与验证链接。

## 从旧版升级

先下载完整工作区 ZIP，保留原 `.env`、数据库密码和数据卷，再更新镜像版本。0.7.0 升级后保留正文、版式、修订、版本、图片、PDF、自动备份策略以及当前实例的模型配置和密钥，并自动增加报告历史表。

0.8.0 的工作区备份格式为 schema 5，简历文档仍为 schema 4；新程序可恢复旧 schema 2–4 备份，旧程序不能恢复 schema 5。需要回到旧程序时，使用升级前的备份在独立实例恢复。工作区 ZIP 不包含模型密钥，换设备恢复后应重新配置模型。

验收使用合成数据和隔离服务；真实简历、`.env`、备份及模型密钥不进入发布包。第三方资源继续遵守各自许可证，见 [第三方声明](../THIRD_PARTY_NOTICES.md)。
