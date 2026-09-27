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
                var p=images.importImage(new ClassPathResource("static/samples/portrait-exif-6.jpg").getContentAsByteArray());
                var l=images.importImage(new ClassPathResource("static/samples/university-logo.png").getContentAsByteArray());
                var layout=doc.layout();
                doc=new ResumeDocument(2,doc.content(),new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),false,
                    new ResumeDraft.ImageSlot(p.id(),true,26,34,"cover",0,1,50,50),new ResumeDraft.ImageSlot(l.id(),true,26,26,"contain",0,1,50,50)));
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
    @PostMapping("/{id}/versions") public Object checkpoint(@PathVariable UUID id,@Valid @RequestBody NamedRevision input) {
        return resumes.checkpoint(id,input.expectedRevision(),input.title());
    }
    @PostMapping("/{id}/versions/{versionId}/restore") public Object restore(@PathVariable UUID id,@PathVariable UUID versionId,@Valid @RequestBody Revision input) {
        return resumes.restore(id,versionId,input.expectedRevision());
    }
    @PostMapping("/{id}/export") public Object export(@PathVariable UUID id,@Valid @RequestBody Revision input) {
        // Load then checkpoint with the same revision. A racing edit yields 409 before rendering.
        var resume=resumes.get(id);
        var checkpoint=resumes.exportCheckpoint(id,input.expectedRevision());
        if(resume.revision()!=checkpoint.sourceRevision()) throw new ApiException("REVISION_CONFLICT","内容已更新，请重新导出。",409);
        var preview=previews.create(resume.document());
        return exports.generate(preview,id.toString(),checkpoint.id().toString(),resume.revision());
    }
}
