package dev.localresume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.core.io.ClassPathResource;
import java.util.*;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {
    public record Create(@NotBlank @Size(max=120) String title, @Valid ResumeDocument document,
                         @Pattern(regexp="blank|one|two") String sample) {}
    public record Update(@NotBlank @Size(max=120) String title,@NotNull @Valid ResumeDocument document,
                         @Min(1) long expectedRevision,@NotNull UUID mutationId) {}
    public record NamedRevision(@Min(1) long expectedRevision,@NotBlank @Size(max=120) String title) {}
    public record Revision(@Min(1) long expectedRevision) {}
    public record ExportRequest(@Min(1) long expectedRevision, @Valid Redaction.Options redaction,
                                @Pattern(regexp="[0-9a-f]{64}") String previewDigest) {}
    private final ResumeService resumes;
    private final ImageService images;
    private final PreviewService previews;
    private final ExportService exports;
    public ResumeController(ResumeService resumes,ImageService images,PreviewService previews,ExportService exports) {
        this.resumes=resumes; this.images=images; this.previews=previews; this.exports=exports;
    }
    @GetMapping public Object list() { return resumes.list(); }
    @GetMapping("/{id}") public Object get(@PathVariable UUID id) { return resumes.get(id); }
    @PostMapping public Object create(@Valid @RequestBody Create input) throws java.io.IOException {
        var doc=input.document();
        if(doc==null) {
            doc=ResumeDocument.sample(input.sample()==null ? "blank" : input.sample());
            if(input.sample()!=null && !input.sample().equals("blank")) {
                var p=images.importImage(new ClassPathResource("static/samples/nailong-portrait.png").getContentAsByteArray());
                var l=images.importImage(new ClassPathResource("static/samples/university-logo.png").getContentAsByteArray());
                var layout=doc.layout();
                doc=new ResumeDocument(ResumeDocument.SCHEMA_VERSION,doc.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),false,
                    new ResumeDraft.ImageSlot(p.id(),true,26,34,"cover",0,1,50,50),new ResumeDraft.ImageSlot(l.id(),true,26,26,"contain",0,1,50,50),layout.presentation()));
            }
        }
        return resumes.create(input.title(),doc);
    }
    @PutMapping("/{id}") public Object save(@PathVariable UUID id,@Valid @RequestBody Update input) {
        return resumes.save(id,new ResumeService.Save(input.title(),input.document(),input.expectedRevision(),input.mutationId()));
    }
    @PostMapping("/{id}/duplicate") public Object duplicate(@PathVariable UUID id,@Valid @RequestBody NamedRevision input) {
        return resumes.duplicate(id,input.expectedRevision(),input.title());
    }
    @DeleteMapping("/{id}") public Object delete(@PathVariable UUID id,@Valid @RequestBody Revision input) {
        resumes.delete(id,input.expectedRevision()); return Map.of("deleted",true);
    }
    @GetMapping("/{id}/versions") public Object versions(@PathVariable UUID id) { return resumes.versions(id); }
    @GetMapping("/{id}/versions/{versionId}") public Object version(@PathVariable UUID id,@PathVariable UUID versionId) { return resumes.version(id,versionId); }
    @PostMapping("/{id}/versions") public Object checkpoint(@PathVariable UUID id,@Valid @RequestBody NamedRevision input) {
        return resumes.checkpoint(id,input.expectedRevision(),input.title());
    }
    @PostMapping("/{id}/versions/{versionId}/restore") public Object restore(@PathVariable UUID id,@PathVariable UUID versionId,@Valid @RequestBody Revision input) {
        return resumes.restore(id,versionId,input.expectedRevision());
    }
    @PostMapping("/{id}/export/preview") public Object exportPreview(@PathVariable UUID id,@Valid @RequestBody ExportRequest input) {
        if(input.redaction()==null) throw new ApiException("REDACTION_OPTIONS_REQUIRED","请选择脱敏范围后预览。",422);
        var resume=resumes.savedRevision(id,input.expectedRevision());
        var preview=previews.create(Redaction.apply(resume.document(),input.redaction()));
        return Map.of("id",preview.id(),"digest",preview.digest(),"url","/render/"+preview.id(),"revision",resume.revision());
    }
    @PostMapping("/{id}/export") public Object export(@PathVariable UUID id,@Valid @RequestBody ExportRequest input) {
        var resume=resumes.savedRevision(id,input.expectedRevision());
        if(input.redaction()!=null&&input.previewDigest()==null)
            throw new ApiException("EXPORT_PREVIEW_REQUIRED","请先检查脱敏预览，再导出 PDF。",422);
        var preview=previews.create(input.redaction()==null?resume.document():Redaction.apply(resume.document(),input.redaction()));
        if(input.redaction()!=null&&!preview.digest().equals(input.previewDigest()))
            throw new ApiException("EXPORT_PREVIEW_CHANGED","脱敏选项已改变，请更新预览后再导出。",422);
        // Pin the same source revision after projection. A racing edit yields 409 before rendering.
        var checkpoint=input.redaction()==null?resumes.exportCheckpoint(id,input.expectedRevision()):
            resumes.checkpoint(id,input.expectedRevision(),"脱敏 PDF 原稿 · r"+input.expectedRevision());
        return exports.generate(preview,id.toString(),checkpoint.id().toString(),resume.revision());
    }
}
