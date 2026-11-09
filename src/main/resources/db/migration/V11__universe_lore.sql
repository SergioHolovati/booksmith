-- Contexto do mundo (§4.2 do produto): religiões, sistemas políticos, idiomas e organizações. Moram em
-- universe.entities com kind = 'lore': o tipo e os lugares onde estão presentes ficam em "attributes"
-- ({"type": "religion", "placeIds": [...]}); os valores da ficha (universe/forms/lore-v1.json) em "appearance",
-- a coluna dos formulários definidos por dados.
alter table universe.entities drop constraint entities_kind_check;
alter table universe.entities add constraint entities_kind_check check (kind in ('character', 'place', 'lore'));
