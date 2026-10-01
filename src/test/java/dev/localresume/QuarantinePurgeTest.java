package dev.localresume;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
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
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class QuarantinePurgeTest {
    @TempDir Path data;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final Instant NOW=Instant.parse("2026-10-01T08:00:00Z"),OLD=NOW.minus(Duration.ofDays(40));
    static final Clock CLOCK=Clock.fixed(NOW,ZoneOffset.UTC);
    static final String IMAGE="11111111-1111-4111-8111-111111111111",PDF="22222222-2222-4222-8222-222222222222",OP="33333333-3333-4333-8333-333333333333",BACKUP="44444444-4444-4444-8444-444444444444",EXPORT="55555555-5555-4555-8555-555555555555",RESUME="66666666-6666-4666-8666-666666666666",VERSION="77777777-7777-4777-8777-777777777777";
    static final QuarantineStore.Move MOVE=(a,b)->Files.move(a,b);
    final QuarantineStore.CleanupInfo PROOF=new QuarantineStore.CleanupInfo(EXPORT,"c".repeat(64),1234);
    QuarantineFiles.Plan plan;
    QuarantineStore.Receipt held;
    final Map<String,byte[]> contents=new LinkedHashMap<>();
    QuarantineStore store(){return new QuarantineStore(data,mapper,CLOCK);}
    Path payload(){return data.resolve("quarantine/"+OP+"/payload");}
    Path discard(){return data.resolve("quarantine/"+OP+"/discard");}
    Path journal(){return data.resolve("quarantine/"+OP+"/journal.json");}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    @BeforeEach void seed()throws Exception {
        contents.put("attachments/"+IMAGE+"/metadata.json","metadata-canary".getBytes());
        contents.put("attachments/"+IMAGE+"/image.png","normalized-canary".getBytes());
        contents.put("attachments/"+IMAGE+"/original.png","original-canary".getBytes());
        contents.put("exports/"+PDF+".json",mapper.writeValueAsBytes(new ExportService.Export(PDF,BACKUP,"a".repeat(64),hash("%PDF-canary".getBytes()),OLD,RESUME,VERSION,3L)));
        contents.put("exports/"+PDF+".pdf","%PDF-canary".getBytes());
        var image=new ArrayList<QuarantineFiles.Entry>();var pdf=new ArrayList<QuarantineFiles.Entry>();
        for(var e:contents.entrySet()){
            Path p=data.resolve(e.getKey());Files.createDirectories(p.getParent());Files.write(p,e.getValue());Files.setLastModifiedTime(p,FileTime.from(OLD));
            (e.getKey().startsWith("attachments/")?image:pdf).add(new QuarantineFiles.Entry(e.getKey(),e.getValue().length,hash(e.getValue()),OLD));
        }
        plan=new QuarantineFiles.Plan(OP,"a".repeat(64),"b".repeat(64),NOW,List.of(new QuarantineFiles.Target("image",IMAGE,image.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),image),new QuarantineFiles.Target("pdf",PDF,pdf.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),pdf)));
        store().reserve(plan);held=store().moveToQuarantine(OP,BACKUP);
    }
    QuarantineStore.Receipt purge(QuarantineStore s)throws Exception{return s.purge(OP,held.digest(),PROOF,v->{});}
    void intact(Path base)throws IOException{for(var e:contents.entrySet())assertThat(Files.readAllBytes(base.resolve(e.getKey()))).isEqualTo(e.getValue());}
    void pending(QuarantineStore.Receipt r){assertThat(r.state()).isEqualTo("purging");assertThat(r.digest()).isEqualTo(held.digest());assertThat(r.cleanup()).isEqualTo(PROOF);}
    @Test void completePurgeBindsProofPersistsTombstoneAndNeverChangesUnrelatedFiles()throws Exception {
        Path outside=data.resolve("unrelated-canary");Files.writeString(outside,"untouched");
        var observed=new AtomicInteger();var done=store().purge(OP,held.digest(),PROOF,v->{intact(payload());assertThat(discard()).doesNotExist();assertThat(v.pdfs()).singleElement().satisfies(p->{assertThat(p.resumeId()).isEqualTo(RESUME);assertThat(p.versionId()).isEqualTo(VERSION);assertThat(p.revision()).isEqualTo(3);});observed.incrementAndGet();});
        assertThat(observed).hasValue(1);assertThat(done.state()).isEqualTo("purged");assertThat(done.cleanup()).isEqualTo(PROOF);assertThat(done.digest()).isEqualTo(held.digest());
        assertThat(payload()).doesNotExist();assertThat(discard()).doesNotExist();assertThat(store().inventory()).isEmpty();
        assertThat(store().purgedInventory()).hasSize(2).allMatch(QuarantineStore.PurgedItem::complete);assertThat(Files.readString(outside)).isEqualTo("untouched");
        assertThat(purge(store())).isEqualTo(done);assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);
        var tree=mapper.readTree(Files.readAllBytes(journal()));assertThat(tree.path("version").asInt()).isEqualTo(2);assertThat(tree.path("digest").asText()).isNotEqualTo(held.digest());
    }
    @Test void referenceCallbackRejectionPreventsIntentRenameAndDelete()throws Exception {
        byte[] before=Files.readAllBytes(journal());assertThatThrownBy(()->store().purge(OP,held.digest(),PROOF,v->{throw new ApiException("REFERENCED","in use",409);})).isInstanceOf(ApiException.class);
        intact(payload());assertThat(discard()).doesNotExist();assertThat(Files.readAllBytes(journal())).isEqualTo(before);
    }
    @Test void callbackLateUnknownFileAndNewOriginalAreRejectedBeforeAnyDelete()throws Exception {
        Path late=payload().resolve("unknown-canary");
        assertThatThrownBy(()->store().purge(OP,held.digest(),PROOF,v->Files.writeString(late,"late"))).isInstanceOf(ApiException.class);intact(payload());assertThat(Files.readString(late)).isEqualTo("late");Files.delete(late);
        Path original=data.resolve("attachments/"+IMAGE);assertThatThrownBy(()->store().purge(OP,held.digest(),PROOF,v->{Files.createDirectory(original);Files.writeString(original.resolve("new-canary"),"new original");})).isInstanceOf(ApiException.class);intact(payload());assertThat(Files.readString(original.resolve("new-canary"))).isEqualTo("new original");
    }
    @Test void wrongTokenOrInvalidProofCannotStart()throws Exception {
        assertThatThrownBy(()->store().purge(OP,"d".repeat(64),PROOF,v->{})).isInstanceOf(ApiException.class);
        for(var p:List.of(new QuarantineStore.CleanupInfo("bad",PROOF.sha256(),1),new QuarantineStore.CleanupInfo(EXPORT,"bad",1),new QuarantineStore.CleanupInfo(EXPORT,PROOF.sha256(),0),new QuarantineStore.CleanupInfo(EXPORT,PROOF.sha256(),1024L*1024*1024+1024*1024+1)))assertThatThrownBy(()->store().purge(OP,held.digest(),p,v->{})).isInstanceOf(ApiException.class);
        intact(payload());assertThat(store().existing(OP).state()).isEqualTo("quarantined");
    }
    enum Point { WRITE,FORCE,REPLACE }
    class FaultIo extends QuarantineJournalIo {
        final Point point;final String selected;final boolean committed;String state;
        FaultIo(Point point,String selected,boolean committed){this.point=point;this.selected=selected;this.committed=committed;}
        boolean fail(Point at){return point==at&&selected.equals(state);}
        @Override void write(FileChannel c,ByteBuffer b)throws IOException {if(b.position()==0){byte[] bytes=new byte[b.remaining()];b.asReadOnlyBuffer().get(bytes);state=mapper.readTree(bytes).path("journal").path("state").asText();}if(fail(Point.WRITE)){var part=b.slice();part.limit(Math.min(7,part.remaining()));super.write(c,part);throw new IOException("partial journal write");}super.write(c,b);}
        @Override void force(FileChannel c)throws IOException {if(fail(Point.FORCE))throw new IOException("force");super.force(c);}
        @Override void replace(Path a,Path b)throws IOException {if(fail(Point.REPLACE)){if(committed)super.replace(a,b);throw new IOException("replace");}super.replace(a,b);}
    }
    @ParameterizedTest @EnumSource(Point.class) void initialIntentFailureRetainsAllPayloadAndNormalJournal(Point point)throws Exception {
        byte[] before=Files.readAllBytes(journal());var io=new FaultIo(point,"purging",false);
        assertThatThrownBy(()->purge(new QuarantineStore(data,mapper,CLOCK,MOVE,io))).isInstanceOf(IOException.class);intact(payload());assertThat(discard()).doesNotExist();assertThat(Files.readAllBytes(journal())).isEqualTo(before);
    }
    @Test void committedIntentReportedFailureRequiresExplicitFreshRetry()throws Exception {
        var io=new FaultIo(Point.REPLACE,"purging",true);assertThatThrownBy(()->purge(new QuarantineStore(data,mapper,CLOCK,MOVE,io))).isInstanceOf(IOException.class);
        pending(store().existing(OP));intact(payload());assertThat(discard()).doesNotExist();store().history(0);store().inventory();store().cleanupView(OP,held.digest());intact(payload());assertThat(purge(store()).state()).isEqualTo("purged");
    }
    QuarantineStore partial()throws Exception {
        return new QuarantineStore(data,mapper,CLOCK,MOVE,new QuarantineJournalIo(),p->{Files.delete(p);if(p.toString().endsWith(PDF+".json"))throw new IOException("unlink completed before error");});
    }
    @Test void actualMetadataUnlinkThenIoErrorRetainsSealedOwnersAndFreshSameProofRetry()throws Exception {
        var failed=purge(partial());pending(failed);assertThat(failed.errorCode()).isEqualTo("QUARANTINE_PURGE_FAILED");assertThat(payload()).doesNotExist();assertThat(discard().resolve("exports/"+PDF+".json")).doesNotExist();assertThat(discard().resolve("exports/"+PDF+".pdf")).exists();
        assertThat(store().inventory()).filteredOn(i->i.kind().equals("pdf")).singleElement().satisfies(i->{assertThat(i.bytes()).isEqualTo(11);assertThat(i.complete()).isFalse();});
        assertThat(store().inventory()).filteredOn(i->i.kind().equals("image")).singleElement().satisfies(i->{assertThat(i.bytes()).isZero();assertThat(i.complete()).isFalse();});
        var view=store().cleanupView(OP,held.digest());assertThat(view.pdfs()).singleElement().satisfies(p->assertThat(p.resumeId()).isEqualTo(RESUME));
        var checks=new AtomicInteger();assertThat(store().purge(OP,held.digest(),PROOF,v->{assertThat(v.pdfs().getFirst().versionId()).isEqualTo(VERSION);checks.incrementAndGet();}).state()).isEqualTo("purged");assertThat(checks).hasValue(1);
    }
    @Test void partialRetryMustRecheckReferencesAndAcceptOnlyBoundProof()throws Exception {
        purge(partial());Path pdf=discard().resolve("exports/"+PDF+".pdf");
        assertThatThrownBy(()->store().purge(OP,held.digest(),new QuarantineStore.CleanupInfo(BACKUP,PROOF.sha256(),PROOF.bytes()),v->{})).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store().purge(OP,held.digest(),PROOF,v->{throw new ApiException("REFERENCED","changed refs",409);})).isInstanceOf(ApiException.class);assertThat(Files.readString(pdf)).isEqualTo("%PDF-canary");pending(store().existing(OP));
        assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);
    }
    @Test void remainingChangedUnknownDuplicateAndNewOriginalStopRetryWithoutDeletingCanaries()throws Exception {
        purge(partial());Path pdf=discard().resolve("exports/"+PDF+".pdf");Files.writeString(pdf,"%PDF-CANARY");assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(Files.readString(pdf)).isEqualTo("%PDF-CANARY");Files.writeString(pdf,"%PDF-canary");
        Path unknown=discard().resolve("foreign-empty");Files.createDirectory(unknown);assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(pdf).exists();Files.delete(unknown);
        Files.createDirectory(payload());assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(pdf).exists();Files.delete(payload());
        Path original=data.resolve("exports/"+PDF+".pdf");Files.writeString(original,"new original");assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(pdf).exists();assertThat(Files.readString(original)).isEqualTo("new original");
    }
    @ParameterizedTest @EnumSource(Point.class) void finalJournalFailureKeepsBoundIntentAndRetryDoesNotRepeatDeletes(Point point)throws Exception {
        var io=new FaultIo(point,"purged",false);pending(purge(new QuarantineStore(data,mapper,CLOCK,MOVE,io)));assertThat(payload()).doesNotExist();assertThat(discard()).doesNotExist();
        var noDelete=new QuarantineStore(data,mapper,CLOCK,MOVE,new QuarantineJournalIo(),p->{throw new AssertionError("no second unlink");});assertThat(purge(noDelete).state()).isEqualTo("purged");
    }
    @Test void committedFinalJournalErrorNeverDowngradesTombstone()throws Exception {
        var io=new FaultIo(Point.REPLACE,"purged",true);assertThatThrownBy(()->purge(new QuarantineStore(data,mapper,CLOCK,MOVE,io))).isInstanceOf(IOException.class);assertThat(store().existing(OP).state()).isEqualTo("purged");assertThat(purge(store()).state()).isEqualTo("purged");
    }
    @Test void purgeErrorJournalFailureRetainsEarlierIntentAndStableProof()throws Exception {
        var io=new FaultIo(Point.REPLACE,"purging",false){@Override boolean fail(Point p){return super.fail(p)&&presentDiscard();}boolean presentDiscard(){return Files.exists(discard());}};
        var s=new QuarantineStore(data,mapper,CLOCK,MOVE,io,p->{Files.delete(p);throw new IOException("actual unlink error");});assertThatThrownBy(()->purge(s)).isInstanceOf(IOException.class);pending(store().existing(OP));assertThat(purge(store()).state()).isEqualTo("purged");
    }
    @Test void completedPurgeRejectsRecreatedDataAndReleasesLogicalIdentity()throws Exception {
        purge(store());Path fresh=payload().resolve("new-canary");Files.createDirectories(fresh.getParent());Files.writeString(fresh,"new data");assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(Files.readString(fresh)).isEqualTo("new data");assertThat(store().purgedInventory()).allMatch(i->!i.complete());Files.delete(fresh);Files.delete(payload());
        for(var e:contents.entrySet()){Path p=data.resolve(e.getKey());Files.createDirectories(p.getParent());Files.write(p,e.getValue());Files.setLastModifiedTime(p,FileTime.from(OLD));}
        var next=new QuarantineFiles.Plan(BACKUP,"d".repeat(64),plan.previewDigest(),NOW,plan.items());assertThat(store().reserve(next).state()).isEqualTo("preparing");
    }
    @Test void normalVersionOneJournalOmitsPurgeAndRoundTripsLegacyReceipt()throws Exception {
        var tree=mapper.readTree(Files.readAllBytes(journal()));assertThat(tree.path("version").asInt()).isEqualTo(1);assertThat(tree.path("journal").has("purge")).isFalse();assertThat(store().existing(OP)).isEqualTo(held);assertThat(held.cleanup()).isNull();
    }
    @Test void actualDiscardRenameThenIoFailureIsExplicitlyResumable()throws Exception {
        var s=new QuarantineStore(data,mapper,CLOCK,(a,b)->{Files.move(a,b);throw new IOException("rename committed");});pending(purge(s));assertThat(payload()).doesNotExist();intact(discard());assertThat(purge(store()).state()).isEqualTo("purged");
    }
    @Test void unknownFileIntroducedAfterIntentPublicationPreventsRenameAndDeletion()throws Exception {
        var io=new QuarantineJournalIo(){@Override void replace(Path a,Path b)throws IOException {super.replace(a,b);if(mapper.readTree(Files.readAllBytes(b)).path("journal").path("state").asText().equals("purging"))Files.writeString(payload().resolve("late-canary"),"late");}};
        assertThatThrownBy(()->purge(new QuarantineStore(data,mapper,CLOCK,MOVE,io))).isInstanceOf(ApiException.class);pending(store().existing(OP));intact(payload());assertThat(discard()).doesNotExist();assertThat(Files.readString(payload().resolve("late-canary"))).isEqualTo("late");
    }
    @Test void changedLaterFileAfterActualFirstUnlinkStopsBeforeDeletingChangedBytes()throws Exception {
        var calls=new AtomicInteger();Path changed=discard().resolve("exports/"+PDF+".pdf");
        var s=new QuarantineStore(data,mapper,CLOCK,MOVE,new QuarantineJournalIo(),p->{Files.delete(p);if(calls.incrementAndGet()==1)Files.writeString(changed,"%PDF-CANARY");});
        assertThatThrownBy(()->purge(s)).isInstanceOf(ApiException.class);assertThat(Files.readString(changed)).isEqualTo("%PDF-CANARY");pending(store().existing(OP));
    }
    @Test void processStopsAfterRealUnlinkAndFreshReadsNeverResumeAutomatically()throws Exception {
        var s=new QuarantineStore(data,mapper,CLOCK,MOVE,new QuarantineJournalIo(),p->{Files.delete(p);throw new AssertionError("process stop");});assertThatThrownBy(()->purge(s)).isInstanceOf(AssertionError.class);
        pending(store().existing(OP));long before;try(var files=Files.walk(discard())){before=files.filter(Files::isRegularFile).count();}store().history(0);store().inventory();store().cleanupView(OP,held.digest());try(var files=Files.walk(discard())){assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(before);}assertThat(purge(store()).state()).isEqualTo("purged");
    }
    void directoryLink(Path link,Path target)throws Exception {
        try{Files.createSymbolicLink(link,target);return;}catch(FileSystemException|UnsupportedOperationException e){Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();String out=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).withFailMessage("junction: %s",out).isZero();}
    }
    @Test void payloadAndDiscardJunctionsCannotDeleteLinkedCanaries()throws Exception {
        Path image=payload().resolve("attachments/"+IMAGE),outside=data.resolve("linked-canary");Files.move(image,outside);directoryLink(image,outside);
        assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(Files.readString(outside.resolve("original.png"))).isEqualTo("original-canary");Files.delete(image);Files.move(outside,image);
        purge(partial());Path real=data.resolve("real-discard");Files.move(discard(),real);directoryLink(discard(),real);assertThatThrownBy(()->purge(store())).isInstanceOf(ApiException.class);assertThat(Files.readString(real.resolve("exports/"+PDF+".pdf"))).isEqualTo("%PDF-canary");Files.delete(discard());
    }
    @Test void invalidCompletePdfMetadataRefusesInitialPurgeWithoutDeletingAnyFile()throws Exception {
        // Exact bytes/SHA belong to the trusted Plan, but the owner shape is invalid.
        var node=(ObjectNode)mapper.readTree(contents.get("exports/"+PDF+".json"));node.put("resumeId","bad-owner");byte[] metadata=mapper.writeValueAsBytes(node);Path file=payload().resolve("exports/"+PDF+".json");Files.write(file,metadata);
        var envelope=(ObjectNode)mapper.readTree(Files.readAllBytes(journal()));var target=(ObjectNode)envelope.path("journal").path("plan").path("items").get(1);var entries=target.path("files");long bytes=0;for(var entry:entries){if(entry.path("path").asText().endsWith(".json")){((ObjectNode)entry).put("bytes",metadata.length);((ObjectNode)entry).put("sha256",hash(metadata));}bytes+=entry.path("bytes").asLong();}target.put("bytes",bytes);envelope.put("digest",hash(mapper.writeValueAsBytes(envelope.get("journal"))));Files.write(journal(),mapper.writeValueAsBytes(envelope));String token=store().existing(OP).digest();
        assertThatThrownBy(()->store().purge(OP,token,PROOF,v->{})).isInstanceOf(ApiException.class);assertThat(Files.readAllBytes(file)).isEqualTo(metadata);assertThat(payload().resolve("exports/"+PDF+".pdf")).exists();assertThat(discard()).doesNotExist();
    }
    @Test void rehashedV2RequiresExactPurgeFieldShapeAndIntegralArchiveBytes()throws Exception {
        purge(partial());byte[] before=Files.readAllBytes(journal());var envelope=(ObjectNode)mapper.readTree(before);((ObjectNode)envelope.path("journal").path("purge")).put("archiveBytes","1234");
        envelope.put("digest",hash(mapper.writeValueAsBytes(envelope.get("journal"))));Files.write(journal(),mapper.writeValueAsBytes(envelope));assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);
    }
    enum Forgery { BAD_BASE64,WRONG_OWNER_BYTES,MISSING_PDF,EXTRA_PDF,TRAVERSAL,VERSION_ONE,PURGE_ON_NORMAL,NULL_PROOF }
    @ParameterizedTest @EnumSource(Forgery.class) void rehashedJournalCannotForgeSealedOwnersOrVersionRelationships(Forgery f)throws Exception {
        purge(partial());var envelope=(ObjectNode)mapper.readTree(Files.readAllBytes(journal()));var j=(ObjectNode)envelope.get("journal");var p=(ObjectNode)j.get("purge");var list=(com.fasterxml.jackson.databind.node.ArrayNode)p.get("pdfMetadata");
        switch(f){case BAD_BASE64->((ObjectNode)list.get(0)).put("base64","!");case WRONG_OWNER_BYTES->{var forged=(ObjectNode)mapper.readTree(contents.get("exports/"+PDF+".json"));forged.put("resumeId",BACKUP);((ObjectNode)list.get(0)).put("base64",Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(forged)));}case MISSING_PDF->list.removeAll();case EXTRA_PDF->list.add(list.get(0).deepCopy());case TRAVERSAL->((ObjectNode)j.path("plan").path("items").get(0).path("files").get(0)).put("path","../../outside");case VERSION_ONE->envelope.put("version",1);case PURGE_ON_NORMAL->j.put("state","quarantined");case NULL_PROOF->p.putNull("archiveSha256");}
        envelope.put("digest",hash(mapper.writeValueAsBytes(j)));Files.write(journal(),mapper.writeValueAsBytes(envelope));assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);assertThat(discard().resolve("exports/"+PDF+".pdf")).exists();
    }
}
