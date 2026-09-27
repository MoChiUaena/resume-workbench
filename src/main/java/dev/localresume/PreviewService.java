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
    public record Snapshot(String id, String digest, ResumeDocument document, String html, Instant createdAt) {}
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    private final TemplateEngine templates;
    private final AttachmentStorage storage;
    private final ObjectMapper mapper;
    public PreviewService(TemplateEngine templates, AttachmentStorage storage, ObjectMapper mapper) {
        this.templates = templates; this.storage = storage; this.mapper = mapper;
    }
    public Snapshot create(ResumeDraft draft) { return create(ResumeDocument.fromLegacy(draft)); }
    public synchronized Snapshot create(ResumeDocument document) {
        try {
            byte[] serialized=mapper.writeValueAsBytes(document);
            if(serialized.length>600000) throw new ApiException("DOCUMENT_TOO_LARGE","内容过多，请拆成多份简历。",413);
            document=mapper.readValue(serialized,ResumeDocument.class);
            var layout=document.layout();
            for (var slot : List.of(layout.photo(), layout.logo())) if (slot.id() != null) storage.metadata(slot.id());
            var ctx = new Context(Locale.SIMPLIFIED_CHINESE);
            ctx.setVariable("content", document.content());
            var rich=new RichText();
            var sections=document.content().sections().stream().map(s->new ResumeDocument.Section(s.id(),s.type(),s.title(),s.visible(),s.pageBreakBefore(),
                s.entries().stream().map(e->new ResumeDocument.Entry(e.id(),e.title(),e.meta(),e.bulleted(),e.bullets().stream().map(rich::render).toList())).toList())).toList();
            ctx.setVariable("sections",sections);
            ctx.setVariable("layout", layout);
            ctx.setVariable("left", layout.swapImages() ? layout.logo() : layout.photo());
            ctx.setVariable("right", layout.swapImages() ? layout.photo() : layout.logo());
            String html = templates.process("resume", ctx);
            var result = new Snapshot(UUID.randomUUID().toString(), ImageService.sha(serialized), document, html, Instant.now());
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
