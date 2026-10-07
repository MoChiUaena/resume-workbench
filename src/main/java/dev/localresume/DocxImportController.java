package dev.localresume;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports/docx")
public class DocxImportController {
    // DocxImports applies nested Bean Validation so schema size violations retain
    // the import-specific 413 response rather than the generic request error.
    public record Create(@NotNull UUID mutationId,@NotBlank @Size(max=120) String title,@NotNull ResumeDocument document) {}
    private final DocxImports imports;
    public DocxImportController(DocxImports imports){this.imports=imports;}
    @PostMapping(value="/preview",consumes="multipart/form-data")
    public DocxImports.Preview preview(@RequestParam(value="file",required=false) MultipartFile file){return imports.preview(file);}
    @PostMapping("/create")
    public DocxImports.Created create(@Valid @RequestBody Create input){return imports.create(input);}
}
