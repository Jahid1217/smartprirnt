package com.smartprint.shop;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class QrCodeService {
    private final String publicBaseUrl;

    public QrCodeService(@Value("${smartprint.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }

    public String publicUrl(Shop shop) {
        return publicBaseUrl + "/s/" + shop.getQrToken();
    }

    public byte[] png(Shop shop) {
        return pngFor(publicUrl(shop), 512);
    }

    public byte[] pngFor(String content, int size) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException ex) {
            throw new IllegalStateException("Could not generate the shop QR code", ex);
        }
    }
}
