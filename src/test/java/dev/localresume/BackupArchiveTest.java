package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class BackupArchiveTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private BackupArchive archive(long limit){return new BackupArchive(mapper,limit);}
    private byte[] zip(Map<String,byte[]> files)throws IOException{var bytes=new ByteArrayOutputStream();try(var out=new ZipOutputStream(bytes)){for(var entry:files.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue());out.closeEntry();}}return bytes.toByteArray();}
    @Test void archiveRoundTripsWithHashesAndRejectsModifiedBytes()throws Exception{
        Path file=temp.resolve("backup.zip");archive(1000000).write(file,Map.of("workspace.json",BackupArchive.Source.json("{}".getBytes()),"settings.json",BackupArchive.Source.json("{}".getBytes())));
        try(var staged=archive(1000000).read(Files.newInputStream(file),temp.resolve("stage"))){assertThat(Files.readString(staged.file("workspace.json"))).isEqualTo("{}");assertThat(staged.manifest().files()).hasSize(2);}
        var contents=new LinkedHashMap<String,byte[]>();try(var in=new ZipInputStream(Files.newInputStream(file))){ZipEntry e;while((e=in.getNextEntry())!=null)contents.put(e.getName(),in.readAllBytes());}
        contents.put("workspace.json","changed".getBytes());
        assertThatThrownBy(()->archive(1000000).read(new ByteArrayInputStream(zip(contents)),temp.resolve("stage"))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_INVALID"));
    }
    @Test void traversalAndUnexpectedFilesCannotEscapeTheStage()throws Exception{
        for(String path:List.of("../../escaped.txt","/absolute.txt","attachments/../secret","attachments\\secret",".env")){
            byte[] bytes=zip(Map.of(path,"no".getBytes()));
            assertThatThrownBy(()->archive(1000000).read(new ByteArrayInputStream(bytes),temp.resolve("stage"))).isInstanceOf(ApiException.class);
            assertThat(Files.exists(temp.resolve("escaped.txt"))).isFalse();
        }
    }
    @Test void rejectsFutureFormatVersions()throws Exception{
        byte[] manifest=mapper.writeValueAsBytes(new BackupArchive.Manifest(BackupArchive.FORMAT,99,2,java.time.Instant.now(),List.of()));
        assertThatThrownBy(()->archive(1000000).read(new ByteArrayInputStream(zip(Map.of("manifest.json",manifest))),temp.resolve("stage")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_VERSION_UNSUPPORTED"));
    }
    @Test void enforcesUncompressedLimitForSmallHighlyCompressedUploads()throws Exception{
        byte[] bytes=zip(Map.of("workspace.json",new byte[100000]));assertThat(bytes.length).isLessThan(2048);
        assertThatThrownBy(()->archive(2048).read(new ByteArrayInputStream(bytes),temp.resolve("stage")))
            .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo("BACKUP_TOO_LARGE"));
    }
}
