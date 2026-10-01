package dev.localresume;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.zip.*;

/** Files-only recovery ZIPs in an owned, bounded temporary cache. Never mutates payload. */
@Component
public class QuarantineArchive {
    private static final long MAX_CACHE_BYTES=1024L*1024*1024+1024*1024, MAX_MANIFEST=1024*1024;
    private static final int MAX_ARTIFACTS=32;
    private static final Duration RETENTION=Duration.ofMinutes(10);
    @FunctionalInterface interface Force {void force(FileChannel channel)throws IOException;}
    private static final byte[] INSTRUCTIONS=("本 ZIP 保存本批暂存的原始图片、处理后的图片、PDF 和文件元数据。\n"+
        "请另存本 ZIP。可通过解压 files 目录取回文件；此 ZIP 不包含简历或历史，不用于完整工作区导入。\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    public record Artifact(QuarantineFiles.Plan plan,String digest,Path path,long bytes,String sha256,Instant createdAt) {}
    private record Key(String id,String digest) {}
    private static final class Cached {
        final Artifact artifact;final BasicFileAttributes attributes;Instant expiresAt;int readers;
        Cached(Artifact artifact,BasicFileAttributes attributes,Instant now){this.artifact=artifact;this.attributes=attributes;this.expiresAt=now.plus(RETENTION);}
    }
    private final QuarantineFs sourceFs,parentFs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Force force;
    private final long byteLimit;
    private final int countLimit;
    private final Map<Key,Cached> cache=new LinkedHashMap<>();
    private QuarantineFs cacheFs;
    private Object cacheRootKey;

    @Autowired
    public QuarantineArchive(@Value("${resume.data-dir}") String data,ObjectMapper mapper){this(Path.of(data),Path.of(System.getProperty("java.io.tmpdir")),mapper,Clock.systemUTC());}
    QuarantineArchive(Path data,Path tempParent,ObjectMapper mapper,Clock clock){this(data,tempParent,mapper,clock,MAX_CACHE_BYTES,MAX_ARTIFACTS);}
    // Package budget boundary permits small real-NIO limit tests without allocating a GiB fixture.
    QuarantineArchive(Path data,Path tempParent,ObjectMapper mapper,Clock clock,long byteLimit,int countLimit){this(data,tempParent,mapper,clock,byteLimit,countLimit,channel->channel.force(true));}
    QuarantineArchive(Path data,Path tempParent,ObjectMapper mapper,Clock clock,long byteLimit,int countLimit,Force force){
        this.sourceFs=new QuarantineFs(data);this.parentFs=new QuarantineFs(tempParent);this.clock=Objects.requireNonNull(clock);
        if(parentFs.root().startsWith(sourceFs.root())||byteLimit<=0||byteLimit>MAX_CACHE_BYTES||countLimit<=0||countLimit>MAX_ARTIFACTS)throw new IllegalArgumentException("Invalid archive cache boundary");
        this.byteLimit=byteLimit;this.countLimit=countLimit;this.force=Objects.requireNonNull(force);
        this.mapper=mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public synchronized Artifact create(QuarantineFiles.Plan plan,String digest)throws IOException {
        QuarantineFiles.validate(plan);if(!QuarantineFiles.sha(digest))throw invalid();
        reclaimExpired();var key=new Key(plan.id(),digest);var existing=cache.get(key);
        if(existing!=null){if(!existing.artifact.plan().equals(plan))throw conflict();verify(existing.artifact);validateSources(plan);existing.expiresAt=clock.instant().plus(RETENTION);return existing.artifact;}
        if(cache.size()>=countLimit)throw limit();validateSources(plan);ensureCache();
        Path file=cacheFs.path(UUID.randomUUID()+".zip");Instant createdAt=clock.instant();BasicFileAttributes initial=null;boolean published=false;
        try {
            try(var channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                initial=cacheFs.regular(file);long available=byteLimit-activeBytes();
                try(var zip=new ZipOutputStream(new LimitedChannelOutput(channel,available))){
                    var candidate=new Artifact(plan,digest,file,0,"",createdAt);writeBytes(zip,"manifest.json",manifest(candidate));writeBytes(zip,"说明.txt",INSTRUCTIONS);
                    for(var target:plan.items())for(var entry:target.files())copy(zip,plan.id(),entry);
                    validateSources(plan);zip.finish();zip.flush();force.force(channel);
                }
            }
            var attr=cacheFs.regular(file);var result=new Artifact(plan,digest,file,attr.size(),hashFile(file,cacheFs,attr),createdAt);
            var cached=new Cached(result,attr,createdAt);cache.put(key,cached);
            try{verify(result);}catch(IOException|RuntimeException e){cache.remove(key);throw e;}
            published=true;return result;
        }finally{
            if(!published&&initial!=null){checkCacheRoot();var current=cacheFs.regular(file);if(!Objects.equals(initial.fileKey(),current.fileKey()))throw conflict();Files.delete(file);}
        }
    }
    /** Read-only: expiration is a POST lifecycle policy, never a reason to delete here. */
    public synchronized void verify(Artifact artifact)throws IOException {
        var cached=owned(artifact);checkCacheRoot();var before=cacheFs.regular(artifact.path());stable(cached.attributes,before);
        if(before.size()!=artifact.bytes()||!hashFile(artifact.path(),cacheFs,before).equals(artifact.sha256()))throw conflict();
        verifyZip(artifact);stable(before,cacheFs.regular(artifact.path()));
        if(!hashFile(artifact.path(),cacheFs,before).equals(artifact.sha256()))throw conflict();
    }
    public synchronized InputStream open(Artifact artifact)throws IOException {
        verify(artifact);var cached=owned(artifact);var stream=QuarantineFs.open(artifact.path());
        try{stable(cached.attributes,cacheFs.regular(artifact.path()));}catch(IOException|RuntimeException e){stream.close();throw e;}
        cached.readers++;
        return new FilterInputStream(stream){boolean closed;@Override public void close()throws IOException {synchronized(QuarantineArchive.this){if(closed)return;closed=true;try{super.close();}finally{cached.readers--;}}}};
    }
    /** Extend owned cache retention to the successful transfer's time plus ten minutes. */
    public synchronized void renew(Artifact artifact,Instant now)throws IOException {Objects.requireNonNull(now);verify(artifact);owned(artifact).expiresAt=now.plus(RETENTION);}
    public synchronized void remove(Artifact artifact)throws IOException {
        var cached=owned(artifact);
        if(cached.readers!=0)throw conflict();verify(artifact);Files.delete(artifact.path());cache.remove(key(artifact));reclaimExpired();
    }
    private Key key(Artifact artifact){if(artifact==null||artifact.plan()==null)throw conflict();return new Key(artifact.plan().id(),artifact.digest());}
    private Cached owned(Artifact artifact){var cached=cache.get(key(artifact));if(cached==null||!cached.artifact.equals(artifact))throw conflict();return cached;}
    private long activeBytes(){return cache.values().stream().mapToLong(value->value.artifact.bytes()).sum();}
    private void ensureCache()throws IOException {
        if(cacheFs!=null){checkCacheRoot();return;}parentFs.directory(parentFs.root());
        Path root=parentFs.path("resume-quarantine-"+UUID.randomUUID());Files.createDirectory(root);parentFs.ordinaryDirectory(root);
        cacheFs=new QuarantineFs(root);cacheRootKey=Files.readAttributes(root,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey();
    }
    private void checkCacheRoot()throws IOException {
        if(cacheFs==null)throw conflict();cacheFs.ordinaryDirectory(cacheFs.root());
        if(!Objects.equals(cacheRootKey,Files.readAttributes(cacheFs.root(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS).fileKey()))throw conflict();
    }
    private void reclaimExpired()throws IOException {
        for(var iterator=cache.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();var cached=entry.getValue();
            if(cached.readers==0&&!clock.instant().isBefore(cached.expiresAt)){verify(cached.artifact);Files.delete(cached.artifact.path());iterator.remove();}
        }
    }
    private void validateSources(QuarantineFiles.Plan plan)throws IOException {
        Path payload=sourceFs.path("quarantine/"+plan.id()+"/payload");sourceFs.ordinaryDirectory(payload);
        var expected=new HashSet<String>();for(var target:plan.items())for(var entry:target.files()){
            expected.add(entry.path());int slash=entry.path().lastIndexOf('/');while(slash>0){expected.add(entry.path().substring(0,slash));slash=entry.path().lastIndexOf('/',slash-1);}
        }
        var actual=new HashSet<String>();inspect(payload,payload,actual,0);if(!actual.equals(expected))throw conflict();
        for(var target:plan.items())for(var entry:target.files()){
            Path file=payload.resolve(entry.path());var before=sourceFs.regular(file);sourceFs.sameStore(sourceFs.root(),file);sourceFs.sameStore(file,sourceFs.root().resolve("quarantine"));evidence(entry,before);
            if(!hashFile(file,sourceFs,before).equals(entry.sha256()))throw conflict();
        }
    }
    private void inspect(Path base,Path directory,Set<String> actual,int depth)throws IOException {
        if(depth>3)throw conflict();sourceFs.ordinaryDirectory(directory);
        try(var children=Files.newDirectoryStream(directory)){for(Path child:children){sourceFs.checked(child);actual.add(base.relativize(child).toString().replace('\\','/'));
            var attr=Files.readAttributes(child,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);if(attr.isDirectory())inspect(base,child,actual,depth+1);else sourceFs.regular(child);
        }}
    }
    private void copy(ZipOutputStream zip,String id,QuarantineFiles.Entry entry)throws IOException {
        Path file=sourceFs.path("quarantine/"+id+"/payload/"+entry.path());var before=sourceFs.regular(file);evidence(entry,before);var digest=digest();long count=0;
        zip.putNextEntry(new ZipEntry("files/"+entry.path()));try(var in=QuarantineFs.open(file)){byte[] buffer=new byte[65536];int length;while((length=in.read(buffer))!=-1){count+=length;if(count>entry.bytes())throw conflict();digest.update(buffer,0,length);zip.write(buffer,0,length);}}
        stable(before,sourceFs.regular(file));if(count!=entry.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))throw conflict();zip.closeEntry();
    }
    private void evidence(QuarantineFiles.Entry entry,BasicFileAttributes attr){if(attr.size()!=entry.bytes()||!attr.lastModifiedTime().toInstant().equals(entry.modified()))throw conflict();}
    private byte[] manifest(Artifact artifact)throws IOException {
        var node=mapper.createObjectNode();node.put("format","resume-workbench-quarantine-files");node.put("version",1);node.put("operationId",artifact.plan().id());node.put("parentDigest",artifact.digest());node.put("createdAt",artifact.createdAt().toString());node.set("plan",mapper.valueToTree(artifact.plan()));
        node.set("files",mapper.valueToTree(artifact.plan().items().stream().flatMap(item->item.files().stream()).toList()));byte[] bytes=mapper.writeValueAsBytes(node);if(bytes.length>MAX_MANIFEST)throw limit();return bytes;
    }
    private void verifyZip(Artifact artifact)throws IOException {
        var expected=new HashMap<String,QuarantineFiles.Entry>();for(var target:artifact.plan().items())for(var file:target.files())expected.put("files/"+file.path(),file);
        var names=new HashSet<>(expected.keySet());names.add("manifest.json");names.add("说明.txt");var central=new HashMap<String,ZipEntry>();
        try(var zip=new ZipFile(artifact.path().toFile())){
            var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();if(entry.isDirectory()||!names.contains(entry.getName())||central.put(entry.getName(),entry)!=null)throw conflict();}if(!central.keySet().equals(names))throw conflict();
            // Bind each central name to the actual bytes an ordinary ZIP extractor will recover.
            for(var entry:central.values())try(var in=zip.getInputStream(entry)){verifyEntry(artifact,entry.getName(),expected.get(entry.getName()),entry,in);}
        }catch(ZipException e){throw conflict();}
        var seen=new HashSet<String>();try(var zip=new ZipInputStream(QuarantineFs.open(artifact.path()))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){
            String name=entry.getName();if(!names.contains(name)||!seen.add(name)||entry.isDirectory())throw conflict();verifyEntry(artifact,name,expected.get(name),central.get(name),zip);zip.closeEntry();
        }}catch(ZipException e){throw conflict();}if(!seen.equals(names))throw conflict();
    }
    private void verifyEntry(Artifact artifact,String name,QuarantineFiles.Entry file,ZipEntry central,InputStream in)throws IOException {
        long limit=file==null?(name.equals("manifest.json")?MAX_MANIFEST:4096):file.bytes();long count=0;var digest=digest();var crc=new CRC32();var text=file==null?new ByteArrayOutputStream():null;
        byte[] buffer=new byte[65536];int length;while((length=in.read(buffer))!=-1){count+=length;if(count>limit)throw conflict();digest.update(buffer,0,length);crc.update(buffer,0,length);if(text!=null)text.write(buffer,0,length);}
        if(central.getSize()!=count||central.getCrc()!=crc.getValue())throw conflict();
        if(file!=null){if(count!=file.bytes()||!HexFormat.of().formatHex(digest.digest()).equals(file.sha256()))throw conflict();}
        else if(name.equals("manifest.json")){if(!mapper.readTree(text.toByteArray()).equals(mapper.readTree(manifest(artifact))))throw conflict();}
        else if(!Arrays.equals(text.toByteArray(),INSTRUCTIONS))throw conflict();
    }
    private static void writeBytes(ZipOutputStream zip,String name,byte[] bytes)throws IOException {zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    private static void stable(BasicFileAttributes before,BasicFileAttributes after){if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))throw conflict();}
    private static String hashFile(Path file,QuarantineFs fs,BasicFileAttributes before)throws IOException {
        var digest=digest();long count=0;try(var in=QuarantineFs.open(file)){byte[] buffer=new byte[65536];int length;while((length=in.read(buffer))!=-1){count+=length;if(count>before.size())throw conflict();digest.update(buffer,0,length);}}
        stable(before,fs.regular(file));if(count!=before.size())throw conflict();return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static ApiException conflict(){return QuarantineFs.conflict();}
    private static ApiException invalid(){return new ApiException("QUARANTINE_INVALID","暂存请求或文件清单无效。",422);}
    private static ApiException limit(){return new ApiException("QUARANTINE_ARCHIVE_LIMIT","文件备份缓存已达到限额，请稍后重试。",409);}
    private static final class LimitedChannelOutput extends OutputStream {
        private final FileChannel channel;private final long limit;private long written;
        LimitedChannelOutput(FileChannel channel,long limit){this.channel=channel;this.limit=limit;}
        @Override public void write(int value)throws IOException {write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {if(length>limit-written)throw limit();var buffer=ByteBuffer.wrap(bytes,offset,length);while(buffer.hasRemaining())channel.write(buffer);written+=length;}
    }
}
