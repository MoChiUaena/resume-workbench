# 纸间 · Local Resume

面向中文技术求职者的本地简历工作台。当前交付 **阶段 A 技术验证**：独立学校 Logo、证件照、共享预览模板和可搜索的中文 A4 PDF。

![实际运行截图](docs/workbench.png)

## 当前可用

- 页眉左侧证件照、中间姓名与联系方式、右上角透明学校 Logo；可以互换左右槽位。
- 两张图片独立上传、替换、隐藏、移除；可配置尺寸、contain/cover、旋转、缩放和裁剪位置。
- JPEG / PNG 内容识别、EXIF 方向校正、透明度保留；默认 5 MiB 文件限制、2400 万像素限制、单边 12000 像素限制。
- 一页 / 两页原创合成样本。预览和 PDF 使用相同服务端 HTML/CSS、字体和标准化图片。
- 本地 Chromium 导出保留文字的 PDF，单任务并发、固定输入快照、明确错误提示。
- 中文字体和许可随项目提供，基础流程无需模型 Key、账号或外部图片服务。

**阶段 A 的边界：**正文是固定验收样本，不是完整编辑器；只提供一个模板的两种页眉位置。当前页面参数不持久保存，刷新后重置；尚未接入 PostgreSQL、自动保存、历史版本、完整备份恢复和发布版 Compose。不要用这个验证版保存唯一的一份正式简历。

## Windows 启动

需要 JDK 21、Node.js 22.12+（本机验证为 24.18.0）、npm。Maven 由 Wrapper 固定到 3.9.16，不要求全局安装。首次准备依赖和浏览器需要联网。

在项目根目录执行：

```powershell
./scripts/start.ps1 -JavaHome '你的 JDK 21 目录'
```

本次环境已经完成构建和浏览器准备，可直接：

```powershell
./scripts/start.ps1 -JavaHome "$env:USERPROFILE/java/jdk-21" -SkipBuild
```

访问 <http://127.0.0.1:18765>。页面会载入两张原创合成图片，切换“一页样本 / 两页样本”并点击“导出 PDF”即可验证。默认只监听 loopback；不要将该阶段版本作为无认证公网服务发布。

`Ctrl+C` 正常停止服务，不会删除本地图片或 PDF。脚本中的 JDK 和浏览器变量仅作用于当前进程。Chromium 使用项目 `.tools/ms-playwright`，同时禁止浏览器安装器清理共享缓存。

Linux/macOS 源码入口为 `scripts/start.sh`，需先具备 JDK 21、Node 和 Chromium 系统依赖；本阶段实际运行验收环境为 Windows，尚未声明跨平台安装验收通过。普通用户镜像及两服务 Compose 在阶段 C 实现。

## 数据位置与配置

默认 `data/` 在项目内，可通过 `RESUME_DATA_DIR` 改到其他目录。

```text
data/
  attachments/<稳定 UUID>/
    original.jpeg 或 original.png  # 上传原文件
    image.png                      # 已校正方向的标准化图片
    metadata.json                  # 格式、字节数、尺寸、方向和 SHA-256
  exports/<UUID>.pdf                # 导出产物
  exports/<UUID>.json               # 输入快照摘要、PDF 摘要与生成时间
```

预览快照仅在内存中保留 30 分钟，最多 64 个。移除图片只解除当前页面引用，不物理删除已导入文件；本阶段没有垃圾回收、历史引用管理或备份协议。`data/`、`.tools/`、`target/`、`output/` 均被 Git 忽略。

`RESUME_MAX_UPLOAD_BYTES` 同时决定后端限制及页面显示的限额，默认 `5242880`；multipart 请求额外预留 `524288` 字节，不把表单开销算作图片大小。`PORT` 默认 `18765`。

## 重复验证

先启动应用，再开另一个终端：

```powershell
$env:JAVA_HOME='你的 JDK 21 目录'
./mvnw.cmd test
cd frontend
npm ci
npm run build
npm run test:e2e
```

E2E 会真实上传图片、操作隐藏/替换/移除/布局切换，并调用 Java 导出器，生成：

- `output/pdf/resume-one-page.pdf`
- `output/pdf/resume-two-page.pdf`
- `output/workbench-one-page.png`
- `output/e2e-results.json` 与 `output/network-check.json`

额外的 PDF 检查需要 Python 的 `pypdf` 和 Poppler（`pdftoppm`、`pdffonts`）：

```powershell
python scripts/verify-pdfs.py
```

它检查 A4 尺寸、实际页数、中文原字符、阅读顺序和嵌入字体，并渲染每页 PNG 供视觉复核。**不对提取文本做 NFKC 归一化**，避免把部首映射错误掩盖成成功。验证记录见 [docs/stage-a-verification.md](docs/stage-a-verification.md)。

前端测试禁止所有非 loopback 请求，Java 导出器只允许当前快照和它引用的本地图片/字体。此证据范围不等于整个操作系统断网验收；完整离线运行检查留在阶段 C。

## 技术选择

Java 21 / Spring Boot 3.5.16 / Playwright Java 1.63.0；Vue 3.5.43 / Vite 8.3.1 / TypeScript 5.9.3。依赖固定在 POM 和 npm lockfile。使用 JDK ImageIO 解码 JPEG/PNG，metadata-extractor 2.21.0 读取 EXIF。

模板在 `src/main/resources/templates/resume.html`，打印样式在 `static/print.css`。Vue 编辑参数后生成不可变预览快照，iframe 和导出器访问同一个渲染地址。当前样本使用明确分页，不代表任意长文档的自动分页已经完成。

当前字体由 Noto Sans SC 派生为两个静态字重，并删除与标准字符共享同一字形的兼容字符别名，修复 Chromium PDF 提取中文时出现康熙部首的问题。原始来源、SHA-256、处理脚本与 OFL 许可均保留，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 下一阶段

阶段 B 接入 PostgreSQL/Flyway、本地结构化编辑与自动保存，再实现第二模板和版本快照。阶段 C 完成两服务部署、重启持久化、完整备份恢复和离线验收；阶段 D 完成发布流程与 CI。AI 和对象存储均不作为这些阶段的前置条件。

原创代码和合成样本采用 MIT；第三方字体和依赖各自保留原许可证。项目尚未创建远端仓库或发布镜像。
