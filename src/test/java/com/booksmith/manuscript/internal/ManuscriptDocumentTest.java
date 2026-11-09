package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManuscriptDocumentTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void derivesPlainTextAndWordCount() {
        var document = ManuscriptDocument.parse(json("""
                {"type":"doc","attrs":{"schemaVersion":1},"content":[
                  {"type":"heading","attrs":{"id":"b_head","level":2},"content":[{"type":"text","text":"Capítulo um"}]},
                  {"type":"paragraph","attrs":{"id":"b_p1"},"content":[
                    {"type":"text","text":"Lia ajeitou o cabelo "},
                    {"type":"text","marks":[{"type":"italic"}],"text":"ruivo"},
                    {"type":"hardBreak"},
                    {"type":"text","text":"e saiu-se bem."}]},
                  {"type":"sceneBreak","attrs":{"id":"b_sb"}},
                  {"type":"paragraph","attrs":{"id":"b_p2"}}
                ]}"""), mapper);

        assertThat(document.plainText()).isEqualTo("Capítulo um\nLia ajeitou o cabelo ruivo\ne saiu-se bem.\n* * *");
        // "saiu-se" conta como uma palavra; a quebra de cena não conta
        assertThat(document.wordCount()).isEqualTo(10);
        assertThat(document.hash()).hasSize(64);
    }

    @Test
    void sameContentProducesSameHash() {
        String doc = """
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_1234"},"content":[{"type":"text","text":"oi"}]}]}""";
        assertThat(ManuscriptDocument.parse(json(doc), mapper).hash())
                .isEqualTo(ManuscriptDocument.parse(json(doc), mapper).hash());
    }

    @Test
    void hashIgnoresKeyOrderBecauseJsonbReordersKeys() {
        var asSent = ManuscriptDocument.parse(json("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_1234"},
                  "content":[{"type":"text","text":"oi","marks":[{"type":"bold"}]}]}]}"""), mapper);
        var asReadFromPostgres = ManuscriptDocument.parse(json("""
                {"content":[{"attrs":{"id":"b_1234"},"content":[{"marks":[{"type":"bold"}],"text":"oi","type":"text"}],
                  "type":"paragraph"}],"type":"doc"}"""), mapper);

        assertThat(asReadFromPostgres.hash()).isEqualTo(asSent.hash());
    }

    @Test
    void emptyDocumentIsValid() {
        var empty = ManuscriptDocument.empty(mapper);

        assertThat(empty.wordCount()).isZero();
        assertThat(ManuscriptDocument.parse(json(empty.json()), mapper).hash()).isEqualTo(empty.hash());
    }

    @Test
    void rejectsBlockWithoutId() {
        assertInvalid("""
                {"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"oi"}]}]}""");
    }

    @Test
    void rejectsDuplicatedBlockIds() {
        assertInvalid("""
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"id":"b_same"}},
                  {"type":"paragraph","attrs":{"id":"b_same"}}]}""");
    }

    @Test
    void rejectsUnknownNodesAndMarks() {
        assertInvalid("""
                {"type":"doc","content":[{"type":"codeBlock","attrs":{"id":"b_1234"}}]}""");
        assertInvalid("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_1234"},
                  "content":[{"type":"text","text":"x","marks":[{"type":"link"}]}]}]}""");
        assertInvalid("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_1234","onclick":"x"}}]}""");
    }

    @Test
    void rejectsUnsupportedSchemaVersionAndEmptyDocument() {
        assertInvalid("""
                {"type":"doc","attrs":{"schemaVersion":99},"content":[{"type":"paragraph","attrs":{"id":"b_1234"}}]}""");
        assertInvalid("""
                {"type":"doc","content":[]}""");
    }

    @Test
    void acceptsParagraphIndentAsBooleanOnly() {
        var document = ManuscriptDocument.parse(json("""
                {"type":"doc","content":[
                  {"type":"paragraph","attrs":{"id":"b_rec1","indent":true},"content":[{"type":"text","text":"Com recuo"}]},
                  {"type":"paragraph","attrs":{"id":"b_rec2","indent":false},"content":[{"type":"text","text":"Sem"}]}]}"""),
                mapper);
        assertThat(document.wordCount()).isEqualTo(3);

        assertInvalid("""
                {"type":"doc","content":[{"type":"paragraph","attrs":{"id":"b_rec3","indent":"sim"}}]}""");
        // Recuo é só de parágrafo
        assertInvalid("""
                {"type":"doc","content":[{"type":"heading","attrs":{"id":"b_rec4","level":2,"indent":true}}]}""");
    }

    @Test
    void validatesNestedLists() {
        var document = ManuscriptDocument.parse(json("""
                {"type":"doc","content":[{"type":"bulletList","attrs":{"id":"b_list"},"content":[
                  {"type":"listItem","attrs":{"id":"b_li1"},"content":[
                    {"type":"paragraph","attrs":{"id":"b_p1"},"content":[{"type":"text","text":"um item"}]}]}]}]}"""),
                mapper);

        assertThat(document.wordCount()).isEqualTo(2);
    }

    private void assertInvalid(String doc) {
        assertThatThrownBy(() -> ManuscriptDocument.parse(json(doc), mapper))
                .isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("invalid_document");
    }

    private JsonNode json(String value) {
        return mapper.readTree(value);
    }
}
