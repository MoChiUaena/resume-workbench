package dev.localresume;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/backups")
public class BackupController {
    private final BackupService backups;
    public BackupController(BackupService backups) { this.backups=backups; }
    @PostMapping public Object create() { return backups.create(); }
    @GetMapping("/{id}/download") public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=resume-workbench-backup-"+id+".zip")
            .body(new FileSystemResource(backups.download(id)));
    }
    @PostMapping("/restore") public Object restore(@RequestParam MultipartFile file) throws java.io.IOException {
        if(file.isEmpty())throw new ApiException("BACKUP_INVALID","请选择完整的备份 ZIP 文件。",422);
        if(file.getSize()>backups.maxBytes)throw new ApiException("BACKUP_TOO_LARGE","备份超过允许大小，请拆分工作区。",413);
        try(var input=file.getInputStream()){return backups.restore(input);}
    }
}
