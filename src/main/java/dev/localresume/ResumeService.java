package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.sql.ResultSet;
import java.sql.SQLException;

@Service
@Transactional
public class ResumeService {
    public record Resume(UUID id, String title, ResumeDocument document, long revision, UUID lastMutationId, Instant updatedAt) {}
    public record Summary(UUID id, String title, long revision, Instant updatedAt) {}
    public record Version(UUID id, String label, String title, long sourceRevision, Instant createdAt) {}
    public record VersionDetail(UUID id, String label, String title, ResumeDocument document, long sourceRevision, Instant createdAt) {}
    public record Save(String title, ResumeDocument document, long expectedRevision, UUID mutationId) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AssetCatalog assets;
    public ResumeService(JdbcTemplate jdbc, ObjectMapper mapper, AssetCatalog assets) { this.jdbc=jdbc; this.mapper=mapper; this.assets=assets; }
    public List<Summary> list() {
        return jdbc.query("SELECT id,title,revision,updated_at FROM resumes ORDER BY updated_at DESC,id",(rs,n)->new Summary(rs.getObject(1,UUID.class),rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant()));
    }
    public Resume get(UUID id) { return load(id,false); }
    @Transactional(readOnly=true)
    public Resume savedRevision(UUID id,long revision) {
        var current=load(id,false);requireRevision(current,revision);return current;
    }
    private Resume load(UUID id, boolean lock) {
        var found=jdbc.query("SELECT * FROM resumes WHERE id=?"+(lock ? " FOR UPDATE" : ""),(rs,n)->read(rs),id);
        if(found.isEmpty()) throw new ApiException("RESUME_NOT_FOUND","这份简历不存在或已被删除。",404);
        return found.getFirst();
    }
    private Resume read(ResultSet rs) throws SQLException {
        return new Resume(rs.getObject("id",UUID.class),rs.getString("title"),parse(rs.getString("document")),rs.getLong("revision"),rs.getObject("last_mutation_id",UUID.class),rs.getTimestamp("updated_at").toInstant());
    }
    private ResumeDocument parse(String json) {
        try { return mapper.readValue(json,ResumeDocument.class); }
        catch(Exception e) { throw new ApiException("DOCUMENT_UNREADABLE","简历数据版本不兼容，请保留数据并检查应用版本。",500); }
    }
    private String json(ResumeDocument doc) {
        try {
            String json=mapper.writeValueAsString(doc);
            if(json.length()>200000) throw new ApiException("DOCUMENT_TOO_LARGE","内容过多，请拆成多份简历。",413);
            return json;
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public Resume create(String title, ResumeDocument doc) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO resumes(id,title,document) VALUES (?,?,?::jsonb)",id,title,json(doc));
        references("resume_assets","resume_id",id,doc);
        var result=load(id,false); snapshot(result,"初始版本"); return result;
    }
    public Resume save(UUID id, Save input) {
        var existing=load(id,true);
        if(input.mutationId()!=null && input.mutationId().equals(existing.lastMutationId())) return existing;
        requireRevision(existing,input.expectedRevision());
        jdbc.update("UPDATE resumes SET title=?,document=?::jsonb,revision=revision+1,last_mutation_id=?,updated_at=now() WHERE id=?",
            input.title(),json(input.document()),input.mutationId(),id);
        jdbc.update("DELETE FROM resume_assets WHERE resume_id=?",id);
        references("resume_assets","resume_id",id,input.document());
        return load(id,false);
    }
    public Resume duplicate(UUID id, long revision, String title) {
        var original=load(id,true); requireRevision(original,revision); return create(title,original.document());
    }
    public static String paragraph(ResumeDocument document,String sectionId,String entryId,int index) {
        var section=document.content().sections().stream().filter(s->s.id().equals(sectionId)).findFirst()
            .orElseThrow(()->new ApiException("AI_SOURCE_CHANGED","原模块已改变，请重新选择要润色的文字。",409));
        var entry=section.entries().stream().filter(e->e.id().equals(entryId)).findFirst()
            .orElseThrow(()->new ApiException("AI_SOURCE_CHANGED","原条目已改变，请重新选择要润色的文字。",409));
        if(index<0||index>=entry.bullets().size())throw new ApiException("AI_SOURCE_CHANGED","原段落已改变，请重新选择文字。",409);
        return entry.bullets().get(index);
    }
    /** Apply one reviewed paragraph and its safety snapshot in the same transaction. */
    public Resume applyParagraph(UUID id,long revision,String sectionId,String entryId,int index,String original,String replacement,UUID mutationId) {
        var existing=load(id,true);
        if(mutationId.equals(existing.lastMutationId()))return existing;
        requireRevision(existing,revision);
        if(!paragraph(existing.document(),sectionId,entryId,index).equals(original))throw new ApiException("AI_SOURCE_CHANGED","原段落已改变，请重新生成建议。",409);
        if(replacement==null||replacement.isBlank()||replacement.length()>800||replacement.equals(original))throw new ApiException("AI_NO_CHANGE","建议未产生可应用的变化。",422);
        var content=existing.document().content();var sections=new ArrayList<ResumeDocument.Section>();
        for(var section:content.sections()){
            if(!section.id().equals(sectionId)){sections.add(section);continue;}
            var entries=new ArrayList<ResumeDocument.Entry>();
            for(var entry:section.entries()){
                if(!entry.id().equals(entryId)){entries.add(entry);continue;}
                var bullets=new ArrayList<>(entry.bullets());bullets.set(index,replacement);
                entries.add(new ResumeDocument.Entry(entry.id(),entry.title(),entry.meta(),entry.bulleted(),List.copyOf(bullets)));
            }
            sections.add(new ResumeDocument.Section(section.id(),section.type(),section.title(),section.visible(),section.pageBreakBefore(),List.copyOf(entries)));
        }
        var changed=new ResumeDocument(ResumeDocument.SCHEMA_VERSION,new ResumeDocument.Content(content.name(),content.headline(),content.email(),content.phone(),content.location(),List.copyOf(sections)),existing.document().layout());
        snapshot(existing,"AI 应用前自动保留 · r"+existing.revision());
        return save(id,new Save(existing.title(),changed,revision,mutationId));
    }
    public void delete(UUID id, long revision) {
        var existing=load(id,true); requireRevision(existing,revision);
        jdbc.update("DELETE FROM resumes WHERE id=?",id);
        // Files are deliberately retained. Future GC must account for all current/version references.
    }
    public List<Version> versions(UUID id) {
        load(id,false);
        return jdbc.query("SELECT id,label,title,source_revision,created_at FROM resume_versions WHERE resume_id=? ORDER BY created_at DESC,id",(rs,n)->new Version(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4),rs.getTimestamp(5).toInstant()),id);
    }
    @Transactional(readOnly=true)
    public VersionDetail version(UUID id,UUID versionId) {
        load(id,false);
        var found=jdbc.query("SELECT * FROM resume_versions WHERE id=? AND resume_id=?",(rs,n)->
            new VersionDetail(rs.getObject("id",UUID.class),rs.getString("label"),rs.getString("title"),parse(rs.getString("document")),
                rs.getLong("source_revision"),rs.getTimestamp("created_at").toInstant()),versionId,id);
        if(found.isEmpty()) throw new ApiException("VERSION_NOT_FOUND","该版本不存在或不属于当前简历。",404);
        return found.getFirst();
    }
    public Version checkpoint(UUID id,long revision,String label) {
        var existing=load(id,true); requireRevision(existing,revision); return snapshot(existing,label);
    }
    private Version snapshot(Resume current,String label) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision) VALUES (?,?,?,?,?::jsonb,?)",
            id,current.id(),current.title(),label,json(current.document()),current.revision());
        references("version_assets","version_id",id,current.document());
        return new Version(id,label,current.title(),current.revision(),Instant.now());
    }
    public Resume restore(UUID id,UUID versionId,long revision) {
        var existing=load(id,true); requireRevision(existing,revision);
        var found=jdbc.query("SELECT title,document FROM resume_versions WHERE id=? AND resume_id=?",(rs,n)->Map.entry(rs.getString(1),parse(rs.getString(2))),versionId,id);
        if(found.isEmpty()) throw new ApiException("VERSION_NOT_FOUND","该版本不存在或不属于当前简历。",404);
        snapshot(existing,"恢复前自动保留 · r"+existing.revision());
        var target=found.getFirst();
        return save(id,new Save(target.getKey(),target.getValue(),revision,UUID.randomUUID()));
    }
    /** Export checkpoints are durable and pin the exact saved revision. */
    public Version exportCheckpoint(UUID id,long revision) { return checkpoint(id,revision,"PDF 导出 · r"+revision); }
    private void requireRevision(Resume current,long expected) {
        if(current.revision()!=expected) throw new ApiException("REVISION_CONFLICT","此简历已在其他页面更新。请另存副本，或重新载入最新版本。",409);
    }
    private void references(String table,String key,UUID owner,ResumeDocument doc) {
        for(var pair:List.of(Map.entry("photo",doc.layout().photo()),Map.entry("logo",doc.layout().logo()))) {
            String id=pair.getValue().id(); if(id==null) continue;
            assets.ensure(id);
            jdbc.update("INSERT INTO "+table+"("+key+",slot,asset_id) VALUES (?,?,?)",owner,pair.getKey(),UUID.fromString(id));
        }
    }
}
