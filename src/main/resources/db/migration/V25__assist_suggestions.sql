-- Sugestões de IA (§4.9 do produto): registro de uso, para o limite diário por pessoa e para acompanhar o custo
-- por provedor. Nunca guarda o texto do autor nem as ideias devolvidas.
create schema if not exists assist;

create table assist.suggestions (
    id           uuid primary key,
    user_id      uuid        not null,          -- referência lógica a identity.users
    kind         text        not null,          -- universe | character | plot
    field        text        not null,          -- campo pedido (ex.: fears, dangers, twist)
    mode         text        not null,          -- ideas | questions
    provider     text        not null,          -- ollama | anthropic
    model        text        not null,
    duration_ms  integer     not null,
    idea_count   integer     not null,
    created_at   timestamptz not null default now()
);

create index suggestions_user_idx on assist.suggestions (user_id, created_at desc);
