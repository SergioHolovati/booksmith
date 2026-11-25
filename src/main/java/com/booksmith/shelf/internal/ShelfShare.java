package com.booksmith.shelf.internal;

import com.booksmith.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A prévia de um link da Estante para quem não roda o app (WhatsApp, Instagram, Telegram, buscadores): uma página HTML
 * mínima com título, descrição e capa nas etiquetas Open Graph. O Caddy manda só esses robôs para cá (pelo
 * User-Agent); pessoas recebem o app. Tudo escapado: o título e a sinopse são do autor.
 */
@Service
public class ShelfShare {

    private static final Pattern WORK = Pattern.compile("^/estante/obra/([a-z0-9-]{1,80})(?:/(\\d{1,4}))?/?$");
    private static final int DESCRIPTION_MAX = 200;

    private final ShelfReading reading;
    private final String siteUrl;

    ShelfShare(ShelfReading reading, @Value("${booksmith.security.frontend-url:http://localhost:4200}") String siteUrl) {
        this.reading = reading;
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    /** O HTML da prévia do caminho ({@code /estante/obra/<slug>} ou {@code /estante/obra/<slug>/<n>}). */
    public String page(String path) {
        Matcher m = WORK.matcher(path == null ? "" : path);
        if (!m.matches()) {
            throw ApiException.notFound("shelf_share_not_found", "Página não encontrada.");
        }
        ShelfReading.WorkCard work = reading.visibleCard(m.group(1));
        String title = work.title();
        String description = work.synopsis();
        if (m.group(2) != null) {
            int number = Integer.parseInt(m.group(2));
            ShelfReading.ChapterView chapter = reading.chapter(work.slug(), number, null);
            title = chapter.title() + " · " + work.title();
            description = "Capítulo " + number + " de " + chapter.total() + ". " + (work.synopsis() == null ? "" : work.synopsis());
        }
        String url = siteUrl + path;
        String image = work.coverUrl() == null || work.coverUrl().isBlank() ? null
                : work.coverUrl().startsWith("http") ? work.coverUrl() : siteUrl + work.coverUrl();
        String text = shorten(description == null || description.isBlank()
                ? "Leia de graça na Estante do Booksmith." : description.strip());
        String byline = "por " + work.authorName() + " · Estante Booksmith";
        StringBuilder html = new StringBuilder(1024)
                .append("<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\">")
                .append("<title>").append(escape(title)).append(" · ").append(escape(byline)).append("</title>")
                .append(meta("name", "description", text))
                .append(meta("property", "og:type", m.group(2) == null ? "book" : "article"))
                .append(meta("property", "og:site_name", "Booksmith"))
                .append(meta("property", "og:locale", "pt_BR"))
                .append(meta("property", "og:title", title))
                .append(meta("property", "og:description", text + " (" + byline + ")"))
                .append(meta("property", "og:url", url))
                .append(meta("name", "twitter:card", image == null ? "summary" : "summary_large_image"));
        if (image != null) {
            html.append(meta("property", "og:image", image)).append(meta("property", "og:image:alt", "Capa de " + work.title()));
        }
        html.append("<link rel=\"canonical\" href=\"").append(escape(url)).append("\">")
                .append("</head><body><h1>").append(escape(title)).append("</h1><p>").append(escape(byline))
                .append("</p><p>").append(escape(text)).append("</p><p><a href=\"").append(escape(url))
                .append("\">Ler na Estante</a></p></body></html>");
        return html.toString();
    }

    static String shorten(String text) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= DESCRIPTION_MAX ? flat : flat.substring(0, DESCRIPTION_MAX - 1).strip() + "…";
    }

    private static String meta(String attribute, String name, String content) {
        return "<meta " + attribute + "=\"" + name + "\" content=\"" + escape(content) + "\">";
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
