# Resume Workbench · 简历工作台

面向中文技术求职者的本地简历工作台。阶段 B 已实现结构化编辑、可靠自动保存、独立学校 Logo / 证件照、两个模板、中文 PDF、简历复制和版本恢复。

![我的简历列表](docs/workbench.png)

编辑页和深色模式的实际截图：[纸张文档风格](docs/workbench-editor.png) · [深色模式](docs/workbench-dark.png)。

## 现在可以做什么

- 在“我的简历”列表选择、新建、重命名、复制和删除简历；从一份基础简历复制 Java 岗和 AI 岗版本。
- 编辑基本信息、教育、工作 / 实习、项目、技能和自定义模块；添加条目、排序、隐藏、按模块另起一页。
- 正文支持段落、项目列表和 `**加粗**`。输入 HTML 会作为文字显示，不接受任意 HTML/CSS。
- 编辑页左侧选模块、中间填写内容、右侧实时预览。界面可选蓝白工具或纸张文档风格，并能独立切换深色模式；选择保存在本机浏览器。PDF 模板仍可独立选择经典单栏或并列页眉。
- 两张图片可以独立替换、隐藏、移除、调整尺寸、旋转和裁剪。
- 新建示例使用“奶龙”作为姓名与卡通证件照；保留一张独立的虚构学校 Logo。已有简历的内容不会被模板更新覆盖。
- 本地黑体 / 宋体，字号 9–12 pt、行距 1.3–1.85、模块间距 2–8 mm、页边距 12–22 mm。
- 700 ms 防抖自动保存，串行写入；数据库确认后才显示“已保存到本机”。失败可重试或另存副本，修订冲突不覆盖其他页面。
- 手动版本快照和恢复；恢复前自动保留当前版本。导出时记录固定修订的版本快照。
- 中文 A4 预览与 PDF 共用模板、字体、图片和分页脚本，PDF 保留可选择、可搜索的文字。

这是本地开发版本。完整备份恢复、发布镜像及普通用户的两服务 Compose 安装属于阶段 C；当前 `compose.dev.yml` 只启动开发数据库。没有账号、AI、云存储或公开分享功能。

## Windows 启动

需要 JDK 21、Node.js 22.12+、npm 和正在运行的 Docker Desktop。Maven Wrapper 固定 Maven 3.9.16；首次准备依赖和浏览器需要联网，应用运行使用项目内的字体。

```powershell
git clone https://github.com/MoChiUaena/resume-workbench.git
cd resume-workbench
./scripts/start.ps1 -JavaHome '你的 JDK 21 目录'
```

本次环境已完成依赖准备与构建，后续直接：

```powershell
./scripts/start.ps1 -JavaHome "$env:USERPROFILE/java/jdk-21" -SkipBuild
```

访问 <http://127.0.0.1:18765>。在“我的简历”选择已有版本，或新建空白简历 / 合成示例。右上角的“界面风格”和“深色模式”可以随时切换；这些设置只影响网站界面，不修改简历内容或导出 PDF 的版式。

脚本首次创建被 Git 忽略的 `.env`，生成随机本地数据库密码；启动 `local-resume-dev` Compose 项目的 PostgreSQL，执行 Flyway 迁移，然后启动应用。不会改变其他项目的 JDK 或浏览器配置。

应用仅监听 `127.0.0.1:18765`；开发数据库仅映射 `127.0.0.1:18766`。不支持把此无认证版本直接暴露到公网。Chromium 使用项目 `.tools/ms-playwright` 并禁止共享缓存清理。

`Ctrl+C` 停止应用，不删除数据。若需要同时停止开发数据库：

```powershell
docker compose -f compose.dev.yml stop
```

重新运行启动脚本会继续使用原数据卷。不要删除 `local-resume-dev_resume_pgdata` 卷，也不要在已有数据卷上重新生成 `.env` 密码。删除卷会丢失简历正文和历史版本。备份恢复闭环尚未交付。

Linux/macOS 提供 `scripts/start.sh`，需要 Docker、JDK 21、Node 和 Chromium 系统依赖；本轮实际验证为 Windows。前端开发可在后端启动后运行 `cd frontend; npm run dev`，本机开发端口为 5173，API、字体和渲染资源由 Vite 代理到后端。

## 使用流程

