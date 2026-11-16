-- Arte da capa com IA (livro concluído): o registro de uso passa a aceitar o tipo "cover".
alter table assist.image_generations drop constraint image_generations_kind_ck;
alter table assist.image_generations
    add constraint image_generations_kind_ck check (kind in ('portrait', 'scene', 'cover'));
