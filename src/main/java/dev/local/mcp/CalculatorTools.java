package dev.local.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class CalculatorTools {

    @McpTool(
            name = "add",
            description = "把两个整数相加，返回它们的和",
            annotations = @McpTool.McpAnnotations(
                    title = "整数相加",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public int add(
            @McpToolParam(description = "第一个加数") int a,
            @McpToolParam(description = "第二个加数") int b) {
        return a + b;
    }

    @McpTool(
            name = "divide",
            description = "把整数 a 除以整数 b，返回商。b 为 0 时调用失败，并把原因返回给模型",
            annotations = @McpTool.McpAnnotations(
                    title = "整数相除",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public double divide(
            @McpToolParam(description = "被除数") int a,
            @McpToolParam(description = "除数，不能为 0") int b) {
        if (b == 0) {
            throw new IllegalArgumentException("除数不能为 0");
        }
        return (double) a / b;
    }
}
