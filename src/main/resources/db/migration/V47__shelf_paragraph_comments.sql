-- Comentário por parágrafo na Estante: o id do bloco do editor (o mesmo do parágrafo no HTML publicado, p-<id>).
-- Null = comentário do capítulo. Se o autor tirar o parágrafo ao republicar, o comentário passa a valer para o
-- capítulo (o app não acha mais o parágrafo e mostra o comentário sem o trecho).
alter table shelf.comments add column block_id text;

create index comments_block_idx on shelf.comments (chapter_id, block_id) where block_id is not null;
