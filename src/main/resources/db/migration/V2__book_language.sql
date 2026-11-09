-- Idioma do livro (tag BCP 47). Orienta o corretor ortográfico do navegador, a hifenização e leitores de tela;
-- no futuro, o corretor gramatical próprio (§8.5 da arquitetura).
alter table manuscript.books
    add column language text not null default 'pt-BR'
        constraint books_language_supported check (language in ('pt-BR', 'pt-PT', 'en', 'es'));
