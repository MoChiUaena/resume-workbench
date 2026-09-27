package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class ExportService {
    public record Export(String id, String snapshotId, String digest, String sha256, Instant createdAt) {}
    private final Semaphore capacity = new Semaphore(1);
    private final Path directory;
    private final int port;
    private final ObjectMapper mapper;
    public ExportService(@Value("${resume.data-dir}") String data, @Value("${server.port}") int port, ObjectMapper mapper) {
        this.directory = Path.of(data).toAbsolutePath().resolve("exports"); this.port = port; this.mapper = mapper;
    }
    public Export generate(PreviewService.Snapshot snapshot) {
        if (!capacity.tryAcquire()) throw new ApiException("EXPORT_BUSY", "另一个 PDF 正在生成，请稍后重试。", 429);
        String id = UUID.randomUUID().toString();
        Path pdf = directory.resolve(id + ".pdf");
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setTimeout(25000));
             BrowserContext context = browser.newContext(new Browser.NewContextOptions().setServiceWorkers(ServiceWorkerPolicy.BLOCK))) {
            String base = "http://127.0.0.1:" + port;
            var allowed = new HashSet<String>(List.of(base + "/render/" + snapshot.id(), base + "/print.css",
                base + "/fonts/LocalResumeSans-Regular.ttf", base + "/fonts/LocalResumeSans-Bold.ttf"));
            for (var slot : List.of(snapshot.draft().photo(), snapshot.draft().logo()))
                if (slot.shown()) allowed.add(base + "/api/assets/" + slot.id() + "/image");
            context.route("**/*", route -> { if (allowed.contains(route.request().url())) route.resume(); else route.abort(); });
            Page page = context.newPage();
            page.setDefaultTimeout(20000); page.setDefaultNavigationTimeout(25000);
            page.navigate(base + "/render/" + snapshot.id(), new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD));
            page.emulateMedia(new Page.EmulateMediaOptions().setMedia(Media.PRINT));
            page.waitForFunction("() => document.fonts.status === 'loaded' && document.fonts.check('12px ResumeSans') && Array.from(document.images).every(i => i.complete && i.naturalWidth > 0)");
            boolean fits = (boolean) page.evaluate("() => Array.from(document.querySelectorAll('.sheet')).every(s => s.querySelector('.page-content').getBoundingClientRect().bottom < s.querySelector('.page-footer').getBoundingClientRect().top - 8)");
            if (!fits) throw new ApiException("LAYOUT_OVERFLOW", "当前内容超出 A4 页面，请缩小图片或缩短页眉文字。", 422);
            byte[] bytes = page.pdf(new Page.PdfOptions().setPreferCSSPageSize(true).setPrintBackground(true));
            Files.createDirectories(directory);
            Path temp = directory.resolve(id + ".tmp");
            try {
                Files.write(temp, bytes);
                Files.move(temp, pdf, StandardCopyOption.ATOMIC_MOVE);
                var result = new Export(id, snapshot.id(), snapshot.digest(), ImageService.sha(bytes), Instant.now());
                mapper.writeValue(directory.resolve(id + ".json").toFile(), result);
                return result;
            } finally { Files.deleteIfExists(temp); }
        } catch (ApiException e) { throw e; }
        catch (PlaywrightException e) { throw new ApiException("EXPORT_RENDER_FAILED", "PDF 渲染失败。请确认已安装项目匹配的 Chromium，再重试。", 503); }
        catch (Exception e) { throw new ApiException("EXPORT_STORAGE_FAILED", "PDF 无法写入本地目录，请检查磁盘空间和目录权限。", 507); }
        finally { capacity.release(); }
    }
    public byte[] read(String id) throws java.io.IOException {
        if (!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !Files.exists(directory.resolve(id + ".json")))
            throw new ApiException("EXPORT_NOT_FOUND", "导出文件不存在，请重新导出。", 404);
        return Files.readAllBytes(directory.resolve(id + ".pdf"));
    }
}
