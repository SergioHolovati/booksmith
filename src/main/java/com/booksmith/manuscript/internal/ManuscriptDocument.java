package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Documento ProseMirror de um capítulo, validado contra o esquema do Booksmith (§8 da arquitetura).
 * O mesmo esquema existe no editor do frontend ({@code booksmith-web/src/app/features/editor/schema}).
 * Qualquer mudança aqui exige a mesma mudança lá, e vice-versa.
 */
public final class ManuscriptDocument {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** ~2 milhões de caracteres serializados: muito acima de um capítulo real, abaixo de um abuso. */
    static final int MAX_SERIALIZED_LENGTH = 2_000_000;
    private static final int MAX_DEPTH = 12;

    private static final Pattern BLOCK_ID = Pattern.compile("[A-Za-z0-9_-]{4,64}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");

    private static final Set<String> MARKS = Set.of("bold", "italic", "underline", "strike", "commentAnchor");

    /** Trecho com nota do autor: {@code threadId} é o id da nota ({@code manuscript.chapter_notes}). */
    public static final String COMMENT_ANCHOR = "commentAnchor";

    /**
     * Menção a algo do universo, digitada com "@" (§4.1 do produto): {@code id} (UUID), {@code kind} (character,
     * place ou lore) e {@code label} (o nome, que vale como texto: conta palavra e sai na exportação).
     */
    public static final String MENTION = "mention";
    private static final Set<String> MENTION_KINDS = Set.of("character", "place", "lore");
    private static final int MAX_MENTION_LABEL = 120;
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** Atributos permitidos por tipo de bloco. Todo bloco tem {@code id}. */
    private static final Map<String, Set<String>> BLOCK_ATTRS = Map.of(
            "paragraph", Set.of("id", "indent"),
            "heading", Set.of("id", "level"),
            "blockquote", Set.of("id"),
            "sceneBreak", Set.of("id"),
            "bulletList", Set.of("id"),
            "orderedList", Set.of("id", "start", "type"),
            "listItem", Set.of("id"));

    private static final Set<String> TOP_LEVEL_BLOCKS =
            Set.of("paragraph", "heading", "blockquote", "sceneBreak", "bulletList", "orderedList");
    private static final Set<String> LIST_ITEM_BLOCKS =
            Set.of("paragraph", "bulletList", "orderedList", "blockquote");

    private final String json;
    private final String plainText;
    private final int wordCount;
    private final String hash;
    private final int schemaVersion;

    private ManuscriptDocument(String json, String canonicalJson, String plainText, int wordCount, int schemaVersion) {
        this.json = json;
        this.plainText = plainText;
        this.wordCount = wordCount;
        this.hash = sha256(canonicalJson);
        this.schemaVersion = schemaVersion;
    }

    /** Valida e deriva texto puro, contagem de palavras e hash. */
    public static ManuscriptDocument parse(JsonNode root, JsonMapper mapper) {
        if (root == null || !root.isObject()) {
            throw invalid("o documento precisa ser um objeto JSON");
        }

        String json = mapper.writeValueAsString(root);
        if (json.length() > MAX_SERIALIZED_LENGTH) {
            throw ApiException.unprocessable("document_too_large",
                    "O capítulo ficou grande demais. Divida-o em dois capítulos.");
        }

        Validator validator = new Validator();
        int schemaVersion = validator.validateRoot(root);
        String text = validator.text.toString().strip();
        String canonicalJson = mapper.writeValueAsString(canonical(root, mapper));
        return new ManuscriptDocument(json, canonicalJson, text, countWords(text), schemaVersion);
    }

