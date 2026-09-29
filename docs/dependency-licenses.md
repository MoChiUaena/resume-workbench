# 依赖许可证清单

解析结果：92 个 Maven 运行期依赖（其中 84 个实际 JAR 随应用分发）、75 个 npm 锁定包，未识别声明为 0。

依据已解析 POM（含父 POM）和 npm lockfile 的上游声明。原始 JAR 的 LICENSE / NOTICE 保留在各依赖内，并提取随应用分发；npm 已安装包的许可证文本同样附带。完整清单、SHA-256 / integrity 和文本位于应用 JAR 的 `META-INF/third-party/`。

前端只分发构建后的 JS/CSS；npm 清单为保守的生产树及构建工具清单，不表示所有包都在浏览器执行。平台可选包即使未安装，也记录其锁定许可声明。

## Java 运行期依赖

| 坐标 | 上游许可声明 |
| --- | --- |
| ch.qos.logback:logback-classic:1.5.34 | EPL-2.0 / LGPL-2.1-only |
| ch.qos.logback:logback-core:1.5.34 | EPL-2.0 / LGPL-2.1-only |
| com.adobe.xmp:xmpcore:6.1.11 | The BSD 3-Clause License (BSD3) |
| com.drewnoakes:metadata-extractor:2.21.0 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core:jackson-annotations:2.21 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core:jackson-core:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.core:jackson-databind:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.dataformat:jackson-dataformat-toml:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.module:jackson-module-jsonSchema:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml.jackson.module:jackson-module-parameter-names:2.21.4 | The Apache Software License, Version 2.0 |
| com.fasterxml:classmate:1.7.3 | Apache License, Version 2.0 |
| com.github.victools:jsonschema-generator:4.38.0 | The Apache License, Version 2.0 |
| com.github.victools:jsonschema-module-jackson:4.38.0 | The Apache License, Version 2.0 |
| com.github.victools:jsonschema-module-swagger-2:4.38.0 | The Apache License, Version 2.0 |
| com.google.code.gson:gson:2.13.2 | Apache-2.0 |
| com.google.errorprone:error_prone_annotations:2.41.0 | Apache 2.0 |
| com.knuddels:jtokkit:1.1.0 | MIT License |
| com.microsoft.playwright:driver-bundle:1.63.0 | Apache License, Version 2.0 |
| com.microsoft.playwright:driver:1.63.0 | Apache License, Version 2.0 |
| com.microsoft.playwright:playwright:1.63.0 | Apache License, Version 2.0 |
| com.twelvemonkeys.common:common-image:3.15.2 | The BSD License |
| com.twelvemonkeys.common:common-io:3.15.2 | The BSD License |
| com.twelvemonkeys.common:common-lang:3.15.2 | The BSD License |
| com.twelvemonkeys.imageio:imageio-core:3.15.2 | The BSD License |
| com.twelvemonkeys.imageio:imageio-metadata:3.15.2 | The BSD License |
| com.twelvemonkeys.imageio:imageio-webp:3.15.2 | The BSD License |
| com.zaxxer:HikariCP:6.3.3 | The Apache Software License, Version 2.0 |
| io.micrometer:context-propagation:1.1.4 | The Apache Software License, Version 2.0 |
| io.micrometer:micrometer-commons:1.15.12 | The Apache Software License, Version 2.0 |
| io.micrometer:micrometer-core:1.15.12 | The Apache Software License, Version 2.0 |
| io.micrometer:micrometer-observation:1.15.12 | The Apache Software License, Version 2.0 |
| io.projectreactor:reactor-core:3.7.19 | Apache License, Version 2.0 |
| io.swagger.core.v3:swagger-annotations-jakarta:2.2.38 | Apache License 2.0 |
| jakarta.annotation:jakarta.annotation-api:2.1.1 | EPL 2.0 / GPL2 w/ CPE |
| jakarta.validation:jakarta.validation-api:3.0.2 | Apache License 2.0 |
| javax.validation:validation-api:1.1.0.Final | The Apache Software License, Version 2.0 |
| org.antlr:ST4:4.3.4 | The BSD License |
| org.antlr:antlr-runtime:3.5.3 | BSD licence |
| org.antlr:antlr4-runtime:4.13.1 | BSD-3-Clause |
| org.apache.logging.log4j:log4j-api:2.24.3 | Apache-2.0 |
| org.apache.logging.log4j:log4j-to-slf4j:2.24.3 | Apache-2.0 |
| org.apache.tomcat.embed:tomcat-embed-core:10.1.55 | Apache License, Version 2.0 |
| org.apache.tomcat.embed:tomcat-embed-el:10.1.55 | Apache License, Version 2.0 |
| org.apache.tomcat.embed:tomcat-embed-websocket:10.1.55 | Apache License, Version 2.0 |
| org.attoparser:attoparser:2.0.7.RELEASE | The Apache Software License, Version 2.0 |
| org.flywaydb:flyway-core:11.7.2 | Apache License, Version 2.0 |
| org.flywaydb:flyway-database-postgresql:11.7.2 | Apache License, Version 2.0 |
| org.hdrhistogram:HdrHistogram:2.2.2 | Public Domain, per Creative Commons CC0 / BSD-2-Clause |
| org.hibernate.validator:hibernate-validator:8.0.3.Final | Apache License 2.0 |
| org.jboss.logging:jboss-logging:3.6.3.Final | Apache License 2.0 |
| org.jspecify:jspecify:1.0.0 | The Apache License, Version 2.0 |
| org.latencyutils:LatencyUtils:2.0.3 | Public Domain, per Creative Commons CC0 |
| org.opentest4j:opentest4j:1.3.0 | The Apache License, Version 2.0 |
| org.postgresql:postgresql:42.7.11 | BSD-2-Clause |
| org.reactivestreams:reactive-streams:1.0.4 | MIT-0 |
| org.slf4j:jul-to-slf4j:2.0.18 | MIT |
| org.slf4j:slf4j-api:2.0.18 | MIT |
| org.springframework.ai:spring-ai-commons:1.1.8 | Apache 2.0 |
| org.springframework.ai:spring-ai-model:1.1.8 | Apache 2.0 |
| org.springframework.ai:spring-ai-openai:1.1.8 | Apache 2.0 |
| org.springframework.ai:spring-ai-retry:1.1.8 | Apache 2.0 |
| org.springframework.ai:spring-ai-template-st:1.1.8 | Apache 2.0 |
| org.springframework.boot:spring-boot-autoconfigure:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-jdbc:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-json:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-logging:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-thymeleaf:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-tomcat:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-validation:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter-web:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot-starter:3.5.16 | Apache License, Version 2.0 |
| org.springframework.boot:spring-boot:3.5.16 | Apache License, Version 2.0 |
| org.springframework.retry:spring-retry:2.0.13 | Apache 2.0 |
| org.springframework:spring-aop:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-beans:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-context-support:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-context:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-core:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-expression:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-jcl:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-jdbc:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-messaging:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-tx:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-web:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-webflux:6.2.19 | Apache License, Version 2.0 |
| org.springframework:spring-webmvc:6.2.19 | Apache License, Version 2.0 |
| org.thymeleaf:thymeleaf-spring6:3.1.5.RELEASE | The Apache Software License, Version 2.0 |
| org.thymeleaf:thymeleaf:3.1.5.RELEASE | The Apache Software License, Version 2.0 |
| org.unbescape:unbescape:1.1.6.RELEASE | The Apache Software License, Version 2.0 |
| org.yaml:snakeyaml:2.4 | Apache License, Version 2.0 |

