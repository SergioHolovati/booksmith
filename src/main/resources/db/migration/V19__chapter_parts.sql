-- Partes do livro (§4.1 do produto: "capítulos, cenas, partes"). A parte é um marcador no capítulo que a abre:
-- reordenar leva o começo da parte junto com o capítulo; excluir o capítulo passa a parte para o seguinte.
alter table manuscript.chapters add column part_title text;
