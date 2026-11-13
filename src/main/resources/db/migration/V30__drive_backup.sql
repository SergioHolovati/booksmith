-- Backup no Google Drive (pedido do autor): a conta Google ligada só para o Drive (escopo drive.file: o Booksmith
-- vê só os arquivos que ele mesmo criou) e o arquivo de cada livro na pasta "Booksmith" do Drive da pessoa.
create schema if not exists export;

create table export.drive_connections (
    user_id         uuid primary key references identity.users (id) on delete cascade,
    google_email    text,
    -- Token de renovação do Google criptografado (SecretBox, chave fora do banco)
    refresh_token   text not null,
    folder_id       text,
    connected_at    timestamptz not null default now(),
    last_backup_at  timestamptz,
    last_error      text
);

create table export.drive_files (
    id          uuid primary key,
    user_id     uuid not null references identity.users (id) on delete cascade,
    book_id     uuid not null,
    file_id     text not null,
    backed_up_at timestamptz not null default now()
);
create unique index drive_files_user_book_uk on export.drive_files (user_id, book_id);
