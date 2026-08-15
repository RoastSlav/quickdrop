package org.rostislav.quickdrop.service;

import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * Renders QR codes for short links as SVG (hand-built from the raw module matrix, no AWT
 * involved) and PNG (by feeding that SVG through the existing, hardened
 * {@link SvgRasterizationService}).
 *
 * <p>Uses zxing's low-level {@link Encoder} rather than the higher-level {@code
 * QRCodeWriter} — {@code Encoder.encode} returns the QR code at its natural module
 * resolution (e.g. 21x21 for a version-1 code) with no scaling or quiet zone applied,
 * which is exactly what's needed to hand-build a compact SVG: one small {@code <path>}
 * covering every dark module, scaled losslessly by the SVG viewBox rather than by
 * rendering a fixed pixel grid.
 *
 * <p>Serves both upload-share links and (once introduced) redirect links identically —
 * both resolve to a URL, and a QR code is just an encoding of that URL.
 */
@Service
public class QrCodeService {
    /** Standard QR quiet zone: 4 modules of blank space on every side. */
    private static final int QUIET_ZONE_MODULES = 4;
    public static final int DEFAULT_SIZE_PX = 256;
    public static final int MIN_SIZE_PX = 64;
    public static final int MAX_SIZE_PX = 1024;

    private final SvgRasterizationService svgRasterizationService;

    public QrCodeService(SvgRasterizationService svgRasterizationService) {
        this.svgRasterizationService = svgRasterizationService;
    }

    /**
     * Renders {@code content} (typically the short link's full URL) as an SVG QR code.
     *
     * @param content    the text to encode
     * @param sizePx     the rendered width/height in pixels; clamped to
     *                   [{@value #MIN_SIZE_PX}, {@value #MAX_SIZE_PX}]
     * @param foreground CSS color for the dark modules, e.g. {@code #000000}
     * @param background CSS color for the light modules and quiet zone, e.g. {@code #ffffff}
     * @throws QrGenerationException if the content can't be encoded (e.g. exceeds QR capacity)
     */
    public String renderSvg(String content, int sizePx, String foreground, String background) {
        ByteMatrix matrix = encode(content);
        int modules = matrix.getWidth();
        int dimension = modules + 2 * QUIET_ZONE_MODULES;
        int clampedSize = clampSize(sizePx);

        StringBuilder svg = new StringBuilder(256 + modules * modules / 4);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ")
                .append(dimension).append(' ').append(dimension)
                .append("\" width=\"").append(clampedSize).append("\" height=\"").append(clampedSize)
                .append("\" shape-rendering=\"crispEdges\">");
        svg.append("<rect width=\"").append(dimension).append("\" height=\"").append(dimension)
                .append("\" fill=\"").append(escapeAttr(background)).append("\"/>");
        svg.append("<path fill=\"").append(escapeAttr(foreground)).append("\" d=\"");
        for (int y = 0; y < modules; y++) {
            for (int x = 0; x < modules; x++) {
                if (matrix.get(x, y) == 1) {
                    int px = x + QUIET_ZONE_MODULES;
                    int py = y + QUIET_ZONE_MODULES;
                    svg.append('M').append(px).append(' ').append(py)
                            .append("h1v1h-1z");
                }
            }
        }
        svg.append("\"/></svg>");
        return svg.toString();
    }

    /**
     * Renders {@code content} as a PNG by generating the SVG then rasterizing it through
     * {@link SvgRasterizationService#rasterizeToPng} — reuses that service's existing
     * hardening (scripts disabled, external resources blocked, canvas size capped) rather
     * than introducing a second, unaudited image-rendering path.
     */
    public byte[] renderPng(String content, int sizePx, String foreground, String background) throws IOException {
        String svg = renderSvg(content, sizePx, foreground, background);
        return svgRasterizationService.rasterizeToPng(
                new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private ByteMatrix encode(String content) {
        if (content == null || content.isBlank()) {
            throw new QrGenerationException("Nothing to encode.");
        }
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        try {
            QRCode qrCode = Encoder.encode(content, ErrorCorrectionLevel.M, hints);
            return qrCode.getMatrix();
        } catch (WriterException e) {
            throw new QrGenerationException("Couldn't generate a QR code for this link.");
        }
    }

    private int clampSize(int sizePx) {
        return Math.max(MIN_SIZE_PX, Math.min(MAX_SIZE_PX, sizePx));
    }

    private String escapeAttr(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
