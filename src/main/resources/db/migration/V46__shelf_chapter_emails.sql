-- Aviso de capítulo novo para quem segue o autor (Estante): no máximo um e-mail por obra a cada 12 horas, e cada
-- leitor pode desligar.
alter table shelf.works add column last_notified_at timestamptz;

create table shelf.preferences (
    user_id        uuid primary key,              -- referência lógica a identity.users
    chapter_emails boolean     not null default true,
    updated_at     timestamptz not null default now()
);