    /**
     * Cópia com as chaves dos objetos em ordem alfabética. O jsonb do Postgres não preserva a ordem das chaves,
     * então o hash precisa ignorá-la: o mesmo conteúdo relido e reenviado não deve gerar versão nova.
     */
    private static JsonNode canonical(JsonNode node, JsonMapper mapper) {
        if (node.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), canonical(entry.getValue(), mapper)));
            return sorted;
        }
        if (node.isArray()) {
            var array = mapper.createArrayNode();
            for (int i = 0; i < node.size(); i++) {
                array.add(canonical(node.get(i), mapper));
            }
            return array;
        }
        return node;
    }

    /** Documento inicial de um capítulo novo: um parágrafo vazio. */
    public static ManuscriptDocument empty(JsonMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "doc");
        root.putObject("attrs").put("schemaVersion", CURRENT_SCHEMA_VERSION);
        ObjectNode paragraph = root.putArray("content").addObject();
        paragraph.put("type", "paragraph");
        paragraph.putObject("attrs").put("id", newBlockId());
        return parse(root, mapper);
    }

    static String newBlockId() {
        return "b_" + UUID.randomUUID().toString().replace("-", "");
    }

    static int countWords(String text) {
        Matcher matcher = WORD.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    public String json() {
        return json;
    }

    public String plainText() {
        return plainText;
    }

    public int wordCount() {
        return wordCount;
    }

    public String hash() {
        return hash;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    private static ApiException invalid(String reason) {
        return ApiException.unprocessable("invalid_document", "Documento inválido: " + reason + ".");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Percorre o documento validando a estrutura e acumulando o texto puro. */
    private static final class Validator {

        private final Set<String> blockIds = new HashSet<>();
        private final StringBuilder text = new StringBuilder();

        int validateRoot(JsonNode root) {
            if (!"doc".equals(type(root))) {
                throw invalid("a raiz precisa ser do tipo \"doc\"");
            }

            int schemaVersion = CURRENT_SCHEMA_VERSION;
            JsonNode attrs = root.get("attrs");
            if (attrs != null && !attrs.isNull()) {
                JsonNode version = attrs.get("schemaVersion");
                if (version != null && (!version.isInt() || version.intValue() != CURRENT_SCHEMA_VERSION)) {
                    throw invalid("versão de esquema não suportada");
                }
            }

            JsonNode content = root.get("content");
            if (content == null || !content.isArray() || content.isEmpty()) {
                throw invalid("o documento precisa ter ao menos um bloco");
            }
            blocks(content, TOP_LEVEL_BLOCKS, 1);
            return schemaVersion;
        }

        private void blocks(JsonNode content, Set<String> allowed, int depth) {
            if (depth > MAX_DEPTH) {
                throw invalid("aninhamento profundo demais");
            }
            for (int i = 0; i < content.size(); i++) {
                block(content.get(i), allowed, depth);
            }
        }

        private void block(JsonNode node, Set<String> allowed, int depth) {
            String type = type(node);
            if (type == null || !allowed.contains(type)) {
                throw invalid("bloco \"%s\" não permitido aqui".formatted(type));
            }
            attrs(node, type);

            JsonNode content = node.get("content");
            switch (type) {
                case "paragraph", "heading" -> {
                    if (content != null && !content.isNull()) {
                        inline(content);
                    }
                    text.append('\n');
                }
                case "sceneBreak" -> {
                    if (content != null && !content.isNull() && !content.isEmpty()) {
                        throw invalid("quebra de cena não tem conteúdo");
                    }
                    text.append("* * *\n");
                }
                case "blockquote" -> blocks(requireNonEmpty(content, type), TOP_LEVEL_BLOCKS, depth + 1);
                case "bulletList", "orderedList" -> blocks(requireNonEmpty(content, type), Set.of("listItem"), depth + 1);
                case "listItem" -> blocks(requireNonEmpty(content, type), LIST_ITEM_BLOCKS, depth + 1);
                default -> throw invalid("bloco desconhecido \"%s\"".formatted(type));
            }
        }

        private void attrs(JsonNode node, String type) {
            JsonNode attrs = node.get("attrs");
            if (attrs == null || !attrs.isObject()) {
                throw invalid("bloco \"%s\" sem atributos (id obrigatório)".formatted(type));
            }

            Set<String> allowedAttrs = BLOCK_ATTRS.get(type);
            for (var property : attrs.properties()) {
                if (!allowedAttrs.contains(property.getKey())) {
                    throw invalid("atributo \"%s\" não permitido em \"%s\"".formatted(property.getKey(), type));
                }
            }

            JsonNode id = attrs.get("id");
            if (id == null || !id.isString() || !BLOCK_ID.matcher(id.stringValue()).matches()) {
                throw invalid("bloco \"%s\" com id ausente ou inválido".formatted(type));
            }
            if (!blockIds.add(id.stringValue())) {
                throw invalid("id de bloco repetido \"%s\"".formatted(id.stringValue()));
            }

            // Recuo da primeira linha (Tab no editor): atributo tipográfico, não caractere de tabulação
            JsonNode indent = attrs.get("indent");
            if (indent != null && !indent.isNull() && !indent.isBoolean()) {
                throw invalid("recuo de parágrafo inválido (use true ou false)");
            }

            if ("heading".equals(type)) {
                JsonNode level = attrs.get("level");
                if (level == null || !level.isInt() || level.intValue() < 1 || level.intValue() > 3) {
                    throw invalid("título com nível inválido (use 1 a 3)");
                }
            }
        }

        private void inline(JsonNode content) {
            if (!content.isArray()) {
                throw invalid("conteúdo de parágrafo precisa ser uma lista");
            }
            for (int i = 0; i < content.size(); i++) {
                JsonNode node = content.get(i);
                String type = type(node);
                if ("hardBreak".equals(type)) {
                    text.append('\n');
                } else if ("text".equals(type)) {
                    JsonNode value = node.get("text");
                    if (value == null || !value.isString() || value.stringValue().isEmpty()) {
                        throw invalid("nó de texto vazio");
                    }
                    marks(node.get("marks"));
                    text.append(value.stringValue());
                } else if (MENTION.equals(type)) {
                    text.append(mention(node));
                } else {
                    throw invalid("elemento \"%s\" não permitido dentro do texto".formatted(type));
                }
            }
        }

        /** Valida a menção e devolve o nome, que entra no texto do capítulo. */
        private static String mention(JsonNode node) {
            JsonNode attrs = node.get("attrs");
            if (attrs == null || !attrs.isObject()) {
                throw invalid("menção sem dados");
            }
            for (String name : attrs.propertyNames()) {
                if (!Set.of("id", "kind", "label").contains(name)) {
                    throw invalid("atributo \"%s\" não permitido na menção".formatted(name));
                }
            }
            JsonNode id = attrs.get("id");
            JsonNode kind = attrs.get("kind");
            JsonNode label = attrs.get("label");
            if (id == null || !id.isString() || !UUID_TEXT.matcher(id.stringValue()).matches()) {
                throw invalid("menção com id inválido");
            }
            if (kind == null || !kind.isString() || !MENTION_KINDS.contains(kind.stringValue())) {
                throw invalid("menção com tipo inválido");
            }
            if (label == null || !label.isString() || label.stringValue().isBlank()
                    || label.stringValue().length() > MAX_MENTION_LABEL) {
                throw invalid("menção sem nome");
            }
            return label.stringValue();
        }

        private void marks(JsonNode marks) {
            if (marks == null || marks.isNull()) {
                return;
            }
            if (!marks.isArray()) {
                throw invalid("formatação inválida");
            }
            for (int i = 0; i < marks.size(); i++) {
                String mark = type(marks.get(i));
                if (mark == null || !MARKS.contains(mark)) {
                    throw invalid("formatação \"%s\" não permitida".formatted(mark));
                }
                if (COMMENT_ANCHOR.equals(mark)) {
                    JsonNode attrs = marks.get(i).get("attrs");
                    JsonNode thread = attrs == null ? null : attrs.get("threadId");
                    if (thread == null || !thread.isString() || !UUID_TEXT.matcher(thread.stringValue()).matches()
                            || attrs.size() != 1) {
                        throw invalid("nota com marca inválida");
                    }
                }
            }
        }

        private static JsonNode requireNonEmpty(JsonNode content, String type) {
            if (content == null || !content.isArray() || content.isEmpty()) {
                throw invalid("\"%s\" precisa ter conteúdo".formatted(type));
            }
            return content;
        }

        private static String type(JsonNode node) {
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode type = node.get("type");
            return type != null && type.isString() ? type.stringValue() : null;
        }
    }
}
