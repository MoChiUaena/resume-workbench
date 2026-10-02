package dev.localresume;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.time.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class QuarantineService {
    public record Selection(String kind,String id) {}
    public record Request(String operationId,String previewDigest,List<Selection> items,Boolean confirm) {}
    public record RestoreRequest(String expectedDigest,Boolean confirm) {}
    public record FileBackupRequest(String requestId,String expectedDigest,Boolean confirm) {}
    public record FileBackupReceipt(String id,String operationId,String digest,long bytes,String sha256,Instant createdAt,Instant expiresAt,boolean downloaded) {}
    public record PurgeRequest(String expectedDigest,String exportId,String archiveSha256,Boolean confirm,Boolean backupSaved,String confirmation) {}
    public record Download(long bytes,String filename,StreamingResponseBody body) {}
    interface Backup {BackupService.Created create();Path download(String id);}
    private final StoragePreviewService preview;
    private final QuarantineStore store;
    private final Backup backups;
    private final WorkspaceGate gate;
    private final QuarantineArchive archive;
    private final Clock clock;
    private static final Duration TICKET_LIFETIME=Duration.ofMinutes(10);
    private static final class Ticket {
        final String id,operationId,digest;final QuarantineArchive.Artifact artifact;
        final Instant createdAt;Instant expiresAt;boolean downloaded,removed;
        Ticket(String id,String operationId,String digest,QuarantineArchive.Artifact artifact,Instant now){this.id=id;this.operationId=operationId;this.digest=digest;this.artifact=artifact;createdAt=now;expiresAt=now.plus(TICKET_LIFETIME);}
    }
    private final Map<String,Ticket> tickets=new LinkedHashMap<>();
    @Autowired
    public QuarantineService(StoragePreviewService preview,QuarantineStore store,BackupService backups,WorkspaceGate gate,QuarantineArchive archive){
        this(preview,store,backup(backups),gate,archive,Clock.systemUTC());
    }
    public QuarantineService(StoragePreviewService preview,QuarantineStore store,BackupService backups,WorkspaceGate gate){this(preview,store,backup(backups),gate);}
    QuarantineService(StoragePreviewService preview,QuarantineStore store,Backup backups,WorkspaceGate gate){this(preview,store,backups,gate,preview.defaultArchive(),Clock.systemUTC());}
    QuarantineService(StoragePreviewService preview,QuarantineStore store,Backup backups,WorkspaceGate gate,QuarantineArchive archive,Clock clock){this.preview=preview;this.store=store;this.backups=backups;this.gate=gate;this.archive=Objects.requireNonNull(archive);this.clock=Objects.requireNonNull(clock);}
    private static Backup backup(BackupService backups){return new Backup(){public BackupService.Created create(){return backups.create();}public Path download(String id){return backups.download(id);}};}

    public FileBackupReceipt fileBackup(String id,FileBackupRequest request){
        confirm(request==null?null:request.confirm());
        if(!StorageInspector.uuid(id)||!StorageInspector.uuid(request.requestId())||!sha(request.expectedDigest()))throw invalid();
        try(var lease=gate.exclusive()){
            synchronized(tickets){var existing=tickets.get(request.requestId());if(existing!=null){if(!existing.operationId.equals(id)||!existing.digest.equals(request.expectedDigest()))throw conflict();return receipt(existing);}}
            var view=store.cleanupView(id,request.expectedDigest());if(!view.receipt().state().equals("quarantined"))throw conflict();
            var now=clock.instant();
            synchronized(tickets){tickets.values().removeIf(t->!active(t,now));if(tickets.size()>=32)throw new ApiException("QUARANTINE_ARCHIVE_LIMIT","文件备份数量已达上限，请稍后重试。",409);}
            var artifact=archive.create(view.plan(),request.expectedDigest());archive.verify(artifact);
            var ticket=new Ticket(request.requestId(),id,request.expectedDigest(),artifact,clock.instant());
            synchronized(tickets){tickets.put(ticket.id,ticket);return receipt(ticket);}
        }catch(IOException e){throw ioFailed();}
    }
    /** Pure memory lookup: GET never reclaims or renews temporary files. */
    public FileBackupReceipt fileBackupStatus(String id,String requestId){
        validateIds(id,requestId);synchronized(tickets){return receipt(ticket(id,requestId));}
    }
    public Download download(String id,String requestId){
        return download(id,requestId,()->false);
    }
    Download download(String id,String requestId,java.util.function.BooleanSupplier cancelled){
        validateIds(id,requestId);Ticket selected;
        try(var lease=gate.mutation()){
            synchronized(tickets){selected=ticket(id,requestId);requireActive(selected);if(selected.removed)throw downloadRequired();}
            requireQuarantined(selected);
        }catch(IOException e){throw ioFailed();}
        // The servlet invokes this later on a different thread. Never hand it a request-thread lease.
        return new Download(selected.artifact.bytes(),"quarantine-files-"+requestId+".zip",output->{
            try(var lease=gate.mutation()){
                requireStreaming(cancelled);
                synchronized(tickets){if(ticket(id,requestId)!=selected||selected.removed)throw downloadRequired();requireActive(selected);}
                requireQuarantined(selected);archive.verify(selected.artifact);
                var hash=BackupArchive.digest();long count=0;
                try(var input=archive.open(selected.artifact)){
                    byte[] buffer=new byte[65536];int length;
                    while((length=input.read(buffer))!=-1){requireStreaming(cancelled);count+=length;if(count>selected.artifact.bytes())throw new IOException("Archive length changed");hash.update(buffer,0,length);output.write(buffer,0,length);}
                }
                if(count!=selected.artifact.bytes()||!HexFormat.of().formatHex(hash.digest()).equals(selected.artifact.sha256()))throw new IOException("Archive evidence changed");
                archive.verify(selected.artifact);requireStreaming(cancelled);output.flush();requireStreaming(cancelled);
                var deliveredAt=clock.instant();archive.renew(selected.artifact,deliveredAt);
                synchronized(tickets){requireStreaming(cancelled);selected.downloaded=true;selected.expiresAt=deliveredAt.plus(TICKET_LIFETIME);}
            }
        });
    }
    public QuarantineStore.Receipt purge(String id,PurgeRequest request){
        confirm(request==null?null:request.confirm());
        if(!StorageInspector.uuid(id)||!sha(request.expectedDigest())||!StorageInspector.uuid(request.exportId())||!sha(request.archiveSha256()))throw invalid();
        if(!Boolean.TRUE.equals(request.backupSaved()))throw new ApiException("QUARANTINE_BACKUP_REQUIRED","请确认已另存本批文件 ZIP。",400);
        if(!id.substring(id.length()-6).equals(request.confirmation()))throw new ApiException("QUARANTINE_CONFIRMATION_MISMATCH","请输入批次编号末六位以确认永久清理。",400);
        try(var lease=gate.exclusive()){
            var view=store.cleanupView(id,request.expectedDigest());var existing=view.receipt();Ticket selected;
            synchronized(tickets){selected=tickets.get(request.exportId());}
            QuarantineStore.CleanupInfo proof;
            if(existing.cleanup()!=null){
                proof=existing.cleanup();if(!proof.exportId().equals(request.exportId())||!proof.sha256().equals(request.archiveSha256()))throw conflict();
            }else{
                synchronized(tickets){selected=ticket(id,request.exportId());if(!selected.digest.equals(request.expectedDigest())||!selected.artifact.sha256().equals(request.archiveSha256()))throw conflict();requireActive(selected);if(!selected.downloaded||selected.removed)throw downloadRequired();}
                archive.verify(selected.artifact);proof=new QuarantineStore.CleanupInfo(selected.id,selected.artifact.sha256(),selected.artifact.bytes());
            }
            // Store supplies its last checked and hash-bound owner evidence to this callback.
            var result=store.purge(id,request.expectedDigest(),proof,this::requireUnreferenced);
            // Persisted retries do not depend on memory. An unrelated restarted cache ticket is never a cleanup candidate.
            if(result.state().equals("purged")&&!existing.state().equals("purged")&&selected!=null&&!selected.removed
                &&selected.operationId.equals(id)&&selected.digest.equals(request.expectedDigest())&&selected.artifact.sha256().equals(proof.sha256())&&selected.artifact.bytes()==proof.bytes()){
                archive.remove(selected.artifact);
                synchronized(tickets){for(var t:tickets.values())if(t.artifact.equals(selected.artifact))t.removed=true;}
            }
            return result;
        }catch(IOException e){throw ioFailed();}
    }
    private void requireUnreferenced(QuarantineStore.CleanupView view){
        var refs=preview.freshReferences();
        if(!refs.consistent())throw new ApiException("QUARANTINE_REFERENCES_UNVERIFIED","引用核验未完成，请保留文件并重新检查。",409);
        for(var item:view.plan().items())if(item.kind().equals("image")&&(refs.currentImages().contains(item.id())||refs.historicalImages().contains(item.id())))throw referenced();
        for(var pdf:view.pdfs())if(pdf.resumeId()!=null&&(refs.resumes().contains(pdf.resumeId())||refs.versions().containsKey(pdf.versionId())))throw referenced();
    }
    private void requireQuarantined(Ticket ticket)throws IOException {if(!store.cleanupView(ticket.operationId,ticket.digest).receipt().state().equals("quarantined"))throw conflict();}
    private static void requireStreaming(java.util.function.BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("Archive delivery cancelled");}
    private Ticket ticket(String id,String requestId){var result=tickets.get(requestId);if(result==null)throw new ApiException("QUARANTINE_BACKUP_NOT_FOUND","文件备份凭据不存在，请重新生成并下载。",404);if(!result.operationId.equals(id))throw conflict();return result;}
    private FileBackupReceipt receipt(Ticket t){return new FileBackupReceipt(t.id,t.operationId,t.digest,t.artifact.bytes(),t.artifact.sha256(),t.createdAt,t.expiresAt,t.downloaded&&active(t,clock.instant()));}
    private boolean active(Ticket t,Instant now){return !t.createdAt.isAfter(now)&&t.expiresAt.isAfter(now);}
    private void requireActive(Ticket t){if(!active(t,clock.instant()))throw new ApiException("QUARANTINE_BACKUP_EXPIRED","文件备份凭据已过期，请重新生成并完整下载。",409);}
    private static void validateIds(String id,String requestId){if(!StorageInspector.uuid(id)||!StorageInspector.uuid(requestId))throw invalid();}
    private static ApiException conflict(){return new ApiException("QUARANTINE_CONFLICT","批次或备份凭据已变化，请保留文件并检查记录。",409);}
    private static ApiException referenced(){return new ApiException("QUARANTINE_REFERENCED","本批文件仍被简历或历史引用，请保留文件。",409);}
    private static ApiException downloadRequired(){return new ApiException("QUARANTINE_DOWNLOAD_REQUIRED","请先完整下载本批文件 ZIP 后再确认清理。",409);}
    public QuarantineStore.Receipt quarantine(Request request){
        confirm(request==null?null:request.confirm());
        if(!StorageInspector.uuid(request.operationId())||!sha(request.previewDigest())||request.items()==null||request.items().isEmpty())throw invalid();
        var selected=new TreeMap<String,Selection>();
        for(var item:request.items()){
            if(item==null||item.kind()==null||!Set.of("image","pdf").contains(item.kind())||!StorageInspector.uuid(item.id()))throw invalid();
            selected.put(item.kind()+":"+item.id(),item);
        }
        if(selected.size()>100)throw invalid();
        try(var lease=gate.exclusive()){
            String digest=requestDigest(request.previewDigest(),selected.keySet());
            var existing=store.existing(request.operationId(),digest);if(existing!=null)return existing;
            preview.requireDisplayed(request.previewDigest());var fresh=preview.freshSnapshot();
            if(!fresh.report().digest().equals(request.previewDigest()))throw new ApiException("STORAGE_PREVIEW_CHANGED","候选或引用已变化，请重新检查并确认后再暂存。",409);
            var targets=new ArrayList<QuarantineFiles.Target>();
            for(String key:selected.keySet()){
                var target=fresh.candidates().get(key);if(target==null)throw new ApiException("QUARANTINE_NOT_CANDIDATE","所选文件不再符合暂存条件，请重新检查。",409);
                targets.add(target);
            }
            var plan=new QuarantineFiles.Plan(request.operationId(),digest,request.previewDigest(),fresh.report().checkedAt(),targets);
            store.reserve(plan);
            // Backup failures leave a visible preparing journal. Only explicit recovery may touch originals.
            var backup=backups.create();
            if(backup==null||!StorageInspector.uuid(backup.id())||backup.bytes()<1)throw backupFailed();
            Path archive=backups.download(backup.id());
            if(archive==null||!Files.isRegularFile(archive,LinkOption.NOFOLLOW_LINKS)||Files.size(archive)!=backup.bytes())throw backupFailed();
            return store.moveToQuarantine(plan.id(),backup.id());
        }catch(IOException e){throw ioFailed();}
    }
    public QuarantineStore.Receipt restore(String id,RestoreRequest request){
        confirm(request==null?null:request.confirm());if(!StorageInspector.uuid(id)||!sha(request.expectedDigest()))throw invalid();
        try(var lease=gate.exclusive()){return store.restore(id,request.expectedDigest());}catch(IOException e){throw ioFailed();}
    }
    public QuarantineStore.History history(int page){
        try(var lease=gate.mutation()){return store.history(page);}catch(IOException e){throw ioFailed();}
    }
    private static String requestDigest(String previewDigest,Set<String> keys)throws IOException {
        return ImageService.sha(new ObjectMapper().writeValueAsBytes(List.of(previewDigest,List.copyOf(keys))));
    }
    private static void confirm(Boolean confirm){if(!Boolean.TRUE.equals(confirm))throw new ApiException("QUARANTINE_CONFIRM_REQUIRED","请明确确认暂存或恢复操作。",400);}
    private static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static ApiException invalid(){return new ApiException("QUARANTINE_INVALID","暂存参数无效，请重新选择候选文件。",422);}
    private static ApiException backupFailed(){return new ApiException("BACKUP_FAILED","备份未完成或无法核验，所选文件尚未移动，请检查备份与暂存记录。",503);}
    private static ApiException ioFailed(){return new ApiException("QUARANTINE_IO_FAILED","文件操作未完成，请保留文件并查看暂存记录后重试。",503);}
}
