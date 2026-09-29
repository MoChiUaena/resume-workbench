package dev.localresume;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Inspect the bounded RIFF container and both canvas/bitstream dimensions before decoding. */
final class WebpHeader {
    record Dimensions(int width, int height) {}
    private WebpHeader() {}
    static boolean matches(byte[] bytes) {
        return bytes.length >= 12 && fourcc(bytes, 0).equals("RIFF") && fourcc(bytes, 8).equals("WEBP");
    }
    static Dimensions read(byte[] bytes, long maxPixels) throws IOException {
        if (!matches(bytes) || bytes.length < 20 || little(bytes, 4, 4) + 8 != bytes.length)
            throw new IOException("Invalid WebP container");
        Dimensions canvas = null, image = null;
        for (int offset = 12; offset < bytes.length;) {
            if (bytes.length - offset < 8) throw new IOException("Truncated WebP chunk");
            String chunk = fourcc(bytes, offset);
            long size = little(bytes, offset + 4, 4), padded = size + (size & 1);
            if (padded > bytes.length - offset - 8L) throw new IOException("Invalid WebP chunk size");
            int start = offset + 8;
            if (chunk.equals("VP8X")) {
                if (offset != 12 || size != 10 || canvas != null) throw new IOException("Invalid WebP canvas");
                canvas = new Dimensions((int) little(bytes, start + 4, 3) + 1, (int) little(bytes, start + 7, 3) + 1);
                checkPixels(canvas.width(), canvas.height(), maxPixels);
                if ((bytes[start] & 2) != 0) throw animated();
            } else if (chunk.equals("ANIM") || chunk.equals("ANMF")) {
                throw animated();
            } else if (chunk.equals("VP8 ") || chunk.equals("VP8L")) {
                if (image != null) throw new IOException("Multiple static WebP images");
                if (chunk.equals("VP8L")) {
                    if (size < 5 || bytes[start] != 0x2f) throw new IOException("Invalid lossless WebP header");
                    long bits = little(bytes, start + 1, 4);
                    if ((bits >>> 29) != 0) throw new IOException("Unsupported lossless WebP version");
                    image = new Dimensions((int) (bits & 0x3fff) + 1, (int) ((bits >>> 14) & 0x3fff) + 1);
                } else {
                    if (size < 10 || (bytes[start] & 1) != 0 || bytes[start + 3] != (byte) 0x9d
                        || bytes[start + 4] != 1 || bytes[start + 5] != 0x2a)
                        throw new IOException("Invalid lossy WebP header");
                    image = new Dimensions((int) little(bytes, start + 6, 2) & 0x3fff, (int) little(bytes, start + 8, 2) & 0x3fff);
                }
                checkPixels(image.width(), image.height(), maxPixels);
            }
            offset += 8 + (int) padded;
        }
        if (image == null || canvas != null && !canvas.equals(image)) throw new IOException("WebP canvas does not match image");
        return image;
    }
    static void checkPixels(int width, int height, long maxPixels) {
        if (width <= 0 || height <= 0 || width > 12000 || height > 12000 || (long) width * height > maxPixels)
            throw new ApiException("PIXEL_LIMIT", "图片像素超限，请缩小至允许的像素数量以内，且单边不超过 12000 像素。", 413);
    }
    private static ApiException animated() {
        return new ApiException("ANIMATED_WEBP_UNSUPPORTED", "暂不支持动态 WebP，请导出为静态图片后重试。", 415);
    }
    private static String fourcc(byte[] bytes, int start) { return new String(bytes, start, 4, StandardCharsets.US_ASCII); }
    private static long little(byte[] bytes, int start, int count) {
        long value = 0; for (int i = 0; i < count; i++) value |= (long) (bytes[start + i] & 255) << (8 * i); return value;
    }
}
