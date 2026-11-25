-- Estante (§14 da arquitetura, §4.9 do produto): obras publicadas, leitura pública, seguir, biblioteca, curtidas,
-- comentários por capítulo e denúncias. Publicar é separado de salvar: a obra guarda uma cópia (HTML gerado no
-- servidor a partir do documento do editor) e só muda quando o autor republica.
create schema if not exists shelf;

create table shelf.works (
    id            uuid primary key,
    book_id       uuid        not null unique,      -- referência lógica a manuscript.books
    owner_id      uuid        not null,             -- referência lógica a identity.users
    slug          text        not null unique,      -- endereço público: /estante/obra/<slug>
    title         text        not null,
    synopsis      text,
    cover_url     text,
    genre         text        not null,             -- o gênero do livro (FICTION, CHILDREN…)
    fanfic        boolean     not null default false,
    fandom        text,
    language      text        not null default 'pt-BR',
    -- Classificação indicativa; 18 fica de fora nesta versão (sem verificação de idade)
    rating        text        not null check (rating in ('L', '10', '12', '14', '16')),
    warnings      text[]      not null default '{}', -- avisos de conteúdo (violence, death, self_harm…)
    tags          text[]      not null default '{}',
    -- Uso de IA no texto: none (escrito pela pessoa), assisted (revisão/ideias), generated (trechos gerados)
    ai_use        text        not null check (ai_use in ('none', 'assisted', 'generated')),
    status        text        not null check (status in ('ongoing', 'complete', 'hiatus')),
    visibility    text        not null check (visibility in ('public', 'unlisted')),
    published_at  timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    -- Tirada do ar pela moderação (o autor vê o motivo; a obra some da vitrine e da leitura)
    removed_at    timestamptz,
    removed_reason text
);

create index works_public_idx on shelf.works (updated_at desc) where visibility = 'public' and removed_at is null;
create index works_owner_idx on shelf.works (owner_id);
create index works_tags_idx on shelf.works using gin (tags);

create table shelf.chapters (
    id                uuid primary key,
    work_id           uuid        not null references shelf.works (id) on delete cascade,
    source_chapter_id uuid        not null,          -- referência lógica a manuscript.chapters
    position          int         not null,
    title             text        not null,
    html              text        not null,          -- gerado no servidor, só com os nós do esquema do editor
    word_count        int         not null,
    edition           int         not null default 1, -- sobe a cada republicação
    published_at      timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    unique (work_id, source_chapter_id)
);

create index chapters_work_idx on shelf.chapters (work_id, position);

create table shelf.follows (
    follower_id uuid        not null,
    author_id   uuid        not null,
    created_at  timestamptz not null default now(),
    primary key (follower_id, author_id)
);

create index follows_author_idx on shelf.follows (author_id);

-- Biblioteca pessoal e onde a pessoa parou
create table shelf.library (
    user_id         uuid        not null,
    work_id         uuid        not null references shelf.works (id) on delete cascade,
    last_chapter_id uuid,
    progress        real        not null default 0,   -- 0 a 1, dentro do último capítulo lido
    added_at        timestamptz not null default now(),
    read_at         timestamptz,
    primary key (user_id, work_id)
);

create table shelf.likes (
    user_id    uuid        not null,
    chapter_id uuid        not null references shelf.chapters (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, chapter_id)
);

create index likes_chapter_idx on shelf.likes (chapter_id);

create table shelf.comments (
    id          uuid primary key,
    chapter_id  uuid        not null references shelf.chapters (id) on delete cascade,
    user_id     uuid        not null,
    body        text        not null,
    created_at  timestamptz not null default now(),
    deleted_at  timestamptz,                        -- apagado por quem escreveu
    hidden_at   timestamptz                         -- escondido pela moderação
);

create index comments_chapter_idx on shelf.comments (chapter_id, created_at);

create table shelf.reports (
    id           uuid primary key,
    target_type  text        not null check (target_type in ('work', 'comment')),
    target_id    uuid        not null,
    reporter_id  uuid        not null,
    reason       text        not null check (reason in ('plagiarism', 'rating', 'hate', 'sexual', 'minor_safety',
                                                        'spam', 'other')),
    details      text,
    status       text        not null default 'open' check (status in ('open', 'removed', 'dismissed')),
    created_at   timestamptz not null default now(),
    resolved_at  timestamptz,
    resolved_by  uuid,                              -- identity.staff_members
    unique (target_type, target_id, reporter_id)    -- uma denúncia por pessoa e alvo
);

create index reports_open_idx on shelf.reports (created_at) where status = 'open';
