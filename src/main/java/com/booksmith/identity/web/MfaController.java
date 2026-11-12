package com.booksmith.identity.web;

import com.booksmith.identity.api.CurrentUser;
import com.booksmith.identity.internal.mfa.MfaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Verificação em duas etapas da conta logada (Perfil → Verificação em duas etapas). */
@RestController
@RequestMapping("/api/v1/me/mfa")
@RequiredArgsConstructor
class MfaController {

    private static final String TOTP = "totp";
    private static final String EMAIL = "email";

    private final MfaService mfa;
    private final CurrentUser currentUser;

    @GetMapping
    MfaService.Status status() {
        return mfa.status(currentUser.id());
    }

    /** Começa o app autenticador: o segredo e o link para o QR code. */
    @PostMapping("/totp")
    MfaService.TotpSetup startTotp() {
        return mfa.startTotp(currentUser.id());
    }

    @PostMapping("/totp/confirm")
    RecoveryCodes confirmTotp(@RequestBody @Valid CodeRequest request) {
        return new RecoveryCodes(mfa.confirm(currentUser.id(), TOTP, request.code()));
    }

    /** Começa o código por e-mail: manda um código para o e-mail da conta. */
    @PostMapping("/email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void startEmail() {
        mfa.startEmail(currentUser.id());
    }

    @PostMapping("/email/confirm")
    RecoveryCodes confirmEmail(@RequestBody @Valid CodeRequest request) {
        return new RecoveryCodes(mfa.confirm(currentUser.id(), EMAIL, request.code()));
    }

    /** Código por e-mail para confirmar uma ação (desativar, novos códigos de recuperação). */
    @PostMapping("/code")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void sendCode() {
        mfa.sendStepUpEmail(currentUser.id());
    }

    @PostMapping("/recovery-codes")
    RecoveryCodes regenerate(@RequestBody @Valid CodeRequest request) {
        return new RecoveryCodes(mfa.regenerateRecoveryCodes(currentUser.id(), request.code()));
    }

    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(@RequestBody @Valid DisableRequest request) {
        mfa.disable(currentUser.id(), request.password(), request.code());
    }

    record CodeRequest(@NotBlank(message = "Digite o código.") @Size(max = 20) String code) {
    }

    record DisableRequest(@Size(max = 200) String password,
                          @NotBlank(message = "Digite o código.") @Size(max = 20) String code) {
    }

    /** Vazio quando o método confirmado não é o primeiro (os códigos de antes continuam valendo). */
    record RecoveryCodes(List<String> recoveryCodes) {
    }
}
