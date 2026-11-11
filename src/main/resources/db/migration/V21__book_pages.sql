-- Páginas antes e depois da história (§4.7 do produto): créditos, dedicatória, epígrafe, agradecimentos e
-- "sobre o autor". Texto simples, tudo opcional; saem no ePub, no PDF e no Word.
alter table manuscript.books
    add column credits text,
    add column dedication text,
    add column epigraph text,
    add column epigraph_source text,
    add column acknowledgments text,
    add column about_author text,
    add column about_author_title text;
