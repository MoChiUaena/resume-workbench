# Resume Workbench · 简历工作台

一个在本机编辑中文简历的开源工具。选择简历、修改内容和排版，右侧实时预览，再导出中文 PDF。简历与备份保存在自己的设备上，无需注册；可选的模型润色仅在确认后发送选中句段。

[下载最新版](https://github.com/MoChiUaena/resume-workbench/releases/latest) · [查看操作演示](docs/demo.gif)

![简历编辑与实时预览](docs/workbench-editor.png)

## 项目功能

- **管理简历**：新建、重命名、复制和删除，为不同岗位保存不同版本。
- **Word 导入（当前源码版）**：本机解析 DOCX，核对并修改识别结果后创建新简历；已有简历保留。[查看使用方法](docs/docx-import.md)。已发布的 v0.8.0 镜像尚不包含此功能。
- **实时编辑**：编辑基本信息、教育、工作、项目、技能和自定义模块，支持排序与隐藏。
- **调整排版**：四种简历模板，可修改字体、字号、颜色、对齐、行距、间距和页边距。
- **处理图片**：支持 JPEG、PNG 和静态 WebP；学校 Logo 与证件照独立设置，可替换、裁剪、旋转和调整大小。
- **保存与导出**：自动保存、撤销与重做、历史版本对比和恢复，导出可选择文字的中文 PDF 或脱敏 PDF。
- **备份与恢复**：手动备份、每天／每周自动备份、备份历史查看、下载和恢复。
- **切换界面**：蓝白工具、纸张文档两种风格，以及深色模式；界面风格与 PDF 模板分别设置。
- **可选文字润色**：接入 DashScope、DeepSeek、GLM 等服务；划选句段并核对差异后再应用。[查看设置方法](docs/model-settings.md)。
- **职位匹配与报告历史**：选择模块并输入岗位要求，核对发送内容后生成分析，逐条人工审核改写建议；可命名保存报告，在历史中查看岗位原文、原材料与只读建议。[查看使用方法](docs/model-settings.md#职位匹配)。

示例简历使用奶龙素材，创建后可替换为自己的内容和图片。

## 安装与启动

准备好 Docker Desktop 或 Docker Engine + Compose。发布镜像为 Linux amd64，运行后支持离线编辑和导出。

1. 从[最新发布](https://github.com/MoChiUaena/resume-workbench/releases/latest)下载 `resume-workbench-config.zip`，解压并在该目录打开终端。
2. 复制配置文件：

   ```powershell
   Copy-Item .env.example .env
   ```

   Linux/macOS 使用 `cp .env.example .env`。

3. 编辑 `.env`，将 `RESUME_DB_PASSWORD` 改为自己的长随机密码。
4. 启动应用：

   ```sh
   docker compose up -d --wait
   ```

5. 打开 <http://127.0.0.1:18765>。

首次下载镜像需要联网。端口被占用时，可修改 `.env` 中的 `RESUME_PORT`。当前版本面向单人本机使用，没有账号认证。

### Windows 源码运行

准备 PowerShell 7.2 或更新版本、JDK 21、Node.js 24 和 Docker Desktop，在项目目录运行。将下面的 JDK 路径换成本机目录：

```powershell
pwsh -File .\scripts\workbench.ps1 -Action start -Build -JavaHome "C:\Java\jdk-21"
```

首次构建会准备前端、Java 和 PDF 浏览器资源，随后在后台启动。日常管理使用同一个入口：

```powershell
pwsh -File .\scripts\workbench.ps1 -Action status
pwsh -File .\scripts\workbench.ps1 -Action stop
pwsh -File .\scripts\workbench.ps1 -Action start -JavaHome "C:\Java\jdk-21"
pwsh -File .\scripts\workbench.ps1 -Action restart -JavaHome "C:\Java\jdk-21"
pwsh -File .\scripts\workbench.ps1 -Action logs
```

更新源码后用 `-Action restart -Build` 重新构建并切换版本。停止前确认页面显示「已保存到本机」；停止只结束经身份核验的本项目 Java 进程，保留数据库、数据和日志。端口被其他程序占用、运行记录损坏或进程身份改变时会拒绝停止。关闭启动终端后，后台应用继续运行。

运行记录在 `.tools/runtime/state.json`，每次启动的日志在 `.tools/runtime/logs`。`status -Json` 返回机器可读信息；`logs` 列出日志目录。自定义应用端口可加 `-Port`；使用已经准备好的外部数据库时可加 `-SkipDatabase`。

再次启动或重启会沿用记录中的 JDK、端口和数据目录；需要更改时明确传入对应参数。开发数据库会核验容器和持久化卷的目录归属，拒绝接管另一份源码目录的数据。

## 怎么使用

1. **选择简历**：在「我的简历」中新建空白简历或示例，也可以复制已有简历后修改。
2. **修改内容**：左侧选择模块，中间填写内容，右侧实时查看效果；在「照片与校徽」中上传学校 Logo 和证件照。
3. **调整样式**：在「版式设置」中选择模板，通过「文字」「样式」「间距」调整排版；页面右上角可切换界面风格和深色模式。
4. **保留版本**：内容会自动保存，关闭前确认显示「已保存到本机」。需要保留某次修改时点击「保存版本」，之后可在「历史版本」中对比或恢复。
5. **导出 PDF**：点击「导出 PDF」下载完整简历；分享时可选择「脱敏 PDF」，设置要隐藏的信息，检查预览后导出。脱敏设置仅用于导出副本，原稿保留。

## 备份与恢复

点击页面顶部的「备份与恢复」：

- **手动备份**：创建并下载完整 ZIP，包含简历、版式、历史版本、已保存的职位匹配报告、图片和相关 PDF。
- **自动备份**：默认关闭。勾选启用，选择每天或每周，再保存设置；数据有变化时生成副本。浏览器无需保持打开；停止应用后暂停，重启后补做到期检查。
- **本机历史**：查看和下载已有备份，或选择备份恢复。也可以导入下载过的 ZIP；恢复会新增简历，现有记录保留。

正文、历史版本和已保存报告保存在本机数据库，图片、PDF 和备份保存在应用数据卷中，备份目录为 `data/backups`。建议将完整 ZIP 下载后另存到独立位置，用于迁移或设备恢复。完整备份包含原始信息，请妥善保存。

含 WebP 原图的备份需要 0.6.0 或更新版本恢复；已有 JPEG／PNG 备份仍可使用。

0.8.0 的完整备份包含报告历史，需要 0.8.0 或更新版本恢复，0.7.0 等旧版无法导入。新程序仍可恢复原来的备份。

在“空间检查”中查看图片与 PDF 占用，确认选中的候选并移至可恢复暂存区，再从记录恢复原位置。暂存仍占磁盘空间；完整批次可在下载文件 ZIP、确认副本已保存并输入批次末六位后永久清理，清理不可撤销。[查看使用方法](docs/storage-preview.md)。

## 停止、重启与升级

停止应用：

```sh
docker compose stop
```

重新启动：

```sh
docker compose up -d --wait
```

升级前先下载完整备份。下载新版配置并更新 `compose.yml`，将原 `.env` 中的 `RESUME_APP_IMAGE` 改为新版 `.env.example` 对应的镜像版本，保留原数据库密码和两个数据卷，再运行：

```sh
docker compose pull
docker compose up -d --wait
```

正常停止、重启和升级均应保留数据卷，以便继续使用原有简历、图片和备份。

从 0.7.0 升级到 0.8.0 时，数据库会自动增加报告历史表，现有简历、版本、图片和模型配置继续保留。升级前的 ZIP 建议另存；需要回到旧程序时，应使用升级前的备份在独立实例恢复，新版备份不能直接导入旧程序。完整工作区 ZIP 不包含模型密钥，迁移到其他设备后需要重新配置模型。

项目代码采用 [MIT 许可证](LICENSE)，字体和第三方素材的授权见 [第三方声明](THIRD_PARTY_NOTICES.md)。
