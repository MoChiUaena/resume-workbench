package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.Set;
import java.util.concurrent.Semaphore;

@Service
public class AutomaticBackups {
    public record Policy(@NotNull Boolean enabled,@NotNull @Pattern(regexp="daily|weekly") String frequency) {}
    public record State(int version,boolean enabled,String frequency,Instant nextCheck,String fingerprint,
                        Instant lastCheck,String outcome,String errorCode,BackupService.Created lastBackup) {}
    public record Status(State state,boolean running,boolean settingsReadable) {}
    private final BackupService backups;
    private final ObjectMapper mapper;
    private final Path file;
    private final Clock clock;
    private final Semaphore operation=new Semaphore(1);
    private volatile State state;
    private volatile boolean settingsReadable=true,running=false;
    @org.springframework.beans.factory.annotation.Autowired
    public AutomaticBackups(BackupService backups,ObjectMapper mapper,@Value("${resume.data-dir}") String data){this(backups,mapper,Path.of(data),Clock.systemUTC());}
    AutomaticBackups(BackupService backups,ObjectMapper mapper,Path data,Clock clock){
        this.backups=backups;this.mapper=mapper;this.file=data.toAbsolutePath().resolve("backups/automatic-settings.json");this.clock=clock;
        state=new State(1,false,"daily",null,null,null,"never",null,null);
        if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)){
            try{
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>16384)throw new IOException("Invalid settings");
                var stored=mapper.readValue(file.toFile(),State.class);
                if(stored.version()!=1||!Set.of("daily","weekly").contains(stored.frequency())||(stored.fingerprint()!=null&&!stored.fingerprint().matches("[0-9a-f]{64}")))throw new IOException("Invalid settings");
                state=stored;
            }catch(Exception ignored){settingsReadable=false;}
        }
    }
    public Status status(){return new Status(state,running,settingsReadable);}
    public Status configure(Policy policy){
        if(policy==null||policy.enabled()==null||policy.frequency()==null||!Set.of("daily","weekly").contains(policy.frequency()))throw new ApiException("INVALID_INPUT","请选择每日或每周的自动备份频率。",400);
        if(!operation.tryAcquire())throw busy();
        try{
            var old=state;Instant next=policy.enabled()?(!old.enabled()||!old.frequency().equals(policy.frequency())?clock.instant():old.nextCheck()):null;
            var updated=new State(1,policy.enabled(),policy.frequency(),next,old.fingerprint(),old.lastCheck(),old.outcome(),old.errorCode(),old.lastBackup());
            persist(updated);state=updated;settingsReadable=true;return status();
        }finally{operation.release();}
    }
    @Scheduled(initialDelayString="${resume.auto-backup-poll-ms:60000}",fixedDelayString="${resume.auto-backup-poll-ms:60000}")
    public void poll(){check(false);}
    public Status checkNow(){if(!state.enabled()||!settingsReadable)throw new ApiException("AUTO_BACKUP_DISABLED","请先保存并启用自动备份设置。",422);return check(true);}
    private Status check(boolean force){
        if(!settingsReadable||!state.enabled()||(!force&&state.nextCheck()!=null&&clock.instant().isBefore(state.nextCheck())))return status();
        if(!operation.tryAcquire()){if(force)throw busy();return status();}
        try{
            if(!state.enabled())return status();
            var old=state;running=true;Instant checked=clock.instant();
            try{
                var result=backups.automatic(old.fingerprint());
                var updated=new State(1,true,old.frequency(),checked.plus(Duration.ofDays(old.frequency().equals("weekly")?7:1)),result.fingerprint(),checked,result.outcome(),null,result.backup()==null?old.lastBackup():result.backup());
                persist(updated);state=updated;
            }catch(Exception failure){
                String code=failure instanceof ApiException api?api.code:"AUTO_BACKUP_FAILED";
                var failed=new State(1,old.enabled(),old.frequency(),checked.plusSeconds(300),old.fingerprint(),checked,"failed",code,old.lastBackup());
                state=failed;try{persist(failed);}catch(ApiException ignored){settingsReadable=false;}
            }
        }finally{running=false;operation.release();}
        return status();
    }
    private void persist(State value){
        Path temp=null;
        try{Files.createDirectories(file.getParent());temp=Files.createTempFile(file.getParent(),"automatic-settings-",".tmp");mapper.writeValue(temp.toFile(),value);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(IOException e){throw new ApiException("AUTO_BACKUP_SETTINGS_FAILED","自动备份设置无法写入，已有备份保留，请检查数据目录后重试。",503);}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(IOException ignored){}}
    }
    private ApiException busy(){return new ApiException("AUTO_BACKUP_BUSY","自动备份检查正在进行，请稍后重试。",423);}
}
