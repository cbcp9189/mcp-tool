package dev.local.mcp.air;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class AirConditionerTools {

    private final AirConditionerStore store;

    public AirConditionerTools(AirConditionerStore store) {
        this.store = store;
    }

    @McpTool(
            name = "find_air_conditioners",
            description = "按名称、位置或设备 id 查找空调。关键字为空时返回全部空调。返回每台的 id、名称、位置、是否在线、当前温度和设定温度。调节温度前必须先调用本工具。结果多于 1 台时不要猜测，先让用户选择。",
            annotations = @McpTool.McpAnnotations(
                    title = "查找空调",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public String findAirConditioners(
            @McpToolParam(description = "名称、位置或设备 id。用户没指定哪一台时传空字符串") String keyword) {
        List<AirConditioner> found = store.find(keyword);
        if (found.isEmpty()) {
            return "找到 0 台空调。";
        }
        StringBuilder text = new StringBuilder();
        text.append("找到 ").append(found.size()).append(" 台空调。");
        if (found.size() > 1) {
            text.append("多于一台，不要调用 set_temperature，先问用户要控制哪一台。");
        }
        for (AirConditioner device : found) {
            text.append("\nid=").append(device.id())
                    .append("，名称=").append(device.name())
                    .append("，位置=").append(device.location())
                    .append("，在线=").append(device.online())
                    .append("，当前温度=").append(device.currentCelsius())
                    .append("，设定温度=").append(device.setpointCelsius());
        }
        return text.toString();
    }

    @McpTool(
            name = "set_temperature",
            description = "把一台在线空调的设定温度改为摄氏度整数。调用前必须先用 find_air_conditioners 拿到设备 id 和当前设定温度。相对调节（降低一度、调高一度）由你先算出目标整数再传入。合法范围是 16 到 30。设备不存在、离线或超出范围时调用失败。",
            annotations = @McpTool.McpAnnotations(
                    title = "设置温度",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false))
    public String setTemperature(
            @McpToolParam(description = "find_air_conditioners 返回的设备 id，例如 ac-a") String deviceId,
            @McpToolParam(description = "目标设定温度，16 到 30 的整数") int temperatureCelsius) {
        AirConditioner before = requireOne(deviceId);
        AirConditioner updated = store.setTemperature(deviceId, temperatureCelsius);
        if (before.setpointCelsius() == updated.setpointCelsius()) {
            return "空调" + updated.name() + "（" + updated.id() + "）的设定温度保持 " + updated.setpointCelsius() + "℃。";
        }
        return "已将" + updated.name() + "（" + updated.id() + "）的设定温度从 "
                + before.setpointCelsius() + "℃ 调整为 " + updated.setpointCelsius() + "℃。";
    }

    private AirConditioner requireOne(String deviceId) {
        List<AirConditioner> found = store.find(deviceId);
        for (AirConditioner device : found) {
            if (device.id().equalsIgnoreCase(deviceId == null ? "" : deviceId.trim())) {
                return device;
            }
        }
        throw new IllegalArgumentException("设备不存在");
    }
}
