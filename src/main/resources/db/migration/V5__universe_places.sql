-- Bíblia do Universo: lugares (§4.2 do produto). Mesma tabela dos personagens, outro "kind".
-- A ficha do lugar (tipo, onde fica, "dentro de", clima, regras...) vai em attributes.
alter table universe.entities drop constraint entities_kind_check;
alter table universe.entities add constraint entities_kind_check check (kind in ('character', 'place'));
