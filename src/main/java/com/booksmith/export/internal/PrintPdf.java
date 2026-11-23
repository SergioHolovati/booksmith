package com.booksmith.export.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Prepara o PDF (em RGB, como sai do OpenHTMLtoPDF) para virar PDF/X-1a em CMYK no Ghostscript:
 * <ul>
 *   <li>tira as anotações (os links do sumário): o PDF/X não aceita, e o Ghostscript desistiria do padrão;</li>
 *   <li>o preto e os cinzas (RGB com os três iguais) viram escala de cinza: o Ghostscript mantém o cinza no
 *       cinza, e ele sai só na chapa preta (K). Sem isso, o texto preto viraria preto composto das quatro tintas, e
 *       a letra borra quando as chapas não se alinham;</li>
 *   <li>toda página ganha o TrimBox (o corte): o PDF/X exige; sem sangria, é a página inteira.</li>
 * </ul>
 */
final class PrintPdf {

    /** Diferença máxima entre R, G e B para contar como cinza (arredondamentos do conversor). */
    private static final float NEUTRAL = 0.004f;

    private PrintPdf() {
    }

    static byte[] prepare(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            Set<COSBase> done = new HashSet<>();
            for (PDPage page : document.getPages()) {
                page.getCOSObject().removeItem(COSName.ANNOTS);
                boxes(page);
                rewritePage(document, page);
                rewriteForms(page.getResources(), done);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** O corte e a sangria de cada página (já arredondados por {@link #prepare}): [TrimBox, BleedBox]. */
    static List<PDRectangle[]> boxesOf(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            List<PDRectangle[]> boxes = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                boxes.add(new PDRectangle[] {page.getTrimBox(), page.getBleedBox()});
            }
            return boxes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Depois do Ghostscript: grava de novo o corte e a sangria (ele não grava no modo PDF/X quando são a página
     * inteira, e o PDF/X exige o corte) e salva sem compactar a estrutura, para continuar PDF 1.3.
     */
    static byte[] finish(byte[] pdfx, List<PDRectangle[]> boxes) {
        try (PDDocument document = Loader.loadPDF(pdfx)) {
            if (document.getNumberOfPages() != boxes.size()) {
                throw new IllegalStateException("O Ghostscript mudou o número de páginas: " + boxes.size() + " → "
                        + document.getNumberOfPages());
            }
            for (int i = 0; i < boxes.size(); i++) {
                PDPage page = document.getPage(i);
                page.setTrimBox(boxes.get(i)[0]);
                page.setBleedBox(boxes.get(i)[1]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out, CompressParameters.NO_COMPRESSION);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * O corte (TrimBox) e a sangria (BleedBox), arredondados para dentro em centésimos de ponto: o Ghostscript grava a
     * página com duas casas, e um corte de 419,5125 numa página de 419,51 "não cabe" e o PDF/X é recusado. Sem sangria,
     * os dois são a página inteira.
     */
    private static void boxes(PDPage page) {
        PDRectangle media = page.getMediaBox();
        PDRectangle bleed = page.getCOSObject().containsKey(COSName.BLEED_BOX) ? page.getBleedBox() : media;
        PDRectangle trim = page.getCOSObject().containsKey(COSName.TRIM_BOX) ? page.getTrimBox() : bleed;
        PDRectangle mediaIn = inward(media, media);
        PDRectangle bleedIn = inward(bleed, mediaIn);
        page.setBleedBox(bleedIn);
        page.setTrimBox(inward(trim, bleedIn));
    }

    /** O retângulo arredondado para dentro (duas casas) e cortado no limite de {@code within}. */
    private static PDRectangle inward(PDRectangle box, PDRectangle within) {
        float x0 = Math.max(ceil(box.getLowerLeftX()), ceil(within.getLowerLeftX()));
        float y0 = Math.max(ceil(box.getLowerLeftY()), ceil(within.getLowerLeftY()));
        float x1 = Math.min(floor(box.getUpperRightX()), floor(within.getUpperRightX()));
        float y1 = Math.min(floor(box.getUpperRightY()), floor(within.getUpperRightY()));
        return new PDRectangle(x0, y0, x1 - x0, y1 - y0);
    }

    private static float floor(float value) {
        return (float) (Math.floor(value * 100 + 1e-3) / 100);
    }

    private static float ceil(float value) {
        return (float) (Math.ceil(value * 100 - 1e-3) / 100);
    }

    private static void rewritePage(PDDocument document, PDPage page) throws IOException {
        List<Object> tokens = neutralToGray(new PDFStreamParser(page).parse());
        PDStream stream = new PDStream(document);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(out).writeTokens(tokens);
        }
        page.setContents(stream);
    }

    /** Os desenhos reaproveitados (form XObjects) também têm cor; cada um é reescrito uma vez. */
    private static void rewriteForms(PDResources resources, Set<COSBase> done) throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject object = resources.getXObject(name);
            if (object instanceof PDFormXObject form && done.add(form.getCOSObject())) {
                List<Object> tokens = neutralToGray(new PDFStreamParser(form).parse());
                try (OutputStream out = form.getContentStream().createOutputStream(COSName.FLATE_DECODE)) {
                    new ContentStreamWriter(out).writeTokens(tokens);
                }
                rewriteForms(form.getResources(), done);
            }
        }
    }

    /** {@code r g b rg} com r = g = b vira {@code r g} (o mesmo para o traço, RG → G). */
    static List<Object> neutralToGray(List<Object> tokens) {
        List<Object> out = new ArrayList<>(tokens.size());
        for (Object token : tokens) {
            if (token instanceof Operator op && ("rg".equals(op.getName()) || "RG".equals(op.getName()))
                    && out.size() >= 3 && out.get(out.size() - 1) instanceof COSNumber b
                    && out.get(out.size() - 2) instanceof COSNumber g && out.get(out.size() - 3) instanceof COSNumber r
                    && Math.abs(r.floatValue() - g.floatValue()) <= NEUTRAL
                    && Math.abs(g.floatValue() - b.floatValue()) <= NEUTRAL) {
                float gray = (r.floatValue() + g.floatValue() + b.floatValue()) / 3;
                out.subList(out.size() - 3, out.size()).clear();
                out.add(new COSFloat(gray));
                out.add(Operator.getOperator("rg".equals(op.getName()) ? "g" : "G"));
            } else {
                out.add(token);
            }
        }
        return out;
    }
}
