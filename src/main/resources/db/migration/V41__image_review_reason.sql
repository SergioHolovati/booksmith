-- Moderação: o motivo da revisão ou da recusa (nudez, violência, ódio, ilegível)
alter table media.image_reviews add column reason text;
update media.image_reviews set reason = 'nudez' where reason is null;
