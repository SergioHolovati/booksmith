-- Construtor de universo (§4.2 do produto): ideia, mundo, povos, mapa, sistemas de magia, ciência e leis do
-- mundo, tudo opcional. Os valores seguem universe/forms/universe-builder-v1.json (validados por UniverseForm).
-- A coluna "settings" (V4) continua reservada para preferências do universo, não para o conteúdo.
alter table universe.universes
    add column details jsonb  not null default '{}',
    add column map_url text,
    add column version bigint not null default 0;
