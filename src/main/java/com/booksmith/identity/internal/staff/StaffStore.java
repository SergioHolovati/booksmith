package com.booksmith.identity.internal.staff;

import com.booksmith.identity.api.StaffRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** As tabelas da equipe (V43): membros, convites, códigos de recuperação e o registro de ações. */
@Component
class StaffStore {

    private static final String MEMBER_COLUMNS = """
            id, email, name, role, password_hash, totp_secret, totp_last_step, active, created_at, activated_at,
            last_login_at""";

    private final JdbcClient jdbc;
    /** O registro grava numa transação própria: uma ação recusada (que desfaz a transação dela) continua registrada. */
    private final TransactionTemplate own;

    StaffStore(JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    long count() {
        return jdbc.sql("select count(*) from identity.staff_members").query(Long.class).single();
    }

    Optional<StaffMember> byEmail(String email) {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from identity.staff_members where lower(email) = :email")
                .param("email", email.strip().toLowerCase(Locale.ROOT))
                .query(StaffStore::member).optional();
    }

    Optional<StaffMember> byId(UUID id) {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from identity.staff_members where id = :id")
                .param("id", id).query(StaffStore::member).optional();
    }

    List<StaffMember> all() {
        return jdbc.sql("select " + MEMBER_COLUMNS + " from identity.staff_members order by active desc, name")
                .query(StaffStore::member).list();
    }

    long activeAdmins() {
        return jdbc.sql("""
                        select count(*) from identity.staff_members
                        where role = 'ADMIN' and active and password_hash is not null""")
                .query(Long.class).single();
    }

    void insert(UUID id, String email, String name, StaffRole role, UUID createdBy, Instant at) {
        jdbc.sql("""
                        insert into identity.staff_members (id, email, name, role, created_by, created_at)
                        values (:id, :email, :name, :role, :by, :at)""")
                .param("id", id).param("email", email.strip()).param("name", name.strip()).param("role", role.name())
                .param("by", createdBy).param("at", Timestamp.from(at))
                .update();
    }

    void role(UUID id, StaffRole role) {
        jdbc.sql("update identity.staff_members set role = :role where id = :id")
                .param("role", role.name()).param("id", id).update();
    }

    void active(UUID id, boolean active) {
        jdbc.sql("update identity.staff_members set active = :active where id = :id")
                .param("active", active).param("id", id).update();
    }

    /** Convite aceito: senha e autenticador novos (e os códigos de recuperação antigos deixam de valer). */
    void credentials(UUID id, String passwordHash, String sealedSecret, Instant at) {
        jdbc.sql("""
                        update identity.staff_members
                        set password_hash = :hash, totp_secret = :secret, totp_last_step = null, active = true,
                            activated_at = coalesce(activated_at, :at)
                        where id = :id""")
                .param("hash", passwordHash).param("secret", sealedSecret).param("at", Timestamp.from(at))
                .param("id", id).update();
        jdbc.sql("delete from identity.staff_recovery_codes where member_id = :id").param("id", id).update();
    }

    /** Reconvite: a senha e o autenticador antigos deixam de valer na hora. */
    void clearCredentials(UUID id) {
        jdbc.sql("update identity.staff_members set password_hash = null, totp_secret = null where id = :id")
                .param("id", id).update();
    }

    /** Grava o passo do código usado; falso se ele (ou um mais novo) já tinha sido usado. */
    boolean useStep(UUID id, long step) {
        return jdbc.sql("""
                        update identity.staff_members set totp_last_step = :step
                        where id = :id and (totp_last_step is null or totp_last_step < :step)""")
                .param("step", step).param("id", id).update() == 1;
    }

    void loggedIn(UUID id, Instant at) {
        jdbc.sql("update identity.staff_members set last_login_at = :at where id = :id")
                .param("at", Timestamp.from(at)).param("id", id).update();
    }

    // ---- Convites ----

    void invite(String tokenHash, UUID memberId, Instant expiresAt) {
        jdbc.sql("update identity.staff_invites set used_at = now() where member_id = :id and used_at is null")
                .param("id", memberId).update();
        jdbc.sql("insert into identity.staff_invites (token_hash, member_id, expires_at) values (:hash, :member, :exp)")
                .param("hash", tokenHash).param("member", memberId).param("exp", Timestamp.from(expiresAt))
                .update();
    }

    /** O convite ainda válido do token: o membro e o segredo do autenticador já gerado (selado), se houver. */
    Optional<Invite> openInvite(String tokenHash, Instant now) {
        return jdbc.sql("""
                        select member_id, totp_secret from identity.staff_invites
                        where token_hash = :hash and used_at is null and expires_at > :now""")
                .param("hash", tokenHash).param("now", Timestamp.from(now))
                .query((rs, row) -> new Invite(rs.getObject("member_id", UUID.class), rs.getString("totp_secret")))
                .optional();
    }

    void inviteSecret(String tokenHash, String sealedSecret) {
        jdbc.sql("update identity.staff_invites set totp_secret = :secret where token_hash = :hash and totp_secret is null")
                .param("secret", sealedSecret).param("hash", tokenHash).update();
    }

    boolean useInvite(String tokenHash, Instant at) {
        return jdbc.sql("update identity.staff_invites set used_at = :at where token_hash = :hash and used_at is null")
                .param("at", Timestamp.from(at)).param("hash", tokenHash).update() == 1;
    }

    boolean hasOpenInvite(UUID memberId, Instant now) {
        return jdbc.sql("""
                        select count(*) from identity.staff_invites
                        where member_id = :id and used_at is null and expires_at > :now""")
                .param("id", memberId).param("now", Timestamp.from(now)).query(Long.class).single() > 0;
    }

    // ---- Códigos de recuperação ----

    void recoveryCodes(UUID memberId, List<String> codeHashes) {
        for (String hash : codeHashes) {
            jdbc.sql("insert into identity.staff_recovery_codes (id, member_id, code_hash) values (:id, :member, :hash)")
                    .param("id", UUID.randomUUID()).param("member", memberId).param("hash", hash).update();
        }
    }

    boolean useRecoveryCode(UUID memberId, String codeHash, Instant at) {
        return jdbc.sql("""
                        update identity.staff_recovery_codes set used_at = :at
                        where member_id = :member and code_hash = :hash and used_at is null""")
                .param("at", Timestamp.from(at)).param("member", memberId).param("hash", codeHash).update() == 1;
    }

    // ---- Registro de ações ----

    void audit(UUID staffId, String staffEmail, String action, String target, String detail, Instant at) {
        own.executeWithoutResult(tx -> jdbc.sql("""
                        insert into identity.staff_audit (id, staff_id, staff_email, action, target, detail, created_at)
                        values (:id, :staff, :email, :action, :target, :detail, :at)""")
                .param("id", UUID.randomUUID()).param("staff", staffId).param("email", staffEmail)
                .param("action", action).param("target", target).param("detail", detail)
                .param("at", Timestamp.from(at)).update());
    }

    List<AuditEntry> audit(int limit) {
        return jdbc.sql("""
                        select staff_email, action, target, detail, created_at from identity.staff_audit
                        order by created_at desc limit :limit""")
                .param("limit", Math.clamp(limit, 1, 500))
                .query((rs, row) -> new AuditEntry(rs.getString("staff_email"), rs.getString("action"),
                        rs.getString("target"), rs.getString("detail"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    record Invite(UUID memberId, String sealedSecret) {
    }

    /** Mapeia uma linha (assinatura do RowMapper do JdbcClient; o número da linha não é usado). */
    static StaffMember member(ResultSet rs, int row) throws SQLException {
        Long step = rs.getObject("totp_last_step", Long.class);
        return new StaffMember(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("name"),
                StaffRole.valueOf(rs.getString("role")), rs.getString("password_hash"), rs.getString("totp_secret"),
                step, rs.getBoolean("active"), instant(rs, "created_at"),
                instant(rs, "activated_at"), instant(rs, "last_login_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
