package dev.local.mcp.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.local.mcp.agent.AirConditionerAgent;
import dev.local.mcp.agent.HistoryMessage;
import dev.local.mcp.agent.LlmProperties;
import dev.local.mcp.air.AirConditioner;
import dev.local.mcp.air.AirConditionerStore;
import tools.jackson.databind.ObjectMapper;

@RestController
public class DemoController {

    private final AirConditionerStore store;
    private final AirConditionerAgent agent;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;

    public DemoController(
            AirConditionerStore store,
            AirConditionerAgent agent,
            LlmProperties llmProperties,
            ObjectMapper objectMapper) {
        this.store = store;
        this.agent = agent;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/api/devices")
    public List<AirConditioner> devices() {
        return store.all();
    }

    @PostMapping("/api/devices/reset")
    public List<AirConditioner> resetDevices() {
        return store.reset();
    }

    @GetMapping("/api/llm")
    public Map<String, Object> llm() {
        return Map.of(
                "configured", llmProperties.configured(),
                "baseUrl", llmProperties.getBaseUrl(),
                "model", llmProperties.getModel());
    }

    @PostMapping(path = "/api/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(180_000L);
        Thread thread = new Thread(() -> {
            try {
                AirConditionerAgent.ChatResult result = agent.run(
                        request == null ? List.of() : request.messages(),
                        step -> send(emitter, "step", step));
                send(emitter, "done", result);
                emitter.complete();
            } catch (RuntimeException exception) {
                send(emitter, "error", Map.of("message", exception.getMessage() == null ? "处理失败" : exception.getMessage()));
                emitter.complete();
            }
        });
        thread.setName("ac-chat");
        thread.start();
        return emitter;
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(data)));
        } catch (IOException exception) {
            emitter.completeWithError(exception);
        } catch (IllegalStateException ignored) {
            // 客户端已经断开，或事件已经发送完毕。
        }
    }

    public record ChatRequest(List<HistoryMessage> messages) {
    }
}
