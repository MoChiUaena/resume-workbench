# 第三方声明

## 字体

上游：Google Fonts / Noto Sans SC，来源 <https://github.com/google/fonts/tree/main/ofl/notosanssc>。

获取时间：2026-09-27。原始可变 TTF SHA-256：

`a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`

字体版权：Copyright 2014-2021 Adobe，保留字体名 Source。许可证为 SIL Open Font License 1.1，全文随字体保存在 `src/main/resources/static/fonts/OFL.txt`。

本项目派生字体改名为 **Local Resume Sans**。`scripts/prepare-fonts.py` 使用 fonttools 4.60.1 将可变字体实例化为 400/700 两个字重，并从 cmap 删除 387 个重复映射：与其 NFKC 字符共享字形的兼容别名，以及与标准 CJK 字符共享字形的部首别名。保留标准 CJK 字符。这样 Chromium/Skia 生成 PDF 时不会优先把普通“行”映射为 U+2F8F 等部首，也处理了没有对应 NFKC 映射的简化部首。此派生字体面向正常中文简历，不保证被移除的特殊兼容字符仍可显示。

派生文件、字重及 SHA-256 记录在 `static/fonts/manifest.json`。构建直接使用已提供的字体，不需运行转换脚本。没有复制任何第三方简历模板、校徽或证件照。

## 主要依赖

| 组件 | 版本 | 上游许可证 |
| --- | --- | --- |
| Spring Boot / Spring Framework | 3.5.16 / BOM 管理 | Apache-2.0 |
| Playwright Java / Playwright Test | 1.63.0 | Apache-2.0 |
| metadata-extractor | 2.21.0 | Apache-2.0 |
| Thymeleaf | Spring Boot BOM 管理 | Apache-2.0 |
| Vue | 3.5.43 | MIT |
| Vite | 8.3.1 | MIT |
| TypeScript | 5.9.3 | Apache-2.0 |
| Maven Wrapper | 3.3.4 | Apache-2.0（脚本保留声明） |

Chromium 由 Playwright 官方安装器准备，保留浏览器分发物中的许可证和 third-party notices；它不进入源码仓库。fonttools、Pillow、pypdf 与 Poppler 只用于开发准备或 QA，不是应用运行依赖。完整传递依赖声明及发布物审计在阶段 D 处理。

## 原创合成素材

`scripts/generate-fixtures.py` 生成示例校徽和示意证件照。校徽代表虚构学校，照片为程序绘制的人物示意图，不对应真实个人。文件采用本仓库 MIT 许可证。字体本身继续使用 OFL，不因用于合成图片而改用 MIT。
