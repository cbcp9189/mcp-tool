package dev.local.mcp.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import dev.local.mcp.air.AirConditioner;
import dev.local.mcp.air.AirConditionerStore;
import io.modelcontextprotocol.spec.McpSchema;

@Service
public class AirConditionerAgent {

    static final String SYSTEM_PROMPT = """
            你是这套演示里的空调控制助手。用户用自然语言提要求，你通过工具完成，并用简体中文回复。
            可用工具只有 find_air_conditioners 和 set_temperature。
            规则：
            1. 每次准备调节温度时，先调用 find_air_conditioners，用返回里的设定温度做计算。不要凭记忆假设当前温度。
            2. 用户没有指明哪一台时，关键字传空字符串列出全部。结果多于 1 台时，禁止调用 set_temperature，先问用户是哪一台。
            3. 结果正好 1 台时可以继续。用户说降低一度或调高一度时，以该设备的设定温度为基准加或减，再调用 set_temperature。
            4. 用户说设置为大概 27 度时，收成整数 27 再设置。
            5. 工具返回错误时，把原因告诉用户，不要假装已经成功。
            6. 设定温度只能是 16 到 30 的整数。
            7. 回复里直接说哪台空调从多少度调到了多少度，不要提工具的英文名。
            """;

    private static final Set<String> AIR_TOOLS = Set.of("find_air_conditioners", "set_temperature");
    private static final int MAX_ROUNDS = 4;
    private static final int MAX_TOOL_CALLS = 6;

    private final AirConditionerStore store;
    private final LlmClient llmClient;
    private final LlmProperties llmProperties;
    private final int port;

    public AirConditionerAgent(
            AirConditionerStore store,
            LlmClient llmClient,
            LlmProperties llmProperties,
            @Value("${server.port:8090}") int port) {
        this.store = store;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.port = port;
    }

    public ChatResult run(List<HistoryMessage> history, Consumer<AgentStep> onStep) {
        List<HistoryMessage> dialogue = sanitize(history);
        onStep.accept(new AgentStep("mcp", "正在连接 MCP", "http://127.0.0.1:" + port + "/mcp"));
        try (McpGateway mcp = new McpGateway(port)) {
            return runConnected(dialogue, mcp, onStep);
        } catch (RuntimeException exception) {
            String message = messageOf(exception);
            onStep.accept(new AgentStep("error", "连接 MCP 失败", message));
            return new ChatResult("连不上本机 MCP 服务：" + message, store.all());
        }
    }

    private ChatResult runConnected(List<HistoryMessage> dialogue, McpGateway mcp, Consumer<AgentStep> onStep) {
        try {
            List<McpSchema.Tool> tools = mcp.listTools();
            List<String> names = tools.stream().map(McpSchema.Tool::name).toList();
            List<Map<String, Object>> advertised = new ArrayList<>();
            for (McpSchema.Tool tool : tools) {
                if (AIR_TOOLS.contains(tool.name())) {
                    advertised.add(toOpenAiTool(tool));
                }
            }
            String advertisedNames = advertised.stream()
                    .map(tool -> String.valueOf(functionOf(tool).get("name")))
                    .reduce((left, right) -> left + "、" + right)
                    .orElse("无");
            onStep.accept(new AgentStep(
                    "mcp",
                    "MCP 返回工具清单",
                    "tools/list 返回：" + String.join("、", names) + "\n交给模型的工具：" + advertisedNames));
            if (advertised.size() != AIR_TOOLS.size()) {
                return finish("MCP 服务里没有找齐空调工具。", onStep);
            }
            if (!llmProperties.configured()) {
                return finish("还没有配置大模型。启动前设置环境变量 LLM_BASE_URL、LLM_API_KEY、LLM_MODEL，然后重启服务。", onStep);
            }
            return converse(dialogue, advertised, mcp, onStep);
        } catch (RuntimeException exception) {
            String message = messageOf(exception);
            onStep.accept(new AgentStep("error", "本轮失败", message));
            return new ChatResult(message, store.all());
        }
    }

