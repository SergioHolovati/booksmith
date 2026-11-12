-- Verificação em duas etapas (MFA): app autenticador (TOTP) e código por e-mail, mais códigos de recuperação.
create table identity.mfa_factors (
    id              uuid primary key,
    user_id         uuid not null references identity.users (id) on delete cascade,
    -- totp | email
    type            text not null,
    -- Segredo do TOTP criptografado (AES-GCM, chave fora do banco); null no e-mail
    secret          text,
    -- Último passo de 30 s usado no TOTP: o mesmo código não vale duas vezes
    last_step       bigint,
    confirmed_at    timestamptz,
    created_at      timestamptz not null default now(),
    constraint mfa_factors_type_ck check (type in ('totp', 'email'))
);
create unique index mfa_factors_user_type_uk on identity.mfa_factors (user_id, type);

create table identity.mfa_recovery_codes (
    id          uuid primary key,
    user_id     uuid not null references identity.users (id) on delete cascade,
    -- SHA-256 do código; o código só aparece uma vez, na tela
    code_hash   text not null,
    used_at     timestamptz,
    created_at  timestamptz not null default now()
);
create index mfa_recovery_codes_user_ix on identity.mfa_recovery_codes (user_id);
