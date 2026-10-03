package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;

@Service
@Transactional
public class JobReportHistory {
    public record Save(UUID previewId,UUID reportId,String label) {}
    public record Summary(UUID id,UUID resumeId,String label,long sourceRevision,Instant createdAt,
                          String profileName,String provider,String model,boolean sourceChanged) {}
    public record Detail(UUID id,UUID resumeId,String label,long sourceRevision,Instant createdAt,
                         String profileName,String provider,String model,boolean sourceChanged,JobReportSnapshot snapshot) {}
    public record History(int page,int pageSize,long total,int pages,List<Summary> items) {}
    public record Deleted(UUID id,boolean deleted) {}
    private record Stored(UUID id,UUID resumeId,UUID previewId,String label,long revision,Instant createdAt,String json,boolean deleted) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final JobMatches matches;
    public JobReportHistory(JdbcTemplate jdbc,ObjectMapper mapper,JobMatches matches){this.jdbc=jdbc;this.mapper=mapper;this.matches=matches;}

    public Detail save(UUID resumeId,Save input) {
        if(input==null||input.previewId()==null||input.reportId()==null||input.label()==null||input.label().isBlank()
            ||input.label().length()>120||input.label().codePoints().anyMatch(Character::isISOControl))throw invalid();
        long current=revision(resumeId,true);
        var existing=find(resumeId,input.reportId());
        if(existing!=null){
            if(existing.deleted())throw new ApiException("JOB_REPORT_DELETED","这份报告已删除，原保存请求不会重新创建记录。",410);
            if(!existing.previewId().equals(input.previewId()))throw new ApiException("JOB_REPORT_SOURCE_INVALID","报告与确认的原材料不匹配，请重新生成并核对。",422);
            if(!existing.label().equals(input.label().trim()))throw new ApiException("REPORT_SAVE_CONFLICT","报告已用另一名称保存，请查看历史记录。",409);
            return detail(existing,current);
        }
        var material=matches.archive(input.previewId(),input.reportId(),resumeId);
        if(jdbc.queryForObject("SELECT count(*) FROM job_reports WHERE resume_id=? AND deleted_at IS NULL",Long.class,resumeId)>=100)
            throw new ApiException("JOB_REPORT_LIMIT","每份简历最多保存 100 份报告，请先整理不需要的历史记录。",422);
        var snapshot=JobReportSnapshot.from(mapper,material);
        try {
            jdbc.update("INSERT INTO job_reports(id,resume_id,preview_id,label,source_revision,snapshot) VALUES (?,?,?,?,?,?::jsonb)",
                input.reportId(),resumeId,input.previewId(),input.label().trim(),snapshot.sourceRevision(),mapper.writeValueAsString(snapshot));
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw JobReportSnapshot.invalid();}
        return detail(Objects.requireNonNull(find(resumeId,input.reportId())),current);
    }
    @Transactional(readOnly=true)
    public History history(UUID resumeId,int page) {
        if(page<0||page>10000)throw invalid();long current=revision(resumeId,false);
        long total=jdbc.queryForObject("SELECT count(*) FROM job_reports WHERE resume_id=? AND deleted_at IS NULL",Long.class,resumeId);
        var rows=jdbc.query("SELECT id,label,source_revision,created_at,substring(snapshot->>'profileName',1,50),substring(snapshot->>'provider',1,20),substring(snapshot->>'model',1,80) FROM job_reports WHERE resume_id=? AND deleted_at IS NULL ORDER BY created_at DESC,id LIMIT 20 OFFSET ?",
            (rs,n)->new Summary(rs.getObject(1,UUID.class),resumeId,rs.getString(2),rs.getLong(3),rs.getTimestamp(4).toInstant(),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getLong(3)!=current),resumeId,page*20);
        return new History(page,20,total,(int)((total+19)/20),List.copyOf(rows));
    }
    @Transactional(readOnly=true)
    public Detail get(UUID resumeId,UUID id) {
        long current=revision(resumeId,false);var stored=find(resumeId,id);
        if(stored==null||stored.deleted())throw notFound();return detail(stored,current);
    }
    public Deleted delete(UUID resumeId,UUID id) {
        revision(resumeId,true);var stored=find(resumeId,id);if(stored==null)throw notFound();
        if(!stored.deleted())jdbc.update("UPDATE job_reports SET snapshot=NULL,label='已删除报告',deleted_at=now() WHERE id=? AND resume_id=?",id,resumeId);
        return new Deleted(id,true);
    }
    private long revision(UUID id,boolean lock) {
        var values=jdbc.query("SELECT revision FROM resumes WHERE id=?"+(lock?" FOR UPDATE":""),(rs,n)->rs.getLong(1),id);
        if(values.isEmpty())throw new ApiException("RESUME_NOT_FOUND","这份简历不存在或已被删除。",404);return values.getFirst();
    }
    private Stored find(UUID resumeId,UUID id) {
        var values=jdbc.query("SELECT id,resume_id,preview_id,label,source_revision,created_at,snapshot,deleted_at FROM job_reports WHERE id=? AND resume_id=?",
            (rs,n)->new Stored(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getLong(5),rs.getTimestamp(6).toInstant(),rs.getString(7),rs.getTimestamp(8)!=null),id,resumeId);
        return values.isEmpty()?null:values.getFirst();
    }
    private Detail detail(Stored stored,long current) {
        try {
            var snapshot=JobReportSnapshot.parseDatabase(mapper,stored.json().getBytes(StandardCharsets.UTF_8));
            if(snapshot.sourceRevision()!=stored.revision())throw JobReportSnapshot.invalid();
            return new Detail(stored.id(),stored.resumeId(),stored.label(),stored.revision(),stored.createdAt(),snapshot.profileName(),
                snapshot.provider(),snapshot.model(),stored.revision()!=current,snapshot);
        }catch(Exception e){throw new ApiException("JOB_REPORT_UNREADABLE","这份报告无法读取，原记录保留，请检查数据或备份。",503);}
    }
    private static ApiException notFound(){return new ApiException("JOB_REPORT_NOT_FOUND","这份历史报告不存在或已删除。",404);}
    private static ApiException invalid(){return new ApiException("JOB_REPORT_INPUT_INVALID","报告名称或请求参数无效。",422);}
}
