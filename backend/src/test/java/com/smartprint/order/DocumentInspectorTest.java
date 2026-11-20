package com.smartprint.order;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentInspectorTest {

    private final DocumentInspector inspector = new DocumentInspector();

    @Test
    void countsPdfPagesFromTheDocument() throws IOException {
        DocumentInspector.Inspection result = inspector.inspect("notes.pdf", pdf(3));

        assertThat(result.pageCount()).isEqualTo(3);
        assertThat(result.mimeType()).isEqualTo("application/pdf");
        assertThat(result.extension()).isEqualTo("pdf");
        assertThat(result.sha256()).hasSize(64);
    }

    @Test
    void treatsImagesAsOnePage() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        DocumentInspector.Inspection result = inspector.inspect("scan.png", png);

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(result.mimeType()).isEqualTo("image/png");
    }

    @Test
    void rejectsFilesWhoseBytesAreNotSupported() {
        byte[] text = "not a document".getBytes();

        assertThatThrownBy(() -> inspector.inspect("notes.txt", text))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a supported document");
    }

    @Test
    void rejectsPdfOverPageLimit() throws IOException {
        assertThatThrownBy(() -> inspector.inspect("large.pdf", pdf(501)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("limit is 500");
    }

    private byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
