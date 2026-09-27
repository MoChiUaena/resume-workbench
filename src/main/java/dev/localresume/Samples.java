package dev.localresume;

import java.util.List;

/** Original synthetic examples, never claims about a real candidate or school. */
public final class Samples {
    public record Entry(String title, String meta, List<String> bullets) {}
    public record Section(String title, List<Entry> entries) {}
    public static List<List<Section>> pages(String sample) {
        var first = List.of(
            new Section("教育背景", List.of(new Entry("示例理工大学 · 软件工程", "2023.09 - 2027.06 · 本科（合成示例）", List.of(
                "主修课程：数据结构、计算机网络、操作系统、数据库系统与软件工程。")))),
            new Section("专业技能", List.of(new Entry("Java 后端 / AI 应用", "", List.of(
                "Java：熟悉集合、异常处理与并发基础；理解线程池、JVM 内存模型及常用排查方法。",
                "Spring Boot：能够设计 REST API，使用 Bean Validation、事务和统一异常处理。",
                "数据与工具：PostgreSQL、Redis、Git、Docker；了解 Spring AI 与工具调用流程。")))),
            new Section("项目经历", List.of(
                new Entry("本地简历工作台", "个人练习项目 · Java / Vue / Chromium", List.of(
                    "设计独立的学校 Logo 与证件照布局槽位，通过 CSS Grid 分配空间，避免图片与姓名、联系方式重叠。",
                    "图片先校验内容格式和像素尺寸，再解码并校正 EXIF 方向；分别保存原图与标准化图片。",
                    "预览与 PDF 共用本地模板和中文字体，导出固定到同一份输入快照，保留可搜索的文本。")),
                new Entry("任务管理 API", "课程实践 · Spring Boot / PostgreSQL", List.of(
                    "按控制器、业务服务和存储接口划分职责，使用数据库迁移管理表结构变更。",
                    "为输入校验、冲突处理和异常响应编写集成验证，并记录可复现的请求样例。")))),
            new Section("实践与学习", List.of(new Entry("工程实践记录", "持续学习", List.of(
                "阅读开源项目的接口设计与部署文档，在独立分支中完成小范围修改和验证。",
                "关注用户可解释的失败提示、数据位置和版本边界；性能与效果仅报告实际测量结果。")))),
            new Section("补充信息", List.of(new Entry("示例链接与说明", "", List.of(
                "长链接换行样本：https://example.invalid/portfolio/local-resume/chinese-typesetting-and-export-verification",
                "此简历仅用于布局与导出验证。姓名、学校、联系方式与经历均为合成内容，请替换为真实信息。"))))
        );
        if (sample.equals("one")) return List.of(first);
        var second = List.of(
            new Section("项目细节 · 续页", List.of(new Entry("从输入到导出的完整链路", "技术说明样本", List.of(
                "输入层按文件内容识别 JPEG 与 PNG，不把文件扩展名当作可信格式；透明校徽保留 alpha 通道。",
                "处理层将 EXIF 方向归一化，预览与导出引用相同的处理结果，避免方向在不同客户端之间变化。",
                "布局层把内容和版式参数分开，使用明确的左右图片槽位及中间文字区域。长英文词组和链接允许换行。",
                "导出层等待字体就绪与图片解码后生成 A4 文档，导出过程使用不可变快照，后续编辑不会混入当前文件。")))),
            new Section("接口与错误处理", List.of(new Entry("让失败可定位、可恢复", "设计练习", List.of(
                "上传限制统一使用 MiB；文件过大、像素超限、格式不支持、解码损坏和本地存储失败使用不同错误码。",
                "页面只有收到成功结果才更新图片引用；失败时保留原来的照片或 Logo，允许修正后重新尝试。",
                "导出并发设置上限，浏览器资源在任务结束时释放；失败后可重试，不要求引入消息队列。")))),
            new Section("测试与验证", List.of(new Entry("可重复的验收证据", "中文 / English / 粗体 / 列表", List.of(
                "一页与两页样本均检查 A4 页面大小、照片和 Logo 的相对位置、中文字体以及分页边界。",
                "使用带颜色方向标记的 EXIF 图片检查旋转，使用透明 PNG 检查背景保留，并测试不支持和损坏文件。",
                "通过 PDF 文本提取确认中文可搜索与阅读顺序；文本验证不替代实际页面的视觉检查。")))),
            new Section("后续计划", List.of(new Entry("按阶段交付", "当前只验证阶段 A", List.of(
                "阶段 B：结构化编辑、自动保存、PostgreSQL、两个模板和版本快照。",
                "阶段 C：两服务部署、完整备份恢复和运行期离线验收。",
                "阶段 D：真实截图、操作文档、开源许可证和发布流程。")))),
            new Section("长中文段落样本", List.of(new Entry("排版质量的边界", "", List.of(
                "可靠的简历工具需要同时关注输入、预览、存储与导出。图片上传成功并不代表最终文档正确，中文能显示也不代表文本能够被提取。本样本以真实输出检查这些环节，并保留明确的能力边界，不宣称任何招聘系统都能无误解析，也不以未经验证的数字描述性能。"))))
        );
        return List.of(first, second);
    }
}
