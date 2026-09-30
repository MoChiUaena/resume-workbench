package dev.localresume;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final StoragePreviewService storage;
    public StorageController(StoragePreviewService storage){this.storage=storage;}
    @GetMapping("/preview") public Object preview(){return storage.preview();}
}
