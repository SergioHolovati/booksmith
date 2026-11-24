-- Contas da equipe (backoffice), separadas das contas dos clientes: login, senha, app autenticador (obrigatório) e
-- papel próprios. Os clientes continuam em identity.users; a marca users.admin deixa de valer para o backoffice.
create table identity.staff_members (
    id             uuid primary key,
    email          text        not null,
    name           text        not null,
    role           text        not null,          -- ADMIN | SUPPORT | MODERATOR
    password_hash  text,                          -- null até aceitar o convite
    totp_secret    text,                          -- selado (SecretBox); null até aceitar o convite
    totp_last_step bigint,                        -- o mesmo código não vale duas vezes
    active         boolean     not null default true,
    created_by     uuid,
    created_at     timestamptz not null default now(),
    activated_at   timestamptz,
    last_login_at  timestamptz
);

create unique index staff_members_email_idx on identity.staff_members (lower(email));

-- Convite (e reconvite: troca de senha e de autenticador). O link leva o token; aqui fica só o hash.
create table identity.staff_invites (
    token_hash  text primary key,
    member_id   uuid        not null references identity.staff_members (id) on delete cascade,
    totp_secret text,                             -- selado; gerado quando o convidado abre o link
    expires_at  timestamptz not null,
    used_at     timestamptz,
    created_at  timestamptz not null default now()
);

create table identity.staff_recovery_codes (
    id         uuid primary key,
    member_id  uuid        not null references identity.staff_members (id) on delete cascade,
    code_hash  text        not null,
    used_at    timestamptz
);

create index staff_recovery_member_idx on identity.staff_recovery_codes (member_id);

-- O que a equipe fez (quem, o quê, sobre o quê, quando). Só metadados.
create table identity.staff_audit (
    id          uuid primary key,
    staff_id    uuid,
    staff_email text        not null,
    action      text        not null,
    target      text,
    detail      text,
    created_at  timestamptz not null default now()
);

create index staff_audit_created_idx on identity.staff_audit (created_at desc);

-- Quem cria convites do beta e decide imagens agora é um funcionário (não um usuário do app)
alter table identity.invite_codes drop constraint if exists invite_codes_created_by_fkey;
