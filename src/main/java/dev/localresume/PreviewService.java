package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service
public class PreviewService {
    public record Snapshot(String id, String digest, ResumeDraft draft, String html, Instant createdAt) {}
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final TemplateEngine templates;
    private final AttachmentStorage storage;
    private final ObjectMapper mapper;
    public PreviewService(TemplateEngine templates, AttachmentStorage storage, ObjectMapper mapper) {
        this.templates = templates; this.storage = storage; this.mapper = mapper;
    }
    public synchronized Snapshot create(ResumeDraft draft) {
        try {
            for (var slot : List.of(draft.photo(), draft.logo())) if (slot.id() != null) storage.metadata(slot.id());
            var ctx = new Context(Locale.SIMPLIFIED_CHINESE);
            ctx.setVariable("draft", draft);
            ctx.setVariable("pages", Samples.pages(draft.sample()));
            ctx.setVariable("left", draft.swapImages() ? draft.logo() : draft.photo());
            ctx.setVariable("right", draft.swapImages() ? draft.photo() : draft.logo());
            String html = templates.process("resume", ctx);
            var result = new Snapshot(UUID.randomUUID().toString(), ImageService.sha(mapper.writeValueAsBytes(draft)), draft, html, Instant.now());
            snapshots.values().removeIf(s -> s.createdAt().isBefore(Instant.now().minusSeconds(1800)));
            if (snapshots.size() >= 64) snapshots.remove(snapshots.keySet().iterator().next());
            snapshots.put(result.id(), result);
            return result;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException("PREVIEW_FAILED", "预览生成失败，请检查图片后重试。", 500); }
    }
    public synchronized Snapshot get(String id) {
        var snapshot = snapshots.get(id);
        if (snapshot == null || snapshot.createdAt().isBefore(Instant.now().minusSeconds(1800)))
            throw new ApiException("PREVIEW_EXPIRED", "预览已过期，请刷新预览后重试。", 410);
        return snapshot;
    }
}
