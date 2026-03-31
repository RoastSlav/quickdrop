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

@Service
public class SvgRasterizationService {
    private static final int MAX_DIMENSION = 4096;
    private static final long MAX_PIXELS = 8_000_000L;

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

    private static final class SafePngTranscoder extends PNGTranscoder {
        @Override
        public BufferedImage createImage(int width, int height) {
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || ((long) width * height) > MAX_PIXELS) {
                throw new IllegalArgumentException("SVG preview dimensions exceed safe limits");
            }
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        }

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

