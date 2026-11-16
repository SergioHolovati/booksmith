package com.booksmith.export.internal;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * Documento ProseMirror (§8 da arquitetura) → XHTML. É o mesmo HTML no ePub e no PDF; só o CSS muda.
 * Regras de tipografia de livro: recuo vira classe (nunca tabulação), quebra de cena vira "* * *" centralizado,
 * parágrafo vazio vira espaço em branco (o autor usa para respirar entre trechos).
 */
final class BookHtml {

    /** Ordem de aninhamento das marcas: a mesma em todo o livro, para o HTML sair estável. */
    private static final List<String> MARK_ORDER = List.of("bold", "italic", "underline", "strike");

    private BookHtml() {
    }

    /** O corpo do capítulo (sem o título), como fragmento XHTML. Ilustração sai só com a legenda. */
    static String body(JsonNode document) {
        return body(document, id -> null);
    }

    /** Idem, com o endereço de cada ilustração: {@code src(imageId)}, ou null quando a imagem não pode ir. */
    static String body(JsonNode document, java.util.function.Function<String, String> src) {
        return body(document, src, null);
    }

    /**
     * Idem, e cada título interno ganha o id {@code <idPrefix>-s<n>} ({@link #sectionId}), para o sumário apontar
     * para os subcapítulos. Sem prefixo, os títulos saem sem id.
     */
    static String body(JsonNode document, java.util.function.Function<String, String> src, String idPrefix) {
        StringBuilder html = new StringBuilder();
        IMAGE_SRC.set(src);
        SECTION_PREFIX.set(idPrefix);
        SECTION_COUNT.set(new int[1]);
        try {
            children(document, html);
        } finally {
            IMAGE_SRC.remove();
            SECTION_PREFIX.remove();
            SECTION_COUNT.remove();
        }
        return html.toString();
    }

    private static final ThreadLocal<java.util.function.Function<String, String>> IMAGE_SRC = new ThreadLocal<>();

    /**
     * Quem desenha a ilustração que tem página própria (página só dela, até a borda, página dupla): o PDF, que sabe
     * o tamanho da página. Devolve null para usar o desenho comum (o do ePub).
     */
    @FunctionalInterface
    interface PageIllustration {
        String render(String imageId, Placement placement, String caption);
    }

    private static final ThreadLocal<PageIllustration> PAGE_ILLUSTRATION = new ThreadLocal<>();

    /** Idem, com quem desenha as ilustrações de página própria. */
    static String body(JsonNode document, java.util.function.Function<String, String> src, String idPrefix,
                       PageIllustration pages) {
        PAGE_ILLUSTRATION.set(pages);
        try {
            return body(document, src, idPrefix);
        } finally {
            PAGE_ILLUSTRATION.remove();
        }
    }
    private static final ThreadLocal<String> SECTION_PREFIX = new ThreadLocal<>();
    private static final ThreadLocal<int[]> SECTION_COUNT = new ThreadLocal<>();

    /** Um título interno do capítulo (o subcapítulo): {@code number} conta os títulos na ordem, a partir de 1. */
    record Section(int number, int level, String title) {
    }

    /** O id do título interno: único no livro inteiro (o PDF é um documento só). */
    static String sectionId(String prefix, int number) {
        return prefix + "-s" + number;
    }

    /** Os títulos internos do capítulo, na mesma ordem e com a mesma numeração dos ids do corpo. */
    static List<Section> sections(JsonNode document) {
        List<Section> sections = new java.util.ArrayList<>();
        collectSections(document, sections);
        return sections;
    }

    private static void collectSections(JsonNode node, List<Section> sections) {
        JsonNode content = node.get("content");
        if (content == null) {
            return;
        }
        for (JsonNode child : content) {
            String type = type(child);
            if ("heading".equals(type)) {
                StringBuilder text = new StringBuilder();
                JsonNode inline = child.get("content");
                if (inline != null) {
                    for (JsonNode part : inline) {
                        JsonNode value = textOf(part);
                        text.append(value == null ? " " : value.stringValue());
                    }
                }
                sections.add(new Section(sections.size() + 1, Math.min(Math.max(attr(child, "level", 1), 1), 3),
                        text.toString().replaceAll("\\s+", " ").strip()));
            } else if (!"paragraph".equals(type) && !"illustration".equals(type)) {
                collectSections(child, sections);
            }
        }
    }

    /** Ids das imagens das ilustrações do documento, na ordem. */
    static List<String> illustrationIds(JsonNode node) {
        List<String> ids = new java.util.ArrayList<>();
        collectIllustrations(node, ids);
        return ids;
    }

    private static void collectIllustrations(JsonNode node, List<String> ids) {
        if ("illustration".equals(type(node)) && node.get("attrs") != null && node.get("attrs").get("imageId") != null) {
            ids.add(node.get("attrs").get("imageId").stringValue());
        }
        JsonNode content = node.get("content");
        if (content != null) {
            content.forEach(child -> collectIllustrations(child, ids));
        }
    }

    /** O título que o leitor vê: o do capítulo ou, sem título, "Capítulo N". */
    static String chapterTitle(String title, int number) {
        return title == null || title.isBlank() ? "Capítulo " + number : title.strip();
    }

    private static void children(JsonNode node, StringBuilder html) {
        JsonNode content = node.get("content");
        if (content == null) {
            return;
        }
        for (JsonNode child : content) {
            block(child, html);
        }
    }

