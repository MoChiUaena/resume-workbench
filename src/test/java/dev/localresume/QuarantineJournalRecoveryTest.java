package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class QuarantineJournalRecoveryTest {
    @TempDir Path data;
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    private static final Instant NOW=Instant.parse("2026-10-01T08:00:00Z"), OLD=NOW.minus(Duration.ofDays(40));
    private static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    private static final String IMAGE="11111111-1111-4111-8111-111111111111", PDF="22222222-2222-4222-8222-222222222222";
    private static final String OP="33333333-3333-4333-8333-333333333333", BACKUP="44444444-4444-4444-8444-444444444444";
    private static final QuarantineStore.Move MOVE=(source,destination)->Files.move(source,destination);
    enum Point { WRITE, FORCE, REPLACE }
    private record Fixture(QuarantineFiles.Plan plan,Map<String,byte[]> files) {}
    @FunctionalInterface private interface FailureAction { void run(String state)throws IOException; }

    private final class FaultIo extends QuarantineJournalIo {
        final Point point;
        final Set<String> states, observed=new HashSet<>();
        Set<String> publishFirst=Set.of();
        FailureAction action=state->{};
        String current="";
        long partialBytes;
        FaultIo(Point point,String... states){this.point=point;this.states=Set.of(states);}
        boolean selected(Point at){return point==at&&states.contains(current);}
        void fail()throws IOException {observed.add(current);action.run(current);}
        @Override void write(FileChannel channel,ByteBuffer bytes)throws IOException {
            if(bytes.position()==0){var copy=bytes.asReadOnlyBuffer();var content=new byte[copy.remaining()];copy.get(content);current=mapper.readTree(content).path("journal").path("state").textValue();}
            if(selected(Point.WRITE)){
                int limit=bytes.limit();bytes.limit(bytes.position()+Math.min(7,bytes.remaining()));
                try{super.write(channel,bytes);partialBytes=channel.position();}finally{bytes.limit(limit);}
                fail();channel.close();super.write(channel,bytes);return;
            }
            super.write(channel,bytes);
        }
        @Override void force(FileChannel channel)throws IOException {
            if(selected(Point.FORCE)){fail();channel.close();}
            super.force(channel);
        }
        @Override void replace(Path source,Path destination)throws IOException {
            if(selected(Point.REPLACE)){
                if(publishFirst.contains(current))super.replace(source,destination);
                fail();throw new IOException("selected atomic journal replacement failure");
            }
            super.replace(source,destination);
        }
    }

    private QuarantineStore store(){return new QuarantineStore(data,mapper,CLOCK);}
    private QuarantineStore store(FaultIo io){return new QuarantineStore(data,mapper,CLOCK,MOVE,io);}
    private Path held(){return data.resolve("quarantine/"+OP+"/payload");}
    private Path journal(){return data.resolve("quarantine/"+OP+"/journal.json");}
    private Fixture seed()throws Exception {
        var contents=new LinkedHashMap<String,String>();
        contents.put("attachments/"+IMAGE+"/metadata.json","image metadata canary");
        contents.put("attachments/"+IMAGE+"/image.png","normalized image canary");
        contents.put("attachments/"+IMAGE+"/original.png","original image canary");
        contents.put("exports/"+PDF+".pdf","%PDF original canary");
        contents.put("exports/"+PDF+".json","pdf metadata canary");
        var files=new LinkedHashMap<String,byte[]>();var image=new ArrayList<QuarantineFiles.Entry>();var pdf=new ArrayList<QuarantineFiles.Entry>();
        for(var item:contents.entrySet()){
            byte[] bytes=item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);files.put(item.getKey(),bytes);
            Path path=data.resolve(item.getKey());Files.createDirectories(path.getParent());Files.write(path,bytes);Files.setLastModifiedTime(path,FileTime.from(OLD));
            var entry=new QuarantineFiles.Entry(item.getKey(),bytes.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),OLD);
            (item.getKey().startsWith("attachments/")?image:pdf).add(entry);
        }
        var targets=List.of(new QuarantineFiles.Target("image",IMAGE,image.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),image),new QuarantineFiles.Target("pdf",PDF,pdf.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),pdf));
        return new Fixture(new QuarantineFiles.Plan(OP,"a".repeat(64),"b".repeat(64),NOW,targets),files);
    }
    private QuarantineStore.Receipt quarantine(Fixture fixture)throws Exception {store().reserve(fixture.plan());return store().moveToQuarantine(OP,BACKUP);}
    private void assertFiles(Fixture fixture,Path base,Instant modified)throws Exception {
        for(var item:fixture.files().entrySet()){
            Path file=base.resolve(item.getKey());assertThat(Files.readAllBytes(file)).as(item.getKey()).isEqualTo(item.getValue());
            assertThat(Files.getLastModifiedTime(file).toInstant()).as(item.getKey()+" timestamp").isEqualTo(modified);
        }
    }
    private void assertExactlyOneLocation(Fixture fixture)throws Exception {
        for(var item:fixture.files().entrySet()){
            Path original=data.resolve(item.getKey()),payload=held().resolve(item.getKey());
            boolean active=Files.exists(original),stored=Files.exists(payload);
            assertThat(active^stored).as(item.getKey()+" has one location").isTrue();
            assertThat(Files.readAllBytes(active?original:payload)).isEqualTo(item.getValue());
        }
    }
    private void assertNoTemps()throws Exception {
        try(var entries=Files.list(journal().getParent())){assertThat(entries.filter(path->path.getFileName().toString().startsWith(".journal-")).toList()).isEmpty();}
    }
    private void assertFault(FaultIo io,String... states){
        assertThat(io.observed).containsExactlyInAnyOrder(states);
        if(io.point==Point.WRITE)assertThat(io.partialBytes).isEqualTo(7);
    }
    private QuarantineStore.Receipt recover(Fixture fixture,String token)throws Exception {
        var result=new AtomicReference<QuarantineStore.Receipt>();
        assertThatCode(()->result.set(store().restore(OP,token))).doesNotThrowAnyException();
        assertThat(result.get().state()).isEqualTo("restored");assertThat(result.get().digest()).isEqualTo(token);
        assertFiles(fixture,data,NOW);assertExactlyOneLocation(fixture);assertNoTemps();return result.get();
    }

    @ParameterizedTest @EnumSource(Point.class)
    void initialReservationFailureNeverMovesOrRetimesOriginals(Point point)throws Exception {
        var fixture=seed();var io=new FaultIo(point,"preparing");
        assertThatThrownBy(()->store(io).reserve(fixture.plan())).isInstanceOf(IOException.class);
        assertFault(io,"preparing");assertFiles(fixture,data,OLD);assertExactlyOneLocation(fixture);assertNoTemps();
        assertThat(store().history(0).unreadable()).isEqualTo(1);
        assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);
        assertFiles(fixture,data,OLD);
    }

    @ParameterizedTest @EnumSource(Point.class)
    void movingJournalFailureRetainsExactPreparingJournalAndOriginals(Point point)throws Exception {
        var fixture=seed();var before=store().reserve(fixture.plan());byte[] bytes=Files.readAllBytes(journal());var io=new FaultIo(point,"moving");
        assertThatThrownBy(()->store(io).moveToQuarantine(OP,BACKUP)).isInstanceOf(IOException.class);
        assertFault(io,"moving");assertThat(Files.readAllBytes(journal())).isEqualTo(bytes);assertNoTemps();
        assertFiles(fixture,data,OLD);assertThat(held()).doesNotExist();assertThat(store().existing(OP)).isEqualTo(before);
        assertThat(store().history(0).items()).containsExactly(before);assertThat(store().inventory()).isEmpty();
        assertFiles(fixture,data,OLD);recover(fixture,before.digest());
    }

    @ParameterizedTest @EnumSource(Point.class)
    void failedFinalQuarantineJournalLeavesReadableAttentionAndRecoverableBytes(Point point)throws Exception {
        var fixture=seed();store().reserve(fixture.plan());var io=new FaultIo(point,"quarantined");
        var receipt=store(io).moveToQuarantine(OP,BACKUP);
        assertFault(io,"quarantined");assertThat(receipt.state()).isEqualTo("attention");assertThat(receipt.errorCode()).isEqualTo("QUARANTINE_MOVE_FAILED");
        assertThat(store().existing(OP)).isEqualTo(receipt);assertFiles(fixture,held(),OLD);assertExactlyOneLocation(fixture);assertNoTemps();recover(fixture,receipt.digest());
    }

    @ParameterizedTest @EnumSource(Point.class)
    void failedFinalQuarantineAndAttentionWritesKeepEarlierMovingJournalRecoverable(Point point)throws Exception {
        var fixture=seed();store().reserve(fixture.plan());var io=new FaultIo(point,"quarantined","attention");
        assertThatThrownBy(()->store(io).moveToQuarantine(OP,BACKUP)).isInstanceOf(IOException.class);
        assertFault(io,"quarantined","attention");assertFiles(fixture,held(),OLD);assertExactlyOneLocation(fixture);assertNoTemps();
        var record=store().existing(OP);assertThat(record.state()).isEqualTo("moving");assertThat(record.backupId()).isEqualTo(BACKUP);
        assertThat(store().history(0).items()).containsExactly(record);assertExactlyOneLocation(fixture);recover(fixture,record.digest());
    }

    @ParameterizedTest @EnumSource(Point.class)
    void initialRestoringJournalFailurePreservesHeldBytesAndOriginalToken(Point point)throws Exception {
        var fixture=seed();var before=quarantine(fixture);byte[] journalBytes=Files.readAllBytes(journal());var io=new FaultIo(point,"restoring");
        assertThatThrownBy(()->store(io).restore(OP,before.digest())).isInstanceOf(IOException.class);
        assertFault(io,"restoring");assertFiles(fixture,held(),OLD);assertExactlyOneLocation(fixture);assertNoTemps();
        assertThat(Files.readAllBytes(journal())).isEqualTo(journalBytes);assertThat(store().existing(OP)).isEqualTo(before);
        assertThat(store().history(0).items()).containsExactly(before);assertFiles(fixture,held(),OLD);recover(fixture,before.digest());
    }

    @ParameterizedTest @EnumSource(Point.class)
    void failedRestoredJournalKeepsBoundTokenAndDoesNotMoveReturnedFilesAgain(Point point)throws Exception {
        var fixture=seed();var held=quarantine(fixture);var io=new FaultIo(point,"restored");
        var attention=store(io).restore(OP,held.digest());assertFault(io,"restored");
        assertThat(attention.state()).isEqualTo("attention");assertThat(attention.digest()).isEqualTo(held.digest());assertFiles(fixture,data,NOW);assertExactlyOneLocation(fixture);
        var noMoves=new QuarantineStore(data,mapper,CLOCK,(source,destination)->{throw new AssertionError("Returned files must not move again");});
        assertThat(noMoves.restore(OP,held.digest()).state()).isEqualTo("restored");assertFiles(fixture,data,NOW);assertNoTemps();
    }

    @ParameterizedTest @EnumSource(Point.class)
    void failedRestoredAndAttentionJournalsRetainStableRecoveryToken(Point point)throws Exception {
        var fixture=seed();var held=quarantine(fixture);var io=new FaultIo(point,"restored","attention");
        assertThatThrownBy(()->store(io).restore(OP,held.digest())).isInstanceOf(IOException.class);
        assertFault(io,"restored","attention");assertFiles(fixture,data,NOW);assertExactlyOneLocation(fixture);assertNoTemps();
        var record=store().existing(OP);assertThat(record.state()).isEqualTo("restoring");assertThat(record.digest()).isEqualTo(held.digest());
        String checksum=mapper.readTree(Files.readAllBytes(journal())).path("digest").textValue();assertThat(checksum).isNotEqualTo(held.digest());
        assertThatThrownBy(()->store().restore(OP,checksum)).isInstanceOf(ApiException.class);assertFiles(fixture,data,NOW);
        var restored=recover(fixture,held.digest());assertThat(store().restore(OP,held.digest())).isEqualTo(restored);
    }

    @ParameterizedTest @EnumSource(Point.class)
    void partialRestoreAndAttentionJournalFailuresRecoverThroughFreshStore(Point point)throws Exception {
        var fixture=seed();var held=quarantine(fixture);var io=new FaultIo(point,"attention");int[] moves={0};
        var failing=new QuarantineStore(data,mapper,CLOCK,(source,destination)->{if(++moves[0]==2)throw new IOException("second restore move failure");Files.move(source,destination);},io);
        assertThatThrownBy(()->failing.restore(OP,held.digest())).isInstanceOf(IOException.class);
        assertFault(io,"attention");assertExactlyOneLocation(fixture);assertNoTemps();
        assertThat(Files.readAllBytes(data.resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo(fixture.files().get("attachments/"+IMAGE+"/original.png"));
        assertThat(Files.readAllBytes(held().resolve("exports/"+PDF+".pdf"))).isEqualTo(fixture.files().get("exports/"+PDF+".pdf"));
        var record=store().history(0).items().getFirst();assertThat(record.state()).isEqualTo("restoring");assertThat(record.digest()).isEqualTo(held.digest());
        assertExactlyOneLocation(fixture);recover(fixture,record.digest());
    }

    // A replacement may have reached disk before an I/O error reaches its caller.
    @Test void publishedQuarantineJournalAndFailedAttentionMustRemainExplicitlyRecoverable()throws Exception {
        var fixture=seed();store().reserve(fixture.plan());var io=new FaultIo(Point.REPLACE,"quarantined","attention");io.publishFirst=Set.of("quarantined");
        assertThatThrownBy(()->store(io).moveToQuarantine(OP,BACKUP)).isInstanceOf(IOException.class);
        assertFault(io,"quarantined","attention");assertExactlyOneLocation(fixture);assertNoTemps();
        var record=store().existing(OP);assertThat(record.state()).isEqualTo("quarantined");assertFiles(fixture,held(),OLD);recover(fixture,record.digest());
    }

    @Test void publishedRestoredJournalStillAcceptsOriginalTokenWithoutAnotherWrite()throws Exception {
        var fixture=seed();var held=quarantine(fixture);var io=new FaultIo(Point.REPLACE,"restored","attention");io.publishFirst=Set.of("restored");
        assertThatThrownBy(()->store(io).restore(OP,held.digest())).isInstanceOf(IOException.class);
        assertFault(io,"restored","attention");assertFiles(fixture,data,NOW);assertExactlyOneLocation(fixture);
        var restored=store().existing(OP);assertThat(restored.state()).isEqualTo("restored");assertThat(restored.digest()).isEqualTo(held.digest());
        var noWrites=new FaultIo(Point.WRITE,"restored","restoring");assertThat(store(noWrites).restore(OP,held.digest())).isEqualTo(restored);assertThat(noWrites.observed).isEmpty();assertNoTemps();
    }

    @Test void stageJournalFailureCannotRollBackOverNewOriginalCanary()throws Exception {
        var fixture=seed();store().reserve(fixture.plan());var io=new FaultIo(Point.REPLACE,"quarantined","attention");
        Path original=data.resolve("attachments/"+IMAGE),canary=original.resolve("new-original.txt");
        io.action=state->{if(state.equals("quarantined")){Files.createDirectory(original);Files.writeString(canary,"new original canary");}};
        assertThatThrownBy(()->store(io).moveToQuarantine(OP,BACKUP)).isInstanceOf(IOException.class);
        assertFault(io,"quarantined","attention");assertThat(Files.readString(canary)).isEqualTo("new original canary");
        assertThat(Files.readAllBytes(held().resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo(fixture.files().get("attachments/"+IMAGE+"/original.png"));
        var record=store().existing(OP);assertThatThrownBy(()->store().restore(OP,record.digest())).isInstanceOf(ApiException.class);
        assertThat(Files.readString(canary)).isEqualTo("new original canary");assertExactlyOneLocation(fixture);
        try(var entries=Files.list(original)){assertThat(entries.toList()).containsExactly(canary);}Files.delete(canary);Files.delete(original);
        recover(fixture,record.digest());
    }

    @Test void interruptedRestoreAndFailedJournalPreserveNewPdfUntilConflictIsResolved()throws Exception {
        var fixture=seed();var held=quarantine(fixture);var io=new FaultIo(Point.REPLACE,"attention");int[] moves={0};
        var failing=new QuarantineStore(data,mapper,CLOCK,(source,destination)->{if(++moves[0]==2)throw new IOException("selected restore failure");Files.move(source,destination);},io);
        assertThatThrownBy(()->failing.restore(OP,held.digest())).isInstanceOf(IOException.class);assertFault(io,"attention");
        Path conflict=data.resolve("exports/"+PDF+".json");Files.writeString(conflict,"new PDF metadata canary",StandardOpenOption.CREATE_NEW);
        assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);
        assertThat(Files.readString(conflict)).isEqualTo("new PDF metadata canary");assertThat(Files.readAllBytes(held().resolve("exports/"+PDF+".json"))).isEqualTo(fixture.files().get("exports/"+PDF+".json"));
        assertThat(store().existing(OP).digest()).isEqualTo(held.digest());Files.delete(conflict);recover(fixture,held.digest());
    }
}
