package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class QuarantineArchiveTest {
    @TempDir Path temp;
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    final MutableClock clock=new MutableClock();
    static final String OP="33333333-3333-4333-8333-333333333333", IMAGE="11111111-1111-4111-8111-111111111111", PDF="22222222-2222-4222-8222-222222222222", DIGEST="d".repeat(64);
    Path data(){return temp.resolve("data");} Path cache(){return temp.resolve("cache");}
    Path payload(String id){return data().resolve("quarantine/"+id+"/payload");}
    QuarantineArchive archive(){return new QuarantineArchive(data(),cache(),mapper,clock);}
    QuarantineFiles.Plan plan(String id)throws Exception {
        var image=new ArrayList<QuarantineFiles.Entry>();
        image.add(write(id,"attachments/"+IMAGE+"/metadata.json","metadata-canary"));
        image.add(write(id,"attachments/"+IMAGE+"/image.png","normalized-canary"));
        image.add(write(id,"attachments/"+IMAGE+"/original.png","original-canary"));
        var pdf=List.of(write(id,"exports/"+PDF+".pdf","%PDF-pdf-canary"),write(id,"exports/"+PDF+".json","pdf-metadata-canary"));
        return new QuarantineFiles.Plan(id,"a".repeat(64),"b".repeat(64),clock.instant(),List.of(target("image",IMAGE,image),target("pdf",PDF,pdf)));
    }
    QuarantineFiles.Entry write(String id,String path,String content)throws Exception {
        Path file=payload(id).resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);Files.setLastModifiedTime(file,FileTime.from(clock.instant().minusSeconds(10)));
        return new QuarantineFiles.Entry(path,Files.size(file),hash(Files.readAllBytes(file)),Files.getLastModifiedTime(file).toInstant());
    }
    QuarantineFiles.Target target(String kind,String id,List<QuarantineFiles.Entry> entries){return new QuarantineFiles.Target(kind,id,entries.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),entries);}
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    Map<String,byte[]> unzip(Path file)throws Exception {
        var result=new LinkedHashMap<String,byte[]>();try(var zip=new ZipInputStream(Files.newInputStream(file))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){assertThat(result.put(entry.getName(),zip.readAllBytes())).isNull();}}
        return result;
    }
    // Catches omitted originals, incorrect prefixes, invented hashes and a workspace-import format.
    @Test void createsIndependentlyReadableFilesOnlyZipWithExactManifestAndHashes()throws Exception {
        var plan=plan(OP);var archive=archive();var result=archive.create(plan,DIGEST);var entries=unzip(result.path());
        assertThat(entries.keySet()).containsExactlyInAnyOrder("manifest.json","说明.txt","files/attachments/"+IMAGE+"/metadata.json","files/attachments/"+IMAGE+"/image.png","files/attachments/"+IMAGE+"/original.png","files/exports/"+PDF+".pdf","files/exports/"+PDF+".json");
        assertThat(new String(entries.get("files/attachments/"+IMAGE+"/original.png"),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("original-canary");
        var manifest=mapper.readTree(entries.get("manifest.json"));assertThat(manifest.path("format").asText()).isEqualTo("resume-workbench-quarantine-files");assertThat(manifest.path("version").asInt()).isEqualTo(1);
        assertThat(manifest.path("operationId").asText()).isEqualTo(OP);assertThat(manifest.path("parentDigest").asText()).isEqualTo(DIGEST);assertThat(mapper.treeToValue(manifest.path("plan"),QuarantineFiles.Plan.class)).isEqualTo(plan);
        for(var target:plan.items())for(var file:target.files()){assertThat(entries.get("files/"+file.path())).hasSize((int)file.bytes());assertThat(hash(entries.get("files/"+file.path()))).isEqualTo(file.sha256());assertThat(Files.readAllBytes(payload(OP).resolve(file.path()))).isEqualTo(entries.get("files/"+file.path()));}
        try(var zip=new ZipFile(result.path().toFile())){var names=new HashSet<String>();var central=zip.entries();while(central.hasMoreElements()){var entry=central.nextElement();names.add(entry.getName());try(var in=zip.getInputStream(entry)){assertThat(in.readAllBytes()).isEqualTo(entries.get(entry.getName()));}}assertThat(names).isEqualTo(entries.keySet());}
        assertThat(result.bytes()).isEqualTo(Files.size(result.path()));assertThat(result.sha256()).isEqualTo(hash(Files.readAllBytes(result.path())));assertThat(result.path()).startsWith(cache()).isNotEqualTo(payload(OP));
        archive.verify(result);try(var in=archive.open(result)){assertThat(hash(in.readAllBytes())).isEqualTo(result.sha256());}
    }
    @Test void constructionDoesNotCreateDirectoriesAndReadsNeverReclaimExpiredArtifacts()throws Exception {
        var archive=archive();assertThat(cache()).doesNotExist();assertThat(data()).doesNotExist();var result=archive.create(plan(OP),DIGEST);clock.advance(Duration.ofMinutes(11));
        archive.verify(result);try(var in=archive.open(result)){assertThat(in.readAllBytes()).hasSize((int)result.bytes());}assertThat(result.path()).exists();
    }
    @Test void reusesSamePlanAndDigestAndRejectsRebindingSameOperation()throws Exception {
        var plan=plan(OP);var archive=archive();var first=archive.create(plan,DIGEST);assertThat(archive.create(plan,DIGEST)).isSameAs(first);
        assertThatThrownBy(()->archive.create(new QuarantineFiles.Plan(OP,plan.requestDigest(),"c".repeat(64),plan.createdAt(),plan.items()),DIGEST)).isInstanceOf(ApiException.class);assertThat(first.path()).exists();
    }
    @Test void changedMissingAndUnknownSourcesFailWithoutChangingAnyCanary()throws Exception {
        var plan=plan(OP);var archive=archive();Path file=payload(OP).resolve("exports/"+PDF+".json");Files.writeString(file,"PDF-metadata-canary");Files.setLastModifiedTime(file,FileTime.from(plan.items().get(1).files().get(1).modified()));
        assertThatThrownBy(()->archive.create(plan,DIGEST)).isInstanceOf(ApiException.class);assertThat(Files.readString(file)).isEqualTo("PDF-metadata-canary");
        Files.writeString(file,"pdf-metadata-canary");Files.setLastModifiedTime(file,FileTime.from(plan.items().get(1).files().get(1).modified()));Files.createDirectory(payload(OP).resolve("foreign-empty"));
        assertThatThrownBy(()->archive.create(plan,DIGEST)).isInstanceOf(ApiException.class);Files.delete(payload(OP).resolve("foreign-empty"));Files.delete(file);
        assertThatThrownBy(()->archive.create(plan,DIGEST)).isInstanceOf(ApiException.class);assertThat(Files.readString(payload(OP).resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");
    }
    @Test void foreignArtifactCannotBeOpenedVerifiedOrRemovedEvenWithCorrectChecksum()throws Exception {
        var plan=plan(OP);var archive=archive();var own=archive.create(plan,DIGEST);Path foreign=temp.resolve("foreign.zip");Files.copy(own.path(),foreign);
        var forged=new QuarantineArchive.Artifact(plan,DIGEST,foreign,own.bytes(),hash(Files.readAllBytes(foreign)),own.createdAt());
        assertThatThrownBy(()->archive.open(forged)).isInstanceOf(ApiException.class);assertThatThrownBy(()->archive.verify(forged)).isInstanceOf(ApiException.class);assertThatThrownBy(()->archive.remove(forged)).isInstanceOf(ApiException.class);assertThat(foreign).exists();
        Path inside=own.path().getParent().resolve("foreign.zip");Files.copy(own.path(),inside);var nested=new QuarantineArchive.Artifact(plan,DIGEST,inside,own.bytes(),own.sha256(),own.createdAt());assertThatThrownBy(()->archive.remove(nested)).isInstanceOf(ApiException.class);assertThat(inside).exists();
    }
    @Test void corruptOwnedArtifactAndRehashedMalformedZipRemainUntouched()throws Exception {
        var archive=archive();var own=archive.create(plan(OP),DIGEST);byte[] original=Files.readAllBytes(own.path());Files.writeString(own.path(),"bad zip");
        assertThatThrownBy(()->archive.verify(own)).isInstanceOf(ApiException.class);assertThatThrownBy(()->archive.remove(own)).isInstanceOf(ApiException.class);
        var rehashed=new QuarantineArchive.Artifact(own.plan(),own.digest(),own.path(),Files.size(own.path()),hash(Files.readAllBytes(own.path())),own.createdAt());assertThatThrownBy(()->archive.verify(rehashed)).isInstanceOf(ApiException.class);assertThatThrownBy(()->archive.remove(rehashed)).isInstanceOf(ApiException.class);assertThat(Files.readString(own.path())).isEqualTo("bad zip");assertThat(original).isNotEmpty();
    }
    // Actual ZIP I/O at the completion boundary: outer SHA is calculated after this mutation.
    @ParameterizedTest @ValueSource(strings={"manifest","sha","missing","unknown","duplicate","crc","oversized","trailing-json"})
    void finishedRehashedBadZipIsRejectedByItsInnerEvidence(String corruption)throws Exception {
        var plan=plan(OP);var good=archive().create(plan,DIGEST);var entries=unzip(good.path());
        String image="files/attachments/"+IMAGE+"/original.png",pdf="files/exports/"+PDF+".pdf",alias=pdf.substring(0,pdf.length()-4)+".zip";
        if(corruption.equals("manifest")){var manifest=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(entries.get("manifest.json"));manifest.put("parentDigest","e".repeat(64));entries.put("manifest.json",mapper.writeValueAsBytes(manifest));}
        if(corruption.equals("sha"))entries.put(image,"original-CANARY".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(corruption.equals("missing"))entries.remove(image);
        if(corruption.equals("unknown"))entries.put("files/../../foreign.txt","outside-canary".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(corruption.equals("duplicate"))entries.put(alias,entries.get(pdf));
        if(corruption.equals("oversized"))entries.put(image,"original-canary-extra".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(corruption.equals("trailing-json"))entries.put("manifest.json",(new String(entries.get("manifest.json"),java.nio.charset.StandardCharsets.UTF_8)+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var output=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(output)){for(var entry:entries.entrySet()){zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();}}
        byte[] bad=output.toByteArray();
        if(corruption.equals("duplicate"))replaceBytes(bad,alias.getBytes(java.nio.charset.StandardCharsets.UTF_8),pdf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if(corruption.equals("crc")){for(int i=0;i<bad.length-20;i++)if(bad[i]==0x50&&bad[i+1]==0x4b&&bad[i+2]==1&&bad[i+3]==2){bad[i+16]^=1;break;}}
        var failing=new QuarantineArchive(data(),temp.resolve("fault-cache"),mapper,clock,1024*1024,32,channel->{channel.truncate(0);channel.position(0);var buffer=ByteBuffer.wrap(bad);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);});
        assertThatThrownBy(()->failing.create(plan,DIGEST)).isInstanceOfAny(ApiException.class,IOException.class);
        assertThat(Files.readString(payload(OP).resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");assertThat(Files.readString(payload(OP).resolve("exports/"+PDF+".pdf"))).isEqualTo("%PDF-pdf-canary");
    }
    void replaceBytes(byte[] bytes,byte[] from,byte[] to){assertThat(to).hasSameSizeAs(from);for(int i=0;i<=bytes.length-from.length;i++){boolean equal=true;for(int j=0;j<from.length;j++)if(bytes[i+j]!=from[j]){equal=false;break;}if(equal){System.arraycopy(to,0,bytes,i,to.length);i+=from.length-1;}}}
    // Catches validating local-header bytes while trusting a different central-directory mapping.
    @Test void centralDirectoryOffsetRedirectIsRejectedBeforePublishingAndLeavesSourcesUnchanged()throws Exception {
        var plan=plan(OP);var good=archive().create(plan,DIGEST);byte[] bad=Files.readAllBytes(good.path());
        String original="files/attachments/"+IMAGE+"/original.png",metadata="files/attachments/"+IMAGE+"/metadata.json";
        var bytes=ByteBuffer.wrap(bad).order(java.nio.ByteOrder.LITTLE_ENDIAN);int originalRecord=centralRecord(bad,original),metadataRecord=centralRecord(bad,metadata);bytes.putInt(originalRecord+42,bytes.getInt(metadataRecord+42));
        Path probe=temp.resolve("redirected.zip");Files.write(probe,bad);
        assertThat(new String(unzip(probe).get(original),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("original-canary");
        try(var zip=new ZipFile(probe.toFile());var in=zip.getInputStream(zip.getEntry(original))){assertThat(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("metadata-canary");}
        var before=new HashMap<String,byte[]>();for(var target:plan.items())for(var entry:target.files())before.put(entry.path(),Files.readAllBytes(payload(OP).resolve(entry.path())));
        Path failureCache=temp.resolve("redirect-cache");var failing=new QuarantineArchive(data(),failureCache,mapper,clock,1024*1024,32,channel->{channel.truncate(0);channel.position(0);var buffer=ByteBuffer.wrap(bad);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);});
        assertThatThrownBy(()->failing.create(plan,DIGEST)).isInstanceOf(ApiException.class);
        for(var target:plan.items())for(var entry:target.files()){Path source=payload(OP).resolve(entry.path());assertThat(Files.readAllBytes(source)).isEqualTo(before.get(entry.path()));assertThat(Files.getLastModifiedTime(source).toInstant()).isEqualTo(entry.modified());}
        try(var paths=Files.walk(failureCache)){assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();}
    }
    int centralRecord(byte[] bytes,String name){
        var buffer=ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);int end=bytes.length-22;while(end>=0&&buffer.getInt(end)!=0x06054b50)end--;assertThat(end).isGreaterThanOrEqualTo(0);
        int record=buffer.getInt(end+16);while(record+46<=end&&buffer.getInt(record)==0x02014b50){int length=Short.toUnsignedInt(buffer.getShort(record+28));String current=new String(bytes,record+46,length,java.nio.charset.StandardCharsets.UTF_8);if(current.equals(name))return record;record+=46+length+Short.toUnsignedInt(buffer.getShort(record+30))+Short.toUnsignedInt(buffer.getShort(record+32));}
        throw new AssertionError("Missing central record: "+name);
    }
    @Test void metadataLimitRefusesThirtyThirdArtifactWithoutDeletingLiveFiles()throws Exception {
        var archive=archive();var artifacts=new ArrayList<QuarantineArchive.Artifact>();
        for(int i=0;i<32;i++){String id=String.format("%08x-3333-4333-8333-333333333333",i);artifacts.add(archive.create(plan(id),DIGEST));}
        assertThatThrownBy(()->archive.create(plan("ffffffff-3333-4333-8333-333333333333"),DIGEST)).isInstanceOf(ApiException.class);for(var artifact:artifacts)assertThat(artifact.path()).exists();
    }
    @Test void explicitCreateReclaimsExpiredOwnedFilesButPreservesOpenAndRenewedFiles()throws Exception {
        var archive=archive();var first=archive.create(plan(OP),DIGEST);var second=archive.create(plan("aaaaaaaa-3333-4333-8333-333333333333"),DIGEST);var stream=archive.open(first);
        clock.advance(Duration.ofMinutes(9));archive.renew(second,clock.instant());clock.advance(Duration.ofMinutes(2));archive.create(plan("bbbbbbbb-3333-4333-8333-333333333333"),DIGEST);
        assertThat(first.path()).exists();assertThat(second.path()).exists();assertThatThrownBy(()->archive.remove(first)).isInstanceOf(ApiException.class);stream.close();archive.create(plan("cccccccc-3333-4333-8333-333333333333"),DIGEST);assertThat(first.path()).doesNotExist();assertThat(second.path()).exists();
    }
    @Test void removeValidArtifactNeverAdoptsRecreatedForeignFile()throws Exception {
        var archive=archive();var artifact=archive.create(plan(OP),DIGEST);archive.remove(artifact);assertThat(artifact.path()).doesNotExist();Files.writeString(artifact.path(),"new-foreign-canary");assertThatThrownBy(()->archive.remove(artifact)).isInstanceOf(ApiException.class);assertThat(Files.readString(artifact.path())).isEqualTo("new-foreign-canary");
    }
    @Test void nonexistentForeignArtifactCannotBeAcceptedAsOwnedRemoval()throws Exception {
        var archive=archive();var own=archive.create(plan(OP),DIGEST);var forged=new QuarantineArchive.Artifact(own.plan(),"e".repeat(64),own.path().getParent().resolve("unowned-missing.zip"),own.bytes(),own.sha256(),own.createdAt());
        assertThatThrownBy(()->archive.remove(forged)).isInstanceOf(ApiException.class);assertThat(own.path()).exists();
    }
    @Test void activeAndInProgressZipBytesShareOnePhysicalBudget()throws Exception {
        var firstPlan=plan(OP);long firstBytes=archive().create(firstPlan,DIGEST).bytes();var archive=new QuarantineArchive(data(),temp.resolve("bounded-cache"),mapper,clock,firstBytes+1,32);var first=archive.create(firstPlan,DIGEST);
        assertThatThrownBy(()->archive.create(plan("aaaaaaaa-3333-4333-8333-333333333333"),DIGEST)).isInstanceOf(ApiException.class);archive.verify(first);
        try(var files=Files.list(first.path().getParent())){assertThat(files.toList()).containsExactly(first.path());}assertThat(first.bytes()).isLessThanOrEqualTo(firstBytes+1);
    }
    @Test void sourceAndCacheJunctionsRejectWithoutFollowingOrDeletingLinkedCanaries()throws Exception {
        var plan=plan(OP);var archive=archive();Path exports=payload(OP).resolve("exports"),outside=temp.resolve("linked-sources");Files.move(exports,outside);directoryLink(exports,outside);
        try{assertThatThrownBy(()->archive.create(plan,DIGEST)).isInstanceOf(ApiException.class);assertThat(Files.readString(outside.resolve(PDF+".pdf"))).isEqualTo("%PDF-pdf-canary");}finally{Files.delete(exports);Files.move(outside,exports);}
        var own=archive.create(plan,DIGEST);Path ownedRoot=own.path().getParent(),foreign=temp.resolve("linked-cache");Files.move(ownedRoot,foreign);directoryLink(ownedRoot,foreign);
        try{assertThatThrownBy(()->archive.open(own)).isInstanceOf(ApiException.class);assertThatThrownBy(()->archive.remove(own)).isInstanceOf(ApiException.class);assertThat(foreign.resolve(own.path().getFileName())).exists();}finally{Files.delete(ownedRoot);Files.move(foreign,ownedRoot);}
    }
    void directoryLink(Path link,Path target)throws Exception {
        try{Files.createSymbolicLink(link,target);}catch(FileSystemException|UnsupportedOperationException ex){org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertThat(process.waitFor()).withFailMessage(output).isZero();}
    }
    @Test void boundedOutputFailureRemovesOnlyPartialCacheFileAndPreservesSources()throws Exception {
        var plan=plan(OP);var archive=new QuarantineArchive(data(),cache(),mapper,clock,128,32);
        assertThatThrownBy(()->archive.create(plan,DIGEST)).isInstanceOf(ApiException.class);
        try(var paths=Files.walk(cache())){assertThat(paths.filter(Files::isRegularFile).toList()).isEmpty();}
        assertThat(Files.readString(payload(OP).resolve("exports/"+PDF+".pdf"))).isEqualTo("%PDF-pdf-canary");assertThat(Files.readString(payload(OP).resolve("attachments/"+IMAGE+"/original.png"))).isEqualTo("original-canary");
    }
    @Test void oversizePlanFailsBeforeTouchingDataOrCache()throws Exception {
        var files=List.of(new QuarantineFiles.Entry("exports/"+PDF+".pdf",1024L*1024*1024,"a".repeat(64),clock.instant()),new QuarantineFiles.Entry("exports/"+PDF+".json",1,"a".repeat(64),clock.instant()));var plan=new QuarantineFiles.Plan(OP,"a".repeat(64),"b".repeat(64),clock.instant(),List.of(target("pdf",PDF,files)));
        assertThatThrownBy(()->archive().create(plan,DIGEST)).isInstanceOf(ApiException.class);assertThat(data()).doesNotExist();assertThat(cache()).doesNotExist();
    }
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-01T08:00:00Z");void advance(Duration d){now=now.plus(d);}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return now;}
    }
}
