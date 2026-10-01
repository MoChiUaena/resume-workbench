package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class StorageInspectorTest {
    @TempDir Path data;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    final Instant now=Instant.parse("2026-09-30T08:00:00Z"),old=now.minus(Duration.ofDays(31));
    final Clock clock=Clock.fixed(now,ZoneOffset.UTC);
    StorageInspector.References refs(Map<String,StorageInspector.Catalog> catalogs,Set<String> current,Set<String> historical,Set<String> resumes,Map<String,StorageInspector.Owner> versions,boolean consistent){return new StorageInspector.References(catalogs,current,historical,resumes,versions,consistent,"synthetic-reference-fingerprint");}
    StorageInspector.References empty(){return refs(Map.of(),Set.of(),Set.of(),Set.of(),Map.of(),true);}
    ImageService.Asset image(boolean age)throws Exception {
        var storage=new LocalFileStorage(data.toString(),mapper);var service=new ImageService(storage,5242880,24000000);
        var asset=service.importImage(Files.readAllBytes(Path.of("fixtures/university-logo.png")));age(data.resolve("attachments/"+asset.id()),age?old:now);return asset;
    }
    void age(Path directory)throws Exception {age(directory,old);}
    void age(Path directory,Instant timestamp)throws Exception {try(var paths=Files.list(directory)){for(Path path:paths.toList())Files.setLastModifiedTime(path,FileTime.from(timestamp));}Files.setLastModifiedTime(directory,FileTime.from(timestamp));}
    StorageInspector.Catalog catalog(ImageService.Asset asset)throws Exception{return new StorageInspector.Catalog(mapper.writeValueAsString(asset),old);}
    String pdf(String resume,String version,Long revision,boolean age)throws Exception {
        String id=UUID.randomUUID().toString();Path exports=data.resolve("exports");Files.createDirectories(exports);byte[] bytes="%PDF-1.4\nsynthetic PDF bytes\n%%EOF".getBytes();
        Files.write(exports.resolve(id+".pdf"),bytes);mapper.writeValue(exports.resolve(id+".json").toFile(),new ExportService.Export(id,UUID.randomUUID().toString(),"a".repeat(64),ImageService.sha(bytes),old,resume,version,revision));
        Files.setLastModifiedTime(exports.resolve(id+".pdf"),FileTime.from(age?old:now));Files.setLastModifiedTime(exports.resolve(id+".json"),FileTime.from(age?old:now));return id;
    }
    StorageInspector.Item item(StorageInspector.Report report,String id){return report.items().stream().filter(i->id.equals(i.id())).findFirst().orElseThrow();}
    Map<String,String> fingerprint()throws Exception {var result=new TreeMap<String,String>();try(var paths=Files.walk(data)){for(Path path:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())result.put(data.relativize(path).toString(),ImageService.sha(Files.readAllBytes(path))+Files.getLastModifiedTime(path));}return result;}
    @Test void currentHistoricalRecentAndOldOrphanImagesAreSeparatedWithoutChangingFiles()throws Exception {
        var current=image(true);var historical=image(true);var orphan=image(true);var recent=image(false);var boundary=image(true);
        Path boundaryPath=data.resolve("attachments/"+boundary.id());try(var paths=Files.list(boundaryPath)){for(Path path:paths.toList())Files.setLastModifiedTime(path,FileTime.from(now.minus(Duration.ofDays(30))));}Files.setLastModifiedTime(boundaryPath,FileTime.from(now.minus(Duration.ofDays(30))));
        Files.createDirectories(data.resolve("backups"));Files.writeString(data.resolve("backups/private.zip"),"backup canary");Files.createDirectories(data.resolve("model-settings"));Files.writeString(data.resolve("model-settings/master.key"),"secret key canary");
        var source=refs(Map.of(current.id(),catalog(current),historical.id(),catalog(historical)),Set.of(current.id()),Set.of(historical.id()),Set.of(),Map.of(),true);
        var before=fingerprint();var report=new StorageInspector(data,mapper,source,clock).inspect();
        assertThat(item(report,current.id()).reason()).isEqualTo("current_image");assertThat(item(report,historical.id()).reason()).isEqualTo("historical_image");
        assertThat(item(report,orphan.id()).status()).isEqualTo("candidate");assertThat(item(report,recent.id()).status()).isEqualTo("recent");assertThat(item(report,boundary.id()).status()).isEqualTo("recent");
        assertThat(report.kinds().getFirst().count()).isEqualTo(5);assertThat(report.bytesComplete()).isTrue();assertThat(fingerprint()).isEqualTo(before);
        assertThat(mapper.writeValueAsString(report)).doesNotContain("backup canary","secret key canary","private.zip","master.key",data.toString());
    }
    @Test void pdfOwnershipAndFileIntegrityMustMatchBeforeOldExportsBecomeCandidates()throws Exception {
        String resume=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        String saved=pdf(resume,version,3L,true),deleted=pdf(UUID.randomUUID().toString(),UUID.randomUUID().toString(),2L,true),unlinked=pdf(null,null,null,true),recent=pdf(null,null,null,false),wrongOwner=pdf(resume,UUID.randomUUID().toString(),3L,true),corrupt=pdf(null,null,null,true),incomplete=pdf(null,null,null,true);
        Files.writeString(data.resolve("exports/"+corrupt+".pdf"),"%PDF-tampered synthetic bytes");Files.setLastModifiedTime(data.resolve("exports/"+corrupt+".pdf"),FileTime.from(old));Files.delete(data.resolve("exports/"+incomplete+".pdf"));
        var source=refs(Map.of(),Set.of(),Set.of(),Set.of(resume),Map.of(version,new StorageInspector.Owner(resume,3)),true);
        var report=new StorageInspector(data,mapper,source,clock).inspect();
        assertThat(item(report,saved).status()).isEqualTo("in_use");assertThat(item(report,deleted).reason()).isEqualTo("deleted_pdf");assertThat(item(report,unlinked).reason()).isEqualTo("unlinked_pdf");assertThat(item(report,recent).status()).isEqualTo("recent");
        assertThat(item(report,wrongOwner).reason()).isEqualTo("owner_mismatch");assertThat(item(report,corrupt).reason()).isEqualTo("file_mismatch");assertThat(item(report,incomplete).reason()).isEqualTo("incomplete_pdf");
        assertThat(report.statuses().stream().filter(c->c.key().equals("candidate")).findFirst().orElseThrow().count()).isEqualTo(2);
    }
    @Test void incompleteReferencesPreventCandidatesAndMissingOrInconsistentAssetsStayVisible()throws Exception {
        var orphan=image(true);var mismatched=image(true);String missing=UUID.randomUUID().toString();
        var wrong=new ImageService.Asset(mismatched.id(),mismatched.format(),mismatched.bytes()+1,mismatched.sourceWidth(),mismatched.sourceHeight(),mismatched.width(),mismatched.height(),1,mismatched.sha256(),mismatched.normalizedSha256());
        var source=refs(Map.of(mismatched.id(),catalog(wrong)),Set.of(missing),Set.of(),Set.of(),Map.of(),false);
        var report=new StorageInspector(data,mapper,source,clock).inspect();
        assertThat(report.referencesVerified()).isFalse();assertThat(item(report,orphan.id()).reason()).isEqualTo("references_unverified");assertThat(item(report,missing).reason()).isEqualTo("missing_image");assertThat(item(report,mismatched.id()).reason()).isEqualTo("metadata_mismatch");assertThat(report.items()).noneMatch(i->i.status().equals("candidate"));
    }
    @Test void unexpectedDirectoriesAndLinksAreNotTraversedAndFilenamesAreNotReturned()throws Exception {
        Files.createDirectories(data.resolve("attachments/private-person-name"));Files.writeString(data.resolve("attachments/private-person-name/secret.txt"),"private canary");
        Path outside=Files.createTempDirectory("storage-preview-link-target-");
        try{Files.writeString(outside.resolve("metadata.json"),"secret link canary");Path link=data.resolve("attachments/"+UUID.randomUUID());
            try{Files.createSymbolicLink(link,outside);}catch(FileSystemException|UnsupportedOperationException e){Files.createDirectory(link);Files.createDirectory(link.resolve("unexpected-child"));}
            var report=new StorageInspector(data,mapper,empty(),clock).inspect();assertThat(report.bytesComplete()).isFalse();assertThat(report.items()).allMatch(i->i.status().equals("check"));
            assertThat(mapper.writeValueAsString(report)).doesNotContain("private-person-name","private canary","secret link canary",outside.toString());assertThat(Files.readString(outside.resolve("metadata.json"))).isEqualTo("secret link canary");
        }finally{Files.deleteIfExists(outside.resolve("metadata.json"));Files.deleteIfExists(outside);}
    }
    @Test void fingerprintsTrackFileAndReferenceChangesAndVerificationBudgetsFailClosed()throws Exception {
        var asset=image(true);var first=new StorageInspector(data,mapper,empty(),clock).inspect();assertThat(new StorageInspector(data,mapper,empty(),clock).inspect().digest()).isEqualTo(first.digest());
        var changedSource=new StorageInspector.References(Map.of(),Set.of(),Set.of(),Set.of(),Map.of(),true,"changed-reference-fingerprint");assertThat(new StorageInspector(data,mapper,changedSource,clock).inspect().digest()).isNotEqualTo(first.digest());
        var budget=new StorageInspector(data,mapper,empty(),clock,1).inspect();assertThat(item(budget,asset.id()).reason()).isEqualTo("verification_limit");assertThat(budget.items()).noneMatch(i->i.status().equals("candidate"));
        Path image=data.resolve("attachments/"+asset.id()+"/image.png");Files.writeString(image,"tampered");Files.setLastModifiedTime(image,FileTime.from(old));var changed=new StorageInspector(data,mapper,empty(),clock).inspect();assertThat(changed.digest()).isNotEqualTo(first.digest());assertThat(item(changed,asset.id()).reason()).isEqualTo("file_mismatch");
    }
    @Test void completeDetailsKeepCountsAndSizesForEveryFileBeyondTheFirstPage()throws Exception {
        Files.createDirectories(data.resolve("exports"));for(int index=0;index<205;index++)Files.writeString(data.resolve("exports/private-name-"+index+".tmp"),"123");
        var report=new StorageInspector(data,mapper,empty(),clock).inspect();assertThat(report.items()).hasSize(205);assertThat(report.statuses().stream().filter(c->c.key().equals("check")).findFirst().orElseThrow().count()).isEqualTo(205);assertThat(report.kinds().get(2).bytes()).isEqualTo(615);assertThat(mapper.writeValueAsString(report)).doesNotContain("private-name-");
    }
    @Test void privateSnapshotCarriesTheExactVerifiedFilesButPublicReportNeverExposesEvidence()throws Exception {
        var asset=image(true);String pdf=pdf(null,null,null,true);var snapshot=new StorageInspector(data,mapper,empty(),clock).snapshot(List.of());
        assertThat(snapshot.candidates()).containsOnlyKeys("image:"+asset.id(),"pdf:"+pdf);
        for(var target:snapshot.candidates().values())for(var entry:target.files()){
            Path actual=data.resolve(entry.path());assertThat(entry.sha256()).isEqualTo(ImageService.sha(Files.readAllBytes(actual)));
            assertThat(entry.bytes()).isEqualTo(Files.size(actual));assertThat(entry.modified()).isEqualTo(Files.getLastModifiedTime(actual).toInstant());
        }
        assertThat(snapshot.candidates().get("image:"+asset.id()).files()).hasSize(3);assertThat(snapshot.candidates().get("pdf:"+pdf).files()).hasSize(2);
        assertThat(mapper.writeValueAsString(snapshot.report())).doesNotContain("metadata.json","original.png","sha256","attachments/",data.toString());
        Path broken=data.resolve("exports/"+pdf+".pdf");Files.writeString(broken,"%PDF-corrupt");Files.setLastModifiedTime(broken,FileTime.from(old));
        assertThat(new StorageInspector(data,mapper,empty(),clock).snapshot(List.of()).candidates()).doesNotContainKey("pdf:"+pdf);
    }
    @Test void completeHeldImagesReplaceOnlyUnreferencedMissingOrEmptyOriginalEntries()throws Exception {
        var asset=image(true);var catalog=catalog(asset);BackupArchive.removeTree(data.resolve("attachments/"+asset.id()));
        var held=new QuarantineStore.HeldItem("image",asset.id(),1234,old,true);
        var orphan=refs(Map.of(asset.id(),catalog),Set.of(),Set.of(),Set.of(),Map.of(),true);
        var complete=new StorageInspector(data,mapper,orphan,clock).snapshot(List.of(held)).report();
        assertThat(complete.items()).singleElement().satisfies(i->{assertThat(i.status()).isEqualTo("quarantined");assertThat(i.bytes()).isEqualTo(1234);});
        Files.createDirectory(data.resolve("attachments/"+asset.id()));
        assertThat(new StorageInspector(data,mapper,orphan,clock).snapshot(List.of(held)).report().items()).singleElement().satisfies(i->assertThat(i.status()).isEqualTo("quarantined"));
        var referenced=refs(Map.of(asset.id(),catalog),Set.of(asset.id()),Set.of(),Set.of(),Map.of(),true);
        BackupArchive.removeTree(data.resolve("attachments/"+asset.id()));
        var required=new StorageInspector(data,mapper,referenced,clock).snapshot(List.of(held)).report();
        assertThat(required.items()).hasSize(2).anyMatch(i->i.reason().equals("missing_image"));
        var partial=new StorageInspector(data,mapper,orphan,clock).snapshot(List.of(new QuarantineStore.HeldItem("image",asset.id(),100,old,false))).report();
        assertThat(partial.items()).hasSize(2).anyMatch(i->i.reason().equals("missing_image")).anyMatch(i->i.reason().equals("incomplete_quarantine"));
        assertThat(partial.digest()).isNotEqualTo(complete.digest());
        assertThat(complete.statuses()).anyMatch(c->c.key().equals("quarantined")&&c.count()==1&&c.bytes()==1234);
        var unverified=refs(Map.of(asset.id(),catalog),Set.of(),Set.of(),Set.of(),Map.of(),false);
        assertThat(new StorageInspector(data,mapper,unverified,clock).snapshot(List.of(held)).report().items()).hasSize(2).anyMatch(i->i.reason().equals("missing_image"));
    }
    @Test void directoryLinkIsNotTraversedIntoTrustedCandidateEvidence()throws Exception {
        var asset=image(true);Path source=data.resolve("attachments/"+asset.id()),outside=data.resolve("external-image");Files.move(source,outside);
        if(System.getProperty("os.name").startsWith("Windows")){
            var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",source.toString(),outside.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor()).isZero();
        }else Files.createSymbolicLink(source,outside);
        try{var snapshot=new StorageInspector(data,mapper,empty(),clock).snapshot(List.of());assertThat(snapshot.candidates()).isEmpty();assertThat(snapshot.report().bytesComplete()).isFalse();}
        finally{Files.deleteIfExists(source);}
    }
}
