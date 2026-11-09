-- Histórico de versões dos capítulos (§9.2 da arquitetura). Cada linha é um retrato do capítulo
-- num momento: automático (antes de mudanças grandes ou espaçadas), manual ("salvar versão") ou de segurança
-- (antes de restaurar uma versão antiga).
create table manuscript.chapter_versions (
    id              uuid primary key,
    chapter_id      uuid        not null references manuscript.chapters (id) on delete cascade,
    chapter_version bigint      not null, -- versão do capítulo que este retrato representa
    title           text,
    content         jsonb       not null,
    word_count      int         not null,
    reason          text        not null check (reason in ('AUTOMATIC', 'MANUAL', 'BEFORE_RESTORE')),
    label           text,                 -- nome dado pelo autor nas versões manuais
    created_at      timestamptz not null
);

create index chapter_versions_chapter_idx on manuscript.chapter_versions (chapter_id, created_at desc);

-- Lixeira: listagem por data de exclusão
create index books_trash_idx on manuscript.books (owner_id, deleted_at) where deleted_at is not null;
create index chapters_trash_idx on manuscript.chapters (book_id, deleted_at) where deleted_at is not null;
