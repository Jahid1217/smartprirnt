package com.smartprint.order;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.LayerUtility;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Converts uploaded documents into a print-ready PDF. PDFs pass through, images are
 * placed on an A4 page, and Word documents are rendered with LibreOffice. The
 * browser never prints the original file: the print agent always receives this PDF.
 */
@Component
public class DocumentConverter {
    private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
        (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};

    private final String libreOfficePath;
    private final Duration conversionTimeout;

    public DocumentConverter(
            @Value("${smartprint.documents.libreoffice-path:soffice}") String libreOfficePath,
            @Value("${smartprint.documents.conversion-timeout-seconds:120}") long timeoutSeconds) {
        this.libreOfficePath = libreOfficePath;
        this.conversionTimeout = Duration.ofSeconds(Math.max(10, timeoutSeconds));
    }

    public enum Kind { PDF, WORD, IMAGE }

    public record Converted(Kind kind, String mimeType, byte[] pdf) {}

    public Converted toPdf(String originalName, byte[] content) {
        return toPdf(originalName, content, detectKind(originalName, content));
    }

    Converted toPdf(String originalName, byte[] content, Kind kind) {
        byte[] pdf = switch (kind) {
            case PDF -> content;
            case IMAGE -> imageToPdf(content);
            case WORD -> wordToPdf(originalName, content);
        };
        if (!isPdf(pdf)) {
            throw new IllegalArgumentException("'" + originalName + "' could not be converted to PDF");
        }
        return new Converted(kind, originalMimeType(kind, content), pdf);
    }

    private String originalMimeType(Kind kind, byte[] content) {
        return switch (kind) {
            case PDF -> "application/pdf";
            case IMAGE -> isPng(content) ? "image/png" : "image/jpeg";
            case WORD -> isOle2(content) ? "application/msword"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        };
    }

