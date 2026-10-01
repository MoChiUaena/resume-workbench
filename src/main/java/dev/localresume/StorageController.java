package dev.localresume;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.context.request.async.WebAsyncUtils;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final StoragePreviewService storage;
    private final QuarantineService quarantine;
    public StorageController(StoragePreviewService storage,QuarantineService quarantine){this.storage=storage;this.quarantine=quarantine;}
    @GetMapping("/preview") public Object preview(){return storage.preview();}
    @GetMapping("/quarantine") public Object history(@RequestParam(defaultValue="0") int page){return quarantine.history(page);}
    @PostMapping("/quarantine") public Object quarantine(@RequestBody QuarantineService.Request request){return quarantine.quarantine(request);}
    @PostMapping("/quarantine/{id}/restore") public Object restore(@PathVariable String id,@RequestBody QuarantineService.RestoreRequest request){return quarantine.restore(id,request);}
    @PostMapping("/quarantine/{id}/file-backups") public Object fileBackup(@PathVariable String id,@RequestBody QuarantineService.FileBackupRequest request){return quarantine.fileBackup(id,request);}
    @GetMapping("/quarantine/{id}/file-backups/{requestId}") public Object fileBackupStatus(@PathVariable String id,@PathVariable String requestId){return quarantine.fileBackupStatus(id,requestId);}
    @GetMapping("/quarantine/{id}/file-backups/{requestId}/download") public ResponseEntity<StreamingResponseBody> download(@PathVariable String id,@PathVariable String requestId,HttpServletRequest request){
        var async=WebAsyncUtils.getAsyncManager(request).getAsyncWebRequest();var cancelled=new AtomicBoolean();
        // Bound this large-file route to the authorization window, independent of the container's shorter default.
        async.setTimeout(600000L);async.addTimeoutHandler(()->cancelled.set(true));async.addErrorHandler(error->cancelled.set(true));
        var result=quarantine.download(id,requestId,cancelled::get);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip")).contentLength(result.bytes())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.filename()).build().toString()).body(result.body());
    }
    @PostMapping("/quarantine/{id}/purge") public Object purge(@PathVariable String id,@RequestBody QuarantineService.PurgeRequest request){return quarantine.purge(id,request);}
}
