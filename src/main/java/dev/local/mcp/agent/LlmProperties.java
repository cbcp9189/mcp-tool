package dev.local.mcp.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    private String baseUrl = "https://api.deepseek.com/chat/completions";
    private String apiKey = "";
    private String model = "deepseek-flash";

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank() && baseUrl != null && !baseUrl.isBlank() && model != null && !model.isBlank();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
