-- Gênero do livro (o que muda nas ferramentas, nos modelos e no tom da IA) e o desenho de cada página do livro
-- ilustrado (infantil): no livro infantil, cada capítulo é uma página, com a ilustração de fundo e o texto por cima.
alter table manuscript.books add column genre text not null default 'FICTION';
alter table manuscript.books add constraint books_genre_ck
    check (genre in ('FICTION', 'CHRISTIAN', 'SELF_HELP', 'SPIRITUAL', 'EDUCATIONAL', 'CHILDREN', 'OTHER'));

-- JSON de com.booksmith.manuscript.api.PageDesign; null = página sem desenho (o padrão)
alter table manuscript.chapters add column page jsonb;