    private static void block(JsonNode node, StringBuilder html) {
        switch (type(node)) {
            case "paragraph" -> paragraph(node, html);
            case "heading" -> {
                // h1 é o título do capítulo; os títulos internos começam em h2
                int level = Math.min(Math.max(attr(node, "level", 1), 1), 3) + 1;
                int[] count = SECTION_COUNT.get();
                String prefix = SECTION_PREFIX.get();
                html.append("<h").append(level);
                if (count != null) {
                    count[0]++;
                    if (prefix != null) {
                        html.append(" id=\"").append(escape(sectionId(prefix, count[0]))).append('"');
                    }
                }
                html.append('>');
                inline(node, html);
                html.append("</h").append(level).append(">\n");
            }
            case "blockquote" -> {
                html.append("<blockquote>\n");
                children(node, html);
                html.append("</blockquote>\n");
            }
            case "sceneBreak" -> html.append("<p class=\"scene-break\">* * *</p>\n");
            case "illustration" -> illustration(node, html);
            case "bulletList" -> list(node, "ul", html);
            case "orderedList" -> list(node, "ol", html);
            case "listItem" -> {
                html.append("<li>");
                children(node, html);
                html.append("</li>\n");
            }
            default -> children(node, html);
        }
    }

    private static void illustration(JsonNode node, StringBuilder html) {
        JsonNode attrs = node.get("attrs");
        String imageId = attrs.get("imageId").stringValue();
        String caption = attrs.get("caption") == null || attrs.get("caption").isNull() ? "" : attrs.get("caption").stringValue().strip();
        Placement placement = Placement.of(attrs);
        PageIllustration pages = PAGE_ILLUSTRATION.get();
        if (placement.ownPage() && pages != null) {
            String page = pages.render(imageId, placement, caption);
            if (page != null) {
                html.append(page);
                return;
            }
        }
        var src = IMAGE_SRC.get();
        String url = src == null ? null : src.apply(imageId);
        if (url == null && caption.isEmpty()) {
            return; // sem imagem e sem legenda, não sobra nada para mostrar
        }
        html.append("<figure class=\"").append(figureClass(attrs, placement)).append("\">");
        if (url != null) {
            html.append("<img src=\"").append(escape(url)).append("\" alt=\"")
                    .append(escape(caption.isEmpty() ? "Ilustração" : caption)).append("\"/>");
        }
        if (!caption.isEmpty()) {
            html.append("<figcaption>").append(escape(caption)).append("</figcaption>");
        }
        html.append("</figure>\n");
    }

    /** "illustration", mais o tamanho e o lado ("size-small align-left"); sem tamanho, como antes. */
    static String figureClass(JsonNode attrs, Placement placement) {
        if (placement == Placement.AUTO) {
            return "illustration";
        }
        String align = Placement.align(attrs, placement);
        return "illustration size-" + placement.css() + ("center".equals(align) ? "" : " align-" + align);
    }

    private static void paragraph(JsonNode node, StringBuilder html) {
        JsonNode content = node.get("content");
        if (content == null || content.isEmpty()) {
            html.append("<p class=\"blank\">&#160;</p>\n");
            return;
        }
        JsonNode attrs = node.get("attrs");
        boolean indent = attrs != null && attrs.get("indent") != null && attrs.get("indent").asBoolean(false);
        html.append(indent ? "<p class=\"indent\">" : "<p>");
        inline(node, html);
        html.append("</p>\n");
    }

    private static void list(JsonNode node, String tag, StringBuilder html) {
        html.append('<').append(tag);
        if ("ol".equals(tag)) {
            int start = attr(node, "start", 1);
            if (start != 1) {
                html.append(" start=\"").append(start).append('"');
            }
        }
        html.append(">\n");
        children(node, html);
        html.append("</").append(tag).append(">\n");
    }

    private static void inline(JsonNode block, StringBuilder html) {
        JsonNode content = block.get("content");
        if (content == null) {
            return;
        }
        for (JsonNode node : content) {
            if ("hardBreak".equals(type(node))) {
                html.append("<br/>");
                continue;
            }
            JsonNode text = textOf(node);
            if (text == null) {
                continue;
            }
            List<String> marks = marks(node);
            for (String mark : marks) {
                html.append('<').append(tag(mark)).append('>');
            }
            html.append(escape(text.stringValue()));
            for (int i = marks.size() - 1; i >= 0; i--) {
                html.append("</").append(tag(marks.get(i))).append('>');
            }
        }
    }

    /** Texto do nó; a menção ("@Lia") sai só com o nome, como texto comum. */
    static JsonNode textOf(JsonNode node) {
        JsonNode text = node.get("text");
        if (text == null && "mention".equals(type(node)) && node.get("attrs") != null) {
            return node.get("attrs").get("label");
        }
        return text;
    }

    private static List<String> marks(JsonNode node) {
        JsonNode marks = node.get("marks");
        if (marks == null || marks.isEmpty()) {
            return List.of();
        }
        return MARK_ORDER.stream()
                .filter(name -> {
                    for (JsonNode mark : marks) {
                        if (name.equals(type(mark))) {
                            return true;
                        }
                    }
                    return false;
                })
                .toList();
    }

    private static String tag(String mark) {
        return switch (mark) {
            case "bold" -> "strong";
            case "italic" -> "em";
            case "underline" -> "u";
            default -> "s";
        };
    }

    static String type(JsonNode node) {
        JsonNode type = node.get("type");
        return type == null ? "" : type.stringValue();
    }

    private static int attr(JsonNode node, String name, int fallback) {
        JsonNode attrs = node.get("attrs");
        JsonNode value = attrs == null ? null : attrs.get(name);
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    /** Escapa para XHTML e remove caracteres de controle, que deixam o XML inválido. */
    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                default -> {
                    if (c >= 0x20 || c == '\n' || c == '\t') {
                        out.appendCodePoint(c == '\t' ? ' ' : c);
                    }
                }
            }
        });
        return out.toString();
    }
}
