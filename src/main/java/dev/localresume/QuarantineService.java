package dev.localresume;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class QuarantineService {
    public record Selection(String kind,String id) {}
    public record Request(String operationId,String previewDigest,List<Selection> items,Boolean confirm) {}
    public record RestoreRequest(String expectedDigest,Boolean confirm) {}
    interface Backup {BackupService.Created create();Path download(String id);}
    private final StoragePreviewService preview;
    private final QuarantineStore store;
    private final Backup backups;
    private final WorkspaceGate gate;
    @Autowired
    public QuarantineService(StoragePreviewService preview,QuarantineStore store,BackupService backups,WorkspaceGate gate){
        this(preview,store,new Backup(){public BackupService.Created create(){return backups.create();}public Path download(String id){return backups.download(id);}},gate);
    }
    QuarantineService(StoragePreviewService preview,QuarantineStore store,Backup backups,WorkspaceGate gate){this.preview=preview;this.store=store;this.backups=backups;this.gate=gate;}
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
    private static ApiException ioFailed(){return new ApiException("QUARANTINE_IO_FAILED","暂存或恢复未完成，请保留文件并查看暂存记录后重试。",503);}
}
