-- Mapa gerado no app (sem IA): guarda a semente e os ajustes do gerador junto com a imagem, para refazer o
-- mesmo mapa e, depois, transformar países, cidades e florestas dele em lugares do universo.
-- Nulo quando o mapa é uma imagem enviada pelo autor.
alter table universe.universes add column map_generator jsonb;
