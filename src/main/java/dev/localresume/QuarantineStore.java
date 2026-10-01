package dev.localresume;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Recoverable, same-filesystem storage. Callers own the workspace's exclusive gate. */
@Component
public class QuarantineStore {
    private static final LinkOption NOFOLLOW=LinkOption.NOFOLLOW_LINKS;
    private static final long MAX_BYTES=1024L*1024*1024, MAX_JOURNAL=1024*1024;
    private static final Set<String> STATES=Set.of("preparing","moving","quarantined","restoring","restored","attention");
    public record Item(String kind,String id,long bytes) {}
    /** digest is the recovery token: once restore starts it remains its bound request digest. */
    public record Receipt(String id,String state,Instant createdAt,Instant updatedAt,List<Item> items,long bytes,String backupId,String digest,String errorCode) {}
    public record History(List<Receipt> items,int page,boolean hasMore,int unreadable) {}
    /** complete means every target in this quarantined batch has the expected structure and sizes, not verified content. */
    public record HeldItem(String kind,String id,long bytes,Instant createdAt,boolean complete) {}
    private record Journal(QuarantineFiles.Plan plan,String state,Instant updatedAt,String backupId,String restoreDigest,String errorCode) {}
    private record Stored(Journal journal,String digest) {}
    private record Unit(String path,List<QuarantineFiles.Entry> files,boolean directory) {}
    @FunctionalInterface interface Move { void move(Path source,Path destination)throws IOException; }
    private final Path root;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Move move;
    private final QuarantineJournalIo journalIo;

