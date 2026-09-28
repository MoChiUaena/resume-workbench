# 参与开发

请先阅读 README 和 [架构说明](docs/architecture.md)。首版范围是单人本地简历工作台；新功能应维持两服务部署、同源本机访问、原有数据与版本引用，以及预览 / PDF 共用排版。

开发使用 JDK 21、Node 22.12+ 和 Docker。执行 scripts/start.ps1 或 start.sh 准备数据库、前端、应用和 Chromium。不要修改已有数据库卷的密码，不要把真实简历、.env、备份 ZIP 或未授权素材提交到仓库。

Java 测试使用独立 resume_test / backup_test schema；浏览器与备份恢复测试使用独立空 Compose 实例。运行恢复测试必须显式设置 RESUME_TEST_ISOLATED=1，切勿指向自己实际使用的工作区。

```powershell
. ./scripts/prepare-db.ps1
$env:JAVA_HOME='你的 JDK 21 目录'
./mvnw.cmd test
cd frontend
npm ci
npm run build
npm run test:e2e
```

PDF 与完整恢复的重复运行方式见 docs/stage-c-verification.md 和 docs/layout-verification.md。保持源文件依赖、POM、npm lockfile 与许可证清单一致；新增图片格式或字体后先验证实际渲染和文本提取，再声明支持。

PR 描述说明具体触发场景、行为变化、验证和数据兼容性。涉及 schema 时明确旧简历 / 备份的处理；涉及文件清理时覆盖当前与历史引用。演示和测试使用合成数据，不构造真实学校、个人经历或未经测量的性能数字。

发布版本的步骤见 docs/releasing.md。发布标签不覆盖，升级流程不删除用户数据卷。
