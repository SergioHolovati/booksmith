-- Agente de continuidade, primeira parte (§10.4 da arquitetura): verificações determinísticas, sem IA.
-- Os alertas guardam a decisão do autor (ignorado, intencional) para não voltarem a cada análise.
create schema if not exists continuity;

create table continuity.alerts (
    id           uuid primary key,
    book_id      uuid          not null,          -- referência lógica a manuscript.books
    type         text          not null check (type in ('attribute', 'naming')),
    severity     text          not null check (severity in ('low', 'medium', 'high')),
    confidence   numeric(3, 2) not null,
    message      text          not null,
    evidence     jsonb         not null,          -- [{chapterId, chapterTitle, blockId, quote}]
    status       text          not null default 'open' check (status in ('open', 'fixed', 'ignored', 'intentional')),
    fingerprint  text          not null,          -- o mesmo problema gera sempre a mesma impressão
    created_at   timestamptz   not null default now(),
    updated_at   timestamptz   not null default now(),
    resolved_at  timestamptz,
    unique (book_id, fingerprint)
);

create index alerts_book_status_idx on continuity.alerts (book_id, status);

-- Última análise de cada livro (para mostrar "analisado há 5 minutos")
create table continuity.analyses (
    book_id      uuid primary key,
    analyzed_at  timestamptz not null,
    chapters     int         not null,
    entities     int         not null
);
