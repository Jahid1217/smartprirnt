package com.smartprint.storage;

import com.smartprint.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over MinIO. The bucket is always private; files are only reachable
 * through backend-authorized short-lived presigned URLs or backend streaming.
 */
@Service
public class ObjectStorageService {
    private static final Logger log = LoggerFactory.getLogger(ObjectStorageService.class);

    private final MinioClient client;
    private final MinioProperties properties;

    public ObjectStorageService(MinioClient client, MinioProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            boolean exists = client.bucketExists(
                BucketExistsArgs.builder().bucket(properties.bucket()).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(properties.bucket()).build());
                log.info("Created private object storage bucket '{}'", properties.bucket());
            }
        } catch (Exception ex) {
            // Do not fail startup: the readiness health indicator reports storage health.
            log.error("Object storage bucket initialization failed: {}", ex.getMessage());
        }
    }

    public void put(String key, InputStream stream, long size, String contentType) {
        try {
            client.putObject(PutObjectArgs.builder()
                .bucket(properties.bucket())
                .object(key)
                .stream(stream, size, -1)
                .contentType(contentType)
                .build());
        } catch (Exception ex) {
            throw new StorageException("Failed to store object " + key, ex);
        }
    }

    public InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder()
                .bucket(properties.bucket())
                .object(key)
                .build());
        } catch (Exception ex) {
            throw new StorageException("Failed to read object " + key, ex);
        }
    }

    public String presignedDownloadUrl(String key) {
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(properties.bucket())
                .object(key)
                .expiry(properties.presignedUrlMinutes(), TimeUnit.MINUTES)
                .build());
        } catch (Exception ex) {
            throw new StorageException("Failed to sign download URL for " + key, ex);
        }
    }

    public void delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                .bucket(properties.bucket())
                .object(key)
                .build());
        } catch (Exception ex) {
            throw new StorageException("Failed to delete object " + key, ex);
        }
    }

    public boolean isHealthy() {
        try {
            return client.bucketExists(BucketExistsArgs.builder().bucket(properties.bucket()).build());
        } catch (Exception ex) {
            return false;
        }
    }
}
