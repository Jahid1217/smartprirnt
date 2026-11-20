package com.smartprint.order;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Determines the real document type from file bytes (never from the client-supplied
 * name or content type), counts printable pages and hashes the original.
 *
 * PDF counts come from the document itself. A JPG/PNG is one page. The order
 * pipeline converts Word documents to PDF before asking this component for a count.
 */
@Component
public class DocumentInspector {
    static final int MAX_PAGES_PER_FILE = 500;

    public record Inspection(String mimeType, String extension, int pageCount, String sha256) {}

    public Inspection inspect(String originalName, byte[] content) {
        String mimeType = detectMimeType(originalName, content);
        int pages = switch (mimeType) {
            case "application/pdf" -> pdfPageCount(originalName, content);
            case "image/jpeg", "image/png" -> 1;
            default -> throw new IllegalArgumentException("Unsupported document type");
        };
        if (pages > MAX_PAGES_PER_FILE) {
            throw new IllegalArgumentException(
                "'" + originalName + "' has " + pages + " pages; the limit is "
                    + MAX_PAGES_PER_FILE + " pages per file");
        }
        return new Inspection(mimeType, extensionFor(mimeType), pages, sha256(content));
    }

    private String detectMimeType(String originalName, byte[] content) {
        if (content.length >= 5
            && new String(content, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            return "application/pdf";
        }
        if (content.length >= 3
            && (content[0] & 0xFF) == 0xFF
            && (content[1] & 0xFF) == 0xD8
            && (content[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (content.length >= 8
            && (content[0] & 0xFF) == 0x89
            && content[1] == 'P' && content[2] == 'N' && content[3] == 'G'
            && (content[4] & 0xFF) == 0x0D && (content[5] & 0xFF) == 0x0A
            && (content[6] & 0xFF) == 0x1A && (content[7] & 0xFF) == 0x0A) {
            return "image/png";
        }
        throw new IllegalArgumentException(
            "'" + originalName + "' is not a supported document. Upload a PDF, JPG or PNG file.");
    }

    private int pdfPageCount(String originalName, byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            int pages = document.getNumberOfPages();
            if (pages <= 0) {
                throw new IllegalArgumentException("'" + originalName + "' contains no printable pages");
            }
            return pages;
        } catch (InvalidPasswordException ex) {
            throw new IllegalArgumentException(
                "'" + originalName + "' is password-protected. Remove the password and upload it again.");
        } catch (IOException ex) {
            throw new IllegalArgumentException(
                "'" + originalName + "' is not a readable PDF. Re-export it and upload it again.");
        }
    }

    private String extensionFor(String mimeType) {
        return switch (mimeType) {
            case "application/pdf" -> "pdf";
            case "image/jpeg" -> "jpg";
            case "image/png" -> "png";
            default -> throw new IllegalArgumentException("Unsupported document type");
        };
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
