package com.smartprint.health;

import com.smartprint.config.MinioProperties;
import com.smartprint.storage.ObjectStorageService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("storage")
public class StorageHealthIndicator implements HealthIndicator {

    private final ObjectStorageService storage;
    private final MinioProperties properties;

    public StorageHealthIndicator(ObjectStorageService storage, MinioProperties properties) {
        this.storage = storage;
        this.properties = properties;
    }

    @Override
    public Health health() {
        boolean healthy = storage.isHealthy();
        return (healthy ? Health.up() : Health.down())
            .withDetail("bucket", properties.bucket())
            .withDetail("endpoint", properties.endpoint())
            .build();
    }
}
