-- Relações entre personagens (§4.2 do produto: "mapa de relações"). Uma relação liga dois personagens do
-- mesmo universo com um tipo (parent, sibling, spouse, romance, friend, mentor, rival, enemy, ally, serves,
-- other). Tipos com direção ("é pai/mãe de", "é mentor de", "serve a") leem de from_id para to_id.
-- since/until apontam para eventos da linha do tempo (quando começou, quando terminou).
create table universe.relations (
    id               uuid primary key,
    universe_id      uuid        not null references universe.universes (id),
    from_id          uuid        not null references universe.entities (id),
    to_id            uuid        not null references universe.entities (id),
    kind             text        not null,
    description      text,
    since_event_id   uuid references universe.timeline_events (id),
    until_event_id   uuid references universe.timeline_events (id),
    version          bigint      not null default 0,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    deleted_at       timestamptz,
    check (from_id <> to_id)
);

create index relations_universe_idx on universe.relations (universe_id) where deleted_at is null;
create index relations_from_idx on universe.relations (from_id) where deleted_at is null;
create index relations_to_idx on universe.relations (to_id) where deleted_at is null;
