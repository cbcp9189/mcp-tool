package dev.local.mcp.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class LlmClient {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public LlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public LlmTurn complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!properties.configured()) {
            throw new IllegalStateException("还没有配置大模型");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("temperature", 0.2);
        body.put("stream", false);
        body.put("messages", messages);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        try {
            String payload = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder(completionsUri())
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + properties.getApiKey().trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("大模型接口返回 " + response.statusCode() + "：" + truncate(response.body()));
            }
            return parseTurn(objectMapper.readValue(response.body(), MAP_TYPE));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("调用大模型失败：" + exception.getMessage(), exception);
        }
    }

    public Map<String, Object> parseArguments(Object raw) {
        try {
            Map<String, Object> parsed;
            if (raw instanceof Map<?, ?> map) {
                parsed = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    parsed.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            } else if (raw instanceof String text && !text.isBlank()) {
                parsed = objectMapper.readValue(text, MAP_TYPE);
            } else {
                parsed = Map.of();
            }
            return normalize(parsed);
        } catch (Exception exception) {
            throw new IllegalStateException("工具参数不是合法 JSON：" + raw);
        }
    }

    private LlmTurn parseTurn(Map<String, Object> body) {
        Object choices = body.get("choices");
        if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> choice)) {
            throw new IllegalStateException("大模型响应里没有 choices");
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            throw new IllegalStateException("大模型响应里没有 message");
        }
        String text = textOf(message.get("content")).trim();
        List<Map<String, Object>> toolCalls = new ArrayList<>();
        if (message.get("tool_calls") instanceof List<?> calls) {
            for (Object call : calls) {
                if (call instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        copy.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                    toolCalls.add(copy);
                }
            }
        }
        return new LlmTurn(text, List.copyOf(toolCalls));
    }

    private static String textOf(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof String fragment) {
                    text.append(fragment);
                } else if (part instanceof Map<?, ?> map && map.get("text") instanceof String fragment) {
                    text.append(fragment);
                }
            }
            return text.toString();
        }
        return String.valueOf(content);
    }

    private static Map<String, Object> normalize(Map<String, Object> source) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            normalized.put(entry.getKey(), normalizeValue(entry.getValue()));
        }
        return normalized;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof Number number) {
            double asDouble = number.doubleValue();
            if (!Double.isNaN(asDouble) && asDouble % 1 == 0 && asDouble >= Integer.MIN_VALUE && asDouble <= Integer.MAX_VALUE) {
                return number.intValue();
            }
        }
        return value;
    }

    private URI completionsUri() {
        String root = properties.getBaseUrl().trim();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        if (root.endsWith("/chat/completions")) {
            return URI.create(root);
        }
        return URI.create(root + "/chat/completions");
    }

    private static String truncate(String text) {
        if (text == null || text.isBlank()) {
            return "无响应正文";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 400 ? compact : compact.substring(0, 400);
    }

    public record LlmTurn(String text, List<Map<String, Object>> toolCalls) {
    }
}
