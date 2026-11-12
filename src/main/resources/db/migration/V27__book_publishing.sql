-- Guia de publicação (§4.7 do produto): destino, ISBNs, palavras-chave da loja e passos feitos à mão.
-- JSON de com.booksmith.manuscript.api.Publishing; '{}' = nada preenchido.
alter table manuscript.books add column publishing jsonb not null default '{}';
