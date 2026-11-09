package com.booksmith.media.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * @param bucket        bucket S3 das imagens
 * @param region        região AWS (padrão sa-east-1, ADR-010)
 * @param endpoint      endpoint alternativo (MinIO local); vazio = AWS
 * @param accessKey     usado só com endpoint alternativo; na AWS vale a cadeia padrão de credenciais (IAM role)
 * @param secretKey     idem
 * @param publicBaseUrl base pública das URLs devolvidas (CloudFront em produção)
 * @param maxImageSize  limite por imagem
 */
@ConfigurationProperties("booksmith.media")
record MediaProperties(
        String bucket,
        String region,
        String endpoint,
        String accessKey,
        String secretKey,
        String publicBaseUrl,
        DataSize maxImageSize
) {
    boolean usesCustomEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
