package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

@Service
public class StoragePreviewService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceGate gate;
    private final TransactionTemplate tx;
    private final TransactionTemplate referenceTx;
    private final Path data;
    private final QuarantineStore quarantine;
    private final Clock clock;
    private final Map<String,Instant> displayed=new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public StoragePreviewService(JdbcTemplate jdbc,ObjectMapper mapper,WorkspaceGate gate,PlatformTransactionManager transactions,@Value("${resume.data-dir}") String data,QuarantineStore quarantine){
        this(jdbc,mapper,gate,transactions,data,quarantine,Clock.systemUTC());
    }
    StoragePreviewService(JdbcTemplate jdbc,ObjectMapper mapper,WorkspaceGate gate,PlatformTransactionManager transactions,String data,QuarantineStore quarantine,Clock clock){
        this.jdbc=jdbc;this.mapper=mapper;this.gate=gate;this.data=Path.of(data);this.quarantine=quarantine;this.clock=clock;
        this.tx=new TransactionTemplate(transactions);tx.setReadOnly(true);tx.setTimeout(20);tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        referenceTx=new TransactionTemplate(transactions);referenceTx.setReadOnly(true);referenceTx.setTimeout(20);referenceTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);referenceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public StorageInspector.Report preview(){
        try(var lease=gate.exclusive()){
            var report=freshSnapshot().report();
            synchronized(displayed){displayed.remove(report.digest());displayed.put(report.digest(),clock.instant());while(displayed.size()>32)displayed.remove(displayed.keySet().iterator().next());}
            return report;
        }
    }
    /** Caller owns the exclusive lease; fresh evidence never renews displayed authorization. */
    StorageInspector.Snapshot freshSnapshot(){
        try{var refs=tx.execute(status->references());return new StorageInspector(data,mapper,refs,clock).snapshot(quarantine.inventory(),quarantine.purgedInventory());}
        catch(java.io.IOException e){throw new ApiException("STORAGE_SCAN_FAILED","本机文件检查未完成，请检查数据目录的读取权限后重试。",503);}
    }
    /** One independent repeatable-read transaction, even if a caller already has a weaker transaction. */
    StorageInspector.References freshReferences(){
        try{var refs=referenceTx.execute(status->references());if(refs==null)throw new IllegalStateException("Missing reference snapshot");return refs;}
        catch(ApiException e){throw e;}catch(RuntimeException e){throw new ApiException("STORAGE_SCAN_FAILED","引用检查未完成，请保留文件并稍后重试。",503);}
    }
    QuarantineArchive defaultArchive(){return new QuarantineArchive(data.toString(),mapper);}
    void requireDisplayed(String digest){
        synchronized(displayed){var timestamp=displayed.get(digest);var now=clock.instant();
            if(timestamp==null||timestamp.isAfter(now)||!timestamp.plus(Duration.ofMinutes(10)).isAfter(now))
                throw new ApiException("STORAGE_PREVIEW_EXPIRED","空间检查已过期，请重新检查并确认候选文件。",409);
        }
    }
    StorageInspector.References references(){
        var catalogs=new TreeMap<String,StorageInspector.Catalog>();var resumes=new TreeSet<String>();var versions=new TreeMap<String,StorageInspector.Owner>();
        var current=new TreeMap<String,String>();var historical=new TreeMap<String,String>();var evidence=new ArrayList<Object>();boolean[] valid={true};
        // Inventory needs schema/layout references, never resume text. Bound malformed JSON before loading it.
        String projection="CASE WHEN octet_length((document->'layout')::text)<=16384 THEN jsonb_build_object('schemaVersion',document->'schemaVersion','layout',document->'layout') ELSE '{}'::jsonb END::text";
        var saved=jdbc.query("SELECT id,"+projection+",revision FROM resumes ORDER BY id LIMIT 2001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getLong(3)));
        var history=jdbc.query("SELECT id,resume_id,"+projection+",source_revision FROM resume_versions ORDER BY id LIMIT 10001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3),rs.getLong(4)));
        var assets=jdbc.query("SELECT id,CASE WHEN octet_length(metadata::text)<=4096 THEN metadata::text ELSE '{}' END,created_at FROM attachments ORDER BY id LIMIT 20001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getTimestamp(3).toInstant()));
        if(saved.size()>2000||history.size()>10000||assets.size()>20000)throw new ApiException("STORAGE_SCAN_LIMIT","工作区记录超出本次检查范围，请保留数据并检查工作区规模。",422);
        for(var row:saved){String id=(String)row.get(0);resumes.add(id);collect(id,(String)row.get(1),current,valid);}
        for(var row:history){String id=(String)row.get(0);versions.put(id,new StorageInspector.Owner((String)row.get(1),(Long)row.get(3)));collect(id,(String)row.get(2),historical,valid);}
        for(var row:assets)catalogs.put((String)row.get(0),new StorageInspector.Catalog((String)row.get(1),(Instant)row.get(2)));
        var currentRows=links("resume_assets","resume_id");var versionRows=links("version_assets","version_id");
        valid[0]&=current.equals(currentRows)&&historical.equals(versionRows);
        var currentIds=new TreeSet<>(current.values());currentIds.addAll(currentRows.values());var historicalIds=new TreeSet<>(historical.values());historicalIds.addAll(versionRows.values());
        valid[0]&=catalogs.keySet().containsAll(currentIds)&&catalogs.keySet().containsAll(historicalIds);
        evidence.addAll(List.of(saved,history,assets,currentRows,versionRows));
        try{return new StorageInspector.References(catalogs,currentIds,historicalIds,resumes,versions,valid[0],ImageService.sha(mapper.writeValueAsBytes(evidence)));}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private Map<String,String> links(String table,String owner){
        var result=new TreeMap<String,String>();var rows=jdbc.query("SELECT "+owner+",slot,asset_id FROM "+table+" ORDER BY "+owner+",slot LIMIT 24001",(rs,n)->List.of(rs.getString(1),rs.getString(2),rs.getString(3)));
        if(rows.size()>24000)throw new ApiException("STORAGE_SCAN_LIMIT","工作区图片引用超出本次检查范围，请保留数据并检查工作区规模。",422);
        for(var row:rows)result.put(row.get(0)+":"+row.get(1),row.get(2));return result;
    }
    private void collect(String owner,String json,Map<String,String> result,boolean[] valid){
        try{var doc=mapper.readValue(json,ResumeDocument.class);var layout=doc.layout();if(!ResumeDocument.supportsSchema(doc.schemaVersion())||layout==null)throw new IllegalArgumentException();
            for(var pair:List.of(Map.entry("photo",layout.photo()),Map.entry("logo",layout.logo()))){String id=pair.getValue().id();if(id!=null){if(!StorageInspector.uuid(id))valid[0]=false;result.put(owner+":"+pair.getKey(),id);}}
        }catch(Exception e){valid[0]=false;}
    }
}
