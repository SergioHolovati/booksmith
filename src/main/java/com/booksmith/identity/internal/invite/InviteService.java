package com.booksmith.identity.internal.invite;

import com.booksmith.common.error.ApiException;
import com.booksmith.identity.internal.user.User;
import com.booksmith.identity.internal.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Beta fechado: criar conta (por e-mail ou pelo Google) gasta um uso de um código de convite. Os códigos são
 * criados por um administrador ({@code identity.users.admin}) na tela "Convites do beta".
 */
@Slf4j
@Service
public class InviteService {

    static final int MAX_BATCH = 50;
    static final int MAX_USES = 1000;
    /** Sem letras e números que se confundem (0/O, 1/I/L). */
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeRepository invites;
    private final UserRepository users;
    private final InviteProperties properties;
    private final Clock clock;

    InviteService(InviteCodeRepository invites, UserRepository users, InviteProperties properties, Clock clock) {
        this.invites = invites;
        this.users = users;
        this.properties = properties;
        this.clock = clock;
    }

    /** Um convite como a tela mostra. */
    public record InviteView(UUID id, String code, int maxUses, int usedCount, String note, Instant expiresAt,
                             Instant createdAt, boolean active) {
    }

    public boolean required() {
        return properties.required();
    }

    /**
     * Gasta um uso do código e devolve o convite (para guardar na conta). Com o beta aberto ({@code required=false})
     * e sem código, não faz nada.
     *
     * @throws ApiException 422 {@code invite_invalid} (não existe, venceu ou acabou)
     */
    @Transactional
    public UUID redeem(String code) {
        String clean = normalize(code);
        if (clean.isEmpty() && !properties.required()) {
            return null;
        }
        if (clean.isEmpty() || invites.use(clean, Instant.now(clock)) != 1) {
            throw ApiException.unprocessable("invite_invalid",
                    "Código de convite inválido, vencido ou já usado. O Booksmith está em beta fechado: peça um convite.");
        }
        return invites.findByCode(clean).map(InviteCode::getId).orElse(null);
    }

    /** Só confere (sem gastar): a tela de cadastro avisa antes de mandar para o Google. */
    @Transactional(readOnly = true)
    public boolean valid(String code) {
        return invites.findByCode(normalize(code)).filter(this::active).isPresent();
    }

    // ===== Administração =====

    @Transactional
    public List<InviteView> create(UUID adminId, int count, int maxUses, String note, Integer expiresInDays) {
        requireAdmin(adminId);
        if (count < 1 || count > MAX_BATCH || maxUses < 1 || maxUses > MAX_USES
                || expiresInDays != null && (expiresInDays < 1 || expiresInDays > 365)) {
            throw ApiException.unprocessable("invite_request_invalid",
                    "De 1 a %d códigos, de 1 a %d usos cada, validade de 1 a 365 dias.".formatted(MAX_BATCH, MAX_USES));
        }
        Instant expires = expiresInDays == null ? null : Instant.now(clock).plus(Duration.ofDays(expiresInDays));
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        List<InviteView> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            InviteCode invite = invites.save(InviteCode.builder().code(newCode()).maxUses(maxUses).note(cleanNote)
                    .expiresAt(expires).createdBy(adminId).build());
            created.add(view(invite));
        }
        log.info("{} convites criados por userId: {}", count, adminId);
        return created;
    }

    @Transactional(readOnly = true)
    public List<InviteView> list(UUID adminId) {
        requireAdmin(adminId);
        return invites.findAllByOrderByCreatedAtDesc().stream().map(this::view).toList();
    }

    /** Cancela: o código deixa de valer na hora (quem já criou conta com ele continua). */
    @Transactional
    public void revoke(UUID adminId, UUID inviteId) {
        requireAdmin(adminId);
        InviteCode invite = invites.findById(inviteId)
                .orElseThrow(() -> ApiException.notFound("invite_not_found", "Convite não encontrado."));
        invite.setExpiresAt(Instant.now(clock));
    }

    @Transactional(readOnly = true)
    public boolean isAdmin(UUID userId) {
        return users.findById(userId).map(User::isAdmin).orElse(false);
    }

    private void requireAdmin(UUID userId) {
        if (!isAdmin(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "admin_only", "Só a equipe do Booksmith cria convites.", Map.of());
        }
    }

    private boolean active(InviteCode invite) {
        return invite.getUsedCount() < invite.getMaxUses()
                && (invite.getExpiresAt() == null || invite.getExpiresAt().isAfter(Instant.now(clock)));
    }

    private InviteView view(InviteCode invite) {
        return new InviteView(invite.getId(), invite.getCode(), invite.getMaxUses(), invite.getUsedCount(), invite.getNote(),
                invite.getExpiresAt(), invite.getCreatedAt(), active(invite));
    }

    private String newCode() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder code = new StringBuilder("BETA");
            for (int i = 0; i < 8; i++) {
                if (i % 4 == 0) {
                    code.append('-');
                }
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            if (!invites.existsByCode(code.toString())) {
                return code.toString();
            }
        }
        throw new IllegalStateException("Não foi possível gerar um código único");
    }

    /** "beta-7kq2 m9xa" → "BETA-7KQ2-M9XA". */
    static String normalize(String code) {
        if (code == null) {
            return "";
        }
        String compact = code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (compact.startsWith("BETA") && compact.length() == 12) {
            return "BETA-" + compact.substring(4, 8) + "-" + compact.substring(8);
        }
        return code.strip().toUpperCase(Locale.ROOT);
    }
}
