package dev.localresume;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.imageio.ImageIO;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ImageService {
    public record Asset(String id, String format, long bytes, int sourceWidth, int sourceHeight,
                        int width, int height, int exifOrientation, String sha256, String normalizedSha256) {}
    private final AttachmentStorage storage;
    public final long maxBytes;
    public final long maxPixels;
    public ImageService(AttachmentStorage storage, @Value("${resume.max-upload-bytes}") long maxBytes,
                        @Value("${resume.max-pixels}") long maxPixels) {
        this.storage = storage; this.maxBytes = maxBytes; this.maxPixels = maxPixels;
        ImageIO.setUseCache(false);
    }
    public Asset importImage(byte[] bytes) {
        if (bytes.length > maxBytes) throw new ApiException("FILE_TOO_LARGE", "图片超过上传限制，请压缩后重试。", 413);
        if (bytes.length == 0) throw new ApiException("EMPTY_FILE", "图片文件为空，请重新选择。", 400);
        String format;
        if (bytes.length >= 8 && bytes[0] == (byte)137 && bytes[1] == 80 && bytes[2] == 78 && bytes[3] == 71
            && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) format = "PNG";
        else if (bytes.length >= 3 && bytes[0] == (byte)255 && bytes[1] == (byte)216 && bytes[2] == (byte)255) format = "JPEG";
        else if (WebpHeader.matches(bytes)) format = "WEBP";
        else throw new ApiException("UNSUPPORTED_FORMAT", "支持 JPEG、PNG 和静态 WebP；请先将 HEIC 或 SVG 转换格式。", 415);
        int sourceWidth, sourceHeight, orientation = 1;
        BufferedImage decoded;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var webp = format.equals("WEBP") ? WebpHeader.read(bytes, maxPixels) : null;
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No decoder");
            var reader = readers.next();
            try {
                reader.setInput(input);
                sourceWidth = reader.getWidth(0); sourceHeight = reader.getHeight(0);
                WebpHeader.checkPixels(sourceWidth, sourceHeight, maxPixels);
                if (webp != null && (webp.width() != sourceWidth || webp.height() != sourceHeight))
                    throw new IOException("WebP reader dimensions do not match container");
                decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != sourceWidth || decoded.getHeight() != sourceHeight)
                    throw new IOException("Invalid decoded dimensions");
            } finally { reader.dispose(); }
            if (format.equals("JPEG") || format.equals("WEBP")) {
                var metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
                var exif = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
                if (exif != null && exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION))
                    orientation = exif.getInt(ExifIFD0Directory.TAG_ORIENTATION);
            }
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("CORRUPT_IMAGE", "无法解码图片，文件可能已损坏，请重新保存为 JPEG、PNG 或静态 WebP。", 422); }
        BufferedImage normalized = orient(decoded, orientation);
        byte[] png;
        try (var out = new ByteArrayOutputStream()) { if (!ImageIO.write(normalized, "png", out)) throw new IOException("No PNG encoder"); png = out.toByteArray(); }
        catch (IOException e) { throw new ApiException("IMAGE_PROCESSING_FAILED", "图片处理失败，请换一张图片重试。", 422); }
        var asset = new Asset(UUID.randomUUID().toString(), format, bytes.length, sourceWidth, sourceHeight,
            normalized.getWidth(), normalized.getHeight(), orientation, sha(bytes), sha(png));
        try { storage.save(asset, bytes, png); }
        catch (IOException e) { throw new ApiException("STORAGE_FAILED", "图片无法写入本地数据目录，请检查磁盘空间和目录权限。", 507); }
        return asset;
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static BufferedImage orient(BufferedImage source, int orientation) {
        int w = source.getWidth(), h = source.getHeight();
        var matrix = switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
            default -> new AffineTransform();
        };
        boolean swap = orientation >= 5 && orientation <= 8;
        var target = new BufferedImage(swap ? h : w, swap ? w : h, BufferedImage.TYPE_INT_ARGB);
        var g = target.createGraphics(); g.drawImage(source, matrix, null); g.dispose(); return target;
    }
}
