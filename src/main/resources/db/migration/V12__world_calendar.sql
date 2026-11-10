-- Calendário do mundo e datas de verdade na linha do tempo (§4.2 do produto). O calendário (eras, meses,
-- dias da semana) é do universo; nulo usa o padrão (o nosso, sem anos bissextos). A ordem dos eventos
-- continua sendo a do autor: a data é opcional e convive com o "quando" em texto livre.
alter table universe.universes add column calendar jsonb;

alter table universe.timeline_events
    add column kind       text,                          -- birth, death, war, battle, founding…
    add column world_date jsonb,                         -- {"era": "e1", "year": 312, "month": 3, "day": 12}
    add column lore_ids   uuid[] not null default '{}';  -- religiões, governos e organizações envolvidos
