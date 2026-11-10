-- Notas do autor ao lado do texto (§4.1 do produto). No documento, o trecho tem a marca commentAnchor com o
-- threadId = id da nota; aqui fica o conteúdo. A nota é criada com o id que o editor gerou para a marca, então
-- os dois nascem juntos. Se o trecho for apagado, a nota continua (aparece como "trecho removido").
create table manuscript.chapter_notes (
    id          uuid primary key,
    chapter_id  uuid        not null references manuscript.chapters (id) on delete cascade,
    owner_id    uuid        not null,
    body        text        not null,
    quote       text,                     -- o trecho no momento em que a nota foi criada
    resolved    boolean     not null default false,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now()
);

create index chapter_notes_chapter_idx on manuscript.chapter_notes (chapter_id, created_at);
