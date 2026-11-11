-- Campos próprios do autor (§4.2 do produto): cada universo define campos extras para personagens, lugares e
-- cada tipo de ficha do mundo ({"character": [{key, label, type, options...}], "creature": [...]}), no mesmo
-- formato dos formulários definidos por dados. Os valores ficam em entities.custom ({"f_k3x9": "Escorpião"}).
alter table universe.universes add column custom_fields jsonb not null default '{}';
alter table universe.entities add column custom jsonb not null default '{}';