## npm 锁定依赖

| 名称 | 版本 | 许可 | 用途 |
| --- | --- | --- | --- |
| @babel/helper-string-parser | 7.29.7 | MIT | production dependency tree |
| @babel/helper-validator-identifier | 7.29.7 | MIT | production dependency tree |
| @babel/parser | 7.29.9 | MIT | production dependency tree |
| @babel/types | 7.29.8 | MIT | production dependency tree |
| @jridgewell/sourcemap-codec | 1.6.0 | MIT | production dependency tree |
| @oxc-project/types | 0.151.0 | MIT | build/test |
| @playwright/test | 1.63.0 | Apache-2.0 | build/test |
| @rolldown/binding-android-arm-eabi | 1.2.11 | MIT | build/test |
| @rolldown/binding-android-arm64 | 1.2.11 | MIT | build/test |
| @rolldown/binding-darwin-arm64 | 1.2.11 | MIT | build/test |
| @rolldown/binding-darwin-x64 | 1.2.11 | MIT | build/test |
| @rolldown/binding-freebsd-x64 | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-arm-gnueabihf | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-arm64-gnu | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-arm64-musl | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-ppc64-gnu | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-s390x-gnu | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-x64-gnu | 1.2.11 | MIT | build/test |
| @rolldown/binding-linux-x64-musl | 1.2.11 | MIT | build/test |
| @rolldown/binding-openharmony-arm64 | 1.2.11 | MIT | build/test |
| @rolldown/binding-win32-arm64-msvc | 1.2.11 | MIT | build/test |
| @rolldown/binding-win32-x64-msvc | 1.2.11 | MIT | build/test |
| @rolldown/pluginutils | 1.0.1 | MIT | build/test |
| @types/node | 24.10.1 | MIT | build/test |
| @vitejs/plugin-vue | 6.0.9 | MIT | build/test |
| @volar/language-core | 2.4.28 | MIT | build/test |
| @volar/source-map | 2.4.28 | MIT | build/test |
| @volar/typescript | 2.4.28 | MIT | build/test |
| @vue/compiler-core | 3.5.43 | MIT | production dependency tree |
| @vue/compiler-dom | 3.5.43 | MIT | production dependency tree |
| @vue/compiler-sfc | 3.5.43 | MIT | production dependency tree |
| @vue/compiler-ssr | 3.5.43 | MIT | production dependency tree |
| @vue/language-core | 3.2.6 | MIT | build/test |
| @vue/reactivity | 3.5.43 | MIT | production dependency tree |
| @vue/runtime-core | 3.5.43 | MIT | production dependency tree |
| @vue/runtime-dom | 3.5.43 | MIT | production dependency tree |
| @vue/server-renderer | 3.5.43 | MIT | production dependency tree |
| @vue/shared | 3.5.43 | MIT | production dependency tree |
| alien-signals | 3.2.1 | MIT | build/test |
| csstype | 3.2.3 | MIT | production dependency tree |
| detect-libc | 2.1.2 | Apache-2.0 | build/test |
| entities | 7.0.1 | BSD-2-Clause | production dependency tree |
| estree-walker | 2.0.2 | MIT | production dependency tree |
| fdir | 6.5.0 | MIT | build/test |
| fsevents | 2.3.3 | MIT | build/test |
| lightningcss | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-android-arm64 | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-darwin-arm64 | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-darwin-x64 | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-freebsd-x64 | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-linux-arm-gnueabihf | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-linux-arm64-gnu | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-linux-arm64-musl | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-linux-x64-gnu | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-linux-x64-musl | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-win32-arm64-msvc | 1.33.0 | MPL-2.0 | build/test |
| lightningcss-win32-x64-msvc | 1.33.0 | MPL-2.0 | build/test |
| magic-string | 0.30.21 | MIT | production dependency tree |
| muggle-string | 0.4.1 | MIT | build/test |
| nanoid | 3.3.19 | MIT | production dependency tree |
| path-browserify | 1.0.1 | MIT | build/test |
| picocolors | 1.1.1 | ISC | production dependency tree |
| picomatch | 4.0.7 | MIT | build/test |
| playwright | 1.63.0 | Apache-2.0 | build/test |
| playwright-core | 1.63.0 | Apache-2.0 | build/test |
| postcss | 8.5.28 | MIT | production dependency tree |
| rolldown | 1.2.11 | MIT | build/test |
| source-map-js | 1.2.1 | BSD-3-Clause | production dependency tree |
| tinyglobby | 0.2.17 | MIT | build/test |
| typescript | 5.9.3 | Apache-2.0 | production dependency tree |
| undici-types | 7.16.0 | MIT | build/test |
| vite | 8.3.1 | MIT | build/test |
| vscode-uri | 3.2.0 | MIT | build/test |
| vue | 3.5.43 | MIT | production dependency tree |
| vue-tsc | 3.2.6 | MIT | build/test |

## 分发与来源

上游库保持各自许可证，项目 MIT 不覆盖第三方代码。Logback 的上游双许可声明完整保留；Jakarta API 的 EPL / GPL + Classpath Exception 选择声明完整保留。Java 源码归档的官方 Maven 地址记录在 inventory.json；未修改这些依赖 JAR。

JDK 的条款保留在镜像 `/opt/java/openjdk/legal/`；Chromium/Playwright 的条款保留在 `/ms-playwright/` 与 `/ms-playwright-driver/`；基础系统包的 copyright 文件保留在 `/usr/share/doc/`。PostgreSQL 是单独的官方服务镜像，按 PostgreSQL License 分发。字体 OFL 和用户提供的奶龙图片边界见 THIRD_PARTY_NOTICES.md。

重新生成：`mvnw dependency:list -DincludeScope=runtime -DoutputAbsoluteArtifactFilename=true -DoutputFile=target/runtime-dependencies.txt`，执行 `npm ci` 后运行 `python scripts/generate-notices.py`。不要把包含宿主机路径的原始 dependency:list 输出提交到仓库。
