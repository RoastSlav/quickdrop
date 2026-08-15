package org.rostislav.quickdrop.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QrCodeServiceTest {

    private final QrCodeService service = new QrCodeService(new SvgRasterizationService());

    @Test
    void renderSvgProducesWellFormedSvgMarkup() {
        String svg = service.renderSvg("https://example.com/s/AbC12", 256, "#000000", "#ffffff");
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.endsWith("</svg>"));
        assertTrue(svg.contains("viewBox"));
    }

    @Test
    void renderSvgEmbedsRequestedSize() {
        String svg = service.renderSvg("https://example.com/s/AbC12", 512, "#000000", "#ffffff");
        assertTrue(svg.contains("width=\"512\""));
        assertTrue(svg.contains("height=\"512\""));
    }

    @Test
    void renderSvgClampsSizeBelowMinimum() {
        String svg = service.renderSvg("https://example.com/s/AbC12", 1, "#000000", "#ffffff");
        assertTrue(svg.contains("width=\"" + QrCodeService.MIN_SIZE_PX + "\""));
    }

    @Test
    void renderSvgClampsSizeAboveMaximum() {
        String svg = service.renderSvg("https://example.com/s/AbC12", 99999, "#000000", "#ffffff");
        assertTrue(svg.contains("width=\"" + QrCodeService.MAX_SIZE_PX + "\""));
    }

    @Test
    void renderSvgUsesRequestedColors() {
        String svg = service.renderSvg("https://example.com/s/AbC12", 256, "#ff0000", "#00ff00");
        assertTrue(svg.contains("#ff0000"));
        assertTrue(svg.contains("#00ff00"));
    }

    @Test
    void renderSvgEscapesColorAttributeValues() {
        String svg = service.renderSvg("https://example.com/s/AbC12", 256, "\"><script>", "#ffffff");
        assertFalse(svg.contains("\"><script>"), "attribute-breaking input must be escaped");
    }

    @Test
    void differentContentProducesDifferentSvg() {
        String a = service.renderSvg("https://example.com/a", 256, "#000000", "#ffffff");
        String b = service.renderSvg("https://example.com/completely-different-page", 256, "#000000", "#ffffff");
        assertNotEquals(a, b);
    }

    @Test
    void blankContentThrows() {
        assertThrows(QrGenerationException.class, () -> service.renderSvg("", 256, "#000000", "#ffffff"));
    }

    @Test
    void nullContentThrows() {
        assertThrows(QrGenerationException.class, () -> service.renderSvg(null, 256, "#000000", "#ffffff"));
    }

    @Test
    void renderPngProducesAValidPngSignature() throws Exception {
        byte[] png = service.renderPng("https://example.com/s/AbC12", 256, "#000000", "#ffffff");
        assertTrue(png.length > 8);
        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
    }
}
