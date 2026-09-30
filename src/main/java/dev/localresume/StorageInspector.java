package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Read-only inventory. A candidate is not permission to delete it. */
final class StorageInspector {
    static final int GRACE_DAYS=30, ENTRY_LIMIT=20000;
    static final long HASH_LIMIT=1024L*1024*1024;
    record Catalog(String json,Instant createdAt) {}
    record Owner(String resumeId,long revision) {}
    record References(Map<String,Catalog> catalogs,Set<String> currentImages,Set<String> historicalImages,
                      Set<String> resumes,Map<String,Owner> versions,boolean consistent,String fingerprint) {}
    record Item(String kind,String id,String status,String reason,long bytes,Instant lastModified) {}
    record Count(String key,long count,long bytes) {}
    record Report(Instant checkedAt,int graceDays,boolean referencesVerified,boolean bytesComplete,
                  List<Count> kinds,List<Count> statuses,List<Item> items,String digest) {}
    private record FileInfo(Path path,long bytes,Instant modified,Object key) {}
    private static final class Check extends Exception {final String reason;Check(String reason){this.reason=reason;}}
    private final Path root;
    private final ObjectMapper mapper;
    private final References refs;
    private final Instant checkedAt,cutoff;
    private final long deadline=System.nanoTime()+20_000_000_000L;
    private final long hashLimit;
    private final MessageDigest fingerprint=BackupArchive.digest();
    private final List<Item> items=new ArrayList<>();
    private boolean bytesComplete=true;
    private int entries;
    private long hashed;
    StorageInspector(Path root,ObjectMapper mapper,References refs,Clock clock){this(root,mapper,refs,clock,HASH_LIMIT);}
    StorageInspector(Path root,ObjectMapper mapper,References refs,Clock clock,long hashLimit){
        this.root=root.toAbsolutePath().normalize();this.mapper=mapper;this.refs=refs;this.checkedAt=clock.instant();
        this.cutoff=checkedAt.minus(Duration.ofDays(GRACE_DAYS));this.hashLimit=hashLimit;
    }
    Report inspect() throws IOException {
        evidence(refs.fingerprint());
        if(Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&!ordinaryDirectory(root)){
            bytesComplete=false;items.add(new Item("other",null,"check","unexpected_path",0,null));
        }else{images();exports();}
        items.sort(Comparator.comparingInt((Item i)->List.of("check","candidate","recent","in_use").indexOf(i.status()))
            .thenComparing(Item::kind).thenComparing(i->i.id()==null?"":i.id()));
        evidence(items);
        var kinds=counts(List.of("image","pdf","other"),true);var statuses=counts(List.of("in_use","recent","candidate","check"),false);
        return new Report(checkedAt,GRACE_DAYS,refs.consistent(),bytesComplete,kinds,statuses,List.copyOf(items),HexFormat.of().formatHex(fingerprint.digest()));
    }
    private List<Count> counts(List<String> keys,boolean kind){return keys.stream().map(key->{var matches=items.stream().filter(i->key.equals(kind?i.kind():i.status())).toList();return new Count(key,matches.size(),matches.stream().mapToLong(Item::bytes).sum());}).toList();}
    private void images()throws IOException {
        var found=new HashSet<String>();
        for(Path path:listRoot(root.resolve("attachments"))){
            String id=path.getFileName().toString();
            if(uuid(id)&&ordinaryDirectory(path)){found.add(id);image(path,id);}else unknown(path);
        }
        var required=new TreeSet<>(refs.catalogs().keySet());required.addAll(refs.currentImages());required.addAll(refs.historicalImages());
        for(String id:required)if(!found.contains(id))items.add(new Item("image",uuid(id)?id:null,"check","missing_image",0,null));
    }
    private void image(Path path,String id)throws IOException {
        long bytes=0;Instant modified=null;
        try{
            var files=files(path);bytes=files.values().stream().mapToLong(FileInfo::bytes).sum();modified=latest(path,files);
            FileInfo metadata=files.get("metadata.json");if(metadata==null)throw new Check("incomplete_image");
            var asset=mapper.readValue(metadata(metadata),ImageService.Asset.class);
            if(asset==null||!id.equals(asset.id())||asset.format()==null||!Set.of("PNG","JPEG","WEBP").contains(asset.format())||asset.bytes()<1
                ||!sha(asset.sha256())||!sha(asset.normalizedSha256())||asset.width()<1||asset.height()<1||asset.sourceWidth()<1||asset.sourceHeight()<1)throw new Check("invalid_metadata");
            String original="original."+asset.format().toLowerCase(Locale.ROOT);
            if(!files.keySet().equals(Set.of("metadata.json","image.png",original)))throw new Check("incomplete_image");
            if(files.get(original).bytes()!=asset.bytes()||files.get("image.png").bytes()<1)throw new Check("file_mismatch");
            var catalog=refs.catalogs().get(id);
            if(catalog!=null){if(!asset.equals(mapper.readValue(catalog.json(),ImageService.Asset.class)))throw new Check("metadata_mismatch");modified=max(modified,catalog.createdAt());}
            boolean current=refs.currentImages().contains(id),historical=refs.historicalImages().contains(id);
            String status,reason;
            if(current||historical){status="in_use";reason=current?"current_image":"historical_image";}
            else if(!refs.consistent()){status="check";reason="references_unverified";}
            else if(!modified.isBefore(cutoff)){status="recent";reason="recent_file";}
            else{verify(files.get(original),asset.sha256(),false);verify(files.get("image.png"),asset.normalizedSha256(),false);status="candidate";reason="unreferenced_image";}
            stable(path,files);items.add(new Item("image",id,status,reason,bytes,modified));
        }catch(Check e){items.add(new Item("image",id,"check",e.reason,bytes,modified));}
        catch(IOException|RuntimeException e){if(e instanceof ApiException a)throw a;if(e instanceof IOException)bytesComplete=false;items.add(new Item("image",id,"check","unreadable_file",bytes,modified));}
    }
    private void exports()throws IOException {
        var groups=new TreeMap<String,Map<String,Path>>();
        for(Path path:listRoot(root.resolve("exports"))){
            String name=path.getFileName().toString();int dot=name.lastIndexOf('.');
            if(dot>0&&uuid(name.substring(0,dot))&&Set.of("pdf","json").contains(name.substring(dot+1)))groups.computeIfAbsent(name.substring(0,dot),key->new TreeMap<>()).put(name.substring(dot+1),path);
            else unknown(path);
        }
        for(var group:groups.entrySet())export(group.getKey(),group.getValue());
    }
    private void export(String id,Map<String,Path> paths)throws IOException {
        long bytes=0;Instant modified=null;
        try{
            var files=new TreeMap<String,FileInfo>();for(var entry:paths.entrySet()){var info=info(entry.getValue());files.put(entry.getKey(),info);bytes+=info.bytes();modified=max(modified,info.modified());}
            if(!files.keySet().equals(Set.of("pdf","json")))throw new Check("incomplete_pdf");
            var value=mapper.readValue(metadata(files.get("json")),ExportService.Export.class);
            if(value==null||!id.equals(value.id())||!uuid(value.snapshotId())||!sha(value.digest())||!sha(value.sha256())||value.createdAt()==null||files.get("pdf").bytes()<5)throw new Check("invalid_metadata");
            modified=max(modified,value.createdAt());
            boolean bound=value.resumeId()!=null||value.versionId()!=null||value.revision()!=null;
            boolean active=false;
            if(bound){
                if(!uuid(value.resumeId())||!uuid(value.versionId())||value.revision()==null||value.revision()<1)throw new Check("invalid_metadata");
                var owner=refs.versions().get(value.versionId());boolean resume=refs.resumes().contains(value.resumeId());
                if(owner!=null&&resume){if(!owner.resumeId().equals(value.resumeId())||owner.revision()!=value.revision())throw new Check("metadata_mismatch");active=true;}
                else if(owner!=null||resume)throw new Check("owner_mismatch");
            }
            String status,reason;
            if(active){status="in_use";reason="saved_pdf";}
            else if(!refs.consistent()){status="check";reason="references_unverified";}
            else if(!modified.isBefore(cutoff)){status="recent";reason="recent_file";}
            else{verify(files.get("pdf"),value.sha256(),true);status="candidate";reason=bound?"deleted_pdf":"unlinked_pdf";}
            for(var file:files.values())stable(file);
            items.add(new Item("pdf",id,status,reason,bytes,modified));
        }catch(Check e){items.add(new Item("pdf",id,"check",e.reason,bytes,modified));}
        catch(IOException|RuntimeException e){if(e instanceof ApiException a)throw a;if(e instanceof IOException)bytesComplete=false;items.add(new Item("pdf",id,"check","unreadable_file",bytes,modified));}
    }
    private List<Path> listRoot(Path directory)throws IOException {
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return List.of();
        if(!ordinaryDirectory(directory)){unknown(directory);return List.of();}
        return list(directory);
    }
    private List<Path> list(Path directory)throws IOException {
        var paths=new ArrayList<Path>();
        try(var stream=Files.newDirectoryStream(directory)){for(Path path:stream){limit();if(++entries>ENTRY_LIMIT)throw limitError();paths.add(path);}}
        paths.sort(Comparator.comparing(p->p.getFileName().toString()));return paths;
    }
    private Map<String,FileInfo> files(Path directory)throws IOException,Check {
        var files=new TreeMap<String,FileInfo>();for(Path path:list(directory))files.put(path.getFileName().toString(),info(path));return files;
    }
    private FileInfo info(Path path)throws IOException,Check {
        var attr=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attr.isRegularFile()||attr.isSymbolicLink()) {bytesComplete=false;throw new Check("unexpected_path");}
        evidence(List.of(path.getFileName().toString(),attr.size(),attr.lastModifiedTime().toInstant()));
        return new FileInfo(path,attr.size(),attr.lastModifiedTime().toInstant(),attr.fileKey());
    }
    private Instant latest(Path directory,Map<String,FileInfo> files)throws IOException {
        Instant value=Files.getLastModifiedTime(directory,LinkOption.NOFOLLOW_LINKS).toInstant();for(var file:files.values())value=max(value,file.modified());return value;
    }
    private byte[] metadata(FileInfo file)throws IOException,Check {
        if(file.bytes()<1||file.bytes()>65536)throw new Check("invalid_metadata");
        byte[] bytes;try(var in=open(file.path())){bytes=in.readNBytes(65537);}
        if(bytes.length!=file.bytes())throw new Check("changed_during_scan");stable(file);evidence(ImageService.sha(bytes));return bytes;
    }
    private void verify(FileInfo file,String expected,boolean pdf)throws IOException,Check {
        if(file.bytes()>hashLimit-hashed)throw new Check("verification_limit");hashed+=file.bytes();
        var digest=BackupArchive.digest();try(var in=open(file.path())){
            if(pdf){byte[] prefix=in.readNBytes(5);if(!Arrays.equals(prefix,"%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))throw new Check("file_mismatch");digest.update(prefix);}
            byte[] buffer=new byte[65536];int length;while((length=in.read(buffer))!=-1){limit();digest.update(buffer,0,length);}
        }
        stable(file);String actual=HexFormat.of().formatHex(digest.digest());evidence(actual);if(!actual.equals(expected))throw new Check("file_mismatch");
    }
    private static InputStream open(Path path)throws IOException{return Channels.newInputStream(Files.newByteChannel(path,Set.of(StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)));}
    private void stable(FileInfo file)throws IOException,Check {
        var current=Files.readAttributes(file.path(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!current.isRegularFile()||current.isSymbolicLink()||current.size()!=file.bytes()||!current.lastModifiedTime().toInstant().equals(file.modified())||!Objects.equals(current.fileKey(),file.key()))throw new Check("changed_during_scan");
    }
    private void stable(Path directory,Map<String,FileInfo> files)throws IOException,Check {
        if(!ordinaryDirectory(directory))throw new Check("changed_during_scan");
        var names=new TreeSet<String>();try(var stream=Files.newDirectoryStream(directory)){for(Path path:stream){limit();names.add(path.getFileName().toString());if(names.size()>ENTRY_LIMIT)throw limitError();}}
        if(!names.equals(files.keySet()))throw new Check("changed_during_scan");for(var file:files.values())stable(file);
    }
    private void unknown(Path path)throws IOException {
        long bytes=0;Instant modified=null;String reason="unexpected_file";
        try{var attr=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);modified=attr.lastModifiedTime().toInstant();if(attr.isRegularFile())bytes=attr.size();else{bytesComplete=false;reason="unexpected_path";}evidence(List.of(path.getFileName().toString(),bytes,String.valueOf(modified)));}
        catch(IOException e){bytesComplete=false;reason="unreadable_file";}
        items.add(new Item("other",null,"check",reason,bytes,modified));
    }
    private void evidence(Object value)throws IOException {fingerprint.update(mapper.writeValueAsBytes(value));fingerprint.update((byte)'\n');}
    private void limit(){if(System.nanoTime()>deadline)throw limitError();}
    private static ApiException limitError(){return new ApiException("STORAGE_SCAN_LIMIT","文件检查超出本次数量或时间范围，请保留数据并检查数据目录规模。",422);}
    private static boolean ordinaryDirectory(Path path){return Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(path);}
    static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    private static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static Instant max(Instant a,Instant b){return a==null?b:b==null?a:a.isAfter(b)?a:b;}
}
