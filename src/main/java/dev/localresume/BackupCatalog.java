package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipFile;

/** Instance-local catalog. Old ZIPs remain readable; no archive is removed by this class. */
final class BackupCatalog {
    record Stored(int version,String kind,BackupService.Created backup,String sha256,String fingerprint) {}
    record Item(BackupService.Created backup,String kind,boolean integrityRecorded) {}
    record History(List<Item> items,int page,boolean hasMore,int unreadable) {}
    private final Path directory;
    private final ObjectMapper mapper;
    private final long maxBytes;
    BackupCatalog(Path data,ObjectMapper mapper,long maxBytes){this.directory=data.resolve("backups");this.mapper=mapper;this.maxBytes=maxBytes;}
    void save(Stored stored)throws IOException {
        String id=stored.backup().id();Path temp=directory.resolve(id+".json.tmp");
        try{mapper.writeValue(temp.toFile(),stored);Files.move(temp,directory.resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE);}
        finally{Files.deleteIfExists(temp);}
    }
    History history(int page) {
        if(page<0||page>10000)throw new ApiException("INVALID_INPUT","备份页码无效。",400);
        if(!Files.isDirectory(directory))return new History(List.of(),page,false,0);
        try(var files=Files.list(directory)){
            var paths=files.filter(p->p.getFileName().toString().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.zip")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))
                .toList();
            long start=(long)page*20;var items=new ArrayList<Item>();int unreadable=0;
            for(Path path:paths){
                try{items.add(item(path));}catch(Exception ignored){unreadable++;}
            }
            items.sort(Comparator.comparing((Item item)->item.backup().createdAt()).reversed().thenComparing(item->item.backup().id()));
            return new History(items.stream().skip(start).limit(20).toList(),page,items.size()>start+20,unreadable);
        }catch(IOException e){throw new ApiException("BACKUP_HISTORY_FAILED","无法读取本机备份历史，请检查数据目录后重试。",503);}
    }
    private Stored stored(Path zip)throws IOException {
        String id=zip.getFileName().toString().replace(".zip","");Path file=directory.resolve(id+".json");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>8192)throw new IOException("Invalid catalog");
        var record=mapper.readValue(file.toFile(),Stored.class);
        if(record==null||record.version()!=1||record.kind()==null||!Set.of("manual","automatic").contains(record.kind())||record.backup()==null||!id.equals(record.backup().id())
            ||record.backup().bytes()!=Files.size(zip)||record.backup().createdAt()==null||record.sha256()==null||!record.sha256().matches("[0-9a-f]{64}")
            ||record.backup().resumes()<0||record.backup().versions()<0||record.backup().attachments()<0||record.backup().exports()<0)throw new IOException("Invalid catalog");
        return record;
    }
    private Item item(Path zip)throws IOException {
        if(Files.size(zip)>maxBytes)throw new IOException("Archive exceeds limit");
        var stored=stored(zip);if(stored!=null)return new Item(stored.backup(),stored.kind(),true);
        String id=zip.getFileName().toString().replace(".zip","");
        try(var archive=new ZipFile(zip.toFile())){
            if(archive.size()>BackupArchive.MAX_FILES+1)throw new IOException("Too many archive entries");
            var manifest=mapper.readValue(read(archive,"manifest.json",1048576),BackupArchive.Manifest.class);
            if(!BackupArchive.FORMAT.equals(manifest.format())||manifest.formatVersion()!=1||manifest.createdAt()==null||!BackupData.supportsSchema(manifest.documentSchemaVersion()))throw new IOException("Unsupported archive");
            var workspace=mapper.readTree(read(archive,"workspace.json",16777216));
            for(String field:List.of("resumes","versions","attachments","exports"))if(!workspace.path(field).isArray())throw new IOException("Invalid archive summary");
            return new Item(new BackupService.Created(id,Files.size(zip),workspace.path("resumes").size(),workspace.path("versions").size(),workspace.path("attachments").size(),workspace.path("exports").size(),manifest.createdAt()),"legacy",false);
        }
    }
    private byte[] read(ZipFile zip,String name,int max)throws IOException {
        var entry=zip.getEntry(name);if(entry==null||entry.getSize()>max)throw new IOException("Missing or oversized summary");
        try(var input=zip.getInputStream(entry)){byte[] bytes=input.readNBytes(max+1);if(bytes.length>max)throw new IOException("Oversized summary");return bytes;}
    }
    void verify(Path zip) {
        try{var record=stored(zip);if(record!=null&&!record.sha256().equals(hash(zip)))throw new IOException("Archive hash mismatch");}
        catch(IOException e){throw new ApiException("BACKUP_CORRUPT","本机备份的大小或校验值不一致，请选择其他备份。",422);}
    }
    static String hash(Path file)throws IOException {
        var digest=BackupArchive.digest();try(var input=Files.newInputStream(file)){byte[] buffer=new byte[65536];int read;while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
