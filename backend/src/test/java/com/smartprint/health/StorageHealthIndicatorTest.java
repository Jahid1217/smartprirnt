package com.smartprint.health;

import com.smartprint.config.MinioProperties;
import com.smartprint.storage.ObjectStorageService;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;

class StorageHealthIndicatorTest {

    private final MinioProperties properties =
        new MinioProperties("http://localhost:9000", "key", "secret", "docs", false, 15);

    private final MinioClient client = MinioClient.builder()
        .endpoint(properties.endpoint())
        .credentials(properties.accessKey(), properties.secretKey())
        .build();

    @Test
    void reportsUpWhenBucketIsReachable() {
        var health = new StorageHealthIndicator(stubStorage(true), properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "docs");
    }

    @Test
    void reportsDownWhenBucketIsUnreachable() {
        var health = new StorageHealthIndicator(stubStorage(false), properties).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    private ObjectStorageService stubStorage(boolean healthy) {
        return new ObjectStorageService(client, properties) {
            @Override
            public boolean isHealthy() {
                return healthy;
            }
        };
    }
}
