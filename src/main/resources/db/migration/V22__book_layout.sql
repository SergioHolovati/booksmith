-- Diagramação do livro (§4.7 do produto): tamanho, letra, entrelinha, margens, abertura de capítulo e quebra de
-- cena do PDF. Objeto vazio = o padrão (o PDF de leitura de antes).
alter table manuscript.books add column layout jsonb not null default '{}';
