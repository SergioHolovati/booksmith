-- Editor de capa (§4.7 do produto): as escolhas do desenho da capa, para editar de novo. A imagem gerada no
-- aparelho continua em cover_url. Null = a capa não foi feita no editor.
alter table manuscript.books add column cover_design jsonb;
