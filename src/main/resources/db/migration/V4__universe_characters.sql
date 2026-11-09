-- Bíblia do Universo, primeira parte: universos e personagens (§7.4 e §12.1 da arquitetura).
-- Cada livro passa a viver num universo. Por enquanto um por livro; séries poderão compartilhar depois.
create schema if not exists universe;

create table universe.universes (
    id         uuid primary key,
    owner_id   uuid        not null,
    name       text        not null,
    template   text,                          -- fantasy | scifi | romance | horror | fanfic | blank
    settings   jsonb       not null default '{}',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index universes_owner_idx on universe.universes (owner_id);

-- Entidades do universo. Hoje só personagens; os tipos personalizáveis (entity_types) entram com
-- lugares, facções e objetos.
create table universe.entities (
    id                      uuid primary key,
    universe_id             uuid        not null references universe.universes (id),
    kind                    text        not null check (kind in ('character')),
    name                    text        not null,
    aliases                 text[]      not null default '{}',
    attributes              jsonb       not null default '{}', -- ficha: papel, idade, personalidade, voz...
    appearance              jsonb       not null default '{}', -- valores do formulário de aparência
    appearance_form_version int         not null default 1,
    canon_status            text check (canon_status in ('canon', 'headcanon', 'au')),
    version                 bigint      not null default 0,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    deleted_at              timestamptz
);

create index entities_universe_idx on universe.entities (universe_id, kind, name) where deleted_at is null;

-- Referência lógica do livro ao universo (módulos diferentes: sem chave estrangeira entre esquemas)
alter table manuscript.books add column universe_id uuid;

-- Livros existentes (inclusive os da lixeira) ganham um universo com o nome do livro
create temporary table book_universe on commit drop as
select id as book_id, gen_random_uuid() as universe_id, owner_id, title
from manuscript.books
where universe_id is null;

insert into universe.universes (id, owner_id, name, created_at, updated_at)
select universe_id, owner_id, title, now(), now()
from book_universe;

update manuscript.books b
set universe_id = m.universe_id
from book_universe m
where b.id = m.book_id;

create index books_universe_idx on manuscript.books (universe_id);
