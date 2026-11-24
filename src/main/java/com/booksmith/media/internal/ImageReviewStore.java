package com.booksmith.media.internal;

import com.booksmith.media.api.ImageCategory;
import com.booksmith.media.api.ImageReviews;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A fila de revisão da moderação ({@code media.image_reviews}). O registro é gravado numa transação própria: a
 * recusa da imagem (que desfaz a transação de quem enviou) não apaga o registro de que ela foi tentada.
 */
@Slf4j
@Component
class ImageReviewStore implements ImageReviews {

    private final JdbcClient jdbc;
    private final TransactionTemplate own;
    private final S3Client s3Client;
    private final MediaProperties properties;
    private final Clock clock;

    ImageReviewStore(JdbcClient jdbc, PlatformTransactionManager transactions, S3Client s3Client,
                     MediaProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.s3Client = s3Client;
        this.properties = properties;
        this.clock = clock;
    }

    /** Imagem guardada que o admin precisa olhar. */
    void pending(UUID ownerId, ImageCategory category, String key, String url, double score, String reason) {
        insert(ownerId, category, key, url, score, "pending", reason);
    }

    /** Imagem recusada na hora (não foi guardada). */
    void blocked(UUID ownerId, ImageCategory category, double score, String reason) {
        insert(ownerId, category, null, null, score, "blocked", reason);
    }

    private void insert(UUID ownerId, ImageCategory category, String key, String url, double score, String status,
                        String reason) {
        own.executeWithoutResult(tx -> jdbc.sql("""
                        insert into media.image_reviews
                            (id, owner_id, category, image_key, image_url, score, status, reason, created_at)
                        values (:id, :owner, :category, :key, :url, :score, :status, :reason, :at)
                        """)
                .param("id", UUID.randomUUID())
                .param("owner", ownerId)
                .param("category", category.name())
                .param("key", key)
                .param("url", url)
                .param("score", score)
                .param("status", status)
                .param("reason", reason)
                .param("at", Timestamp.from(Instant.now(clock)))
                .update());
    }

    @Override
    public List<Review> list(int limit) {
        return jdbc.sql("""
                        select id, owner_id, category, image_url, score, status, reason, created_at, decided_at
                        from media.image_reviews
                        order by (status = 'pending') desc, created_at desc
                        limit :limit
                        """)
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, row) -> new Review(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getString("category"), rs.getString("image_url"), rs.getDouble("score"),
                        rs.getString("status"), rs.getString("reason"), rs.getTimestamp("created_at").toInstant(),
                        Optional.ofNullable(rs.getTimestamp("decided_at")).map(Timestamp::toInstant).orElse(null)))
                .list();
    }

    @Override
    public boolean keep(UUID id, UUID adminId) {
        return decide(id, adminId, "kept");
    }

    @Override
    public boolean remove(UUID id, UUID adminId) {
        Optional<String> key = jdbc.sql("select image_key from media.image_reviews where id = :id and status = 'pending'")
                .param("id", id)
                .query(String.class)
                .optional();
        if (key.isEmpty()) {
            return false;
        }
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key.get()).build());
        log.warn("Moderação: imagem {} removida pelo admin {}", key.get(), adminId);
        return decide(id, adminId, "removed");
    }

    private boolean decide(UUID id, UUID adminId, String status) {
        return jdbc.sql("""
                        update media.image_reviews set status = :status, decided_at = :at, decided_by = :admin
                        where id = :id and status = 'pending'
                        """)
                .param("status", status)
                .param("at", Timestamp.from(Instant.now(clock)))
                .param("admin", adminId)
                .param("id", id)
                .update() == 1;
    }
}
