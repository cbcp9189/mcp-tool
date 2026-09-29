package dev.local.mcp.air;

public record AirConditioner(
        String id,
        String name,
        String location,
        boolean online,
        int currentCelsius,
        int setpointCelsius) {
}
