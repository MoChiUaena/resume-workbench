package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Persistent ownership and physical-byte accounting for one workspace's temporary ZIP cache. */
final class QuarantineArchiveCache {
    interface Check { void verify(QuarantineArchive.Artifact artifact) throws IOException; }
    record Stored(String name,QuarantineFiles.Plan plan,String digest,long bytes,String sha256,Instant createdAt,String fileKey) {}
    record Owned(QuarantineArchive.Artifact artifact,Path metadata,Path expiry,Path lease,BasicFileAttributes zipAttributes,BasicFileAttributes metadataAttributes,BasicFileAttributes expiryAttributes,BasicFileAttributes leaseAttributes,Instant expiresAt) {}
    private record Owner(String dataPath,String dataKey,String rootKey) {}
    private static final Map<Path,ReentrantLock> JVM_LOCKS=new ConcurrentHashMap<>();
    private static final Map<Path,ReaderLease> READERS=new HashMap<>();
    private static final int EXPIRY_BYTES=20;
    private final QuarantineFs dataFs,parentFs;
    private final ObjectMapper mapper;
    private Path root;
    private String ownerDataPath;
    private final long byteLimit;
    private final int countLimit;
    private QuarantineFs rootFs;
    private Object rootKey;

    QuarantineArchiveCache(QuarantineFs dataFs,QuarantineFs parentFs,ObjectMapper mapper,long byteLimit,int countLimit){
        this.dataFs=dataFs;this.parentFs=parentFs;this.mapper=mapper;this.byteLimit=byteLimit;this.countLimit=countLimit;
    }
    Path root(){return root;}
    QuarantineFs fs(){if(rootFs==null)throw conflict();return rootFs;}
    private Path child(String name){if(name.contains("/")||name.contains("\\")||name.equals(".")||name.equals(".."))throw conflict();return root.resolve(name);}
    private static String sha(String value){try{var md=MessageDigest.getInstance("SHA-256");return HexFormat.of().formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static ApiException conflict(){return QuarantineFs.conflict();}
    private static ApiException limit(){return new ApiException("QUARANTINE_ARCHIVE_LIMIT","文件备份缓存已达到限额，请稍后重试。",409);}
    private static String key(BasicFileAttributes attr){return attr.fileKey()==null?"":attr.fileKey().toString();}
    private static BasicFileAttributes attributes(Path path)throws IOException{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    private static void regular(BasicFileAttributes attr){if(!attr.isRegularFile()||attr.isSymbolicLink()||attr.isOther())throw conflict();}
    private BasicFileAttributes directRegular(Path path)throws IOException {if(!path.getParent().equals(root))throw conflict();var attr=attributes(path);regular(attr);return attr;}
    private Path canonicalDataRoot()throws IOException {dataFs.ordinaryDirectory(dataFs.root());return dataFs.root().toRealPath(LinkOption.NOFOLLOW_LINKS);}
    private static void same(BasicFileAttributes before,BasicFileAttributes after){regular(after);if(before.size()!=after.size()||!Objects.equals(key(before),key(after))||!before.lastModifiedTime().equals(after.lastModifiedTime()))throw conflict();}
    private void checkRoot()throws IOException {
        if(rootFs==null)throw conflict();rootFs.ordinaryDirectory(root);
        var attr=attributes(root);if(!Objects.equals(rootKey,attr.fileKey()))throw conflict();
        Path owner=child(".owner"),lock=child(".lock");var ownerAttributes=directRegular(owner);directRegular(lock);
        if(ownerAttributes.size()>1024)throw conflict();
        String currentDataPath=canonicalDataRoot().toString();if(!currentDataPath.equals(ownerDataPath))throw conflict();
        var expected=new Owner(currentDataPath,key(attributes(dataFs.root())),String.valueOf(rootKey));
        if(!mapper.readValue(Files.readAllBytes(owner),Owner.class).equals(expected))throw conflict();
    }
    /** Only a POST may create the cache root or its control files. */
    void ensure()throws IOException {
        if(rootFs!=null){checkRoot();return;}
        parentFs.directory(parentFs.root());
        Path canonicalData=canonicalDataRoot(),canonicalParent=parentFs.root().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if(canonicalParent.startsWith(canonicalData))throw conflict();
        ownerDataPath=canonicalData.toString();root=canonicalParent.resolve("resume-quarantine-v2-"+sha(ownerDataPath));
        if(!QuarantineFs.present(root)){
            Files.createDirectory(root);var fs=new QuarantineFs(root);fs.ordinaryDirectory(root);
            var dataKey=key(attributes(dataFs.root()));var rootFileKey=attributes(root).fileKey();
            byte[] owner=mapper.writeValueAsBytes(new Owner(ownerDataPath,dataKey,String.valueOf(rootFileKey)));
            if(owner.length>byteLimit)throw limit();
            Files.write(child(".owner"),owner,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Files.write(child(".lock"),new byte[0],StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }
        rootFs=new QuarantineFs(root);rootKey=attributes(root).fileKey();checkRoot();
    }
    Guard lock()throws IOException {
        checkRoot();var local=JVM_LOCKS.computeIfAbsent(root,p->new ReentrantLock());local.lock();
        try{var channel=FileChannel.open(child(".lock"),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);try{return new Guard(local,channel,channel.lock());}catch(Throwable t){channel.close();throw t;}}
        catch(IOException|RuntimeException e){local.unlock();throw e;}
    }
    final class Guard implements AutoCloseable {
        private final ReentrantLock local;private final FileChannel channel;private final FileLock fileLock;
        Guard(ReentrantLock local,FileChannel channel,FileLock fileLock){this.local=local;this.channel=channel;this.fileLock=fileLock;}
        @Override public void close()throws IOException {try{fileLock.release();}finally{try{channel.close();}finally{local.unlock();}}}
        List<Owned> list()throws IOException {
            checkRoot();var files=new HashMap<String,EnumSet<Part>>();
            try(var entries=Files.newDirectoryStream(root)){for(Path path:entries){directRegular(path);String name=path.getFileName().toString();
                if(name.equals(".owner")||name.equals(".lock"))continue;
                int dot=name.lastIndexOf('.');if(dot<0)throw conflict();String id=name.substring(0,dot);try{if(!UUID.fromString(id).toString().equals(id))throw conflict();}catch(IllegalArgumentException e){throw conflict();}
                Part part=switch(name.substring(dot)){case ".zip"->Part.ZIP;case ".json"->Part.JSON;case ".ttl"->Part.TTL;case ".lease"->Part.LEASE;default->throw conflict();};
                if(!files.computeIfAbsent(id,k->EnumSet.noneOf(Part.class)).add(part))throw conflict();
            }}
            var result=new ArrayList<Owned>();for(var group:files.entrySet()){
                if(!group.getValue().equals(EnumSet.allOf(Part.class)))throw conflict();String id=group.getKey();
                Path zip=child(id+".zip"),meta=child(id+".json"),ttl=child(id+".ttl"),lease=child(id+".lease");
                var zipAttr=directRegular(zip);var metaAttr=directRegular(meta);var ttlAttr=directRegular(ttl);var leaseAttr=directRegular(lease);
                if(ttlAttr.size()!=EXPIRY_BYTES||Files.size(meta)>2*1024*1024)throw conflict();
                var stored=mapper.readValue(Files.readAllBytes(meta),Stored.class);
                if(!id.equals(stored.name())||stored.plan()==null||stored.digest()==null||stored.sha256()==null||stored.fileKey()==null||
                    !QuarantineFiles.sha(stored.digest())||!QuarantineFiles.sha(stored.sha256())||stored.bytes()!=zipAttr.size()||!stored.fileKey().equals(key(zipAttr)))throw conflict();
                QuarantineFiles.validate(stored.plan());String expiryText=Files.readString(ttl,java.nio.charset.StandardCharsets.US_ASCII);
                if(!expiryText.matches("[0-9]{20}"))throw conflict();Instant expiry=Instant.ofEpochSecond(Long.parseLong(expiryText));
                result.add(new Owned(new QuarantineArchive.Artifact(stored.plan(),stored.digest(),zip,stored.bytes(),stored.sha256(),stored.createdAt()),meta,ttl,lease,zipAttr,metaAttr,ttlAttr,leaseAttr,expiry));
            }
            if(result.size()>countLimit)throw conflict();return result;
        }
        long physical()throws IOException {checkRoot();long total=0;try(var paths=Files.newDirectoryStream(root)){for(Path path:paths)total=Math.addExact(total,directRegular(path).size());}return total;}
        void room(List<Owned> owned,long reservation)throws IOException {if(owned.size()>=countLimit||reservation<0||reservation>byteLimit-physical())throw limit();}
        long reservation(QuarantineArchive.Artifact candidate)throws IOException {
            var placeholder=new Stored(candidate.path().getFileName().toString().replace(".zip",""),candidate.plan(),candidate.digest(),Long.MAX_VALUE,"f".repeat(64),candidate.createdAt(),"f".repeat(256));
            return mapper.writeValueAsBytes(placeholder).length+EXPIRY_BYTES;
        }
        void publish(QuarantineArchive.Artifact artifact,BasicFileAttributes attr)throws IOException {
            checkRoot();String id=artifact.path().getFileName().toString().replace(".zip","");if(!artifact.path().equals(child(id+".zip")))throw conflict();
            var stored=new Stored(id,artifact.plan(),artifact.digest(),artifact.bytes(),artifact.sha256(),artifact.createdAt(),key(attr));
            byte[] metadata=mapper.writeValueAsBytes(stored);long actual=metadata.length+EXPIRY_BYTES;if(actual>byteLimit-physical())throw limit();
            Files.write(child(id+".json"),metadata,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Files.writeString(child(id+".ttl"),expiry(artifact.createdAt().plus(QuarantineArchive.RETENTION)),java.nio.charset.StandardCharsets.US_ASCII,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            Files.write(child(id+".lease"),new byte[0],StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        }
        Owned find(QuarantineArchive.Artifact artifact)throws IOException {for(var owned:list())if(owned.artifact().equals(artifact))return owned;throw conflict();}
        void renew(Owned owned,Instant expiresAt)throws IOException {
            checkRoot();var before=rootFs.regular(owned.expiry());if(before.size()!=EXPIRY_BYTES)throw conflict();
            try(var channel=FileChannel.open(owned.expiry(),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var bytes=ByteBuffer.wrap(expiry(expiresAt).getBytes(java.nio.charset.StandardCharsets.US_ASCII));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
            var after=rootFs.regular(owned.expiry());if(!Objects.equals(key(before),key(after))||after.size()!=EXPIRY_BYTES)throw conflict();
        }
        boolean delete(Owned owned,Check verifier,boolean optional)throws IOException {
            checkRoot();FileLock exclusive=null;FileChannel channel=FileChannel.open(owned.lease(),StandardOpenOption.READ,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try{try{exclusive=channel.tryLock();}catch(OverlappingFileLockException e){if(optional)return false;throw conflict();}if(exclusive==null){if(optional)return false;throw conflict();}
                var fresh=find(owned.artifact());same(owned.zipAttributes(),fresh.zipAttributes());verifier.verify(owned.artifact());
                safeDelete(fresh.artifact().path(),fresh.zipAttributes());safeDelete(fresh.metadata(),fresh.metadataAttributes());safeDelete(fresh.expiry(),fresh.expiryAttributes());
            }finally{if(exclusive!=null)exclusive.release();channel.close();}
            safeDelete(owned.lease(),owned.leaseAttributes());return true;
        }
        private void safeDelete(Path path,BasicFileAttributes before)throws IOException {checkRoot();same(before,directRegular(path));Files.delete(path);}
        ReaderLease readLease(Owned owned)throws IOException {return ReaderLease.acquire(owned.lease());}
    }
    private enum Part {ZIP,JSON,TTL,LEASE}
    private static String expiry(Instant value){long second=value.getEpochSecond();if(second<0)throw conflict();return String.format(Locale.ROOT,"%020d",second);}
    static final class ReaderLease implements AutoCloseable {
        final Path path;final FileChannel channel;final FileLock lock;int uses=1;
        ReaderLease(Path path,FileChannel channel,FileLock lock){this.path=path;this.channel=channel;this.lock=lock;}
        static synchronized ReaderLease acquire(Path path)throws IOException {
            var current=READERS.get(path);if(current!=null){current.uses++;return current;}
            var channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            try{var lease=new ReaderLease(path,channel,channel.lock(0,Long.MAX_VALUE,true));READERS.put(path,lease);return lease;}catch(Throwable t){channel.close();throw t;}
        }
        @Override public void close()throws IOException {synchronized(ReaderLease.class){if(--uses==0){READERS.remove(path);try{lock.release();}finally{channel.close();}}}}
    }
}
