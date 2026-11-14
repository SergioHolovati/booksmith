-- Ilustrações com IA (Estúdio Visual, §4.8): retratos de personagem e cenas de capítulo geradas pelo FLUX.
-- Toda imagem gerada fica marcada na biblioteca do universo; cada geração fica registrada (só metadados) para o
-- limite semanal por pessoa e para medir o custo real.
alter table universe.images add column ai_generated boolean not null default false;

create table assist.image_generations (
    id            uuid primary key,
    user_id       uuid        not null,
    universe_id   uuid        not null,
    -- portrait | scene
    kind          text        not null,
    provider      text        not null,
    model         text        not null,
    -- ready | moderated | failed (só "ready" conta no limite: o fornecedor não cobra as outras)
    status        text        not null,
    -- Créditos do fornecedor (1 crédito = US$ 0,01 no FLUX)
    cost_credits  numeric(10, 2),
    image_id      uuid,
    duration_ms   integer     not null,
    created_at    timestamptz not null default now(),
    constraint image_generations_kind_ck check (kind in ('portrait', 'scene')),
    constraint image_generations_status_ck check (status in ('ready', 'moderated', 'failed'))
);
create index image_generations_user_ix on assist.image_generations (user_id, created_at desc);
