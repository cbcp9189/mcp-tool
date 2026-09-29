package dev.local.mcp.air;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class AirConditionerStore {

    private final List<Device> devices = new ArrayList<>();

    public AirConditionerStore() {
        reset();
    }

    public synchronized List<AirConditioner> all() {
        return snapshots();
    }

    public synchronized List<AirConditioner> reset() {
        devices.clear();
        devices.add(new Device("ac-a", "空调A", "客厅", true, 26, 26));
        devices.add(new Device("ac-b", "空调B", "卧室", true, 24, 24));
        devices.add(new Device("ac-c", "空调C", "书房", false, 28, 28));
        return snapshots();
    }

    public synchronized List<AirConditioner> find(String keyword) {
        String query = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return snapshots();
        }
        List<AirConditioner> matched = new ArrayList<>();
        for (Device device : devices) {
            if (device.id.toLowerCase(Locale.ROOT).contains(query)
                    || device.name.toLowerCase(Locale.ROOT).contains(query)
                    || device.location.toLowerCase(Locale.ROOT).contains(query)) {
                matched.add(device.snapshot());
            }
        }
        return matched;
    }

    public synchronized AirConditioner setTemperature(String deviceId, int temperatureCelsius) {
        Device device = findById(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("设备不存在");
        }
        if (!device.online) {
            throw new IllegalArgumentException("设备离线，不能调节温度");
        }
        if (temperatureCelsius < 16 || temperatureCelsius > 30) {
            throw new IllegalArgumentException("温度超出 16–30");
        }
        device.setpointCelsius = temperatureCelsius;
        return device.snapshot();
    }

    private Device findById(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return null;
        }
        for (Device device : devices) {
            if (device.id.equalsIgnoreCase(deviceId.trim())) {
                return device;
            }
        }
        return null;
    }

    private List<AirConditioner> snapshots() {
        List<AirConditioner> copies = new ArrayList<>();
        for (Device device : devices) {
            copies.add(device.snapshot());
        }
        return copies;
    }

    private static final class Device {
        private final String id;
        private final String name;
        private final String location;
        private final boolean online;
        private final int currentCelsius;
        private int setpointCelsius;

        private Device(String id, String name, String location, boolean online, int currentCelsius, int setpointCelsius) {
            this.id = id;
            this.name = name;
            this.location = location;
            this.online = online;
            this.currentCelsius = currentCelsius;
            this.setpointCelsius = setpointCelsius;
        }

        private AirConditioner snapshot() {
            return new AirConditioner(id, name, location, online, currentCelsius, setpointCelsius);
        }
    }
}
