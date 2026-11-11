package com.booksmith.manuscript.internal;

import com.booksmith.common.error.ApiException;
import com.booksmith.manuscript.api.BookLayout;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CoverPdfReaderTest {

    @Test
    void portraitPageIsTheFront() throws Exception {
        var result = CoverPdfReader.read(pdf(mm(154.4), mm(216.4), null), BookLayout.DEFAULT);
        assertThat(result.kind()).isEqualTo("front");
        assertThat(result.widthMm()).isCloseTo(154.4, within(0.1));
        assertThat(result.heightMm()).isCloseTo(216.4, within(0.1));
        assertThat(result.frontImage()).startsWith("data:image/jpeg;base64,");
    }

    @Test
    void landscapePageIsTheWholeCover() throws Exception {
        // A5 aberto: 3 + 148 + 10 (lombada) + 148 + 3 por 3 + 210 + 3
        var result = CoverPdfReader.read(pdf(mm(312), mm(216), null), BookLayout.DEFAULT);
        assertThat(result.kind()).isEqualTo("wrap");
        assertThat(result.widthMm()).isCloseTo(312, within(0.1));
        assertThat(result.pages()).isEqualTo(1);
    }

    @Test
    void protectedOrBrokenPdfIsRejected() throws Exception {
        assertThatThrownBy(() -> CoverPdfReader.read(pdf(mm(148), mm(210), "segredo"), BookLayout.DEFAULT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("cover_pdf_protected"));
        assertThatThrownBy(() -> CoverPdfReader.read("%PDF-1.7 quebrado".getBytes(), BookLayout.DEFAULT))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("cover_pdf_invalid"));
    }

    private static float mm(double mm) {
        return (float) (mm / 25.4 * 72);
    }

    static byte[] pdf(float width, float height, String password) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(width, height)));
            if (password != null) {
                var policy = new StandardProtectionPolicy("dono", password, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
