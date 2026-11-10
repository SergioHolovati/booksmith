-- Biblioteca de imagens do universo (§4.4 do produto): retratos, referências visuais, fotos de lugares, mapas
-- desenhados à mão. Cada imagem pode estar ligada a vários personagens, lugares e fichas do mundo
-- (entity_ids, referência lógica: o que foi excluído some da imagem sem erro). O arquivo fica no S3/MinIO.
create table universe.images (
    id            uuid primary key,
    universe_id   uuid        not null references universe.universes (id),
    url           text        not null,
    content_type  text        not null,
    bytes         bigint      not null,
    caption       text,
    entity_ids    uuid[]      not null default '{}',
    version       bigint      not null default 0,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    deleted_at    timestamptz
);

create index images_universe_idx on universe.images (universe_id, created_at desc) where deleted_at is null;
create index images_entities_idx on universe.images using gin (entity_ids) where deleted_at is null;

-- Retrato de um personagem ou lugar: uma das imagens da biblioteca (referência lógica; imagem excluída = sem retrato)
alter table universe.entities add column portrait_id uuid;