    /** Applies the customer's page selection and orientation to a copy of the PDF. */
    public byte[] render(byte[] pdf, List<Integer> pages, boolean landscape) {
        try (PDDocument source = Loader.loadPDF(pdf);
             PDDocument target = new PDDocument()) {
            LayerUtility layers = new LayerUtility(target);
            for (int pageNumber : pages) {
                if (pageNumber < 1 || pageNumber > source.getNumberOfPages()) {
                    throw new IllegalArgumentException("Page " + pageNumber + " is outside the document");
                }
                PDPage original = source.getPage(pageNumber - 1);
                PDRectangle box = original.getCropBox();
                boolean rotated = original.getRotation() % 180 != 0;
                float width = rotated ? box.getHeight() : box.getWidth();
                float height = rotated ? box.getWidth() : box.getHeight();
                if (width == height || (width > height) == landscape) {
                    PDPage imported = target.importPage(original);
                    imported.setResources(original.getResources());
                    continue;
                }

                // Change the sheet dimensions while keeping its content upright.
                PDPage sheet = new PDPage(new PDRectangle(height, width));
                target.addPage(sheet);
                float scale = Math.min(height / width, width / height);
                var form = layers.importPageAsForm(source, original);
                try (PDPageContentStream stream = new PDPageContentStream(target, sheet)) {
                    stream.transform(new Matrix(scale, 0, 0, scale,
                        (height - width * scale) / 2, (width - height * scale) / 2));
                    stream.drawForm(form);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            target.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("The PDF could not be prepared for printing", ex);
        }
    }

    public byte[] thumbnail(byte[] pdf, int page, boolean landscape, boolean grayscale) {
        try (PDDocument document = Loader.loadPDF(render(pdf, List.of(page), landscape));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage sheet = document.getPage(0);
            float longestEdge = Math.max(sheet.getCropBox().getWidth(), sheet.getCropBox().getHeight());
            var image = new PDFRenderer(document).renderImage(
                0, Math.min(1.25f, 880f / longestEdge), grayscale
                    ? ImageType.GRAY : ImageType.RGB);
            ImageIO.write(image, "jpeg", output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("The document preview could not be generated", ex);
        }
    }

    public Kind detectKind(String originalName, byte[] content) {
        if (isPdf(content)) return Kind.PDF;
        if (isJpeg(content) || isPng(content)) return Kind.IMAGE;
        if (isOle2(content) || isDocx(content)) return Kind.WORD;
        throw new IllegalArgumentException("'" + originalName
            + "' is not a supported document. Upload a PDF, DOC, DOCX, JPG or PNG file.");
    }

    public boolean isPdf(byte[] content) {
        return content != null && content.length >= 5
            && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-");
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
            && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] content) {
        return content.length >= 8
            && (content[0] & 0xFF) == 0x89 && content[1] == 'P' && content[2] == 'N'
            && content[3] == 'G' && (content[4] & 0xFF) == 0x0D && (content[5] & 0xFF) == 0x0A
            && (content[6] & 0xFF) == 0x1A && (content[7] & 0xFF) == 0x0A;
    }

    private boolean isOle2(byte[] content) {
        if (content.length < OLE2.length) return false;
        for (int i = 0; i < OLE2.length; i++) {
            if (content[i] != OLE2[i]) return false;
        }
        return true;
    }

    private boolean isDocx(byte[] content) {
        if (content.length < 4 || content[0] != 'P' || content[1] != 'K') return false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            boolean document = false;
            boolean contentTypes = false;
            int entries = 0;
            long expandedSize = 0;
            byte[] buffer = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 4096) return false;
                document |= "word/document.xml".equals(entry.getName());
                contentTypes |= "[Content_Types].xml".equals(entry.getName());
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expandedSize += count;
                    if (expandedSize > 128L * 1024 * 1024) return false;
                }
            }
            return document && contentTypes;
        } catch (IOException ex) {
            return false;
        }
    }

    private byte[] imageToPdf(byte[] content) {
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(content));
             PDDocument document = new PDDocument()) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("The image could not be read");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 25_000_000) {
                    throw new IllegalArgumentException("Images must be 25 megapixels or smaller");
                }
            } finally {
                reader.dispose();
            }
            PDImageXObject image = PDImageXObject.createFromByteArray(document, content, "upload");
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            float margin = 18f;
            float availableWidth = page.getMediaBox().getWidth() - margin * 2;
            float availableHeight = page.getMediaBox().getHeight() - margin * 2;
            float scale = Math.min(availableWidth / image.getWidth(),
                availableHeight / image.getHeight());
            float width = image.getWidth() * scale;
            float height = image.getHeight() * scale;
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.drawImage(image,
                    margin + (availableWidth - width) / 2,
                    margin + (availableHeight - height) / 2,
                    width, height);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalArgumentException("The image could not be converted to PDF", ex);
        }
    }

    private byte[] wordToPdf(String originalName, byte[] content) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("smartprint-doc-");
            boolean legacy = isOle2(content);
            String extension = legacy ? ".doc" : ".docx";
            Path input = workDir.resolve("source" + extension);
            Files.write(input, content);

            Process process = new ProcessBuilder(
                libreOfficePath, "-env:UserInstallation=" + workDir.resolve("profile").toUri(),
                "--headless", "--norestore",
                legacy ? "--infilter=MS Word 97" : "--infilter=Office Open XML Text",
                "--convert-to", "pdf",
                "--outdir", workDir.toString(), input.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            boolean finished = process.waitFor(conversionTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalArgumentException("Converting '" + originalName
                    + "' took too long. Save it as PDF and upload it again.");
            }
            Path output = workDir.resolve("source.pdf");
            if (process.exitValue() != 0 || !Files.exists(output)) {
                log.warn("LibreOffice could not convert '{}' (exit {})", originalName, process.exitValue());
                throw new IllegalArgumentException("'" + originalName
                    + "' could not be converted. Save it as PDF and upload it again.");
            }
            if (Files.size(output) > 40 * 1024 * 1024) {
                throw new IllegalArgumentException("The converted PDF is larger than 40 MB");
            }
            return Files.readAllBytes(output);
        } catch (IOException ex) {
            log.warn("Word conversion is unavailable: {}", ex.getMessage());
            throw new IllegalArgumentException("Word documents cannot be converted on this server. "
                + "Save the file as PDF and upload it again.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("The document conversion was interrupted");
        } finally {
            deleteTree(workDir);
        }
    }

    private void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    log.debug("Could not delete temporary path {}", path);
                }
            });
        } catch (IOException ex) {
            log.debug("Could not clear temporary conversion directory: {}", ex.getMessage());
        }
    }
}
