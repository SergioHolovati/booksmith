-- Capa importada em PDF (§4.7 do produto): o autor fez a capa fora do app. Guarda a referência ao PDF original
-- (armazenamento de PDFs), o tipo (só a frente ou a capa aberta) e as medidas. Null = sem capa em PDF.
alter table manuscript.books add column cover_pdf jsonb;