    private ChatResult converse(
            List<HistoryMessage> dialogue,
            List<Map<String, Object>> tools,
            McpGateway mcp,
            Consumer<AgentStep> onStep) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        for (HistoryMessage message : dialogue) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        int toolCalls = 0;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            LlmClient.LlmTurn turn = llmClient.complete(messages, tools);
            if (turn.toolCalls().isEmpty()) {
                String reply = turn.text().isBlank() ? "模型没有给出回复。" : turn.text();
                onStep.accept(new AgentStep("llm", "模型给出最终回复", reply));
                return new ChatResult(reply, store.all());
            }
            if (!turn.text().isBlank()) {
                onStep.accept(new AgentStep("llm", "模型说明", turn.text()));
            }
            messages.add(assistantMessage(turn));
            for (Map<String, Object> toolCall : turn.toolCalls()) {
                if (toolCalls >= MAX_TOOL_CALLS) {
                    onStep.accept(new AgentStep("error", "工具调用次数已达上限", "本轮最多调用 " + MAX_TOOL_CALLS + " 次工具"));
                    messages.add(toolMessage(toolCall, "错误：本轮工具调用次数已达上限"));
                    break;
                }
                toolCalls++;
                String name = functionName(toolCall);
                Map<String, Object> arguments = llmClient.parseArguments(functionArguments(toolCall));
                onStep.accept(new AgentStep("llm", "模型决定调用 " + name, formatArguments(arguments)));
                String outcome = execute(mcp, name, arguments, onStep);
                messages.add(toolMessage(toolCall, outcome));
            }
        }
        LlmClient.LlmTurn closing = llmClient.complete(messages, List.of());
        String reply = closing.text().isBlank() ? "模型没有给出回复。" : closing.text();
        onStep.accept(new AgentStep("llm", "模型给出最终回复", reply));
        return new ChatResult(reply, store.all());
    }

    private String execute(McpGateway mcp, String name, Map<String, Object> arguments, Consumer<AgentStep> onStep) {
        if (!AIR_TOOLS.contains(name)) {
            String text = "错误：不能调用 " + name;
            onStep.accept(new AgentStep("error", "拒绝工具 " + name, text));
            return text;
        }
        try {
            McpSchema.CallToolResult result = mcp.callTool(name, arguments);
            String text = McpGateway.flatten(result);
            boolean failed = Boolean.TRUE.equals(result.isError());
            String forModel = failed ? "错误：" + text : text;
            onStep.accept(new AgentStep(failed ? "error" : "mcp", "MCP 调用 " + name, "参数\n" + formatArguments(arguments) + "\n\n结果\n" + text));
            return forModel;
        } catch (RuntimeException exception) {
            String text = exception.getMessage() == null ? "工具调用失败" : exception.getMessage();
            onStep.accept(new AgentStep("error", "MCP 调用 " + name + " 失败", text));
            return "错误：" + text;
        }
    }

    private ChatResult finish(String reply, Consumer<AgentStep> onStep) {
        onStep.accept(new AgentStep("error", "本轮停止", reply));
        return new ChatResult(reply, store.all());
    }

    private static List<HistoryMessage> sanitize(List<HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            throw new IllegalArgumentException("请输入要做的事");
        }
        List<HistoryMessage> kept = new ArrayList<>();
        for (HistoryMessage message : history) {
            if (message == null || message.role() == null || message.content() == null) {
                continue;
            }
            String role = message.role().trim();
            String content = message.content().trim();
            if (content.isEmpty() || (!role.equals("user") && !role.equals("assistant"))) {
                continue;
            }
            if (content.length() > 2000) {
                throw new IllegalArgumentException("单条内容不能超过 2000 字");
            }
            kept.add(new HistoryMessage(role, content));
        }
        if (kept.size() > 12) {
            kept = new ArrayList<>(kept.subList(kept.size() - 12, kept.size()));
        }
        if (kept.isEmpty() || !"user".equals(kept.get(kept.size() - 1).role())) {
            throw new IllegalArgumentException("请输入要做的事");
        }
        return kept;
    }

    private static Map<String, Object> toOpenAiTool(McpSchema.Tool tool) {
        Map<String, Object> schema = tool.inputSchema() == null ? Map.of() : tool.inputSchema();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", schema.getOrDefault("properties", Map.of()));
        if (schema.get("required") != null) {
            parameters.put("required", schema.get("required"));
        }
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description() == null ? "" : tool.description());
        function.put("parameters", parameters);
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }

    private static Map<String, Object> assistantMessage(LlmClient.LlmTurn turn) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", turn.text().isBlank() ? null : turn.text());
        message.put("tool_calls", turn.toolCalls());
        return message;
    }

    private static Map<String, Object> toolMessage(Map<String, Object> toolCall, String content) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "tool");
        message.put("tool_call_id", String.valueOf(toolCall.getOrDefault("id", "call")));
        message.put("content", content);
        return message;
    }

    private static Map<?, ?> functionOf(Map<String, Object> tool) {
        Object function = tool.get("function");
        if (function instanceof Map<?, ?> map) {
            return map;
        }
        return Map.of();
    }

    private static String messageOf(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "处理失败" : message;
    }

    private static String functionName(Map<String, Object> toolCall) {
        if (toolCall.get("function") instanceof Map<?, ?> function && function.get("name") instanceof String name) {
            return name;
        }
        return "";
    }

    private static Object functionArguments(Map<String, Object> toolCall) {
        if (toolCall.get("function") instanceof Map<?, ?> function) {
            return function.get("arguments");
        }
        return Map.of();
    }

    private static String formatArguments(Map<String, Object> arguments) {
        if (arguments.isEmpty()) {
            return "（无参数）";
        }
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(entry.getKey()).append(" = ").append(entry.getValue());
        }
        return text.toString();
    }

    public record ChatResult(String reply, List<AirConditioner> devices) {
    }
}
