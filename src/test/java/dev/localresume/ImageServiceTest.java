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
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code).isEqualTo(code); assertThat(e.getMessage()).doesNotContain(temp.toString());
        });
    }
}
