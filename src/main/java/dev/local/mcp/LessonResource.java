package dev.local.mcp;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class LessonResource {

    @McpResource(
            uri = "lesson://notes",
            name = "lesson-notes",
            title = "学习笔记",
            description = "一份可按 URI 读取的固定说明，用来对比工具调用和资源读取",
            mimeType = "text/plain")
    public String notes() {
        return """
                工具会执行一次函数。add 返回两数之和，divide 在除数为 0 时抛出 IllegalArgumentException，这条错误会回到模型。
                资源不会执行计算。客户端按 URI lesson://notes 读取的就是这段已经写好的文本。
                """;
    }
}