    @Autowired
    public QuarantineStore(@Value("${resume.data-dir}") String data,ObjectMapper mapper){this(Path.of(data),mapper,Clock.systemUTC());}
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock){this(data,mapper,clock,(source,destination)->Files.move(source,destination));}
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock,Move move){
        this(data,mapper,clock,move,new QuarantineJournalIo());
    }
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock,Move move,QuarantineJournalIo journalIo){
        this.root=data.toAbsolutePath().normalize();this.mapper=mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);this.clock=clock;this.move=move;this.journalIo=Objects.requireNonNull(journalIo);
    }
    public synchronized Receipt existing(String id)throws IOException {var stored=read(id);return stored==null?null:receipt(stored);}
    public synchronized Receipt existing(String id,String requestDigest)throws IOException {
        require(sha(requestDigest));var stored=read(id);if(stored==null)return null;
        if(!stored.journal().plan().requestDigest().equals(requestDigest))throw conflict();return receipt(stored);
    }
    public synchronized Receipt reserve(QuarantineFiles.Plan plan)throws IOException {
        validate(plan);var old=read(plan.id());
        if(old!=null){
            if(!old.journal().plan().requestDigest().equals(plan.requestDigest())||!old.journal().plan().previewDigest().equals(plan.previewDigest())||!keys(old.journal().plan()).equals(keys(plan)))throw conflict();
            return receipt(old);
        }
        var requested=keys(plan);
        for(Path batch:records()){
            Stored owned;try{owned=read(batch.getFileName().toString());}catch(ApiException|IOException e){continue;}
            if(owned!=null&&!owned.journal().state().equals("restored")&&!Collections.disjoint(requested,keys(owned.journal().plan())))throw conflict();
        }
        directory(root);directory(root.resolve("quarantine"));Path batch=path("quarantine/"+plan.id());
        if(present(batch))throw conflict();Files.createDirectory(batch);
        return receipt(write(new Journal(plan,"preparing",clock.instant(),null,null,null)));
    }
    public synchronized Receipt moveToQuarantine(String id,String backupId)throws IOException {
        require(uuid(backupId));var stored=required(id);var journal=stored.journal();
        if(!journal.state().equals("preparing")){
            if(!Objects.equals(journal.backupId(),backupId))throw conflict();return receipt(stored);
        }
        journal=transition(journal,"moving",backupId,null,null);write(journal);
        var units=units(journal.plan());var attempted=new ArrayList<Unit>();Path payload=payload(id);boolean finalizing=false;
        try{
            if(present(payload))throw conflict();
            // Validate the entire batch before creating payload or moving its first unit.
            for(var unit:units){verify(root,unit,true);ensureAbsent(payload.resolve(unit.path()));sameStore(root.resolve(unit.path()),payload.resolve(unit.path()));}
            directory(payload);
            for(var unit:units){
                Path destination=payload.resolve(unit.path());directory(destination.getParent());ensureAbsent(destination);
                // Earlier moves do not prevent an external writer changing a later unit.
                verify(root,unit,true);attempted.add(unit);safeMove(root.resolve(unit.path()),destination);
            }
            // Never publish success against evidence that changed during or after a move.
            checkPayload(journal.plan());for(var unit:units)verify(payload,unit,true);
            finalizing=true;
            return receipt(write(transition(journal,"quarantined",backupId,null,null)));
        }catch(ApiException e){
            rollback(journal,attempted);write(transition(journal,"attention",backupId,null,"QUARANTINE_CONFLICT"));throw e;
        }catch(IOException e){
            // A final journal replacement may already be committed despite the reported I/O error.
            // Keep verified payload consistent with either moving or quarantined if attention also fails.
            if(!finalizing)rollback(journal,attempted);return receipt(write(transition(journal,"attention",backupId,null,"QUARANTINE_MOVE_FAILED")));
        }
    }
    public synchronized Receipt restore(String id,String expectedDigest)throws IOException {
        require(sha(expectedDigest));var stored=required(id);var journal=stored.journal();
        String bound=journal.restoreDigest()==null?stored.digest():journal.restoreDigest();
        if(!bound.equals(expectedDigest))throw conflict();
        if(journal.state().equals("restored"))return receipt(stored);
        var units=units(journal.plan());Path payload=payload(id);checkPayload(journal.plan());
        var pending=new ArrayList<Unit>();
        // Interrupted operations can have units at either location, but never both or neither.
        for(var unit:units){
            Path original=root.resolve(unit.path()),held=payload.resolve(unit.path());boolean active=present(original),quarantined=present(held);
            if(active==quarantined||journal.state().equals("quarantined")&&active)throw conflict();
            verify(active?root:payload,unit,false);if(quarantined){ensureAbsent(original);sameStore(held,original);pending.add(unit);}
        }
        journal=transition(journal,"restoring",journal.backupId(),bound,null);write(journal);
        try{
            for(var unit:pending){Path destination=root.resolve(unit.path());directory(destination.getParent());safeMove(payload.resolve(unit.path()),destination);}
            // Touch only verified original regular files after every unit has returned.
            for(var unit:units)verify(root,unit,false);
            for(var unit:units)for(var entry:unit.files()){Path file=path(entry.path());regular(file);Files.setLastModifiedTime(file,FileTime.from(clock.instant()));}
            return receipt(write(transition(journal,"restored",journal.backupId(),bound,null)));
        }catch(ApiException|IOException e){return receipt(write(transition(journal,"attention",journal.backupId(),bound,"QUARANTINE_RESTORE_FAILED")));}
    }
    public synchronized History history(int page)throws IOException {
        require(page>=0&&page<=Integer.MAX_VALUE/20);var records=records();var receipts=new ArrayList<Receipt>();int unreadable=0;
        for(Path batch:records){try{var stored=read(batch.getFileName().toString());if(stored!=null)receipts.add(receipt(stored));else unreadable++;}catch(ApiException|IOException e){unreadable++;}}
        receipts.sort(Comparator.comparing(Receipt::createdAt).reversed().thenComparing(Receipt::id));int start=Math.min(page*20,receipts.size()),end=Math.min(start+20,receipts.size());
        return new History(List.copyOf(receipts.subList(start,end)),page,end<receipts.size(),unreadable);
    }
    public synchronized List<HeldItem> inventory()throws IOException {
        var held=new ArrayList<HeldItem>();
        for(Path batch:records()){
            Stored stored;try{stored=read(batch.getFileName().toString());}catch(ApiException|IOException e){continue;}if(stored==null)continue;
            var journal=stored.journal();boolean layout=journal.state().equals("quarantined");
            try{
                checkPayload(journal.plan());Path base=payload(journal.plan().id());
                for(var target:journal.plan().items())for(var entry:target.files())if(regular(base.resolve(entry.path())).size()!=entry.bytes())layout=false;
            }catch(ApiException|IOException e){layout=false;}
            for(var target:journal.plan().items()){
                long bytes=0;boolean exists=false,complete=layout;var base=payload(journal.plan().id());
                if(target.kind().equals("image")){
                    Path image=base.resolve("attachments/"+target.id());
                    try{checked(image);exists=present(image);if(exists){ordinaryDirectory(image);try(var stream=Files.newDirectoryStream(image)){
                        int count=0;for(Path file:stream){if(++count>20000){complete=false;break;}try{bytes=Math.addExact(bytes,regular(file).size());}catch(ApiException|IOException|ArithmeticException e){complete=false;}}
                    }}}catch(ApiException|IOException e){complete=false;}
                }
                for(var entry:target.files()){
                    Path file=base.resolve(entry.path());try{checked(file);if(present(file)){exists=true;var attr=regular(file);if(target.kind().equals("pdf"))bytes=Math.addExact(bytes,attr.size());if(attr.size()!=entry.bytes())complete=false;}else complete=false;}
                    catch(ApiException|IOException|ArithmeticException e){complete=false;}
                }
                if(exists)held.add(new HeldItem(target.kind(),target.id(),bytes,journal.plan().createdAt(),complete));
            }
        }
        return List.copyOf(held);
    }
    private List<Path> records()throws IOException {
        Path quarantine=path("quarantine");if(!present(quarantine))return List.of();ordinaryDirectory(quarantine);
        var result=new ArrayList<Path>();try(var stream=Files.newDirectoryStream(quarantine)){for(Path batch:stream)result.add(batch);}return result;
    }
    private Stored required(String id)throws IOException {var stored=read(id);if(stored==null)throw new ApiException("QUARANTINE_NOT_FOUND","暂存记录不存在。",404);return stored;}
    private Stored read(String id)throws IOException {
        require(uuid(id));Path batch=path("quarantine/"+id);if(!present(batch))return null;
        try{
            ordinaryDirectory(batch);Path file=path("quarantine/"+id+"/journal.json");var attr=regular(file);require(attr.size()>0&&attr.size()<=MAX_JOURNAL);
            byte[] bytes;try(var in=open(file)){bytes=in.readNBytes((int)MAX_JOURNAL+1);}require(bytes.length==attr.size());
            JsonNode envelope=mapper.readTree(bytes);require(envelope!=null&&envelope.isObject()&&fields(envelope).equals(Set.of("version","digest","journal"))
                &&envelope.get("version").isIntegralNumber()&&envelope.get("version").canConvertToInt()&&envelope.get("version").intValue()==1&&envelope.get("digest").isTextual());
            String digest=envelope.get("digest").textValue();require(sha(digest)&&digest.equals(hash(mapper.writeValueAsBytes(envelope.get("journal")))));
            Journal journal=mapper.treeToValue(envelope.get("journal"),Journal.class);validate(journal.plan());
            require(id.equals(journal.plan().id())&&STATES.contains(journal.state())&&journal.updatedAt()!=null
                &&(journal.backupId()==null||uuid(journal.backupId()))&&(journal.restoreDigest()==null||sha(journal.restoreDigest()))
                &&(journal.errorCode()==null||Set.of("QUARANTINE_CONFLICT","QUARANTINE_MOVE_FAILED","QUARANTINE_RESTORE_FAILED").contains(journal.errorCode())));
            require(!Set.of("moving","quarantined").contains(journal.state())||journal.backupId()!=null);
            require(!Set.of("restoring","restored").contains(journal.state())||journal.restoreDigest()!=null);
            return new Stored(journal,digest);
        }catch(IOException|RuntimeException e){throw new ApiException("QUARANTINE_UNREADABLE","暂存记录无法核验，请保留文件并检查暂存记录。",409);}
    }
    private Stored write(Journal journal)throws IOException {
        Path batch=path("quarantine/"+journal.plan().id());ordinaryDirectory(batch);JsonNode tree=mapper.valueToTree(journal);String digest=hash(mapper.writeValueAsBytes(tree));
        var envelope=mapper.createObjectNode();envelope.put("version",1);envelope.put("digest",digest);envelope.set("journal",tree);byte[] bytes=mapper.writeValueAsBytes(envelope);require(bytes.length<=MAX_JOURNAL);
        Path destination=batch.resolve("journal.json"),temp=batch.resolve(".journal-"+UUID.randomUUID()+".tmp");checked(destination);if(present(destination))regular(destination);
        try{
            try(var channel=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW)){
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())journalIo.write(channel,buffer);journalIo.force(channel);
            }
            checked(temp);checked(destination);if(present(destination))regular(destination);
            journalIo.replace(temp,destination);
        }finally{Files.deleteIfExists(temp);}
        return new Stored(journal,digest);
    }
    private Journal transition(Journal journal,String state,String backup,String restore,String error){return new Journal(journal.plan(),state,clock.instant(),backup,restore,error);}
    private Receipt receipt(Stored stored){
        var journal=stored.journal();var items=journal.plan().items().stream().map(i->new Item(i.kind(),i.id(),i.bytes())).toList();
        String token=journal.restoreDigest()==null?stored.digest():journal.restoreDigest();
        return new Receipt(journal.plan().id(),journal.state(),journal.plan().createdAt(),journal.updatedAt(),items,items.stream().mapToLong(Item::bytes).sum(),journal.backupId(),token,journal.errorCode());
    }
    private void rollback(Journal journal,List<Unit> units){
        Path payload=root.resolve("quarantine/"+journal.plan().id()+"/payload");
        for(var unit:units){try{
            Path held=payload.resolve(unit.path()),original=root.resolve(unit.path());checked(held);checked(original);
            if(present(held)&&!present(original)){verify(payload,unit,false);directory(original.getParent());safeMove(held,original);}
        }catch(ApiException|IOException e){/* Leave genuine remaining payload and journal for explicit recovery. */}}
    }
    private List<Unit> units(QuarantineFiles.Plan plan){
        var result=new ArrayList<Unit>();for(var target:plan.items()){
            if(target.kind().equals("image"))result.add(new Unit("attachments/"+target.id(),target.files(),true));
            else for(var entry:target.files())result.add(new Unit(entry.path(),List.of(entry),false));
        }return result;
    }
    private void verify(Path base,Unit unit,boolean timestamps)throws IOException {
        Path location=base.resolve(unit.path());checked(location);
        if(unit.directory()){
            ordinaryDirectory(location);var names=new HashSet<String>();try(var stream=Files.newDirectoryStream(location)){for(Path child:stream){checked(child);regular(child);names.add(child.getFileName().toString());}}
            var expected=new HashSet<String>();for(var file:unit.files())expected.add(Path.of(file.path()).getFileName().toString());if(!names.equals(expected))throw conflict();
        }
        for(var entry:unit.files()){
            Path file=base.resolve(entry.path());var before=regular(file);
            if(before.size()!=entry.bytes()||timestamps&&!before.lastModifiedTime().toInstant().equals(entry.modified()))throw conflict();
            var digest=digest();try(var in=open(file)){byte[] buffer=new byte[65536];int length;long read=0;while((length=in.read(buffer))!=-1){read+=length;if(read>entry.bytes())throw conflict();digest.update(buffer,0,length);}if(read!=entry.bytes())throw conflict();}
            var after=regular(file);if(after.size()!=before.size()||!after.lastModifiedTime().equals(before.lastModifiedTime())||!Objects.equals(after.fileKey(),before.fileKey())||!HexFormat.of().formatHex(digest.digest()).equals(entry.sha256()))throw conflict();
        }
    }
    /** Reject all unexpected payload entries, including empty foreign directories and links. */
    private void checkPayload(QuarantineFiles.Plan plan)throws IOException {
        Path payload=payload(plan.id());if(!present(payload))return;ordinaryDirectory(payload);var allowed=new HashSet<String>();
        for(var target:plan.items())for(var entry:target.files()){
            allowed.add(entry.path());int index=entry.path().lastIndexOf('/');while(index>0){allowed.add(entry.path().substring(0,index));index=entry.path().lastIndexOf('/',index-1);}
        }
        inspectPayload(payload,payload,allowed,0);
    }
    private void inspectPayload(Path base,Path directory,Set<String> allowed,int depth)throws IOException {
        if(depth>3)throw conflict();try(var stream=Files.newDirectoryStream(directory)){for(Path child:stream){
            checked(child);String relative=base.relativize(child).toString().replace('\\','/');if(!allowed.contains(relative))throw conflict();
            var attr=Files.readAttributes(child,BasicFileAttributes.class,NOFOLLOW);if(attr.isDirectory()){ordinaryDirectory(child);inspectPayload(base,child,allowed,depth+1);}else regular(child);
        }}
    }
    private void safeMove(Path source,Path destination)throws IOException {
        checked(source);checked(destination);if(present(destination))throw conflict();ordinaryDirectory(destination.getParent());
        sameStore(source,destination);
        // ATOMIC_MOVE is deliberately excluded: its contract can replace an existing destination.
        move.move(source,destination);
    }
    private void sameStore(Path source,Path destination)throws IOException {
        checked(source);checked(destination);Path parent=destination.getParent();
        while(parent!=null&&!present(parent))parent=parent.getParent();
        if(parent==null||!parent.startsWith(root))throw conflict();ordinaryDirectory(parent);
        if(!Files.getFileStore(source).equals(Files.getFileStore(parent)))throw conflict();
    }
    private void ensureAbsent(Path file)throws IOException {checked(file);if(present(file))throw conflict();}
    private Path payload(String id)throws IOException {require(uuid(id));return path("quarantine/"+id+"/payload");}
    private Path path(String relative)throws IOException {Path value=root.resolve(relative).normalize();checked(value);return value;}
    private void checked(Path value)throws IOException {
        Path absolute=value.toAbsolutePath().normalize();if(!absolute.startsWith(root))throw conflict();
        Path cursor=absolute.getRoot();for(Path part:absolute){cursor=cursor.resolve(part);if(present(cursor)){
            var attr=Files.readAttributes(cursor,BasicFileAttributes.class,NOFOLLOW);
            if(attr.isSymbolicLink()||attr.isOther()||!cursor.toRealPath(NOFOLLOW).equals(cursor.toRealPath()))throw conflict();
            if(!cursor.equals(absolute)&&!attr.isDirectory())throw conflict();
        }}
    }
    private void directory(Path value)throws IOException {
        checked(value);if(present(value)){ordinaryDirectory(value);return;}
        if(value.equals(root)){Files.createDirectories(root);ordinaryDirectory(root);return;}
        Path parent=value.getParent();if(parent==null)throw conflict();directory(parent);Files.createDirectory(value);ordinaryDirectory(value);
    }
    private void ordinaryDirectory(Path value)throws IOException {checked(value);var attr=Files.readAttributes(value,BasicFileAttributes.class,NOFOLLOW);if(!attr.isDirectory()||attr.isSymbolicLink()||attr.isOther())throw conflict();}
    private BasicFileAttributes regular(Path value)throws IOException {checked(value);var attr=Files.readAttributes(value,BasicFileAttributes.class,NOFOLLOW);if(!attr.isRegularFile()||attr.isSymbolicLink()||attr.isOther())throw conflict();return attr;}
    private static boolean present(Path value){return Files.exists(value,NOFOLLOW);}
    private static InputStream open(Path value)throws IOException {return Channels.newInputStream(Files.newByteChannel(value,Set.of(StandardOpenOption.READ,NOFOLLOW)));}
    private static Set<String> fields(JsonNode node){var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return names;}
    private static Set<String> keys(QuarantineFiles.Plan plan){var result=new HashSet<String>();for(var target:plan.items())result.add(target.kind()+"/"+target.id());return result;}
    private static void validate(QuarantineFiles.Plan plan){
        require(plan!=null&&uuid(plan.id())&&sha(plan.requestDigest())&&sha(plan.previewDigest())&&plan.createdAt()!=null&&plan.items()!=null&&!plan.items().isEmpty()&&plan.items().size()<=100);
        var targets=new HashSet<String>();long total=0;
        for(var target:plan.items()){
            require(target!=null&&target.kind()!=null&&Set.of("image","pdf").contains(target.kind())&&uuid(target.id())&&target.files()!=null&&target.bytes()>0&&target.bytes()<=MAX_BYTES&&targets.add(target.kind()+"/"+target.id()));
            var paths=new HashSet<String>();long size=0;
            for(var file:target.files()){
                require(file!=null&&file.path()!=null&&file.bytes()>0&&file.bytes()<=MAX_BYTES&&sha(file.sha256())&&file.modified()!=null&&paths.add(file.path()));
                size+=file.bytes();require(size<=MAX_BYTES);
            }
            require(size==target.bytes());
            if(target.kind().equals("pdf"))require(paths.equals(Set.of("exports/"+target.id()+".pdf","exports/"+target.id()+".json")));
            else{
                String prefix="attachments/"+target.id()+"/";require(paths.size()==3&&paths.contains(prefix+"metadata.json")&&paths.contains(prefix+"image.png")
                    &&paths.stream().filter(p->Set.of(prefix+"original.png",prefix+"original.jpeg",prefix+"original.webp").contains(p)).count()==1);
            }
            total+=size;require(total<=MAX_BYTES);
        }
    }
    private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    private static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void require(boolean valid){if(!valid)throw new ApiException("QUARANTINE_INVALID","暂存请求或文件清单无效。",422);}
    private static ApiException conflict(){return new ApiException("QUARANTINE_CONFLICT","文件或暂存状态已变化，请刷新后重试；现有文件已保留。",409);}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hash(byte[] value){return HexFormat.of().formatHex(digest().digest(value));}
}
