-- Linha do tempo básica (§4.2 do produto, §7.4 da arquitetura). A ordem é a do autor (sort_key), não uma
-- data calculada: ficção tem calendários próprios, flashbacks e datas vagas ("três dias depois").
create table universe.timeline_events (
    id             uuid primary key,
    universe_id    uuid          not null references universe.universes (id),
    title          text          not null,
    when_label     text,                       -- "Verão de 1999", "Três dias depois"
    description    text,
    chapter_id     uuid,                       -- capítulo onde acontece (referência lógica ao manuscript)
    place_id       uuid references universe.entities (id),
    character_ids  uuid[]        not null default '{}',
    sort_key       numeric       not null,     -- inserir entre dois eventos não renumera os outros
    version        bigint        not null default 0,
    created_at     timestamptz   not null default now(),
    updated_at     timestamptz   not null default now(),
    deleted_at     timestamptz
);

create index timeline_events_universe_idx on universe.timeline_events (universe_id, sort_key) where deleted_at is null;
