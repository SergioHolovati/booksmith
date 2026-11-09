-- Posição do lugar no mapa do universo, em frações da imagem (0 a 1, da esquerda e do topo). Vem dos lugares
-- criados a partir do mapa gerado; nula para lugares sem ponto no mapa.
alter table universe.entities
    add column map_x real,
    add column map_y real;
