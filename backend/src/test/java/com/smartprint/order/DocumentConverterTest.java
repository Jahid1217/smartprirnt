package com.smartprint.order;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentConverterTest {
    private final DocumentConverter converter = new DocumentConverter("soffice", 30);

    @Test
    void selectedLandscapePageKeepsPrintableTextAndRendersInGrayscale() throws IOException {
        byte[] input;
        try (PDDocument source = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            source.addPage(new PDPage(PDRectangle.A4));
            PDPage second = new PDPage(PDRectangle.A4);
            source.addPage(second);
            try (var content = new PDPageContentStream(source, second)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                content.newLineAtOffset(32, 80);
                content.showText("Print this page");
                content.endText();
            }
            source.save(bytes);
            input = bytes.toByteArray();
        }
        try (PDDocument rendered = Loader.loadPDF(converter.render(input, List.of(2), true))) {
            assertThat(rendered.getNumberOfPages()).isEqualTo(1);
            var page = rendered.getPage(0).getCropBox();
            assertThat(page.getWidth()).isGreaterThan(page.getHeight());
            assertThat(new PDFTextStripper().getText(rendered)).contains("Print this page");
        }
        BufferedImage preview = ImageIO.read(new ByteArrayInputStream(
            converter.thumbnail(input, 2, true, true)));
        assertThat(preview.getWidth()).isGreaterThan(preview.getHeight());
        assertThat(preview.getColorModel().getNumColorComponents()).isEqualTo(1);
    }

    @Test
    void imageBytesBecomeASinglePrintablePage() throws IOException {
        var input = new BufferedImage(20, 15, BufferedImage.TYPE_INT_RGB);
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(input, "png", bytes);
            var converted = converter.toPdf("photo.png", bytes.toByteArray());
            assertThat(converted.kind()).isEqualTo(DocumentConverter.Kind.IMAGE);
            try (PDDocument rendered = Loader.loadPDF(converted.pdf())) {
                assertThat(rendered.getNumberOfPages()).isEqualTo(1);
            }
        }
    }
}
