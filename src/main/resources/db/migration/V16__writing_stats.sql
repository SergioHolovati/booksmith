-- Meta diária de palavras e sequência de dias (§4.1 do produto). Só o que se escreve no editor conta (o
-- salvamento automático): importar arquivo ou restaurar versão não. "words" é o saldo do dia (escritas menos
-- apagadas), no fuso horário do aparelho de quem escreve.
create table manuscript.writing_days (
    owner_id    uuid        not null,
    day         date        not null,
    words       int         not null default 0,
    updated_at  timestamptz not null default now(),
    primary key (owner_id, day)
);

create table manuscript.writing_goals (
    owner_id     uuid primary key,
    daily_words  int,                       -- null: sem meta (a sequência conta dias com qualquer escrita)
    updated_at   timestamptz not null default now()
);
