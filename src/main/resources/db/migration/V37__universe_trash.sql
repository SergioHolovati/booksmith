-- Exclusão de universo: vai para a lixeira (como livros e capítulos). Some das listas e telas; restaurar um livro
-- dele traz o universo de volta.
alter table universe.universes add column deleted_at timestamptz;
