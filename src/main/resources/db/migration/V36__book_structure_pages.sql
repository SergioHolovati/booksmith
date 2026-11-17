-- Estrutura completa do livro: falsa folha de rosto (só o título, antes da folha de rosto), ficha catalográfica
-- (dados de catalogação, no verso da folha de rosto), colofão (a última página: tipografia, papel, gráfica) e a foto
-- de quem escreveu (na página "sobre" e na contracapa), uma imagem da biblioteca do universo do livro.
alter table manuscript.books add column half_title boolean not null default false;
alter table manuscript.books add column catalog_card text;
alter table manuscript.books add column colophon text;
alter table manuscript.books add column author_photo_id uuid;
