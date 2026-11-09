-- Um esquema por módulo (§7 da arquitetura). Sem chaves estrangeiras entre esquemas de módulos diferentes.

create schema if not exists identity;
create schema if not exists manuscript;

-- ============================================================
-- identity
-- ============================================================

create table identity.users (
    id                            uuid primary key,
    first_name                    text,
    last_name                     text,
    username                      text,
    email                         text        not null,
    password_hash                 text,
    phone                         text,
    document                      text,
    birth_date                    date,
    provider                      text        not null,
    google_linked                 boolean     not null default false,
    email_verified                boolean     not null default false,
    email_verification_code_hash  text,
    email_verification_expires_at timestamptz,
    last_login_at                 timestamptz,
    created_at                    timestamptz not null default now(),
    updated_at                    timestamptz not null default now()
);

create unique index users_email_key on identity.users (lower(email));
create unique index users_document_key on identity.users (document) where document is not null;

create table identity.author_profiles (
    user_id           uuid primary key references identity.users (id),
    pen_name          text        not null,
    bio               text,
    avatar_url        text,
    audience          text,
    language          text,
    social_links      text[]      not null default '{}',
    book_genres       text[]      not null default '{}',
    writing_styles    text[]      not null default '{}',
    author_references text[]      not null default '{}',
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create table identity.password_reset_tokens (
    id         uuid primary key,
    user_id    uuid        not null references identity.users (id),
    token_hash text        not null unique,
    expires_at timestamptz not null,
    used_at    timestamptz,
    created_at timestamptz not null default now()
);

create index password_reset_tokens_user_idx on identity.password_reset_tokens (user_id);

-- ============================================================
-- manuscript
-- ============================================================

create table manuscript.books (
    id         uuid primary key,
    owner_id   uuid        not null, -- identity.users.id (referência lógica)
    title      text        not null,
    slug       text        not null,
    synopsis   text,
    kind       text        not null check (kind in ('ORIGINAL', 'FANFIC')),
    fandom     text,
    cover_url  text,
    status     text        not null check (status in ('DRAFT', 'WRITING', 'COMPLETE')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    deleted_at timestamptz,
    constraint books_fanfic_requires_fandom check (kind <> 'FANFIC' or fandom is not null)
);

create index books_owner_idx on manuscript.books (owner_id, updated_at desc) where deleted_at is null;

create table manuscript.chapters (
    id             uuid primary key,
    book_id        uuid        not null references manuscript.books (id),
    position       int         not null,
    title          text,
    content        jsonb       not null, -- documento ProseMirror (§8)
    content_text   text        not null,
    word_count     int         not null default 0,
    content_hash   text        not null,
    schema_version int         not null,
    version        bigint      not null default 0,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    deleted_at     timestamptz
);

create index chapters_book_idx on manuscript.chapters (book_id, position) where deleted_at is null;
