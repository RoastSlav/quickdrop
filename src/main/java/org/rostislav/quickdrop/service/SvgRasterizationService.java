package org.rostislav.quickdrop.service;

import org.apache.batik.bridge.UserAgent;
import org.apache.batik.bridge.UserAgentAdapter;
import org.apache.batik.transcoder.SVGAbstractTranscoder;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.batik.util.ParsedURL;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Transcodes SVG files to PNG bitmaps using Apache Batik.
 *
 * <p>Script execution and external resource loading are disabled. Any attempt to load
 * a script or external resource throws {@link SecurityException}, wrapped as
 * {@link IOException}. Canvas dimensions are capped at
 * {@value #MAX_DIMENSION}×{@value #MAX_DIMENSION} px and {@value #MAX_PIXELS} total pixels.
 */
@Service
public class SvgRasterizationService {
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 8_000_000L;

    /**
     * Transcodes the SVG content from {@code svgInputStream} into a PNG byte array.
     * The stream is always closed before this method returns.
     *
     * @param svgInputStream the SVG content to transcode
     * @return PNG-encoded bytes
     * @throws IOException if transcoding fails, including malformed XML, blocked scripts,
     *                     blocked external resources, or canvas dimensions exceeding the limits
     */
    public byte[] rasterizeToPng(InputStream svgInputStream) throws IOException {
        try (InputStream in = svgInputStream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            SafePngTranscoder transcoder = new SafePngTranscoder();
            transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_EXECUTE_ONLOAD, Boolean.FALSE);
            transcoder.addTranscodingHint(SVGAbstractTranscoder.KEY_CONSTRAIN_SCRIPT_ORIGIN, Boolean.TRUE);
            transcoder.transcode(new TranscoderInput(in), new TranscoderOutput(out));
            return out.toByteArray();
        } catch (TranscoderException | RuntimeException ex) {
            throw new IOException("Failed to rasterize SVG preview", ex);
        }
    }

    /** {@link PNGTranscoder} with script execution, external resources, and canvas size restricted. */
    private static final class SafePngTranscoder extends PNGTranscoder {
        /**
         * Returns a {@link BufferedImage} for the given dimensions, or throws
         * {@link IllegalArgumentException} if either axis exceeds {@link #MAX_DIMENSION}
         * or the total pixel count exceeds {@link #MAX_PIXELS}.
         */
        @Override
        public BufferedImage createImage(int width, int height) {
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || ((long) width * height) > MAX_PIXELS) {
                throw new IllegalArgumentException("SVG preview dimensions exceed safe limits");
            }
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

        /**
         * Returns a {@link UserAgent} whose {@code checkLoadScript} and
         * {@code checkLoadExternalResource} methods always throw {@link SecurityException}.
         */
        @Override
        protected UserAgent createUserAgent() {
            return new UserAgentAdapter() {
                @Override
                public void checkLoadScript(String scriptType, ParsedURL scriptURL, ParsedURL docURL) {
                    throw new SecurityException("SVG scripts are not allowed in previews");
                }

                @Override
                public void checkLoadExternalResource(ParsedURL resourceURL, ParsedURL docURL) {
                    throw new SecurityException("External SVG resources are not allowed in previews");
                }
            };
        }
    }
}

