package com.booksmith.app.service.impl;

import com.booksmith.app.service.LocalStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Base64;


@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStorageServiceImpl implements LocalStorageService {

    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucket;

    @Value("${aws.s3.endpoint}")
    private String endpoint;

    @Override
    public String upload(
            String path,
            String authorId,
            String base64
    ) {

        try {

            String cleanBase64 =
                    sanitize(base64);

            byte[] imageBytes =
                    Base64.getDecoder()
                            .decode(cleanBase64);

            String extension =
                    extractExtension(base64);

            String key =
                    path
                            .formatted(
                                    authorId,
                                    extension
                            );

            String contentType =
                    extractContentType(base64);

            PutObjectRequest request =
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build();

            s3Client.putObject(
                    request,
                    RequestBody.fromBytes(
                            imageBytes
                    )
            );

            return "%s/%s/%s"
                    .formatted(
                            endpoint,
                            bucket,
                            key
                    );

        } catch (Exception e) {

            throw new RuntimeException(
                    "Invalid Image",
                    e
            );
        }
    }

    @Override
    public void delete(
            String filePath
    ) {

        try {

            if (filePath == null
                    || filePath.isBlank()) {
                return;
            }

            String key =
                    extractKey(filePath);

            DeleteObjectRequest request =
                    DeleteObjectRequest
                            .builder()
                            .bucket(bucket)
                            .key(key)
                            .build();

            s3Client.deleteObject(
                    request
            );

        } catch (Exception e) {

            log.error(
                    "Error deleting file {}",
                    filePath,
                    e
            );
        }
    }

    private String sanitize(
            String base64
    ) {

        if (base64 == null
                || base64.isBlank()) {
            throw new RuntimeException(
                    "Image not sendend"
            );
        }

        if (base64.contains(",")) {
            return base64.split(",")[1];
        }

        return base64;
    }

    private String extractExtension(
            String base64
    ) {

        if (base64.contains("image/png")) {
            return "png";
        }

        if (base64.contains("image/jpeg")
                || base64.contains("image/jpg")) {
            return "jpg";
        }

        if (base64.contains("image/webp")) {
            return "webp";
        }

        throw new RuntimeException(
                "Image format not supported"
        );
    }

    private String extractContentType(
            String base64
    ) {

        if (base64.contains("image/png")) {
            return "image/png";
        }

        if (base64.contains("image/jpeg")
                || base64.contains("image/jpg")) {
            return "image/jpeg";
        }

        if (base64.contains("image/webp")) {
            return "image/webp";
        }

        throw new RuntimeException(
                "Format not supported"
        );
    }

    private String extractKey(
            String url
    ) {

        return url.replace(
                endpoint + "/" + bucket + "/",
                ""
        );
    }
}