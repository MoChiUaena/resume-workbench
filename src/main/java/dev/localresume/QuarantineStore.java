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
    private static final Set<String> STATES=Set.of("preparing","moving","quarantined","restoring","restored","attention","purging","purged");
    public record Item(String kind,String id,long bytes) {}
    /** digest is the recovery token: once restore or purge starts it remains its bound request digest. */
    public record CleanupInfo(String exportId,String sha256,long bytes) {}
    public record Receipt(String id,String state,Instant createdAt,Instant updatedAt,List<Item> items,long bytes,String backupId,String digest,String errorCode,CleanupInfo cleanup) {
        public Receipt(String id,String state,Instant createdAt,Instant updatedAt,List<Item> items,long bytes,String backupId,String digest,String errorCode){this(id,state,createdAt,updatedAt,items,bytes,backupId,digest,errorCode,null);}
    }
    record CleanupView(QuarantineFiles.Plan plan,Receipt receipt,List<ExportService.Export> pdfs) {}
    public record PurgedItem(String kind,String id,Instant createdAt,boolean complete) {}
    @FunctionalInterface interface PurgeCheck {void check(CleanupView view)throws IOException;}
    @FunctionalInterface interface Delete {void delete(Path path)throws IOException;}
    public record History(List<Receipt> items,int page,boolean hasMore,int unreadable) {}
    /** complete means every target in this quarantined batch has the expected structure and sizes, not verified content. */
    public record HeldItem(String kind,String id,long bytes,Instant createdAt,boolean complete) {}
    private record PdfMetadata(String id,String base64) {}
    private record Purge(String token,String exportId,String archiveSha256,long archiveBytes,List<PdfMetadata> pdfMetadata) {
        Purge {if(pdfMetadata!=null)pdfMetadata=List.copyOf(pdfMetadata);}
        CleanupInfo info(){return new CleanupInfo(exportId,archiveSha256,archiveBytes);}
    }
    private record Journal(QuarantineFiles.Plan plan,String state,Instant updatedAt,String backupId,String restoreDigest,String errorCode,Purge purge) {
        Journal(QuarantineFiles.Plan plan,String state,Instant updatedAt,String backupId,String restoreDigest,String errorCode){this(plan,state,updatedAt,backupId,restoreDigest,errorCode,null);}
    }
    private record Stored(Journal journal,String digest) {}
    private record Unit(String path,List<QuarantineFiles.Entry> files,boolean directory) {}
    @FunctionalInterface interface Move { void move(Path source,Path destination)throws IOException; }
    private final Path root;
    private final QuarantineFs fs;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Move move;
    private final QuarantineJournalIo journalIo;
    private final Delete delete;

    @Autowired
    public QuarantineStore(@Value("${resume.data-dir}") String data,ObjectMapper mapper){this(Path.of(data),mapper,Clock.systemUTC());}
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock){this(data,mapper,clock,(source,destination)->Files.move(source,destination));}
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock,Move move){
        this(data,mapper,clock,move,new QuarantineJournalIo());
    }
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock,Move move,QuarantineJournalIo journalIo){
        this(data,mapper,clock,move,journalIo,Files::delete);
    }
    QuarantineStore(Path data,ObjectMapper mapper,Clock clock,Move move,QuarantineJournalIo journalIo,Delete delete){
        this.fs=new QuarantineFs(data);this.root=fs.root();this.mapper=mapper.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);this.clock=clock;this.move=move;this.journalIo=Objects.requireNonNull(journalIo);this.delete=Objects.requireNonNull(delete);
    }
    synchronized CleanupView cleanupView(String id,String expectedDigest)throws IOException {
        require(sha(expectedDigest));var stored=required(id);var journal=stored.journal();
        if(!receipt(stored).digest().equals(expectedDigest))throw conflict();
        if(!Set.of("quarantined","purging","purged").contains(journal.state()))throw conflict();
        if(journal.state().equals("purged")){ensureAbsent(payload(id));ensureAbsent(discard(id));return new CleanupView(journal.plan(),receipt(stored),owners(journal.plan(),journal.purge()));}
        checkRemaining(journal.plan(),journal.state().equals("quarantined"));
        return new CleanupView(journal.plan(),receipt(stored),journal.purge()==null?owners(journal.plan(),capture(journal.plan())):owners(journal.plan(),journal.purge()));
    }
    synchronized Receipt purge(String id,String expectedDigest,CleanupInfo proof,PurgeCheck check)throws IOException {
        validateProof(proof);Objects.requireNonNull(check);var view=cleanupView(id,expectedDigest);var stored=required(id);var journal=stored.journal();
        if(journal.purge()!=null&&!journal.purge().info().equals(proof))throw conflict();
        if(journal.state().equals("purged"))return receipt(stored);
        // Reference evidence comes from the last fully checked view, including sealed PDF owners on retries.
        check.check(view);cleanupView(id,expectedDigest);
        if(journal.purge()==null){
            var sealed=new Purge(expectedDigest,proof.exportId(),proof.sha256(),proof.bytes(),capture(journal.plan()));
            journal=new Journal(journal.plan(),"purging",clock.instant(),journal.backupId(),null,null,sealed);
            // Outside the recovery catch: unknown initial publication never triggers a move or delete.
            write(journal);
        }
        boolean finalizing=false;
        try{
            checkRemaining(journal.plan(),false);Path payload=payload(id),discard=discard(id);
            if(present(payload)){safeMove(payload,discard);checkRemaining(journal.plan(),false);}
            for(var target:journal.plan().items())for(var entry:target.files()){
                // An external writer can introduce unknown entries or originals after any earlier action.
                checkRemaining(journal.plan(),false);Path file=discard.resolve(entry.path());
                if(present(file)){verify(discard,new Unit(entry.path(),List.of(entry),false),false);delete.delete(file);if(present(file))throw conflict();}
            }
            deleteEmptyDirectories(journal.plan());ensureAbsent(payload);ensureAbsent(discard);
            finalizing=true;return receipt(write(transition(journal,"purged",journal.backupId(),null,null)));
        }catch(ApiException|IOException e){
            // A completed tombstone may already be published: never replace it with a retry intent.
            if(finalizing){var observed=required(id);if(observed.journal().state().equals("purged"))throw e;}
            var failed=write(transition(journal,"purging",journal.backupId(),null,"QUARANTINE_PURGE_FAILED"));
            if(e instanceof ApiException a)throw a;return receipt(failed);
        }
    }
    public synchronized List<PurgedItem> purgedInventory()throws IOException {
        var result=new ArrayList<PurgedItem>();
        for(Path batch:records()){
            Stored stored;try{stored=read(batch.getFileName().toString());}catch(ApiException|IOException e){continue;}
            if(stored==null||!stored.journal().state().equals("purged"))continue;var plan=stored.journal().plan();boolean complete=false;
            try{complete=!present(payload(plan.id()))&&!present(discard(plan.id()));}catch(ApiException|IOException e){/* fail closed */}
            for(var target:plan.items())result.add(new PurgedItem(target.kind(),target.id(),plan.createdAt(),complete));
        }return List.copyOf(result);
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
            if(owned!=null&&!Set.of("restored","purged").contains(owned.journal().state())&&!Collections.disjoint(requested,keys(owned.journal().plan())))throw conflict();
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
        if(journal.purge()!=null)throw conflict();
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
            if(journal.state().equals("purged"))continue;
            if(journal.state().equals("purging")){purgingInventory(journal,held);continue;}
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
                &&envelope.get("version").isIntegralNumber()&&envelope.get("version").canConvertToInt()&&Set.of(1,2).contains(envelope.get("version").intValue())&&envelope.get("digest").isTextual());
            String digest=envelope.get("digest").textValue();require(sha(digest)&&digest.equals(hash(mapper.writeValueAsBytes(envelope.get("journal")))));
            int version=envelope.get("version").intValue();var expected=new HashSet<>(Set.of("plan","state","updatedAt","backupId","restoreDigest","errorCode"));if(version==2)expected.add("purge");require(envelope.get("journal").isObject()&&fields(envelope.get("journal")).equals(expected));
            Journal journal=mapper.treeToValue(envelope.get("journal"),Journal.class);validate(journal.plan());
            require(id.equals(journal.plan().id())&&STATES.contains(journal.state())&&journal.updatedAt()!=null
                &&(journal.backupId()==null||uuid(journal.backupId()))&&(journal.restoreDigest()==null||sha(journal.restoreDigest()))
                &&(journal.errorCode()==null||Set.of("QUARANTINE_CONFLICT","QUARANTINE_MOVE_FAILED","QUARANTINE_RESTORE_FAILED","QUARANTINE_PURGE_FAILED").contains(journal.errorCode())));
            require(!Set.of("moving","quarantined").contains(journal.state())||journal.backupId()!=null);
            require(!Set.of("restoring","restored").contains(journal.state())||journal.restoreDigest()!=null);
            boolean cleanup=Set.of("purging","purged").contains(journal.state());require(cleanup==(version==2)&&cleanup==(journal.purge()!=null));
            if(cleanup){
                var p=envelope.get("journal").get("purge");require(p!=null&&p.isObject()&&fields(p).equals(Set.of("token","exportId","archiveSha256","archiveBytes","pdfMetadata")));
                for(String field:List.of("token","exportId","archiveSha256"))require(p.get(field).isTextual());
                require(p.get("archiveBytes").isIntegralNumber()&&p.get("archiveBytes").canConvertToLong()&&p.get("pdfMetadata").isArray());
                for(var metadata:p.get("pdfMetadata"))require(metadata.isObject()&&fields(metadata).equals(Set.of("id","base64"))&&metadata.get("id").isTextual()&&metadata.get("base64").isTextual());
                require(journal.backupId()!=null&&journal.restoreDigest()==null&&sha(journal.purge().token())&&(journal.errorCode()==null||journal.errorCode().equals("QUARANTINE_PURGE_FAILED")));validateProof(journal.purge().info());owners(journal.plan(),journal.purge());
            }
            else require(!"QUARANTINE_PURGE_FAILED".equals(journal.errorCode()));
            return new Stored(journal,digest);
        }catch(IOException|RuntimeException e){throw new ApiException("QUARANTINE_UNREADABLE","暂存记录无法核验，请保留文件并检查暂存记录。",409);}
    }
    private Stored write(Journal journal)throws IOException {
        Path batch=path("quarantine/"+journal.plan().id());ordinaryDirectory(batch);JsonNode tree=mapper.valueToTree(journal);if(journal.purge()==null)((com.fasterxml.jackson.databind.node.ObjectNode)tree).remove("purge");String digest=hash(mapper.writeValueAsBytes(tree));
        var envelope=mapper.createObjectNode();envelope.put("version",journal.purge()==null?1:2);envelope.put("digest",digest);envelope.set("journal",tree);byte[] bytes=mapper.writeValueAsBytes(envelope);require(bytes.length<=MAX_JOURNAL);
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
    private Journal transition(Journal journal,String state,String backup,String restore,String error){return new Journal(journal.plan(),state,clock.instant(),backup,restore,error,journal.purge());}
    private Receipt receipt(Stored stored){
        var journal=stored.journal();var items=journal.plan().items().stream().map(i->new Item(i.kind(),i.id(),i.bytes())).toList();
        String token=journal.purge()!=null?journal.purge().token():journal.restoreDigest()==null?stored.digest():journal.restoreDigest();
        return new Receipt(journal.plan().id(),journal.state(),journal.plan().createdAt(),journal.updatedAt(),items,items.stream().mapToLong(Item::bytes).sum(),journal.backupId(),token,journal.errorCode(),journal.purge()==null?null:journal.purge().info());
    }
    private void rollback(Journal journal,List<Unit> units){
        Path payload=root.resolve("quarantine/"+journal.plan().id()+"/payload");
        for(var unit:units){try{
            Path held=payload.resolve(unit.path()),original=root.resolve(unit.path());checked(held);checked(original);
            if(present(held)&&!present(original)){verify(payload,unit,false);directory(original.getParent());safeMove(held,original);}
        }catch(ApiException|IOException e){/* Leave genuine remaining payload and journal for explicit recovery. */}}
    }
    private void validateProof(CleanupInfo proof){require(proof!=null&&uuid(proof.exportId())&&sha(proof.sha256())&&proof.bytes()>0&&proof.bytes()<=MAX_BYTES+1024*1024);}
    private List<PdfMetadata> capture(QuarantineFiles.Plan plan)throws IOException {
        var metadata=new ArrayList<PdfMetadata>();Path base=payload(plan.id());
        for(var target:plan.items())if(target.kind().equals("pdf")){
            var entry=target.files().stream().filter(e->e.path().endsWith(".json")).findFirst().orElseThrow();require(entry.bytes()<=4096);
            verify(base,new Unit(entry.path(),List.of(entry),false),false);byte[] bytes;try(var in=open(base.resolve(entry.path()))){bytes=in.readNBytes(4097);}
            require(bytes.length==entry.bytes()&&hash(bytes).equals(entry.sha256()));metadata.add(new PdfMetadata(target.id(),Base64.getEncoder().encodeToString(bytes)));
        }
        owners(plan,metadata);return List.copyOf(metadata);
    }
    /** Validate sealed exact bytes against the Plan, including metadata already unlinked. */
    private List<ExportService.Export> owners(QuarantineFiles.Plan plan,Purge purge)throws IOException {
        require(purge!=null);return owners(plan,purge.pdfMetadata());
    }
    private List<ExportService.Export> owners(QuarantineFiles.Plan plan,List<PdfMetadata> metadata)throws IOException {
        require(metadata!=null);var planned=new HashMap<String,QuarantineFiles.Target>();
        for(var target:plan.items())if(target.kind().equals("pdf"))planned.put(target.id(),target);
        var seen=new HashSet<String>();var result=new ArrayList<ExportService.Export>();
        for(var sealed:metadata){
            require(sealed!=null&&planned.containsKey(sealed.id())&&seen.add(sealed.id())&&sealed.base64()!=null&&sealed.base64().length()<=5464);
            byte[] bytes=Base64.getDecoder().decode(sealed.base64());require(bytes.length>0&&bytes.length<=4096&&Base64.getEncoder().encodeToString(bytes).equals(sealed.base64()));
            var target=planned.get(sealed.id());var json=target.files().stream().filter(e->e.path().endsWith(".json")).findFirst().orElseThrow();var pdf=target.files().stream().filter(e->e.path().endsWith(".pdf")).findFirst().orElseThrow();
            require(bytes.length==json.bytes()&&hash(bytes).equals(json.sha256()));var node=mapper.readTree(bytes);
            require(node!=null&&node.isObject()&&fields(node).containsAll(Set.of("id","snapshotId","digest","sha256","createdAt"))
                &&Set.of("id","snapshotId","digest","sha256","createdAt","resumeId","versionId","revision").containsAll(fields(node)));
            for(String field:List.of("id","snapshotId","digest","sha256","createdAt"))require(node.get(field).isTextual());
            for(String field:List.of("resumeId","versionId"))require(!node.hasNonNull(field)||node.get(field).isTextual());
            require(!node.hasNonNull("revision")||node.get("revision").isIntegralNumber()&&node.get("revision").canConvertToLong());
            var owner=mapper.treeToValue(node,ExportService.Export.class);
            require(owner!=null&&sealed.id().equals(owner.id())&&uuid(owner.snapshotId())&&sha(owner.digest())&&sha(owner.sha256())&&owner.createdAt()!=null&&pdf.bytes()>=5&&pdf.sha256().equals(owner.sha256()));
            boolean bound=owner.resumeId()!=null||owner.versionId()!=null||owner.revision()!=null;
            require(!bound||uuid(owner.resumeId())&&uuid(owner.versionId())&&owner.revision()!=null&&owner.revision()>=1);result.add(owner);
        }
        require(seen.equals(planned.keySet()));return List.copyOf(result);
    }
    private Path discard(String id)throws IOException {require(uuid(id));return path("quarantine/"+id+"/discard");}
    /** Closed-world structure plus hashes for either the complete payload or remaining planned subset. */
    private Path checkRemaining(QuarantineFiles.Plan plan,boolean complete)throws IOException {
        Path payload=payload(plan.id()),discard=discard(plan.id());boolean p=present(payload),d=present(discard);
        if(p&&d||complete&&(!p||d))throw conflict();for(var unit:units(plan))ensureAbsent(root.resolve(unit.path()));
        Path base=d?discard:payload;if(!p&&!d)return base;ordinaryDirectory(base);inspectPayload(base,base,allowed(plan),0);
        for(var target:plan.items())for(var entry:target.files()){
            Path file=base.resolve(entry.path());checked(file);if(present(file))verify(base,new Unit(entry.path(),List.of(entry),false),false);else if(complete)throw conflict();
        }return base;
    }
    private Set<String> allowed(QuarantineFiles.Plan plan){
        var result=new HashSet<String>();for(var target:plan.items())for(var entry:target.files()){
            result.add(entry.path());int index=entry.path().lastIndexOf('/');while(index>0){result.add(entry.path().substring(0,index));index=entry.path().lastIndexOf('/',index-1);}
        }return result;
    }
    private void deleteEmptyDirectories(QuarantineFiles.Plan plan)throws IOException {
        Path base=discard(plan.id());var directories=new HashSet<String>();
        for(var target:plan.items())for(var entry:target.files()){
            int index=entry.path().lastIndexOf('/');while(index>0){directories.add(entry.path().substring(0,index));index=entry.path().lastIndexOf('/',index-1);}
        }
        var sorted=directories.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for(String relative:sorted)deleteEmptyDirectory(base.resolve(relative),plan);deleteEmptyDirectory(base,plan);
    }
    private void deleteEmptyDirectory(Path directory,QuarantineFiles.Plan plan)throws IOException {
        checkRemaining(plan,false);checked(directory);if(!present(directory))return;ordinaryDirectory(directory);
        try(var stream=Files.newDirectoryStream(directory)){if(stream.iterator().hasNext())throw conflict();}
        delete.delete(directory);if(present(directory))throw conflict();
    }
    private void purgingInventory(Journal journal,List<HeldItem> held)throws IOException {
        var plan=journal.plan();var bases=List.of(payload(plan.id()),discard(plan.id()));
        for(var target:plan.items()){
            long bytes=0;boolean exists=false;
            for(Path base:bases)try{
                checked(base);if(!present(base))continue;ordinaryDirectory(base);
                if(target.kind().equals("image")){
                    Path image=base.resolve("attachments/"+target.id());checked(image);if(!present(image))continue;exists=true;ordinaryDirectory(image);
                    try(var stream=Files.newDirectoryStream(image)){int count=0;for(Path child:stream){if(++count>20000)break;try{bytes=Math.addExact(bytes,regular(child).size());}catch(ApiException|IOException|ArithmeticException e){/* unreadable stays incomplete */}}}
                }else for(var entry:target.files()){Path file=base.resolve(entry.path());checked(file);if(present(file)){exists=true;bytes=Math.addExact(bytes,regular(file).size());}}
            }catch(ApiException|IOException|ArithmeticException e){/* Never claim complete during purge. */}
            if(exists)held.add(new HeldItem(target.kind(),target.id(),bytes,plan.createdAt(),false));
        }
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
    private void sameStore(Path source,Path destination)throws IOException {fs.sameStore(source,destination);}
    private void ensureAbsent(Path file)throws IOException {checked(file);if(present(file))throw conflict();}
    private Path payload(String id)throws IOException {require(uuid(id));return path("quarantine/"+id+"/payload");}
    private Path path(String relative)throws IOException {return fs.path(relative);}
    private void checked(Path value)throws IOException {fs.checked(value);}
    private void directory(Path value)throws IOException {fs.directory(value);}
    private void ordinaryDirectory(Path value)throws IOException {fs.ordinaryDirectory(value);}
    private BasicFileAttributes regular(Path value)throws IOException {return fs.regular(value);}
    private static boolean present(Path value){return QuarantineFs.present(value);}
    private static InputStream open(Path value)throws IOException {return QuarantineFs.open(value);}
    private static Set<String> fields(JsonNode node){var names=new HashSet<String>();node.fieldNames().forEachRemaining(names::add);return names;}
    private static Set<String> keys(QuarantineFiles.Plan plan){var result=new HashSet<String>();for(var target:plan.items())result.add(target.kind()+"/"+target.id());return result;}
    private static void validate(QuarantineFiles.Plan plan){QuarantineFiles.validate(plan);}
    private static boolean uuid(String value){return QuarantineFiles.uuid(value);}
    private static boolean sha(String value){return QuarantineFiles.sha(value);}
    private static void require(boolean valid){if(!valid)throw new ApiException("QUARANTINE_INVALID","暂存请求或文件清单无效。",422);}
    private static ApiException conflict(){return new ApiException("QUARANTINE_CONFLICT","文件或暂存状态已变化，请刷新后重试；现有文件已保留。",409);}
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static String hash(byte[] value){return HexFormat.of().formatHex(digest().digest(value));}
}
