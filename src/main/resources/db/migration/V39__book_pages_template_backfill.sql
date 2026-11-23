-- Os livros criados antes do modelo das páginas (V38 e antes) ganham o mesmo modelo dos livros novos
-- (Manuscripts.BookPages.TEMPLATE) nas páginas ainda vazias: o que está entre colchetes é para completar e não sai no
-- livro enquanto não for completado. O que o autor já escreveu fica como está.
update manuscript.books
set credits = coalesce(credits,
        E'Edição: [editora, ou edição independente]\n'
        || E'Revisão: [nome de quem revisou]\n'
        || E'Ilustração: [nome de quem ilustrou]\n'
        || E'Capa: [nome de quem fez a capa]\n'
        || E'Projeto gráfico e diagramação: [nome]\n'
        || '[Cidade], [ano]'),
    dedication = coalesce(dedication, '[Para quem é este livro? Ex.: Para a Lia, que me ensinou a ver cores.]'),
    epigraph_source = case when epigraph is null then '[De quem é a frase]' else epigraph_source end,
    epigraph = coalesce(epigraph, '[Uma frase que abre o livro, de um livro, música ou pessoa]'),
    acknowledgments = coalesce(acknowledgments,
        '[A quem você quer agradecer: quem leu antes, revisou, ilustrou, apoiou.]'),
    about_author = coalesce(about_author,
        '[Conte em poucas linhas quem você é: onde vive, o que faz, o que escreve e por que escreveu este livro.]'),
    colophon = coalesce(colophon,
        'Este livro foi composto em [fonte] e impresso em [papel] pela [gráfica], em [mês e ano].');
