# 阶段 A 验收记录

日期：2026-09-27。范围是“学校 Logo + 证件照 + 中文 PDF”最小完整流程；不是首版全部功能验收。

## 环境核验

| 项目 | 实际结果 |
| --- | --- |
| 仓库 | 新建独立 `original/local-resume/.git`；分支 `codex/stage-a` |
| 隔离 | 未修改 `original/agent-triage`、`community` 或父仓库配置；未找到适用的 AGENTS.md |
| JDK | 默认 17；当前项目进程使用已安装的 JDK 21.0.12.1 |
| Maven | Wrapper 3.3.4 / Maven 3.9.16 |
| Node / npm | 24.18.0 / 11.16.0 |
| Docker | Desktop 4.89.0，Engine 29.7.2 可用；阶段 A 未启动新容器 |
| GitHub CLI | keyring 登录有效，登录名 MoChiUaena；未创建远端或推送 |
| 网络 | Maven Central、npm registry、字体来源和 Playwright 浏览器下载可用 |
| 应用 | 打包 JAR 在 `127.0.0.1:18765` 运行，实际监听地址已核对 |

JDK 和 Chromium 配置限于脚本进程。首次浏览器准备时安装器使用了共享目录并自动清理旧缓存；已恢复原有 `chromium-1161`、`chromium_headless_shell-1161`、`firefox-1475`、`webkit-2140`。项目最终使用独立 `.tools/ms-playwright` 并设置 `PLAYWRIGHT_SKIP_BROWSER_GC=1`。

## 实际执行结果

| 验证 | 结果与证据 |
| --- | --- |
| Vue TypeScript + Vite 构建 | 通过；TypeScript 固定 5.9.3，避免 7.x 与 vue-tsc 3.2.6 的已实测不兼容 |
| Java clean package | 18 项测试通过，0 failure / error；报告位于 `target/surefire-reports` |
| 端到端 | 2 组流程通过，包含真实 UI 操作、真实文件上传和 Java/Chromium PDF 导出 |
| 校徽 | 原创透明 PNG，11,038 字节，512×512；标准化后角点 alpha 仍为 0 |
| 证件照 | 原创 JPEG，19,079 字节，EXIF=6；存储像素 480×360，显示归一化为 360×480 |
| EXIF | 1–8 全部方向以角点颜色核验；真实 EXIF=6 样本通过端到端导入 |
| 格式判断 | 扩展名 `.svg`、MIME octet-stream 的 PNG 仍按 PNG 内容成功识别 |
| 错误解释 | 不支持 SVG→415/UNSUPPORTED_FORMAT；损坏 PNG→422/CORRUPT_IMAGE；空文件、像素超限、存储失败有独立错误码 |
| 大小边界 | HTTP 实传 5,242,880 字节成功；5,242,881 字节→413/FILE_TOO_LARGE |
| 存储异常 | 不可作为目录的目标触发 STORAGE_FAILED，不向响应泄露磁盘路径 |
| 独立图片 | 隐藏/替换 Logo 时照片引用不变；移除照片时 Logo 仍显示；可互换左右槽位 |
| 图片配置 | UI 旋转、缩放、重置后预览得到对应样式；失败上传保留原图片 |
| 页面内容 | 姓名编辑实时反映；HTML 特殊字符转义；未知 schemaVersion 被拒绝 |
| 导出一致性 | 预览和 PDF 共用模板；已生成快照不受下一次预览修改影响；生成时不读取 Vue 可变状态 |
| 请求保护 | 外部 Origin、跨站 Fetch-Site、非本机 Host、缺少自定义头的修改请求被拒绝 |
| 外连检查 | E2E 浏览器拦截一切非 loopback 请求，记录为 0；浏览器脚本异常为 0 |

## 中文 PDF 验证

初次输出视觉正常，但姓名字段中的普通汉字被映射为 U+2F8F，部分普通汉字变成康熙或 CJK 部首。变量字体还被 Chromium 输出成大量 Type 3 字体。修复方式是生成两个静态字重，并消除兼容别名/部首与标准汉字的重复 cmap 映射。处理脚本、字体散列和许可证均已保留。

最终检查直接比较中文原字符，**没有**通过 NFKC 归一化放宽验证。

| 最终产物 | 页数 | 大小 | 文本字符数 |
| --- | --- | --- | --- |
| `output/pdf/resume-one-page.pdf` | 1 | 152,702 字节 | 899 |
| `output/pdf/resume-two-page.pdf` | 2 | 193,104 字节 | 899 + 873 |

- 两份文件页面均为 A4（约 594.96 × 841.92 pt），未出现额外空白页。
- `pdffonts` 显示 LocalResumeSans-Bold / Regular 两个 CID TrueType 字体，均嵌入、子集化，并带 Unicode 映射。
- `pypdf` 正确提取姓名、项目标题等原字符。标题阅读顺序正确，没有 U+2E80–U+2FFF 部首替换和 U+FFFD。
- Poppler 渲染最终三个页面并逐页视觉检查：照片正向、右上角校徽透明，无文字或图片重叠；正文、续页、页脚无截断。
- 长链接、中英文技术词、粗体、列表、中文长段落均包含在样本中。没有做“100% ATS”兼容承诺。

最终 PDF SHA-256：

```text
one: 69cbd0ddb55f24916a2be8bfe7405528efe22374245ae527ad1d188a2ec0d64b
two: cc21780f8720512fa8e4f4aab1759415b9d06a2d668cef7d52d5a4a7700c1778
```

机器可读报告为 `output/pdf-verification.json`、`output/e2e-results.json`、`output/network-check.json`。重新导出时生成时间改变，因此 PDF 散列会变化。真实工作台截图保存在 `docs/workbench.png`。

## 尚未验收的范围

PostgreSQL/Flyway、自动保存、任意正文编辑与自动分页、第二模板、版本恢复、完整备份、两服务 Compose、跨平台安装、系统级断网和 CI/发布均留在后续阶段。WebP/HEIC/SVG 不声明导入支持。移除按钮只解除当前图片引用，物理附件清理及历史引用回收未实现。当前图片与 PDF 保存在本机，页面参数刷新后重置。
