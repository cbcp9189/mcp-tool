package dev.local.mcp.agent;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

public final class McpGateway implements AutoCloseable {

    private final McpSyncClient client;

    public McpGateway(int port) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp")
                .build();
        this.client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("ac-web", "0.0.1"))
                .requestTimeout(Duration.ofSeconds(30))
                .initializationTimeout(Duration.ofSeconds(30))
                .build();
        this.client.initialize();
    }

    public List<McpSchema.Tool> listTools() {
        return client.listTools().tools();
    }

    public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
        return client.callTool(new McpSchema.CallToolRequest(name, arguments == null ? Map.of() : arguments));
    }

    public static String flatten(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        if (result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent textContent) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(textContent.text());
                }
            }
        }
        if (text.isEmpty() && result.structuredContent() != null) {
            text.append(result.structuredContent());
        }
        if (text.isEmpty()) {
            text.append(Boolean.TRUE.equals(result.isError()) ? "工具调用失败" : "工具没有返回内容");
        }
        return collapseDuplicateLines(text.toString());
    }

    private static String collapseDuplicateLines(String text) {
        String normalized = text.replace("\r\n", "\n").trim();
        String[] lines = normalized.split("\n");
        if (lines.length == 2 && lines[0].trim().equals(lines[1].trim())) {
            return lines[0].trim();
        }
        return normalized;
    }

    @Override
    public void close() {
        try {
            client.closeGracefully();
        } catch (RuntimeException ignored) {
            // 结果已经拿到，关闭会话失败不影响这一轮回复。
        }
    }
}
