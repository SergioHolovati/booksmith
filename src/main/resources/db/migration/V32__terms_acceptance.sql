-- Aceite dos termos de uso, do termo de responsabilidade e da política de privacidade ao criar a conta (por e-mail
-- ou pelo Google). A versão aceita fica no usuário; cada aceite também vira uma linha de histórico (prova, LGPD).
-- Quando a versão em vigor (booksmith.terms.version) muda, a conta só volta a usar o app depois de aceitar de novo.
alter table identity.users add column terms_version text;
alter table identity.users add column terms_accepted_at timestamptz;

create table identity.terms_acceptances (
    id          uuid primary key,
    user_id     uuid not null references identity.users (id) on delete cascade,
    -- A versão em vigor na hora do aceite, ex.: 2026-11-13
    version     text not null,
    accepted_at timestamptz not null default now()
);
create index terms_acceptances_user_ix on identity.terms_acceptances (user_id);
