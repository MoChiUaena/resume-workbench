# 第三方声明

## 字体

上游：Google Fonts / Noto Sans SC，来源 <https://github.com/google/fonts/tree/main/ofl/notosanssc>。

获取时间：2026-09-27。原始可变 TTF SHA-256：

`a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da`

字体版权：Copyright 2014-2021 Adobe，保留字体名 Source。许可证为 SIL Open Font License 1.1，全文随字体保存在 `src/main/resources/static/fonts/OFL.txt`。

本项目派生字体改名为 **Local Resume Sans**。`scripts/prepare-fonts.py` 使用 fonttools 4.60.1 将可变字体实例化为 400/700 两个字重，并从 cmap 删除 387 个重复映射：与其 NFKC 字符共享字形的兼容别名，以及与标准 CJK 字符共享字形的部首别名。保留标准 CJK 字符。这样 Chromium/Skia 生成 PDF 时不会优先把普通“行”映射为 U+2F8F 等部首，也处理了没有对应 NFKC 映射的简化部首。此派生字体面向正常中文简历，不保证被移除的特殊兼容字符仍可显示。

派生文件、字重及 SHA-256 记录在 `static/fonts/manifest.json`。构建直接使用已提供的字体，不需运行转换脚本。没有复制任何第三方简历模板、校徽或证件照。

## 主要依赖

阶段 B 新增 Noto Serif SC，来源 <https://github.com/google/fonts/tree/main/ofl/notoserifsc>。原始可变字体 SHA-256 为 `050080d9255a86808f2945bffac582b31ef32bc36411ce29563b4961670c66f9`，Copyright 2012 Google Inc.，SIL OFL 1.1，许可证保存在 `static/fonts/OFL-Serif.txt`。`prepare-fonts.py --serif` 派生 **Local Resume Serif** 的 400/700 字重，并消除 405 个重复映射；派生校验值见 `static/fonts/manifest-serif.json`。

新增 PostgreSQL JDBC 和 Flyway（由 Spring Boot 3.5.16 BOM 固定，分别按 BSD-2-Clause / Apache-2.0 声明），PostgreSQL 服务镜像固定 `postgres:16.10-alpine`，数据库本身使用 PostgreSQL License。

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

Chromium 由 Playwright 官方安装器准备，保留浏览器分发物中的许可证和 third-party notices；它不进入源码仓库。fonttools、Pillow、pypdf 与 Poppler 只用于开发准备或 QA，不是应用运行依赖。

`0.8.0` 的[完整依赖清单](docs/dependency-licenses.md)覆盖 92 个已解析 Maven 运行期依赖（84 个实际分发 JAR）和 75 个 npm 锁定包。POM 父级许可、npm SPDX 声明、依赖 SHA-256 / integrity、上游 LICENSE / NOTICE 与可获取的源码版权头保存在应用 JAR 的 `META-INF/third-party/`；`verify-distribution.py` 检查实际分发 JAR 与清单的完整性。未安装的平台可选 npm 包记录锁定声明，不作为运行期分发物。

项目 MIT 不替代第三方许可证。Logback 的 EPL-2.0 / LGPL-2.1-only 双许可及 Jakarta 的 EPL / GPL + Classpath Exception 声明完整保留；库的官方源码归档位置列在清单中。Adobe XMP 按上游 POM 的 BSD-3-Clause 声明分发，并附 [Adobe SDK 的上游 BSD 许可](https://github.com/adobe/XMP-Toolkit-SDK/blob/main/LICENSE)。JDK、基础系统、Chromium、Playwright 自身分发条款保留在镜像原有的 legal / copyright / license 目录，不将它们改为本项目 MIT。

## 原创合成素材

`scripts/generate-fixtures.py` 生成虚构学校校徽和用于 JPEG / EXIF 测试的卡通图片。这些脚本绘制的素材采用本仓库 MIT 许可证。字体本身继续使用 OFL，不因用于合成图片而改用 MIT。

新建简历示例使用 `src/main/resources/static/samples/nailong-portrait.png`，它是用户在本轮提供的图片的原样副本，不是脚本生成的素材。原图为 690 × 930 像素、648,656 字节；SHA-256 为 `759776674d2f4ef8f30ec1e3223f9e456b3871cc02518cea328eff266a926e2f`。本仓库的 MIT 许可证不对该图片授予额外权利。

## WebP 解码

静态 WebP 使用 TwelveMonkeys ImageIO 3.15.2，按上游 BSD 三条款许可证分发。包含 imageio-webp、imageio-core、imageio-metadata、common-lang、common-io 和 common-image；完整上游条款和各 JAR 散列随 `META-INF/third-party/` 分发，来源见依赖许可证清单。项目没有修改这些依赖，也不需要额外的原生 WebP 库。

## 可选文字模型接入

0.7.0 使用 Spring AI 1.1.8 的兼容文字接口适配器，按上游 Apache-2.0 条款分发，用于 DashScope、DeepSeek、GLM 与用户配置的兼容服务。各新增 Java 依赖的上游条款、来源和 SHA-256 记录在 `META-INF/third-party/inventory.json`，随应用分发。项目不分发模型权重，也不包含用户 API Key。
