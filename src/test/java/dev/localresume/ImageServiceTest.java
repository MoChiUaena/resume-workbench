package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.*;

class ImageServiceTest {
    @TempDir Path temp;
    private ImageService service() { return new ImageService(new LocalFileStorage(temp.toString(), new ObjectMapper()), 5242880, 24000000); }
    private byte[] fixture(String name) throws IOException { return Files.readAllBytes(Path.of("fixtures", name)); }
    @Test void importsSmallTransparentLogoAndStoresOriginalAndNormalizedHashes() throws Exception {
        byte[] bytes = fixture("university-logo.png");
        assertThat(bytes.length).isLessThan(1000000);
        var asset = service().importImage(bytes);
        var storage = new LocalFileStorage(temp.toString(), new ObjectMapper());
        var normalized = ImageIO.read(new ByteArrayInputStream(storage.image(asset.id())));
        assertThat(normalized.getRGB(0, 0) >>> 24).isZero();
        assertThat(asset.width()).isEqualTo(512);
        assertThat(storage.metadata(asset.id()).sha256()).isEqualTo(ImageService.sha(bytes));
        assertThat(asset.normalizedSha256()).isEqualTo(ImageService.sha(storage.image(asset.id())));
        assertThat(Files.readAllBytes(temp.resolve("attachments/" + asset.id() + "/original.png"))).isEqualTo(bytes);
    }
    @Test void appliesExifSixBeforePersisting() throws Exception {
        var asset = service().importImage(fixture("portrait-exif-6.jpg"));
        assertThat(asset.sourceWidth()).isEqualTo(480); assertThat(asset.sourceHeight()).isEqualTo(360);
        assertThat(asset.exifOrientation()).isEqualTo(6);
        assertThat(asset.width()).isEqualTo(360); assertThat(asset.height()).isEqualTo(480);
        var image = ImageIO.read(temp.resolve("attachments/" + asset.id() + "/image.png").toFile());
        var red = new java.awt.Color(image.getRGB(30, 30));
        assertThat(red.getRed()).isGreaterThan(red.getGreen() + 80);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8})
    void allExifOrientationsKeepTheCorrectCorners(int orientation) {
        var source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xffff0000); source.setRGB(2, 0, 0xff00ff00);
        source.setRGB(0, 1, 0xff0000ff); source.setRGB(2, 1, 0xffffffff);
        var image = ImageService.orient(source, orientation);
        int[][] corners = {{0,1,2,3},{1,0,3,2},{3,2,1,0},{2,3,0,1},{0,2,1,3},{2,0,3,1},{3,1,2,0},{1,3,0,2}};
        int[] colors = {0xffff0000,0xff00ff00,0xff0000ff,0xffffffff};
        int[] actual = {image.getRGB(0,0),image.getRGB(image.getWidth()-1,0),image.getRGB(0,image.getHeight()-1),image.getRGB(image.getWidth()-1,image.getHeight()-1)};
        for (int i=0;i<4;i++) assertThat(actual[i]).isEqualTo(colors[corners[orientation-1][i]]);
    }
    @Test void rejectsUnsupportedAndCorruptWithDifferentCodes() throws Exception {
        assertCode("UNSUPPORTED_FORMAT", () -> service().importImage(fixture("unsupported.svg")));
        assertCode("CORRUPT_IMAGE", () -> service().importImage(fixture("corrupt.png")));
        assertCode("EMPTY_FILE", () -> service().importImage(new byte[0]));
    }
    @Test void acceptsExactByteLimitAndRejectsOneByteOver() throws Exception {
        byte[] original = fixture("university-logo.png");
        var service = new ImageService(new LocalFileStorage(temp.toString(), new ObjectMapper()), original.length, 24000000);
        assertThat(service.importImage(original).bytes()).isEqualTo(original.length);
        assertCode("FILE_TOO_LARGE", () -> service.importImage(Arrays.copyOf(original, original.length+1)));
    }
    @Test void checksPixelLimitBeforeDecodingPixels() throws Exception {
        byte[] png = fixture("university-logo.png");
        // Keep only signature and IHDR. Pixel limit must win over the missing image data.
        byte[] header = Arrays.copyOf(png, 33);
        var service = new ImageService(new LocalFileStorage(temp.toString(), new ObjectMapper()), 5242880, 100);
        assertCode("PIXEL_LIMIT", () -> service.importImage(header));
    }
    @Test void explainsStorageFailureWithoutLeakingHostPath() throws Exception {
        Path file = temp.resolve("not-a-directory"); Files.writeString(file, "blocked");
        var service = new ImageService(new LocalFileStorage(file.toString(), new ObjectMapper()), 5242880, 24000000);
        assertCode("STORAGE_FAILED", () -> service.importImage(fixture("university-logo.png")));
    }
    @Test void doesNotAllowPathTraversal() {
        var storage = new LocalFileStorage(temp.toString(), new ObjectMapper());
        assertCode("ASSET_NOT_FOUND", () -> storage.image("../../secret"));
    }
    @Test void losslessWebpKeepsTransparentLogoPixelsAndOriginalBytes() throws Exception {
        byte[] bytes = fixture("university-logo-lossless.webp");
        var imageService = service();
        var source = imageService.importImage(fixture("university-logo.png"));
        var asset = imageService.importImage(bytes);
        var storage = new LocalFileStorage(temp.toString(), new ObjectMapper());
        assertThat(bytes.length).isLessThan(1000000);
        assertThat(asset.format()).isEqualTo("WEBP");
        assertThat(asset.width()).isEqualTo(512);
        assertThat(asset.normalizedSha256()).isEqualTo(source.normalizedSha256());
        assertThat(storage.original(asset.id())).isEqualTo(bytes);
        assertThat(storage.image(asset.id())).isEqualTo(storage.image(source.id()));
    }
    @Test void lossyWebpKeepsAlphaAndImportsPortrait() throws Exception {
        var logo = service().importImage(fixture("university-logo-lossy.webp"));
        var normalized = ImageIO.read(temp.resolve("attachments/" + logo.id() + "/image.png").toFile());
        assertThat(normalized.getRGB(0, 0) >>> 24).isZero();
        assertThat(normalized.getRGB(256, 232) >>> 24).isEqualTo(255);
        var photo = service().importImage(fixture("portrait-lossy.webp"));
        assertThat(photo.format()).isEqualTo("WEBP");
        assertThat(photo.width()).isEqualTo(360); assertThat(photo.height()).isEqualTo(480);
    }
    @Test void appliesWebpExifOrientationWithoutChangingOriginal() throws Exception {
        byte[] bytes = fixture("portrait-exif-6.webp");
        var asset = service().importImage(bytes);
        assertThat(asset.sourceWidth()).isEqualTo(480); assertThat(asset.sourceHeight()).isEqualTo(360);
        assertThat(asset.exifOrientation()).isEqualTo(6);
        assertThat(asset.width()).isEqualTo(360); assertThat(asset.height()).isEqualTo(480);
        var image = ImageIO.read(temp.resolve("attachments/" + asset.id() + "/image.png").toFile());
        var red = new java.awt.Color(image.getRGB(30, 30));
        assertThat(red.getRed()).isGreaterThan(red.getGreen() + 80);
        assertThat(new LocalFileStorage(temp.toString(), new ObjectMapper()).original(asset.id())).isEqualTo(bytes);
    }
    @Test void explainsAnimatedAndCorruptWebpAndRejectsTruncatedOrOverflowChunks() throws Exception {
        assertCode("ANIMATED_WEBP_UNSUPPORTED", () -> service().importImage(fixture("animated.webp")));
        assertCode("CORRUPT_IMAGE", () -> service().importImage(fixture("corrupt.webp")));
        byte[] oversizedChunk = fixture("portrait-lossy.webp");
        ByteBuffer.wrap(oversizedChunk).order(ByteOrder.LITTLE_ENDIAN).putInt(16, -1);
        assertCode("CORRUPT_IMAGE", () -> service().importImage(oversizedChunk));
        byte[] corruptPayload = fixture("portrait-lossy.webp");
        Arrays.fill(corruptPayload, 20, corruptPayload.length, (byte) 0);
        assertCode("CORRUPT_IMAGE", () -> service().importImage(corruptPayload));
    }
    @Test void webpLimitsApplyBeforeBitstreamDecodeAndAtExactByteLimit() throws Exception {
        byte[] original = fixture("university-logo-lossless.webp");
        var bounded = new ImageService(new LocalFileStorage(temp.toString(), new ObjectMapper()), original.length, 24000000);
        assertThat(bounded.importImage(original).bytes()).isEqualTo(original.length);
        assertCode("FILE_TOO_LARGE", () -> bounded.importImage(Arrays.copyOf(original, original.length + 1)));
        var pixels = new ImageService(new LocalFileStorage(temp.toString(), new ObjectMapper()), 5242880, 100);
        assertCode("PIXEL_LIMIT", () -> pixels.importImage(original));
        byte[] header = new byte[26];
        ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(18)
            .put("WEBPVP8L".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(5).put((byte) 0x2f).putInt(12000);
        assertCode("PIXEL_LIMIT", () -> service().importImage(header));
    }
    @Test void refusesMismatchedCanvasAndBitstreamInsteadOfAllocatingFromEither() throws Exception {
        byte[] bytes = fixture("university-logo-lossy.webp");
        assertThat(new String(bytes, 12, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("VP8X");
        bytes[24] = 1; bytes[25] = 0; bytes[26] = 0;
        assertCode("CORRUPT_IMAGE", () -> service().importImage(bytes));
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code).isEqualTo(code); assertThat(e.getMessage()).doesNotContain(temp.toString());
        });
    }
}
