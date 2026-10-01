package dev.localresume;

import org.springframework.web.bind.annotation.*;

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
}
