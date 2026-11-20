package com.smartprint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "smartprint.storage")
public record MinioProperties(
    String endpoint,
    String accessKey,
    String secretKey,
    String bucket,
    boolean secure,
    int presignedUrlMinutes
) {
}
