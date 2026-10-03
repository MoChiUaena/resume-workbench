package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public class BackupService {
    public record Created(String id, long bytes, int resumes, int versions, int attachments, int exports, Instant createdAt) {}
    public record Restored(int resumes, int versions, int attachments, int exports, List<UUID> resumeIds) {}
    public record AutomaticResult(Created backup,String fingerprint,String outcome) {}
    private record Snapshot(BackupData workspace,Map<UUID,byte[]> reports) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final AttachmentStorage storage;
    private final WorkspaceGate gate;
    private final TransactionTemplate tx;
    private final Path data;
    private final BackupArchive archive;
    private final BackupCatalog catalog;
    public final long maxBytes;
    private final long maxUploadBytes;
    private final long maxPixels;
    public BackupService(JdbcTemplate jdbc, ObjectMapper mapper, Validator validator, AttachmentStorage storage,
                         WorkspaceGate gate, PlatformTransactionManager transactions,
                         @Value("${resume.data-dir}") String data,
                         @Value("${resume.max-backup-bytes}") long maxBytes,
                         @Value("${resume.max-upload-bytes}") long maxUploadBytes,
                         @Value("${resume.max-pixels}") long maxPixels) {
        this.jdbc=jdbc; this.mapper=mapper; this.validator=validator; this.storage=storage; this.gate=gate;
        this.tx=new TransactionTemplate(transactions); this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ); this.data=Path.of(data).toAbsolutePath().normalize();
        this.maxBytes=maxBytes; this.maxUploadBytes=maxUploadBytes; this.maxPixels=maxPixels;
        this.archive=new BackupArchive(mapper,maxBytes);
        this.catalog=new BackupCatalog(this.data,mapper,maxBytes);
    }
    public Created create() { return create("manual",null).backup(); }
    public AutomaticResult automatic(String previousFingerprint) { return create("automatic",previousFingerprint); }
    private AutomaticResult create(String kind,String previousFingerprint) {
        try (var lease=gate.exclusive()) {
            Snapshot bundle=Objects.requireNonNull(tx.execute(status->snapshot()));
            BackupData snapshot=bundle.workspace();
            String fingerprint=ImageService.sha(mapper.writeValueAsBytes(snapshot));
            if(kind.equals("automatic")&&fingerprint.equals(previousFingerprint))return new AutomaticResult(null,fingerprint,"unchanged");
            if(kind.equals("automatic")&&previousFingerprint==null&&snapshot.resumes().isEmpty())return new AutomaticResult(null,fingerprint,"empty");
            String id=UUID.randomUUID().toString();
            Path directory=data.resolve("backups"); Files.createDirectories(directory);
            Path temp=directory.resolve(id+".tmp"), destination=directory.resolve(id+".zip");
            var files=new LinkedHashMap<String,BackupArchive.Source>();
            files.put("workspace.json",BackupArchive.Source.json(mapper.writeValueAsBytes(snapshot)));
            files.put("settings.json",BackupArchive.Source.json(mapper.writeValueAsBytes(new BackupData.Settings(BackupData.SCHEMA_VERSION,maxUploadBytes,maxPixels))));
            for(var report:snapshot.jobReports())files.put("job-reports/"+report.id()+".json",BackupArchive.Source.json(bundle.reports().get(report.id())));
            for(var asset:snapshot.attachments()) {
                String prefix="attachments/"+asset.id()+"/";
                files.put(prefix+"metadata.json",BackupArchive.Source.json(mapper.writeValueAsBytes(asset)));
                files.put(prefix+"original."+asset.format().toLowerCase(Locale.ROOT),BackupArchive.Source.file(attachmentPath(asset,"original."+asset.format().toLowerCase(Locale.ROOT)),asset.sha256()));
                files.put(prefix+"image.png",BackupArchive.Source.file(attachmentPath(asset,"image.png"),asset.normalizedSha256()));
            }
            for(var export:snapshot.exports()) {
                files.put("exports/"+export.id()+".pdf",BackupArchive.Source.file(data.resolve("exports").resolve(export.id()+".pdf"),export.sha256()));
                files.put("exports/"+export.id()+".json",BackupArchive.Source.json(mapper.writeValueAsBytes(export)));
            }
            try {
                var manifest=archive.write(temp,files);
                Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);
                var created=new Created(id,Files.size(destination),snapshot.resumes().size(),snapshot.versions().size(),snapshot.attachments().size(),snapshot.exports().size(),manifest.createdAt());
                try{catalog.save(new BackupCatalog.Stored(1,kind,created,BackupCatalog.hash(destination),fingerprint));}
                catch(IOException e){Files.deleteIfExists(destination);throw e;}
                return new AutomaticResult(created,fingerprint,"created");
            } finally { Files.deleteIfExists(temp); }
        } catch(ApiException e) { throw e; }
        catch(Exception e) { throw new ApiException("BACKUP_FAILED","备份未完成，请检查本地文件完整性、磁盘空间和数据库状态后重试。",503); }
    }
    public Path download(String id) {
        requireUuid(id);
        Path file=data.resolve("backups").resolve(id+".zip");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) throw new ApiException("BACKUP_NOT_FOUND","备份文件不存在，请重新创建。",404);
        catalog.verify(file);
        return file;
    }
    public BackupCatalog.History history(int page) { try(var lease=gate.mutation()){return catalog.history(page);} }
    public Restored restoreSaved(String id) {
        try(var input=Files.newInputStream(download(id))){return restore(input);}
        catch(IOException e){throw new ApiException("BACKUP_NOT_FOUND","本机备份无法读取，请选择其他备份。",404);}
    }
    private Snapshot snapshot() {
        var resumes=jdbc.query("SELECT * FROM resumes ORDER BY id",(rs,n)->new BackupData.SavedResume(rs.getObject("id",UUID.class),rs.getString("title"),parse(rs.getString("document"),ResumeDocument.class),rs.getLong("revision"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant()));
        var versions=jdbc.query("SELECT * FROM resume_versions ORDER BY id",(rs,n)->new BackupData.SavedVersion(rs.getObject("id",UUID.class),rs.getObject("resume_id",UUID.class),rs.getString("title"),rs.getString("label"),parse(rs.getString("document"),ResumeDocument.class),rs.getLong("source_revision"),rs.getTimestamp("created_at").toInstant()));
        if(resumes.size()>2000 || versions.size()>10000) throw BackupArchive.invalid("工作区记录数量超限，请拆分工作区后备份。");
        var ids=new TreeSet<String>();
        resumes.forEach(r->collect(ids,r.document())); versions.forEach(v->collect(ids,v.document()));
        var assets=new ArrayList<ImageService.Asset>();
        for(String id:ids) {
            try {
                var disk=storage.metadata(id);
                var rows=jdbc.query("SELECT metadata::text FROM attachments WHERE id=?",(rs,n)->parse(rs.getString(1),ImageService.Asset.class),UUID.fromString(id));
                if(rows.size()!=1 || !rows.getFirst().equals(disk)) throw BackupArchive.invalid("图片元数据不一致，备份未完成。");
                validateAsset(disk); assets.add(disk);
            } catch(IOException e) { throw BackupArchive.invalid("图片文件缺失，备份未完成。"); }
        }
        var resumeIds=new HashSet<String>();resumes.forEach(r->resumeIds.add(r.id().toString()));
        var versionIds=new HashSet<String>();versions.forEach(v->versionIds.add(v.id().toString()));
        var exports=new ArrayList<ExportService.Export>();
        Path directory=data.resolve("exports");
        if(Files.isDirectory(directory)) {
            try(var paths=Files.list(directory)) {
                for(Path path:paths.filter(p->p.getFileName().toString().matches("[0-9a-f-]{36}\\.json")).toList()) {
                    var export=mapper.readValue(path.toFile(),ExportService.Export.class);
                    if(resumeIds.contains(export.resumeId()) && versionIds.contains(export.versionId())) {
                        requireUuid(export.id());
                        if(!path.getFileName().toString().equals(export.id()+".json")) throw BackupArchive.invalid("导出元数据无效。");
                        exports.add(export);
                    }
                }
            } catch(IOException e) { throw BackupArchive.invalid("导出文件读取失败，备份未完成。"); }
        }
        exports.sort(Comparator.comparing(ExportService.Export::id));
        var reportFiles=new LinkedHashMap<UUID,byte[]>();
        var reports=jdbc.query("SELECT id,resume_id,label,source_revision,created_at,snapshot FROM job_reports WHERE deleted_at IS NULL ORDER BY id",(rs,n)->{
            UUID id=rs.getObject("id",UUID.class);
            byte[] bytes;
            try {
                var snapshot=databaseReportSnapshot(rs.getString("snapshot").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                if(snapshot.sourceRevision()!=rs.getLong("source_revision"))throw BackupArchive.invalid("报告修订信息不一致。");
                bytes=mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(snapshot);
            }catch(IOException e){throw BackupArchive.invalid("报告无法读取，备份未完成。");}
            reportFiles.put(id,bytes);
            return new BackupData.SavedJobReport(id,rs.getObject("resume_id",UUID.class),rs.getString("label"),rs.getLong("source_revision"),rs.getTimestamp("created_at").toInstant(),ImageService.sha(bytes));
        });
        validateReportMetadata(reports,resumes);
        return new Snapshot(new BackupData(BackupData.SCHEMA_VERSION,List.copyOf(resumes),List.copyOf(versions),List.copyOf(assets),List.copyOf(exports),List.copyOf(reports)),Map.copyOf(reportFiles));
    }
    private Path attachmentPath(ImageService.Asset asset,String filename) { requireUuid(asset.id()); return data.resolve("attachments").resolve(asset.id()).resolve(filename); }
    public Restored restore(InputStream input) {
        var createdAssets=new ArrayList<String>();var createdExports=new ArrayList<Path>();
        try (var staged=archive.read(input,data.resolve("tmp")); var lease=gate.exclusive()) {
            var backup=mapper.readValue(staged.file("workspace.json").toFile(),BackupData.class);
            validate(backup,staged);
            return tx.execute(status->importData(backup,staged,createdAssets,createdExports));
        } catch(Exception e) {
            // Only files created by this import are removed. Existing data is never overwritten.
            for(String id:createdAssets) { try {storage.delete(id);}catch(IOException ignored){} }
            for(Path file:createdExports) { try {Files.deleteIfExists(file);}catch(IOException ignored){} }
            if(e instanceof ApiException a) throw a;
            throw new ApiException("RESTORE_FAILED","恢复未完成，原有简历未被覆盖。请检查备份、磁盘空间和数据库状态后重试。",503);
        }
    }
    private void validate(BackupData backup,BackupArchive.Staged staged) throws IOException {
        if(backup==null || !BackupData.supportsSchema(backup.schemaVersion()) || backup.resumes()==null || backup.versions()==null || backup.attachments()==null || backup.exports()==null || backup.jobReports()==null
            || (backup.schemaVersion()<BackupData.SCHEMA_VERSION&&!backup.jobReports().isEmpty())
            || backup.resumes().size()>2000 || backup.versions().size()>10000) throw BackupArchive.invalid("工作区数据无效或数量超限。");
        var resumeIds=new HashSet<UUID>();var versionIds=new HashSet<UUID>();var assetIds=new HashSet<String>();
        var versionOwners=new HashMap<UUID,BackupData.SavedVersion>();
        var required=new TreeSet<String>(List.of("workspace.json","settings.json"));
        for(var r:backup.resumes()) {if(r==null||r.id()==null||!resumeIds.add(r.id())||r.revision()<1||r.createdAt()==null||r.updatedAt()==null)throw BackupArchive.invalid("简历记录无效。");validateTitle(r.title());validateDocument(r.document());}
        for(var v:backup.versions()) {if(v==null||v.id()==null||!versionIds.add(v.id())||!resumeIds.contains(v.resumeId())||v.sourceRevision()<1||v.createdAt()==null)throw BackupArchive.invalid("历史版本关系无效。");validateTitle(v.title());validateTitle(v.label());validateDocument(v.document());versionOwners.put(v.id(),v);}
        for(var asset:backup.attachments()) {
            validateAsset(asset); if(!assetIds.add(asset.id()))throw BackupArchive.invalid("图片 ID 重复。");
            String prefix="attachments/"+asset.id()+"/";
            for(String name:List.of("metadata.json","image.png","original."+asset.format().toLowerCase(Locale.ROOT)))required.add(prefix+name);
            var meta=mapper.readValue(staged.file(prefix+"metadata.json").toFile(),ImageService.Asset.class);
            if(!meta.equals(asset))throw BackupArchive.invalid("图片元数据不一致。");
            byte[] original=Files.readAllBytes(staged.file(prefix+"original."+asset.format().toLowerCase(Locale.ROOT)));
            if(original.length!=asset.bytes()||!ImageService.sha(original).equals(asset.sha256()))throw BackupArchive.invalid("原图校验失败。");
            checkImageHeader(staged.file(prefix+"original."+asset.format().toLowerCase(Locale.ROOT)),asset.format(),asset.sourceWidth(),asset.sourceHeight());
            Path image=staged.file(prefix+"image.png");
            if(!hash(image).equals(asset.normalizedSha256()))throw BackupArchive.invalid("处理后图片校验失败。");
            try(var stream=ImageIO.createImageInputStream(image.toFile())) {
                var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw BackupArchive.invalid("处理后图片无法识别。");
                var reader=readers.next();try{reader.setInput(stream);if(!"png".equalsIgnoreCase(reader.getFormatName())||reader.getWidth(0)!=asset.width()||reader.getHeight(0)!=asset.height())throw BackupArchive.invalid("处理后图片尺寸或格式不匹配。");}finally{reader.dispose();}
            }
        }
        var referenced=new TreeSet<String>();backup.resumes().forEach(r->collect(referenced,r.document()));backup.versions().forEach(v->collect(referenced,v.document()));
        if(!referenced.equals(new TreeSet<>(assetIds)))throw BackupArchive.invalid("简历引用的图片缺失或清单含多余图片。");
        var exportIds=new HashSet<String>();
        for(var export:backup.exports()) {
            if(export==null)throw BackupArchive.invalid("导出记录无效。"); requireUuid(export.id());requireUuid(export.resumeId());requireUuid(export.versionId());
            if(!exportIds.add(export.id())||!resumeIds.contains(UUID.fromString(export.resumeId()))||!versionIds.contains(UUID.fromString(export.versionId())))throw BackupArchive.invalid("导出记录关系无效。");
            var version=versionOwners.get(UUID.fromString(export.versionId()));
            if(!version.resumeId().equals(UUID.fromString(export.resumeId()))||export.revision()==null||export.revision()!=version.sourceRevision()||export.createdAt()==null)throw BackupArchive.invalid("PDF 对应版本关系无效。");
            required.add("exports/"+export.id()+".pdf");required.add("exports/"+export.id()+".json");
            var meta=mapper.readValue(staged.file("exports/"+export.id()+".json").toFile(),ExportService.Export.class);
            if(!meta.equals(export)||!hash(staged.file("exports/"+export.id()+".pdf")).equals(export.sha256()))throw BackupArchive.invalid("PDF 校验失败。");
        }
        validateReportMetadata(backup.jobReports(),backup.resumes());
        for(var report:backup.jobReports()) {
            String name="job-reports/"+report.id()+".json";required.add(name);
            Path path=staged.file(name);
            if(!Files.isRegularFile(path)||!hash(path).equals(report.sha256()))throw BackupArchive.invalid("报告文件缺失或校验失败。");
            var snapshot=reportSnapshot(Files.readAllBytes(path));
            if(snapshot.sourceRevision()!=report.sourceRevision())throw BackupArchive.invalid("报告修订信息不一致。");
        }
        var actual=new TreeSet<String>();staged.manifest().files().forEach(file->actual.add(file.path()));
        if(!required.equals(actual))throw BackupArchive.invalid("备份清单含多余或缺失文件。");
        var settings=mapper.readValue(staged.file("settings.json").toFile(),BackupData.Settings.class);
        if(settings==null || settings.schemaVersion()!=backup.schemaVersion() || settings.schemaVersion()!=staged.manifest().documentSchemaVersion())throw BackupArchive.invalid("备份设置版本无效或不一致。");
    }
    private Restored importData(BackupData backup,BackupArchive.Staged staged,List<String> createdAssets,List<Path> createdExports) {
        var assets=new HashMap<String,String>();var resumes=new HashMap<UUID,UUID>();var versions=new HashMap<UUID,UUID>();
        try {
            for(var old:backup.attachments()) {
                String id;do{id=UUID.randomUUID().toString();}while(Files.exists(data.resolve("attachments").resolve(id)));
                String prefix="attachments/"+old.id()+"/";
                var asset=new ImageService.Asset(id,old.format(),old.bytes(),old.sourceWidth(),old.sourceHeight(),old.width(),old.height(),old.exifOrientation(),old.sha256(),old.normalizedSha256());
                createdAssets.add(id);
                storage.save(asset,Files.readAllBytes(staged.file(prefix+"original."+old.format().toLowerCase(Locale.ROOT))),Files.readAllBytes(staged.file(prefix+"image.png")));
                jdbc.update("INSERT INTO attachments(id,metadata) VALUES (?,?::jsonb)",UUID.fromString(id),mapper.writeValueAsString(asset));assets.put(old.id(),id);
            }
            for(var old:backup.resumes()) {
                UUID id=UUID.randomUUID();var doc=remap(old.document(),assets);resumes.put(old.id(),id);
                jdbc.update("INSERT INTO resumes(id,title,document,revision,created_at,updated_at) VALUES (?,?,?::jsonb,?,?,?)",id,old.title(),mapper.writeValueAsString(doc),old.revision(),Timestamp.from(old.createdAt()),Timestamp.from(old.updatedAt()));
                refs("resume_assets","resume_id",id,doc);
            }
            for(var old:backup.versions()) {
                UUID id=UUID.randomUUID();var doc=remap(old.document(),assets);versions.put(old.id(),id);
                jdbc.update("INSERT INTO resume_versions(id,resume_id,title,label,document,source_revision,created_at) VALUES (?,?,?,?,?::jsonb,?,?)",id,resumes.get(old.resumeId()),old.title(),old.label(),mapper.writeValueAsString(doc),old.sourceRevision(),Timestamp.from(old.createdAt()));
                refs("version_assets","version_id",id,doc);
            }
            for(var old:backup.jobReports()) {
                var snapshot=reportSnapshot(Files.readAllBytes(staged.file("job-reports/"+old.id()+".json")));
                jdbc.update("INSERT INTO job_reports(id,resume_id,preview_id,label,source_revision,created_at,snapshot) VALUES (?,?,?,?,?,?,?::jsonb)",
                    UUID.randomUUID(),resumes.get(old.resumeId()),UUID.randomUUID(),old.label(),old.sourceRevision(),Timestamp.from(old.createdAt()),mapper.writeValueAsString(snapshot));
            }
            Path exports=data.resolve("exports");Files.createDirectories(exports);
            for(var old:backup.exports()) {
                String id=UUID.randomUUID().toString();Path pdf=exports.resolve(id+".pdf"),json=exports.resolve(id+".json");
                Files.copy(staged.file("exports/"+old.id()+".pdf"),pdf);
                createdExports.add(pdf);
                try(var out=Files.newOutputStream(json,StandardOpenOption.CREATE_NEW)) {
                    createdExports.add(json);
                    mapper.writeValue(out,new ExportService.Export(id,old.snapshotId(),old.digest(),old.sha256(),old.createdAt(),resumes.get(UUID.fromString(old.resumeId())).toString(),versions.get(UUID.fromString(old.versionId())).toString(),old.revision()));
                }
            }
            return new Restored(resumes.size(),versions.size(),assets.size(),backup.exports().size(),List.copyOf(resumes.values()));
        } catch(IOException e) { throw new ApiException("RESTORE_STORAGE_FAILED","无法写入恢复文件，原有简历未被覆盖。请检查磁盘空间和权限。",507); }
    }
    private void refs(String table,String key,UUID owner,ResumeDocument doc) {for(var pair:List.of(Map.entry("photo",doc.layout().photo()),Map.entry("logo",doc.layout().logo())))if(pair.getValue().id()!=null)jdbc.update("INSERT INTO "+table+"("+key+",slot,asset_id) VALUES (?,?,?)",owner,pair.getKey(),UUID.fromString(pair.getValue().id()));}
    private ResumeDocument remap(ResumeDocument doc,Map<String,String> ids) {var l=doc.layout();return new ResumeDocument(ResumeDocument.SCHEMA_VERSION,doc.content(),new ResumeDocument.Layout(l.template(),l.font(),l.fontSize(),l.lineHeight(),l.sectionGapMm(),l.marginMm(),l.swapImages(),slot(l.photo(),ids),slot(l.logo(),ids),l.presentation()));}
    private ResumeDraft.ImageSlot slot(ResumeDraft.ImageSlot old,Map<String,String> ids) {return new ResumeDraft.ImageSlot(old.id()==null?null:ids.get(old.id()),old.visible(),old.widthMm(),old.heightMm(),old.fit(),old.quarterTurns(),old.zoom(),old.positionX(),old.positionY());}
    private static void collect(Set<String> ids,ResumeDocument doc) {for(var slot:List.of(doc.layout().photo(),doc.layout().logo()))if(slot.id()!=null)ids.add(slot.id());}
    private void validateDocument(ResumeDocument doc) {if(doc==null||!validator.validate(doc).isEmpty())throw BackupArchive.invalid("简历内容或版式参数无效。");try{if(mapper.writeValueAsString(doc).length()>200000)throw BackupArchive.invalid("单份简历内容过大。");}catch(IOException e){throw BackupArchive.invalid("简历内容无效。");}}
    private void validateTitle(String title) {if(title==null||title.isBlank()||title.length()>120)throw BackupArchive.invalid("标题或版本名称无效。");}
    private static void validateReportMetadata(List<BackupData.SavedJobReport> reports,List<BackupData.SavedResume> resumes) {
        if(reports.size()>BackupArchive.MAX_FILES)throw BackupArchive.invalid("报告数量超限。");
        var revisions=new HashMap<UUID,Long>();resumes.forEach(r->revisions.put(r.id(),r.revision()));
        var ids=new HashSet<UUID>();var counts=new HashMap<UUID,Integer>();
        for(var report:reports) {
            if(report==null||report.id()==null||!ids.add(report.id())||report.resumeId()==null||!revisions.containsKey(report.resumeId())
                ||report.sourceRevision()<1||report.sourceRevision()>revisions.get(report.resumeId())||report.createdAt()==null
                ||report.label()==null||report.label().isBlank()||report.label().length()>120||report.label().codePoints().anyMatch(Character::isISOControl)
                ||report.sha256()==null||!report.sha256().matches("[0-9a-f]{64}")||counts.merge(report.resumeId(),1,Integer::sum)>100)
                throw BackupArchive.invalid("报告记录或所属简历关系无效。");
        }
    }
    private JobReportSnapshot reportSnapshot(byte[] bytes) {
        try{return JobReportSnapshot.parse(mapper,bytes);}catch(ApiException e){throw BackupArchive.invalid("报告内容或原材料引用无效。");}
    }
    private JobReportSnapshot databaseReportSnapshot(byte[] bytes) {
        try{return JobReportSnapshot.parseDatabase(mapper,bytes);}catch(ApiException e){throw BackupArchive.invalid("报告内容或原材料引用无效。");}
    }
    private void validateAsset(ImageService.Asset asset) {if(asset==null)throw BackupArchive.invalid("图片元数据无效。");requireUuid(asset.id());if(!Set.of("PNG","JPEG","WEBP").contains(asset.format())||asset.bytes()<1||asset.bytes()>BackupArchive.MAX_ENTRY||asset.sha256()==null||!asset.sha256().matches("[0-9a-f]{64}")||asset.normalizedSha256()==null||!asset.normalizedSha256().matches("[0-9a-f]{64}"))throw BackupArchive.invalid("图片元数据无效。");for(var size:List.of(new int[]{asset.width(),asset.height()},new int[]{asset.sourceWidth(),asset.sourceHeight()}))if(size[0]<1||size[1]<1||size[0]>12000||size[1]>12000||(long)size[0]*size[1]>maxPixels)throw BackupArchive.invalid("图片尺寸超限。");}
    private static void requireUuid(String id) {if(id==null||!id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw BackupArchive.invalid("文件 ID 无效。");}
    private <T>T parse(String json,Class<T> type) {try{return mapper.readValue(json,type);}catch(IOException e){throw BackupArchive.invalid("工作区数据无法解析。");}}
    private static String hash(Path file) throws IOException {var digest=BackupArchive.digest();try(var in=Files.newInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
    private void checkImageHeader(Path file,String format,int width,int height)throws IOException{if(format.equals("WEBP")){try{WebpHeader.read(Files.readAllBytes(file),maxPixels);}catch(ApiException|IOException e){throw BackupArchive.invalid("备份中的 WebP 图片无效、尺寸超限或包含动画。");}}try(var input=ImageIO.createImageInputStream(file.toFile())){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw BackupArchive.invalid("原图无法识别。");var reader=readers.next();try{reader.setInput(input);if(!reader.getFormatName().equalsIgnoreCase(format)||reader.getWidth(0)!=width||reader.getHeight(0)!=height)throw BackupArchive.invalid("原图格式或尺寸不匹配。");}finally{reader.dispose();}}}
}
