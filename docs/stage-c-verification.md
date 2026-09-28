# 阶段 C 验收记录

2026-09-28，Windows / Docker Desktop，使用独立 `resume-workbench-smoke` 和 `resume-workbench-restore` Compose 项目。开发数据库与已有用户简历未参与恢复测试。

## 实际完成的检查

| 检查 | 结果与证据 |
| --- | --- |
| 固定镜像构建 | Node 24.18.0、Maven 3.9.16 / JDK 21、Playwright Java 1.63.0 的基础镜像均固定 digest；容器实际 Java 21.0.12.1，用户为 `pwuser` |
| 两服务安装 | 从两个新目录，仅复制 Compose 和 `.env`，使用本地已构建镜像启动 app / db；Flyway 自动初始化，健康检查通过 |
| 端口 | 应用仅映射 `127.0.0.1:18767` / `18769`；数据库没有发布宿主机端口 |
| 后端 | 30 项测试，0 失败、0 错误；备份专项含校验失败、路径越界、解压超限、未来版本、失败回滚和排他锁 |
| 浏览器 | 9 项测试全部通过，覆盖编辑、失败重试、冲突副本、图片历史、两个模板、分页、A/B 与深色偏好、完整备份界面 |
| 全新实例恢复 | 1 份两页简历、3 个历史版本、3 张附件（含只在旧版本使用的 EXIF 照片）、1 份既有 PDF；迁移前后结构与所有文件校验值相同 |
| 恢复后使用 | 从导入后的版本恢复旧照片，原图 / 标准化图校验值相同；修改求职方向后再次成功导出两页 PDF |
| 停止与重建 | 移除两个实例的容器和网络，保留数据卷，再启动；正文、修订、时间、照片、Logo 和历史版本均保留 |
| PDF | 6 份 PDF、9 页通过 A4、中文原字符、阅读顺序、本地嵌入字体检查，全部页面用 Poppler 渲染检查 |

## 离线检查的具体边界

资源准备好后，使用临时测试辅助容器共享 app 网络命名空间，删除 app 的默认路由；辅助容器用已缓存的 PostgreSQL 镜像，只为执行 `ip route`，完成即退出。应用容器自身始终是普通用户，不增加网络管理权限。

外部 TCP 探测 `1.1.1.1:443` 返回 `ENETUNREACH`。应用仍能访问同一 Docker 子网的数据库，并通过宿主机本地端口访问。在这个状态下运行全部 9 项浏览器检查与跨实例备份恢复，PDF 的字体、图片与 Chromium 均来自本地。浏览器另外拒绝非 loopback 请求；相关检查未发现外部请求。

这证明准备资源后的核心流程可在无外网出口时工作。首次拉取镜像、安装依赖和浏览器仍可能需要联网。默认 Compose 使用常规 bridge 网络以兼容宿主机端口映射，并不提供网络防火墙。Docker 内部网络在本次版本中会使发布端口不可访问，因此没有将它作为默认配置。

## 可重复运行

先在两个独立空实例运行应用，分别监听 `18767` 和 `18769`；切勿指向自己的工作区：

```powershell
$env:RESUME_TEST_BASE_URL='http://127.0.0.1:18767'
$env:RESUME_TEST_ISOLATED='1'
cd frontend
npm run test:e2e
cd ..
python scripts/verify-backup.py --isolated
python scripts/verify-pdfs.py --stage-c
```

`verify-backup.py` 在创建测试数据前要求两个工作区都为空；保留合成记录用于重启检查。重新创建容器但保留数据卷后运行：

```powershell
python scripts/verify-backup.py --isolated --check-persistence
```

本地生成的报告与样本位于被 Git 忽略的 `output/`：`e2e-results.json`、`stage-c-backup-verification.json`、`pdf-verification-stage-c.json` 和 `pdf/`。备份测试的 Java 集成测试只清理独立 `backup_test` schema，执行前检查当前 schema；正常开发 schema 不被清空。

GitHub Actions 将重复执行后端、前端、镜像、离线浏览器、全新实例恢复、PDF 和容器重建检查，并上传合成数据的验证产物。远端运行是否成功以对应 Actions 记录为准，本记录中的计数为本地实际结果。