1. 在“我的简历”中打开已有简历，或新建空白简历 / 合成示例。编辑页左侧选择“项目经历”等模块；中间修改标题、时间和“经历描述 / 正文”，右侧实时显示结果。
2. “图片”中分别导入校徽和照片。支持 JPEG/PNG，默认 5 MiB、2400 万像素、单边 12000 px；透明 PNG 与 EXIF 方向已验证。
3. “版式”选择模板与字体，查看右侧分页。短段落 / 列表条目之间可以自动续页；单条过高时明确报错，避免静默裁掉文字。
4. 等到“已保存到本机”。修改正在保存时可以继续输入；新内容排队保存。两个页面同时编辑发生冲突时，保留当前页面内容并选择“另存副本”或“重新载入”。
5. 在“版本”保存快照，或“复制简历”创建岗位变体。恢复旧版会先保留当前版，旧照片仍可读取。
6. “导出 PDF”会先保存最新输入，再为对应修订创建持久快照。导出文件元数据包含简历 ID、快照 ID、修订号及校验值。

## 数据在哪里

| 数据 | 位置 |
| --- | --- |
| 正文、版式、修订、版本快照、附件元数据和引用 | PostgreSQL `local_resume`，Docker 卷 `local-resume-dev_resume_pgdata` |
| 上传原图、标准化图片和附属 metadata.json | `data/attachments/<UUID>/` |
| PDF 与对应元数据 | `data/exports/<UUID>.pdf` / `.json` |
| 本地数据库凭据 | `.env`（不入 Git） |
| 浏览器依赖 | `.tools/ms-playwright`（不入 Git） |

`RESUME_DATA_DIR` 可指定附件和导出目录；`RESUME_DB_URL`、`RESUME_DB_USER`、`RESUME_DB_PASSWORD` 配置 PostgreSQL；`PORT` 默认为 18765。`RESUME_MAX_UPLOAD_BYTES` 默认 5242880，前后端共用，multipart 额外预留 524288 字节。

结构化文档采用 `schemaVersion: 2`，分离 `content` 与 `layout`。文件只通过 UUID 引用，不在正文中保存宿主机路径。移除当前照片不会删除历史文件；删除整份简历会删除它的历史记录，但仍保留图片文件。本阶段没有附件垃圾回收。

内存中的短期渲染快照保留 30 分钟、最多 64 个；它与 PostgreSQL 中可长期恢复的版本快照不同。浏览器只记住所选简历 ID，不用 localStorage 保存正文。

## 验证与测试

后端测试使用真实 PostgreSQL，在独立 `resume_test` schema 内执行并回滚事务，不使用第二种数据库：

```powershell
. ./scripts/prepare-db.ps1
$env:JAVA_HOME='你的 JDK 21 目录'
./mvnw.cmd test
```

先启动应用，然后另开终端：

```powershell
cd frontend
npm ci
npm run build
npm run test:e2e
```

E2E 只删除测试自己创建的简历 ID，不清空工作区。测试覆盖编辑、重开、排序隐藏、照片历史、复制删除、失败重试、保存中的继续输入、双窗口冲突、两个模板 PDF 和长内容分页。数据目录可能保留测试上传文件，尚未实现垃圾回收。

PDF QA 需要 Python `pypdf` 以及 Poppler 的 `pdftoppm`、`pdffonts`：

```powershell
python scripts/verify-pdfs.py
```

产物在 `output/pdf/stage-b-{classic|banner}-{one|two}.pdf`，报告在 `output/pdf-verification-stage-b.json`，E2E 报告在 `output/e2e-results.json`。逐字检查中文和标题顺序，不用 NFKC 归一化掩盖部首替换错误。`--stage-a` 只用于检查保留的阶段 A 旧样本。

详情见 [阶段 B 验收记录](docs/stage-b-verification.md) 和 [阶段 A 记录](docs/stage-a-verification.md)。浏览器已做禁止非本机请求的检查；完整系统级离线验收留待阶段 C。

## 技术与取舍

JDK 21 / Spring Boot 3.5.16 / PostgreSQL 16.10 / Flyway / Playwright Java 1.63.0；Vue 3.5.43 / Vite 8.3.1 / TypeScript 5.9.3。依赖由 POM、Boot BOM 与 npm lockfile 固定。

- `ResumeService` 用事务与修订号控制更新；mutation ID 处理最近一次保存重试。
- `AttachmentStorage` 隔离图片存储实现，当前只使用本地文件；当前简历与版本各自保留外键引用。
- `PreviewService` 将输入深拷贝为渲染快照；`paginate.js` 在字体、图片就绪后测量排版，预览与导出共用。
- 分页按条目 / 段落边界切分，最多 10 页；超长单条内容会拒绝导出。不是通用文字处理器，也不提供任意画布。
- 黑体和宋体均本地嵌入，修复共享字形的 Unicode 反向映射；不承诺所有 ATS 系统兼容。

字体、来源散列与许可见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。原创代码与合成样本采用 MIT。当前提供阶段 A/B 的源码开发版，发布镜像和正式版本尚未交付。
