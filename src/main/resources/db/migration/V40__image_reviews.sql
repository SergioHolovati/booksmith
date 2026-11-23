-- Moderação de imagens: o que o classificador separou. "pending" = entrou e espera o admin; "blocked" = recusada na
-- hora (a imagem não foi guardada: sem chave nem URL); "kept" e "removed" = decididas pelo admin.
create schema if not exists media;

create table media.image_reviews (
    id          uuid primary key,
    owner_id    uuid not null,
    category    text not null,
    image_key   text,
    image_url   text,
    score       double precision not null,
    status      text not null check (status in ('pending', 'blocked', 'kept', 'removed')),
    created_at  timestamptz not null default now(),
    decided_at  timestamptz,
    decided_by  uuid
);

create index image_reviews_status_created on media.image_reviews (status, created_at desc);
