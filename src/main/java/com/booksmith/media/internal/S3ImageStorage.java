package com.booksmith.media.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageStorage;
import com.booksmith.media.api.StoredImage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
class S3ImageStorage implements ImageStorage {

    private final S3Client s3Client;
    private final MediaProperties properties;

    @Override
    public StoredImage store(ImageCategory category, UUID ownerId, String dataUrl) {
        byte[] bytes = decode(dataUrl);

        if (bytes.length > properties.maxImageSize().toBytes()) {
            throw ApiException.unprocessable("image_too_large",
                    "A imagem passa do limite de %d MB.".formatted(properties.maxImageSize().toMegabytes()));
        }

        ImageFormat format = ImageFormat.detect(bytes).orElseThrow(() ->
                ApiException.unprocessable("image_format_not_supported", "Envie uma imagem PNG, JPEG ou WebP."));

        // Chave única por upload: evita cache desatualizado na CDN ao trocar a imagem
        String key = "%s/%s/%s.%s".formatted(category.keyPrefix(), ownerId, UUID.randomUUID(), format.extension);

        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType(format.contentType)
                        .build(),
                RequestBody.fromBytes(bytes));

        return new StoredImage(key, publicUrl(key), format.contentType, bytes.length);
    }

    @Override
    public Optional<ImageData> read(String url) {
        String prefix = properties.publicBaseUrl() + "/";
        if (url == null || !url.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            byte[] bytes = s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(url.substring(prefix.length()))
                    .build()).asByteArray();
            return ImageFormat.detect(bytes).map(format -> new ImageData(bytes, format.contentType, format.extension));
        } catch (RuntimeException e) {
            log.warn("Não foi possível ler a imagem {}", url, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String prefix = properties.publicBaseUrl() + "/";
        if (!url.startsWith(prefix)) {
            log.warn("Imagem fora do bucket configurado não será removida: {}", url);
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(url.substring(prefix.length()))
                    .build());
        } catch (RuntimeException e) {
            log.error("Erro ao remover imagem {}", url, e);
        }
    }

    private String publicUrl(String key) {
        return properties.publicBaseUrl() + "/" + key;
    }

    private static byte[] decode(String dataUrl) {
        if (dataUrl == null || dataUrl.isBlank()) {
            throw ApiException.badRequest("image_missing", "Nenhuma imagem enviada.");
        }
        String base64 = dataUrl.contains(",") ? dataUrl.substring(dataUrl.indexOf(',') + 1) : dataUrl;
        try {
            return Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.unprocessable("image_invalid", "Não foi possível ler a imagem enviada.");
        }
    }
}
