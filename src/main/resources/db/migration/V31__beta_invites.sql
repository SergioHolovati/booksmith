-- Beta fechado: criar conta (por e-mail ou pelo Google) exige um código de convite. Quem já tem conta entra
-- normalmente. Os códigos são criados por um administrador na tela "Convites do beta".
create table identity.invite_codes (
    id          uuid primary key,
    -- Ex.: BETA-7KQ2-M9XA (maiúsculo; a pessoa pode digitar em minúsculas)
    code        text not null unique,
    max_uses    integer not null check (max_uses > 0),
    used_count  integer not null default 0,
    -- Para quem o convite foi: "Discord", "amigos", "Ana"
    note        text,
    expires_at  timestamptz,
    created_by  uuid references identity.users (id) on delete set null,
    created_at  timestamptz not null default now()
);

alter table identity.users add column invite_code_id uuid references identity.invite_codes (id) on delete set null;
-- Administrador do Booksmith (cria convites). Promovido por SQL: update identity.users set admin = true where ...
alter table identity.users add column admin boolean not null default false;
