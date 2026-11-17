package com.booksmith.export.internal;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * O miolo com as páginas lado a lado, como muitas gráficas pedem o livro ilustrado: cada folha do PDF é o livro
 * aberto (a página par à esquerda, a ímpar à direita), com a primeira página sozinha (ela fica à direita, sem par) e a
 * última sozinha quando o livro tem número par de páginas. Cada lado leva a sua caixa de corte: na folha, o corte vai
 * da borda de corte da página da esquerda à da direita (a sangria, se houver, fica nas bordas de fora).
 */
final class SpreadSheets {

    private SpreadSheets() {
    }

    static byte[] join(byte[] pdf) {
        try (PDDocument source = Loader.loadPDF(pdf); PDDocument target = new PDDocument()) {
            LayerUtility layers = new LayerUtility(target);
            int count = source.getNumberOfPages();
            single(target, layers, source, 0);
            int i = 1;
            for (; i + 1 < count; i += 2) {
                pair(target, layers, source, i, i + 1);
            }
            if (i < count) {
                single(target, layers, source, i);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            target.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void single(PDDocument target, LayerUtility layers, PDDocument source, int index) throws IOException {
        PDPage page = source.getPage(index);
        PDRectangle box = page.getMediaBox();
        PDPage sheet = new PDPage(new PDRectangle(box.getWidth(), box.getHeight()));
        target.addPage(sheet);
        PDFormXObject form = layers.importPageAsForm(source, index);
        try (PDPageContentStream content = new PDPageContentStream(target, sheet)) {
            content.drawForm(form);
        }
        sheet.setBleedBox(sheet.getMediaBox());
        sheet.setTrimBox(page.getTrimBox());
    }

    private static void pair(PDDocument target, LayerUtility layers, PDDocument source, int left, int right)
            throws IOException {
        PDPage l = source.getPage(left);
        PDPage r = source.getPage(right);
        float lw = l.getMediaBox().getWidth();
        float width = lw + r.getMediaBox().getWidth();
        float height = Math.max(l.getMediaBox().getHeight(), r.getMediaBox().getHeight());
        PDPage sheet = new PDPage(new PDRectangle(width, height));
        target.addPage(sheet);
        PDFormXObject leftForm = layers.importPageAsForm(source, left);
        PDFormXObject rightForm = layers.importPageAsForm(source, right);
        try (PDPageContentStream content = new PDPageContentStream(target, sheet)) {
            content.drawForm(leftForm);
            content.saveGraphicsState();
            content.transform(Matrix.getTranslateInstance(lw, 0));
            content.drawForm(rightForm);
            content.restoreGraphicsState();
        }
        PDRectangle lt = l.getTrimBox();
        PDRectangle rt = r.getTrimBox();
        float x0 = lt.getLowerLeftX();
        float x1 = lw + rt.getUpperRightX();
        sheet.setBleedBox(sheet.getMediaBox());
        sheet.setTrimBox(new PDRectangle(x0, lt.getLowerLeftY(), x1 - x0, lt.getHeight()));
    }
}
