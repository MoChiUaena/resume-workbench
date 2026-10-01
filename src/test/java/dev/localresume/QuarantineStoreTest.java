package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Assumptions;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class QuarantineStoreTest {
    @TempDir Path data;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    final Instant now=Instant.parse("2026-10-01T08:00:00Z"), old=now.minus(Duration.ofDays(40));
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    static final String IMAGE="11111111-1111-4111-8111-111111111111", PDF="22222222-2222-4222-8222-222222222222";
    static final String OP="33333333-3333-4333-8333-333333333333", BACKUP="44444444-4444-4444-8444-444444444444";
    QuarantineStore store(){return new QuarantineStore(data,mapper,clock);}
    QuarantineFiles.Entry write(String path,String content)throws Exception {
        Path file=data.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);Files.setLastModifiedTime(file,FileTime.from(old));
        return new QuarantineFiles.Entry(path,Files.size(file),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))),old);
    }
    QuarantineFiles.Target image()throws Exception {
        var files=List.of(write("attachments/"+IMAGE+"/metadata.json","metadata-canary"),write("attachments/"+IMAGE+"/image.png","normalized-canary"),write("attachments/"+IMAGE+"/original.png","original-canary"));
        return target("image",IMAGE,files);
    }
    QuarantineFiles.Target pdf()throws Exception{return target("pdf",PDF,List.of(write("exports/"+PDF+".pdf","%PDF-pdf-canary"),write("exports/"+PDF+".json","pdf-metadata-canary")));}
    QuarantineFiles.Target target(String kind,String id,List<QuarantineFiles.Entry> files){return new QuarantineFiles.Target(kind,id,files.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),files);}
    QuarantineFiles.Plan plan(QuarantineFiles.Target... items){return new QuarantineFiles.Plan(OP,"a".repeat(64),"b".repeat(64),now,List.of(items));}
    Path payload(String path){return data.resolve("quarantine/"+OP+"/payload/"+path);}
    void quarantine(QuarantineFiles.Plan plan)throws Exception{store().reserve(plan);store().moveToQuarantine(OP,BACKUP);}

    // Catches omitted original files, incorrect move direction, missing durable state and missing renewed protection.
    @Test void entireImageAndPdfPairRoundTripAndRestoredRetryIsStable()throws Exception {
        var plan=plan(image(),pdf());var store=store();store.reserve(plan);var held=store.moveToQuarantine(OP,BACKUP);
        assertThat(data.resolve("attachments/"+IMAGE)).doesNotExist();assertThat(data.resolve("exports/"+PDF+".pdf")).doesNotExist();assertThat(data.resolve("exports/"+PDF+".json")).doesNotExist();
        assertThat(Files.readString(payload("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");assertThat(Files.readString(payload("exports/"+PDF+".pdf"))).isEqualTo("%PDF-pdf-canary");
        assertThat(held.state()).isEqualTo("quarantined");assertThat(held.backupId()).isEqualTo(BACKUP);
        assertThat(mapper.writeValueAsString(held)).doesNotContain("attachments/","exports/","original.png",data.toString());
        var restored=new QuarantineStore(data,mapper,clock).restore(OP,held.digest());assertThat(restored.state()).isEqualTo("restored");
        assertThat(Files.readString(data.resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");
        assertThat(Files.getLastModifiedTime(data.resolve("exports/"+PDF+".json")).toInstant()).isEqualTo(now);
        assertThat(store.restore(OP,held.digest())).isEqualTo(restored);
        assertThatThrownBy(()->store.restore(OP,"f".repeat(64))).isInstanceOf(ApiException.class);
    }
    // Catches moving before validating the final planned target and target overwrites.
    @Test void movePreflightRejectsChangedLastFileBeforeMovingAnyImage()throws Exception {
        var plan=plan(image(),pdf());store().reserve(plan);Files.writeString(data.resolve("exports/"+PDF+".json"),"changed");
        assertThatThrownBy(()->store().moveToQuarantine(OP,BACKUP)).isInstanceOf(ApiException.class);
        assertThat(Files.readString(data.resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");assertThat(payload("attachments/"+IMAGE)).doesNotExist();
    }
    @Test void restorePreflightConflictLeavesEveryPayloadAndNewOriginalIntact()throws Exception {
        quarantine(plan(image(),pdf()));var held=store().existing(OP);Files.writeString(data.resolve("exports/"+PDF+".json"),"new-original-canary");
        assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);
        assertThat(Files.readString(payload("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");assertThat(Files.readString(data.resolve("exports/"+PDF+".json"))).isEqualTo("new-original-canary");
    }
    // Catches trusting payload sizes or ignoring unknown directory entries.
    @Test void corruptSameSizePayloadAndExtraEntriesRejectRestoreWithoutMoving()throws Exception {
        quarantine(plan(image(),pdf()));var held=store().existing(OP);Path file=payload("attachments/"+IMAGE+"/original.png");Files.writeString(file,"original-CANARY");
        assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);assertThat(payload("exports/"+PDF+".pdf")).exists();
        Files.writeString(file,"original-canary");Files.writeString(payload("attachments/"+IMAGE+"/extra.txt"),"extra-canary");
        assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);assertThat(data.resolve("attachments/"+IMAGE)).doesNotExist();
    }
    @Test void reserveRejectsTraversalWrongRelationshipsTotalsAndDuplicateTargets()throws Exception {
        var image=image();var good=plan(image);var store=store();
        var bad=new QuarantineFiles.Entry("attachments/"+IMAGE+"/../../private.txt",1,"a".repeat(64),old);
        assertThatThrownBy(()->store.reserve(plan(target("image",IMAGE,List.of(bad))))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.reserve(plan(new QuarantineFiles.Target("image",IMAGE,image.bytes()+1,image.files())))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->store.reserve(plan(image,image))).isInstanceOf(ApiException.class);
        assertThat(data.resolve("quarantine/"+OP)).doesNotExist();store.reserve(good);assertThat(store.reserve(good).state()).isEqualTo("preparing");
        assertThatThrownBy(()->store.reserve(new QuarantineFiles.Plan(OP,"c".repeat(64),good.previewDigest(),now,good.items()))).isInstanceOf(ApiException.class);
    }
    // Catches checksum-only path trust and silently ignoring corrupt journals.
    @Test void malformedAndChecksumChangedJournalsAreUnreadableWithoutFollowingPaths()throws Exception {
        quarantine(plan(image()));Path journal=data.resolve("quarantine/"+OP+"/journal.json");String original=Files.readString(journal);
        Files.writeString(journal,original.replace("quarantined","restored"));assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);
        assertThat(store().history(0).unreadable()).isEqualTo(1);assertThat(store().inventory()).isEmpty();assertThat(payload("attachments/"+IMAGE+"/original.png")).exists();
        Files.writeString(journal,"{ broken");assertThatThrownBy(()->store().restore(OP,"a".repeat(64))).isInstanceOf(ApiException.class);
    }
    // Real NIO moves prove a later move failure rolls back earlier directory and PDF moves.
    @Test void partialMoveFailureRollsBackActualMovesWithoutDataLoss()throws Exception {
        var plan=plan(image(),pdf());store().reserve(plan);int[] calls={0};
        var failing=new QuarantineStore(data,mapper,clock,(source,destination)->{if(++calls[0]==3)throw new IOException("selected move failure");Files.move(source,destination);});
        var failed=failing.moveToQuarantine(OP,BACKUP);assertThat(failed.state()).isEqualTo("attention");
        assertThat(Files.readString(data.resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");assertThat(Files.readString(data.resolve("exports/"+PDF+".pdf"))).isEqualTo("%PDF-pdf-canary");
        assertThat(store().restore(OP,failed.digest()).state()).isEqualTo("restored");
    }
    // Error simulates process termination after an actual move; fresh instance never moves on GET.
    @Test void freshStoreExplicitlyRecoversInterruptedMoveAndInterruptedRestore()throws Exception {
        var plan=plan(image(),pdf());store().reserve(plan);int[] calls={0};
        var crash=new QuarantineStore(data,mapper,clock,(source,destination)->{Files.move(source,destination);if(++calls[0]==1)throw new AssertionError("process ended");});
        assertThatThrownBy(()->crash.moveToQuarantine(OP,BACKUP)).isInstanceOf(AssertionError.class);
        var reopened=store();var held=reopened.existing(OP);assertThat(held.state()).isEqualTo("moving");assertThat(data.resolve("attachments/"+IMAGE)).doesNotExist();assertThat(data.resolve("exports/"+PDF+".pdf")).exists();
        assertThat(reopened.restore(OP,held.digest()).state()).isEqualTo("restored");
    }
    @Test void restoreCrashCanRetryBoundDigestAfterSomeFilesWereAlreadyReturned()throws Exception {
        quarantine(plan(image(),pdf()));var held=store().existing(OP);int[] calls={0};
        var crash=new QuarantineStore(data,mapper,clock,(source,destination)->{Files.move(source,destination);if(++calls[0]==1)throw new AssertionError("process ended");});
        assertThatThrownBy(()->crash.restore(OP,held.digest())).isInstanceOf(AssertionError.class);assertThat(store().existing(OP).state()).isEqualTo("restoring");
        assertThatThrownBy(()->store().restore(OP,"c".repeat(64))).isInstanceOf(ApiException.class);
        assertThat(store().restore(OP,held.digest()).state()).isEqualTo("restored");assertThat(Files.readString(data.resolve("exports/"+PDF+".pdf"))).isEqualTo("%PDF-pdf-canary");
    }
    @Test void inventoryReportsActualSizesAndStructuralCompletenessWithoutClaimingHashes()throws Exception {
        quarantine(plan(image(),pdf()));assertThat(store().inventory()).hasSize(2).allMatch(QuarantineStore.HeldItem::complete);
        Files.writeString(payload("attachments/"+IMAGE+"/original.png"),"original-CANARY");assertThat(store().inventory()).filteredOn(i->i.kind().equals("image")).allMatch(QuarantineStore.HeldItem::complete);
        Files.delete(payload("exports/"+PDF+".json"));assertThat(store().inventory()).filteredOn(i->i.kind().equals("pdf")).allMatch(i->!i.complete());
    }
    @Test void symlinkInSourceOrRootIsRejectedWithoutChangingLinkedFile()throws Exception {
        var plan=plan(image());Path source=data.resolve("attachments/"+IMAGE+"/original.png"),outside=data.resolve("outside-canary");Files.writeString(outside,"original-canary");Files.delete(source);
        try{Files.createSymbolicLink(source,outside);}catch(FileSystemException|UnsupportedOperationException e){Assumptions.abort("Symlink creation unavailable: "+e.getClass().getSimpleName());}
        store().reserve(plan);assertThatThrownBy(()->store().moveToQuarantine(OP,BACKUP)).isInstanceOf(ApiException.class);assertThat(Files.readString(outside)).isEqualTo("original-canary");
    }
    @Test void competingOperationCannotReserveIdentityHeldByUnfinishedJournal()throws Exception {
        var plan=plan(image());store().reserve(plan);String other="55555555-5555-4555-8555-555555555555";
        assertThatThrownBy(()->store().reserve(new QuarantineFiles.Plan(other,"c".repeat(64),plan.previewDigest(),now,plan.items()))).isInstanceOf(ApiException.class);
        assertThat(data.resolve("quarantine/"+other)).doesNotExist();assertThat(Files.readString(data.resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");
    }
    @Test void originalRequestBindingIsCheckedBeforeRetryingCompletedOperation()throws Exception {
        quarantine(plan(image()));assertThat(store().existing(OP,"a".repeat(64)).state()).isEqualTo("quarantined");
        assertThatThrownBy(()->store().existing(OP,"c".repeat(64))).isInstanceOf(ApiException.class);
        assertThat(store().existing("55555555-5555-4555-8555-555555555555","a".repeat(64))).isNull();
    }
    @Test void preexistingPayloadIsNeverAdoptedOrRolledBackByMovePreflight()throws Exception {
        var plan=plan(image());store().reserve(plan);Path destination=payload("attachments/"+IMAGE);Files.createDirectories(destination.getParent());Files.move(data.resolve("attachments/"+IMAGE),destination);
        assertThatThrownBy(()->store().moveToQuarantine(OP,BACKUP)).isInstanceOf(ApiException.class);
        assertThat(data.resolve("attachments/"+IMAGE)).doesNotExist();assertThat(Files.readString(destination.resolve("original.png"))).isEqualTo("original-canary");
    }
    @Test void recomputedChecksumCannotAuthorizeTraversingJournalPaths()throws Exception {
        quarantine(plan(image()));Path journal=data.resolve("quarantine/"+OP+"/journal.json");var envelope=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(journal));
        var file=(com.fasterxml.jackson.databind.node.ObjectNode)envelope.path("journal").path("plan").path("items").get(0).path("files").get(0);file.put("path","attachments/"+IMAGE+"/../../outside-canary");
        envelope.put("digest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(envelope.get("journal")))));Files.write(journal,mapper.writeValueAsBytes(envelope));
        assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);assertThat(payload("attachments/"+IMAGE+"/original.png")).exists();
    }
    @Test void nullKindAndOverLimitEvidenceFailAtStoreBoundary()throws Exception {
        var image=image();assertThatThrownBy(()->store().reserve(plan(new QuarantineFiles.Target(null,IMAGE,image.bytes(),image.files())))).isInstanceOf(ApiException.class);
        var large=List.of(new QuarantineFiles.Entry("exports/"+PDF+".pdf",1024L*1024*1024,"a".repeat(64),old),new QuarantineFiles.Entry("exports/"+PDF+".json",1,"a".repeat(64),old));
        assertThatThrownBy(()->store().reserve(plan(target("pdf",PDF,large)))).isInstanceOf(ApiException.class);assertThat(data.resolve("quarantine/"+OP)).doesNotExist();
    }
    @Test void inventoryIncludesActualUnexpectedBytesAndEmptyHeldImageDirectory()throws Exception {
        var image=image();quarantine(plan(image));Files.writeString(payload("attachments/"+IMAGE+"/extra.txt"),"12345");
        assertThat(store().inventory()).singleElement().satisfies(i->{assertThat(i.bytes()).isEqualTo(image.bytes()+5);assertThat(i.complete()).isFalse();});
        try(var files=Files.list(payload("attachments/"+IMAGE))){for(Path file:files.toList())Files.delete(file);}
        assertThat(store().inventory()).singleElement().satisfies(i->{assertThat(i.bytes()).isZero();assertThat(i.complete()).isFalse();});
    }
    @Test void rollbackCannotOverwriteNewOriginalAndLeavesRecoverablePayload()throws Exception {
        var plan=plan(image(),pdf());store().reserve(plan);int[] calls={0};
        var failing=new QuarantineStore(data,mapper,clock,(source,destination)->{if(++calls[0]==2){Files.createDirectories(data.resolve("attachments/"+IMAGE));Files.writeString(data.resolve("attachments/"+IMAGE+"/new-canary"),"new-original");throw new IOException("move failed");}Files.move(source,destination);});
        var receipt=failing.moveToQuarantine(OP,BACKUP);assertThat(receipt.state()).isEqualTo("attention");assertThat(Files.readString(payload("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");
        assertThat(Files.readString(data.resolve("attachments/"+IMAGE+"/new-canary"))).isEqualTo("new-original");assertThatThrownBy(()->store().restore(OP,receipt.digest())).isInstanceOf(ApiException.class);
    }
    void directoryLink(Path link,Path target)throws Exception {
        try{Files.createSymbolicLink(link,target);return;}catch(FileSystemException|UnsupportedOperationException e){
            Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"),"Directory links unavailable");
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            String output=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).withFailMessage("Junction creation failed: %s",output).isZero();
        }
    }
    @Test void directoryJunctionInSourceIsRejectedWithoutFollowingOrMovingIt()throws Exception {
        var plan=plan(image());Path image=data.resolve("attachments/"+IMAGE),outside=data.resolve("linked-source-canary");Files.move(image,outside);directoryLink(image,outside);
        store().reserve(plan);assertThatThrownBy(()->store().moveToQuarantine(OP,BACKUP)).isInstanceOf(ApiException.class);assertThat(Files.readString(outside.resolve("original.png"))).isEqualTo("original-canary");
        Files.delete(image);
    }
    @Test void directoryJunctionInPayloadAndDataRootIsRejectedBeforeRestore()throws Exception {
        quarantine(plan(image(),pdf()));var receipt=store().existing(OP);Path image=payload("attachments/"+IMAGE),outside=data.resolve("linked-payload-canary");Files.move(image,outside);directoryLink(image,outside);
        assertThatThrownBy(()->store().restore(OP,receipt.digest())).isInstanceOf(ApiException.class);assertThat(payload("exports/"+PDF+".pdf")).exists();assertThat(Files.readString(outside.resolve("original.png"))).isEqualTo("original-canary");
        Files.delete(image);Path rootLink=data.resolve("root-link");directoryLink(rootLink,data);
        assertThatThrownBy(()->new QuarantineStore(rootLink,mapper,clock).existing(OP)).isInstanceOf(ApiException.class);Files.delete(rootLink);
    }
    @Test void historyIsReadOnlyPagedAndKeepsUnreadableEntriesCounted()throws Exception {
        var image=image();for(int index=0;index<21;index++){
            String operation=String.format("%08x-6666-4666-8666-666666666666",index);var plan=new QuarantineFiles.Plan(operation,"a".repeat(64),"b".repeat(64),now.plusSeconds(index),List.of(image));
            // Completed receipts release logical ownership and retain history independently.
            store().reserve(plan);var held=store().moveToQuarantine(operation,BACKUP);store().restore(operation,held.digest());
            for(var entry:image.files())Files.setLastModifiedTime(data.resolve(entry.path()),FileTime.from(old));
        }
        Path bad=data.resolve("quarantine/77777777-7777-4777-8777-777777777777");Files.createDirectory(bad);Files.writeString(bad.resolve("journal.json"),"bad");
        var first=store().history(0);assertThat(first.items()).hasSize(20);assertThat(first.items().getFirst().id()).isEqualTo("00000014-6666-4666-8666-666666666666");assertThat(first.hasMore()).isTrue();assertThat(first.unreadable()).isEqualTo(1);
        var second=store().history(1);assertThat(second.items()).hasSize(1);assertThat(second.hasMore()).isFalse();assertThat(Files.readString(bad.resolve("journal.json"))).isEqualTo("bad");assertThat(store().history(2).items()).isEmpty();
    }
    @Test void sameOperationCannotBindDifferentPreviewEvenWithSameSuppliedRequestDigest()throws Exception {
        var plan=plan(image());store().reserve(plan);
        assertThatThrownBy(()->store().reserve(new QuarantineFiles.Plan(OP,plan.requestDigest(),"c".repeat(64),now,plan.items()))).isInstanceOf(ApiException.class);
    }
    @Test void missingPayloadOrDuplicateOriginalRejectsEntireRestoreBeforeMoving()throws Exception {
        quarantine(plan(image(),pdf()));var held=store().existing(OP);Path pdf=payload("exports/"+PDF+".pdf");byte[] canary=Files.readAllBytes(pdf);Files.delete(pdf);
        assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);assertThat(payload("attachments/"+IMAGE)).exists();Files.write(pdf,canary);
        Files.copy(pdf,data.resolve("exports/"+PDF+".pdf"));assertThatThrownBy(()->store().restore(OP,held.digest())).isInstanceOf(ApiException.class);assertThat(payload("attachments/"+IMAGE)).exists();
    }
    @Test void partialRestoreIoFailureKeepsBoundDigestAndFreshStoreFinishesRecovery()throws Exception {
        quarantine(plan(image(),pdf()));var held=store().existing(OP);int[] calls={0};
        var failure=new QuarantineStore(data,mapper,clock,(source,destination)->{if(++calls[0]==2)throw new IOException("restore move failed");Files.move(source,destination);});
        var attention=failure.restore(OP,held.digest());assertThat(attention.state()).isEqualTo("attention");assertThat(data.resolve("attachments/"+IMAGE)).exists();assertThat(payload("exports/"+PDF+".pdf")).exists();
        assertThat(attention.digest()).isEqualTo(held.digest());var freshHistory=store().history(0).items().getFirst();
        assertThat(freshHistory.digest()).isEqualTo(held.digest());assertThat(store().restore(OP,freshHistory.digest()).state()).isEqualTo("restored");
        assertThat(store().restore(OP,held.digest()).state()).isEqualTo("restored");
    }
    @Test void journalRejectsOverflowingVersionAndTrailingJsonEvenWithUnchangedChecksum()throws Exception {
        store().reserve(plan(image()));Path journal=data.resolve("quarantine/"+OP+"/journal.json");String original=Files.readString(journal);
        Files.writeString(journal,original.replace("\"version\":1","\"version\":4294967297"));assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);
        Files.writeString(journal,original+" {}");assertThatThrownBy(()->store().existing(OP)).isInstanceOf(ApiException.class);
    }
    @Test void moreThanOneHundredDistinctTargetsCannotBeReserved()throws Exception {
        var targets=new ArrayList<QuarantineFiles.Target>();for(int index=0;index<101;index++){
            String id=String.format("%08x-9999-4999-8999-999999999999",index);var files=List.of(new QuarantineFiles.Entry("exports/"+id+".pdf",1,"a".repeat(64),old),new QuarantineFiles.Entry("exports/"+id+".json",1,"a".repeat(64),old));targets.add(target("pdf",id,files));
        }
        assertThatThrownBy(()->store().reserve(new QuarantineFiles.Plan(OP,"a".repeat(64),"b".repeat(64),now,targets))).isInstanceOf(ApiException.class);assertThat(data.resolve("quarantine")).doesNotExist();
    }
    @Test void partialOrInterruptedBatchNeverClaimsCompleteHeldTargets()throws Exception {
        quarantine(plan(image(),pdf()));Files.delete(payload("exports/"+PDF+".json"));assertThat(store().inventory()).hasSize(2).allMatch(i->!i.complete());
    }
}
