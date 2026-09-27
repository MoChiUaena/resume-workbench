package dev.localresume;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
public class WorkbenchController {
    private final ImageService images;
    private final AttachmentStorage storage;
    private final PreviewService previews;
    private final ExportService exports;
    private final AssetCatalog catalog;
    public WorkbenchController(ImageService images, AttachmentStorage storage, PreviewService previews, ExportService exports, AssetCatalog catalog) {
        this.images = images; this.storage = storage; this.previews = previews; this.exports = exports; this.catalog=catalog;
    }
    @GetMapping("/api/config") public Object config() {
        return Map.of("maxUploadBytes", images.maxBytes, "maxPixels", images.maxPixels,
            "formats", new String[]{"JPEG", "PNG"}, "stage", "B", "schemaVersion", 2);
    }
    @PostMapping("/api/assets") public Object upload(@RequestParam MultipartFile file) throws java.io.IOException {
        if (file.getSize() > images.maxBytes) throw new ApiException("FILE_TOO_LARGE", "图片超过上传限制，请压缩后重试。", 413);
        var asset=images.importImage(file.getBytes()); catalog.register(asset); return asset;
    }
    @GetMapping("/api/assets/{id}/image") public ResponseEntity<byte[]> image(@PathVariable String id) throws java.io.IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(storage.image(id));
    }
    @GetMapping("/api/assets/{id}") public Object metadata(@PathVariable String id) throws java.io.IOException { return storage.metadata(id); }
    @PostMapping("/api/previews") public Object preview(@Valid @RequestBody ResumeDraft draft) {
        var snap = previews.create(draft);
        return Map.of("id", snap.id(), "digest", snap.digest(), "url", "/render/" + snap.id(), "pages", Samples.pages(draft.sample()).size());
    }
    @PostMapping("/api/documents/preview") public Object documentPreview(@Valid @RequestBody ResumeDocument document) {
        var snap=previews.create(document);
        return Map.of("id",snap.id(),"digest",snap.digest(),"url","/render/"+snap.id());
    }
    @GetMapping(value="/render/{id}", produces="text/html;charset=UTF-8") public ResponseEntity<String> render(@PathVariable String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(previews.get(id).html());
    }
    @PostMapping("/api/exports/{snapshotId}") public Object export(@PathVariable String snapshotId) {
        return exports.generate(previews.get(snapshotId));
    }
    @GetMapping("/api/exports/{id}/pdf") public ResponseEntity<byte[]> pdf(@PathVariable String id) throws java.io.IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=resume-workbench.pdf").body(exports.read(id));
    }
}
