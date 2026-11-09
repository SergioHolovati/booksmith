package com.booksmith.manuscript.internal;

import com.booksmith.manuscript.internal.ImportedText.Paragraph;
import com.booksmith.manuscript.internal.ImportedText.Result;
import com.booksmith.manuscript.internal.ImportedText.Segment;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Block;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomNode;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lê texto puro (.txt) e Markdown (.md).
 * <p>
 * <b>.txt</b>: cada linha é um parágrafo. Se as linhas foram quebradas numa largura fixa (e-mails, arquivos
 * antigos, com uma linha em branco entre parágrafos), as linhas de cada bloco são juntadas. Linha que começa
 * com Tab ou dois espaços tem recuo.
 * <p>
 * <b>.md</b>: títulos ({@code #}, {@code ##}), itálico, negrito, tachado ({@code ~~}), {@code <u>} para
 * sublinhado, linha horizontal como quebra de cena. Blocos de código recuados ficam desligados: quem escreve
 * ficção recua parágrafos com Tab, e isso não pode virar código. Um único {@code #} no começo, com os capítulos
 * em {@code ##}, é o título do livro.
 */
final class TextReader {

    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final String SCENE_BREAK = "* * *";
    /** Largura típica de texto quebrado à mão; linhas maiores que isso são parágrafos inteiros. */
    private static final int WRAP_WIDTH = 90;

    private TextReader() {
    }

    // ===== Decodificação =====

    /** UTF-8 (com ou sem BOM), UTF-16 com BOM, ou Windows-1252 (o "ANSI" do Bloco de Notas antigo). */
    static String decode(byte[] file) {
        String text;
        if (file.length >= 2 && (file[0] & 0xFF) == 0xFF && (file[1] & 0xFF) == 0xFE) {
            text = new String(file, 2, file.length - 2, StandardCharsets.UTF_16LE);
        } else if (file.length >= 2 && (file[0] & 0xFF) == 0xFE && (file[1] & 0xFF) == 0xFF) {
            text = new String(file, 2, file.length - 2, StandardCharsets.UTF_16BE);
        } else {
            boolean bom = file.length >= 3 && (file[0] & 0xFF) == 0xEF && (file[1] & 0xFF) == 0xBB
                    && (file[2] & 0xFF) == 0xBF;
            int start = bom ? 3 : 0;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(file, start, file.length - start))
                        .toString();
            } catch (CharacterCodingException e) {
                text = new String(file, WINDOWS_1252);
            }
        }
        if (text.indexOf('\0') >= 0) {
            throw ImportFormat.unsupported();
        }
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ===== Texto puro =====

    static Result plain(String text) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                if (!block.isEmpty()) {
                    blocks.add(block);
                    block = new ArrayList<>();
                }
            } else {
                block.add(line);
            }
        }
        if (!block.isEmpty()) {
            blocks.add(block);
        }

        List<Paragraph> paragraphs = new ArrayList<>();
        boolean wrapped = blocks.size() > 1 && isHardWrapped(blocks);
        for (List<String> lines : blocks) {
            if (wrapped) {
                StringBuilder joined = new StringBuilder();
                for (String line : lines) {
                    joined.append(joined.isEmpty() ? "" : " ").append(clean(line));
                }
                paragraphs.add(Paragraph.plain(indented(lines.getFirst()), joined.toString()));
            } else {
                for (String line : lines) {
                    paragraphs.add(Paragraph.plain(indented(line), clean(line)));
                }
            }
        }
        return new Result(List.copyOf(paragraphs), 0, 0, 0);
    }

    /**
     * Texto quebrado numa largura fixa: as linhas que não fecham o bloco são quase todas curtas, e a maioria chega
     * perto da largura. Diálogos curtos, um por linha, não passam no segundo teste.
     */
    private static boolean isHardWrapped(List<List<String>> blocks) {
        int inner = 0;
        int narrow = 0;
        int full = 0;
        for (List<String> block : blocks) {
            for (int i = 0; i < block.size() - 1; i++) {
                int length = block.get(i).strip().length();
                inner++;
                if (length <= WRAP_WIDTH) {
                    narrow++;
                }
                if (length >= 50) {
                    full++;
                }
            }
        }
        return inner > 0 && narrow * 10 >= inner * 9 && full * 2 >= inner;
    }

    private static boolean indented(String line) {
        return line.startsWith("\t") || line.startsWith("  ");
    }

    private static String clean(String line) {
        return line.strip().replace('\t', ' ');
    }

    // ===== Markdown =====

    static Result markdown(String text) {
        Parser parser = Parser.builder()
                .extensions(List.of(StrikethroughExtension.create()))
                .enabledBlockTypes(Set.<Class<? extends Block>>of(Heading.class, HtmlBlock.class, ThematicBreak.class,
                        FencedCodeBlock.class, BlockQuote.class, ListBlock.class))
                .includeSourceSpans(IncludeSourceSpans.BLOCKS)
                .build();
        MarkdownWalker walker = new MarkdownWalker(text.split("\n", -1));
        walker.blocks(parser.parse(text));
        return new Result(walker.titled(), walker.images, 0, 0);
    }

    private static final class MarkdownWalker {

        private static final Map<String, String> HTML_MARKS = Map.of(
                "u", "underline", "ins", "underline", "i", "italic", "em", "italic", "b", "bold", "strong", "bold",
                "s", "strike", "del", "strike");

        private final String[] lines;
        private final List<Paragraph> paragraphs = new ArrayList<>();
        private int images;

        MarkdownWalker(String[] lines) {
            this.lines = lines;
        }

        void blocks(Node parent) {
            for (Node node = parent.getFirstChild(); node != null; node = node.getNext()) {
                block(node, null);
            }
        }

        private void block(Node node, String prefix) {
            switch (node) {
                case Heading heading -> {
                    List<Segment> segments = inline(heading, null);
                    if (segments.isEmpty()) {
                        // "#" sozinho numa linha é quebra de cena em muito manuscrito, não um título vazio
                        paragraphs.add(Paragraph.plain(false, SCENE_BREAK));
                    } else {
                        paragraphs.add(new Paragraph(heading.getLevel(), false, false, false, segments));
                    }
                }
                case org.commonmark.node.Paragraph paragraph -> paragraphs.add(new Paragraph(0, false, false,
                        prefix == null && indented(paragraph), inline(paragraph, prefix)));
                case ThematicBreak ignored -> paragraphs.add(Paragraph.plain(false, SCENE_BREAK));
                case BlockQuote quote -> blocks(quote);
                case ListBlock list -> {
                    int number = list instanceof OrderedList ordered && ordered.getStartNumber() > 0
                            ? ordered.getStartNumber() : 1;
                    for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                        String marker = list instanceof BulletList ? "• " : (number++) + ". ";
                        boolean first = true;
                        for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                            block(child, first ? marker : null);
                            first = false;
                        }
                    }
                }
                case FencedCodeBlock code -> {
                    List<Segment> segments = new ArrayList<>();
                    for (String line : code.getLiteral().stripTrailing().split("\n", -1)) {
                        if (!segments.isEmpty()) {
                            segments.add(Segment.lineBreak());
                        }
                        segments.add(new Segment(line, Set.of()));
                    }
                    paragraphs.add(new Paragraph(0, false, false, false, ImportedText.trim(segments)));
                }
                case ListItem item -> blocks(item);
                default -> {
                    // HTML solto (comentários, por exemplo): não é texto do livro
                }
            }
        }

        /** O parágrafo foi escrito com Tab ou dois espaços no começo da linha. */
        private boolean indented(Node node) {
            if (node.getSourceSpans().isEmpty()) {
                return false;
            }
            int line = node.getSourceSpans().getFirst().getLineIndex();
            return line < lines.length && TextReader.indented(lines[line]);
        }

        private List<Segment> inline(Node parent, String prefix) {
            List<Segment> segments = new ArrayList<>();
            if (prefix != null) {
                segments.add(new Segment(prefix, Set.of()));
            }
            Set<String> marks = new LinkedHashSet<>();
            parent.accept(new AbstractVisitor() {
                @Override
                public void visit(Text text) {
                    segments.add(new Segment(text.getLiteral(), Set.copyOf(marks)));
                }

                @Override
                public void visit(Code code) {
                    segments.add(new Segment(code.getLiteral(), Set.copyOf(marks)));
                }

                @Override
                public void visit(SoftLineBreak softLineBreak) {
                    segments.add(new Segment(" ", Set.copyOf(marks)));
                }

                @Override
                public void visit(HardLineBreak hardLineBreak) {
                    segments.add(Segment.lineBreak());
                }

                @Override
                public void visit(Emphasis emphasis) {
                    withMark("italic", emphasis);
                }

                @Override
                public void visit(StrongEmphasis strong) {
                    withMark("bold", strong);
                }

                @Override
                public void visit(CustomNode node) {
                    if (node instanceof Strikethrough) {
                        withMark("strike", node);
                    } else {
                        visitChildren(node);
                    }
                }

                @Override
                public void visit(Image image) {
                    images++;
                }

                @Override
                public void visit(HtmlInline html) {
                    String literal = html.getLiteral();
                    String tag = literal.toLowerCase(Locale.ROOT).replaceAll("[<>/\\s]", "");
                    String mark = HTML_MARKS.get(tag);
                    if (mark == null) {
                        return;
                    }
                    if (literal.startsWith("</")) {
                        marks.remove(mark);
                    } else {
                        marks.add(mark);
                    }
                }

                private void withMark(String mark, Node node) {
                    boolean added = marks.add(mark);
                    visitChildren(node);
                    if (added) {
                        marks.remove(mark);
                    }
                }
            });
            return ImportedText.trim(ImportedText.merge(segments));
        }

        /**
         * Um único {@code #} no começo, com os capítulos em níveis abaixo, é o nome do livro (como o estilo "Título"
         * do Word).
         */
        List<Paragraph> titled() {
            List<Paragraph> result = new ArrayList<>(paragraphs);
            long topLevel = result.stream().filter(p -> p.headingLevel() == 1).count();
            boolean deeper = result.stream().anyMatch(p -> p.headingLevel() > 1);
            if (!result.isEmpty() && result.getFirst().headingLevel() == 1 && topLevel == 1 && deeper) {
                result.set(0, new Paragraph(0, true, false, false, result.getFirst().segments()));
            }
            return List.copyOf(result);
        }
    }
}
