-- Dicionário do livro para o corretor (§8.5, etapa 2): palavras que o autor mandou aceitar ("Adicionar ao
-- dicionário do livro"). Os nomes do universo já são aceitos sem estar aqui.
create table manuscript.book_dictionary (
    book_id     uuid        not null references manuscript.books (id) on delete cascade,
    word        text        not null,
    created_at  timestamptz not null default now(),
    primary key (book_id, word)
);
