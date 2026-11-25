package com.booksmith.export.internal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class WebReadingHtmlTest {

    private final JsonMapper json = new JsonMapper();

    private JsonNode document(String id) {
        return json.readTree("""
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"id":"%s","indent":true},"content":[{"type":"text","text":"Era uma vez."}]}
                ]}""".formatted(id));
    }

    @Test
    void theWebReadingGivesEachParagraphItsBlockId() {
        String html = new WebReadingHtml().chapter(document("b_abc-1"));
        assertThat(html).contains("<p class=\"indent blk blk-b_abc-1\">Era uma vez.</p>");
    }

    @Test
    void theEpubAndPdfKeepParagraphsWithoutIds() {
        assertThat(BookHtml.body(document("b_abc-1"))).contains("<p class=\"indent\">Era uma vez.</p>").doesNotContain("blk");
    }

    @Test
    void aStrangeBlockIdIsLeftOut() {
        String html = new WebReadingHtml().chapter(document("x\\\" onclick=\\\"alert(1)"));
        assertThat(html).doesNotContain("onclick").doesNotContain("blk");
    }
}
