-- Livro ilustrado: a organização das páginas que o Booksmith gera (folha de rosto, créditos, dedicatória, sobre o
-- autor, colofão…). JSON de uma lista de com.booksmith.manuscript.api.ExtraPage: o lugar de cada uma entre as páginas
-- da história e o fundo (ilustração ou cor) só dela. "[]" = o lugar de sempre e o fundo da diagramação.
alter table manuscript.books add column extra_pages jsonb not null default '[]';
