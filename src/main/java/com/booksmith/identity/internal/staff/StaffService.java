package com.booksmith.identity.internal.staff;

import com.booksmith.common.alerts.SecurityIncident;
import com.booksmith.common.error.ApiException;
import com.booksmith.identity.api.AuthenticatedStaff;
import com.booksmith.identity.api.StaffRole;
import com.booksmith.identity.internal.user.TokenHasher;
import com.booksmith.notification.api.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A equipe, do lado do administrador: convidar (o convidado cria a senha e o autenticador pelo link), mudar o papel,
 * desativar, reativar e reconvidar (perdeu o celular: senha e autenticador novos). Nunca deixa a equipe sem um
 * administrador ativo. A primeira conta vem do {@code booksmith.staff.bootstrap-email} ao subir com a equipe vazia.
 */
@Slf4j
@Service
public class StaffService {

    static final Duration INVITE_TTL = Duration.ofHours(72);
    private static final Map<StaffRole, String> ROLE_NAME = Map.of(
            StaffRole.ADMIN, "administrador", StaffRole.SUPPORT, "atendente", StaffRole.MODERATOR, "moderador");

    private final StaffStore store;
    private final StaffTokens tokens;
    private final EmailSender emailSender;
    private final StaffProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    StaffService(StaffStore store, StaffTokens tokens, EmailSender emailSender, StaffProperties properties,
                 ApplicationEventPublisher events, Clock clock) {
        this.store = store;
        this.tokens = tokens;
        this.emailSender = emailSender;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    /** Quem é da equipe, com a situação: ativo, convidado (não aceitou ainda) ou desativado. */
    @Transactional(readOnly = true)
    public List<MemberView> list() {
        Instant now = Instant.now(clock);
        return store.all().stream().map(m -> new MemberView(m.id(), m.email(), m.name(), m.role(),
                !m.active() ? "inactive" : m.passwordHash() == null ? "invited" : "active",
                m.passwordHash() == null && store.hasOpenInvite(m.id(), now), m.createdAt(), m.lastLoginAt())).toList();
    }

    @Transactional
    public MemberView invite(AuthenticatedStaff admin, String email, String name, StaffRole role) {
        String cleanEmail = email == null ? "" : email.strip();
        String cleanName = name == null ? "" : name.strip();
        if (!cleanEmail.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") || cleanEmail.length() > 200) {
            throw ApiException.unprocessable("staff_invalid", "E-mail inválido.");
        }
        if (cleanName.isEmpty() || cleanName.length() > 80 || role == null) {
            throw ApiException.unprocessable("staff_invalid", "Informe o nome e o papel.");
        }
        if (!properties.allowed(cleanEmail)) {
            // Fica no registro (quem tentou) e no monitoramento: alguém tentando pôr um e-mail de fora na equipe
            store.audit(admin.id(), admin.email(), "staff.invite_rejected", domain(cleanEmail), null, Instant.now(clock));
            events.publishEvent(new SecurityIncident(SecurityIncident.Kind.STAFF_DOMAIN_REJECTED, "invite"));
            log.warn("Backoffice: convite recusado para um e-mail de fora do domínio ({}), por {}", domain(cleanEmail),
                    admin.id());
            throw ApiException.unprocessable("staff_email_domain",
                    "Só e-mails da empresa (%s) entram na equipe.".formatted(String.join(", ", properties.emailDomains())));
        }
        if (store.byEmail(cleanEmail).isPresent()) {
            throw ApiException.conflict("staff_exists", "Esse e-mail já está na equipe.", Map.of());
        }
        UUID id = UUID.randomUUID();
        store.insert(id, cleanEmail, cleanName, role, admin.id(), Instant.now(clock));
        sendInvite(id, cleanEmail, role);
        log.info("Backoffice: {} convidado como {} por {}", id, role, admin.id());
        return view(id);
    }

    /** Senha e autenticador novos (perdeu o celular, esqueceu a senha): os antigos e as sessões acabam na hora. */
    @Transactional
    public MemberView reinvite(AuthenticatedStaff admin, UUID id) {
        StaffMember member = member(id);
        if (member.id().equals(admin.id())) {
            throw forbidden("Peça a outro administrador para reconvidar você.");
        }
        protectLastAdmin(member, "reconvidar");
        store.clearCredentials(id);
        tokens.closeAll(id);
        sendInvite(id, member.email(), member.role());
        return view(id);
    }

    @Transactional
    public MemberView role(AuthenticatedStaff admin, UUID id, StaffRole role) {
        StaffMember member = member(id);
        if (role == null) {
            throw ApiException.unprocessable("staff_invalid", "Informe o papel.");
        }
        if (member.id().equals(admin.id())) {
            throw forbidden("Você não pode mudar o próprio papel.");
        }
        if (role != StaffRole.ADMIN) {
            protectLastAdmin(member, "mudar o papel de");
        }
        store.role(id, role);
        // O papel vai no token: as sessões abertas acabam para o novo papel valer já
        tokens.closeAll(id);
        return view(id);
    }

    @Transactional
    public MemberView active(AuthenticatedStaff admin, UUID id, boolean active) {
        StaffMember member = member(id);
        if (member.id().equals(admin.id())) {
            throw forbidden("Você não pode desativar a própria conta.");
        }
        if (!active) {
            protectLastAdmin(member, "desativar");
            tokens.closeAll(id);
        }
        store.active(id, active);
        return view(id);
    }

    /** Ao subir: equipe sem ninguém ativo e {@code STAFF_BOOTSTRAP_EMAIL} definido → convite de administrador. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        String email = properties.bootstrapEmail();
        if (email == null || email.isBlank() || store.activeAdmins() > 0) {
            return;
        }
        if (!properties.allowed(email)) {
            log.error("Backoffice: STAFF_BOOTSTRAP_EMAIL não é de um domínio da empresa ({}); nenhum convite enviado",
                    properties.emailDomains());
            events.publishEvent(new SecurityIncident(SecurityIncident.Kind.STAFF_DOMAIN_REJECTED, "bootstrap"));
            return;
        }
        Instant now = Instant.now(clock);
        StaffMember member = store.byEmail(email).orElse(null);
        if (member == null) {
            if (store.count() > 0) {
                return;
            }
            UUID id = UUID.randomUUID();
            store.insert(id, email, email.substring(0, Math.max(1, email.indexOf('@'))), StaffRole.ADMIN, null, now);
            member = store.byId(id).orElseThrow();
        }
        if (member.role() == StaffRole.ADMIN && member.passwordHash() == null && !store.hasOpenInvite(member.id(), now)) {
            sendInvite(member.id(), member.email(), StaffRole.ADMIN);
            log.warn("Backoffice: convite da primeira conta da equipe enviado (STAFF_BOOTSTRAP_EMAIL)");
        }
    }

    private void sendInvite(UUID id, String email, StaffRole role) {
        String token = TokenHasher.randomUrlToken();
        store.invite(TokenHasher.sha256(token), id, Instant.now(clock).plus(INVITE_TTL));
        // O token vai depois do "#": o navegador não manda essa parte ao servidor (não fica em log nem em Referer)
        String link = (properties.adminUrl() == null ? "" : properties.adminUrl()) + "/convite#" + token;
        emailSender.sendNotice(email, "Convite para a equipe do Booksmith",
                ("Você foi convidado para o backoffice do Booksmith como %s.%n%n"
                        + "Crie sua senha e ative o app autenticador (Google Authenticator, Authy ou outro) por este link,"
                        + " que vale 72 horas:%n%s%n%nSe não esperava este convite, ignore este e-mail.")
                        .formatted(ROLE_NAME.get(role), link));
    }

    /** Só o domínio vai para o registro e o log (o resto do e-mail de quem não é da equipe não interessa). */
    static String domain(String email) {
        int at = email.lastIndexOf('@');
        return at < 0 ? "?" : email.substring(at + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private void protectLastAdmin(StaffMember member, String action) {
        if (member.role() == StaffRole.ADMIN && member.active() && member.passwordHash() != null
                && store.activeAdmins() <= 1) {
            throw ApiException.conflict("staff_last_admin",
                    "Não dá para %s o único administrador ativo.".formatted(action), Map.of());
        }
    }

    private StaffMember member(UUID id) {
        return store.byId(id).orElseThrow(() -> ApiException.notFound("staff_not_found", "Pessoa não encontrada."));
    }

    private MemberView view(UUID id) {
        return list().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "staff_self", message, Map.of());
    }

    /**
     * @param status       active, invited (ainda não aceitou) ou inactive
     * @param inviteOpen   o convite ainda vale (senão, reconvidar)
     */
    public record MemberView(UUID id, String email, String name, StaffRole role, String status, boolean inviteOpen,
                             Instant createdAt, Instant lastLoginAt) {
    }
}
