package com.booksmith.media.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.media.api.PdfStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** PDFs em {@code documents/<dono>/<id>.pdf}, no bucket das imagens. */
@Slf4j
@Service
@RequiredArgsConstructor
class S3PdfStorage implements PdfStorage {

    /** Uma capa em PDF raramente passa de alguns MB; o limite segue o do envio de arquivos (15 MB). */
    static final int MAX_BYTES = 15 * 1024 * 1024;

    private static final byte[] SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final S3Client s3Client;
    private final MediaProperties properties;

    @Override
    public String store(UUID ownerId, byte[] pdf) {
        if (pdf == null || pdf.length < SIGNATURE.length
                || !Arrays.equals(Arrays.copyOf(pdf, SIGNATURE.length), SIGNATURE)) {
            throw ApiException.unprocessable("pdf_invalid", "O arquivo não é um PDF.");
        }
        if (pdf.length > MAX_BYTES) {
            throw ApiException.unprocessable("pdf_too_large", "O PDF passa de 15 MB.");
        }
        String key = "documents/%s/%s.pdf".formatted(ownerId, UUID.randomUUID());
        s3Client.putObject(PutObjectRequest.builder()
                        .bucket(properties.bucket())
                        .key(key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromBytes(pdf));
        return properties.publicBaseUrl() + "/" + key;
    }

    @Override
    public Optional<byte[]> read(String url) {
        String key = key(url);
        if (key == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(properties.bucket()).key(key).build()).asByteArray());
        } catch (RuntimeException e) {
            log.warn("Não foi possível ler o PDF {}", url, e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String url) {
        String key = key(url);
        if (key == null) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
        } catch (RuntimeException e) {
            log.error("Erro ao remover o PDF {}", url, e);
        }
    }

    /** Só PDFs deste armazenamento (pasta documents/). */
    private String key(String url) {
        String prefix = properties.publicBaseUrl() + "/documents/";
        return url == null || !url.startsWith(prefix) ? null : url.substring(properties.publicBaseUrl().length() + 1);
    }
}
