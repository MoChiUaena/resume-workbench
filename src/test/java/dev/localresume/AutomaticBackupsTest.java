package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticBackupsTest {
    @TempDir Path data;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    static class Time extends Clock {
        Instant now=Instant.parse("2026-09-29T00:00:00Z");
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
    @Test void disabledByDefaultThenRunsDueChecksAndRestartsWithoutDuplicateOrDeletingFiles()throws Exception {
        var backups=mock(BackupService.class);var clock=new Time();
        var created=new BackupService.Created(UUID.randomUUID().toString(),100,1,2,2,1,clock.instant());
        when(backups.automatic(null)).thenReturn(new BackupService.AutomaticResult(created,"1".repeat(64),"created"));
        when(backups.automatic("1".repeat(64))).thenReturn(new BackupService.AutomaticResult(null,"1".repeat(64),"unchanged"));
        var auto=new AutomaticBackups(backups,mapper,data,clock);auto.poll();verifyNoInteractions(backups);
        auto.configure(new AutomaticBackups.Policy(true,"daily"));auto.poll();assertThat(auto.status().state().lastBackup()).isEqualTo(created);
        assertThat(auto.status().state().nextCheck()).isEqualTo(clock.instant().plusSeconds(86400));assertThat(auto.status().running()).isFalse();
        var restarted=new AutomaticBackups(backups,mapper,data,clock);restarted.poll();verify(backups,times(1)).automatic(null);
        clock.now=clock.now.plusSeconds(3*86400);restarted.poll();assertThat(restarted.status().state().outcome()).isEqualTo("unchanged");
        verify(backups,times(1)).automatic("1".repeat(64));assertThat(restarted.status().state().lastBackup()).isEqualTo(created);
        Path manual=data.resolve("backups/retained-user-file.zip");Files.writeString(manual,"manual bytes");
        restarted.configure(new AutomaticBackups.Policy(false,"weekly"));clock.now=clock.now.plusSeconds(7*86400);restarted.poll();
        assertThat(Files.readString(manual)).isEqualTo("manual bytes");assertThat(new AutomaticBackups(backups,mapper,data,clock).status().state().enabled()).isFalse();
        verifyNoMoreInteractions(backups);
    }
    @Test void failedBackupKeepsLastSuccessAndRetriesAfterFiveMinutes() {
        var backups=mock(BackupService.class);var clock=new Time();var created=new BackupService.Created(UUID.randomUUID().toString(),100,1,1,0,0,clock.instant());
        when(backups.automatic(null)).thenReturn(new BackupService.AutomaticResult(created,"a".repeat(64),"created"));
        when(backups.automatic("a".repeat(64))).thenThrow(new ApiException("BACKUP_FAILED","synthetic",503)).thenReturn(new BackupService.AutomaticResult(null,"a".repeat(64),"unchanged"));
        var auto=new AutomaticBackups(backups,mapper,data,clock);auto.configure(new AutomaticBackups.Policy(true,"weekly"));auto.checkNow();clock.now=clock.now.plusSeconds(7*86400);auto.poll();
        assertThat(auto.status().state().outcome()).isEqualTo("failed");assertThat(auto.status().state().lastBackup()).isEqualTo(created);assertThat(auto.status().state().fingerprint()).isEqualTo("a".repeat(64));
        clock.now=clock.now.plusSeconds(299);auto.poll();verify(backups,times(1)).automatic("a".repeat(64));
        clock.now=clock.now.plusSeconds(1);auto.poll();assertThat(auto.status().state().outcome()).isEqualTo("unchanged");assertThat(auto.status().state().errorCode()).isNull();
    }
    @Test void unreadableSettingsPauseWithoutOverwritingAndExplicitSaveRecovers()throws Exception {
        Files.createDirectories(data.resolve("backups"));Path settings=data.resolve("backups/automatic-settings.json");Files.writeString(settings,"broken");
        var backups=mock(BackupService.class);var auto=new AutomaticBackups(backups,mapper,data,new Time());auto.poll();verifyNoInteractions(backups);
        assertThat(auto.status().settingsReadable()).isFalse();assertThat(Files.readString(settings)).isEqualTo("broken");
        assertThatThrownBy(auto::checkNow).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AUTO_BACKUP_DISABLED"));
        auto.configure(new AutomaticBackups.Policy(false,"daily"));assertThat(auto.status().settingsReadable()).isTrue();
        assertThat(new AutomaticBackups(backups,mapper,data,new Time()).status().state().enabled()).isFalse();
    }
    @Test void settingsFailureDoesNotEnableAndConcurrentChecksDoNotOverlap()throws Exception {
        var backups=mock(BackupService.class);var clock=new Time();Path blocked=data.resolve("blocked");Files.writeString(blocked,"file");
        var failed=new AutomaticBackups(backups,mapper,blocked,clock);
        assertThatThrownBy(()->failed.configure(new AutomaticBackups.Policy(true,"daily"))).isInstanceOf(ApiException.class);assertThat(failed.status().state().enabled()).isFalse();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(backups.automatic(null)).thenAnswer(call->{started.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return new BackupService.AutomaticResult(null,"b".repeat(64),"empty");});
        var auto=new AutomaticBackups(backups,mapper,data,clock);auto.configure(new AutomaticBackups.Policy(true,"daily"));
        var executor=Executors.newSingleThreadExecutor();try{var running=executor.submit(auto::checkNow);assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();assertThat(auto.status().running()).isTrue();
            assertThatThrownBy(auto::checkNow).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("AUTO_BACKUP_BUSY"));release.countDown();assertThat(running.get(10,TimeUnit.SECONDS).running()).isFalse();verify(backups,times(1)).automatic(null);
        }finally{release.countDown();executor.shutdownNow();}
    }
}
