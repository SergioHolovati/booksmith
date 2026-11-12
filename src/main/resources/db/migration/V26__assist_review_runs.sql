-- Revisão do capítulo com IA conta como um uso só da cota (§4.11, 12/11/2026). Com a IA paga, o limite é de
-- usos por pessoa (3 a cada 24 horas): uma revisão completa, um pedido de ideias ou uma pergunta ao livro. A
-- revisão faz várias chamadas (cinco revisões, em partes); "abrir" a revisão gasta o uso e devolve este id, que
-- só vale para aquele capítulo, por pouco tempo e até max_calls chamadas.
create table assist.review_runs (
    id          uuid primary key,
    user_id     uuid        not null,          -- referência lógica a identity.users
    chapter_id  uuid        not null,          -- referência lógica a manuscript.chapters
    max_calls   integer     not null,
    calls       integer     not null default 0,
    created_at  timestamptz not null default now()
);

create index review_runs_user_idx on assist.review_runs (user_id, created_at desc);
