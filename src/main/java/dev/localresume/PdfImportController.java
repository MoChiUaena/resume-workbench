package dev.localresume;

import java.util.UUID;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports/pdf")
public class PdfImportController {
    public record Create(@NotNull UUID mutationId,@NotBlank @Size(max=120) String title,@NotNull ResumeDocument document) {}
    private final PdfImports imports;
    public PdfImportController(PdfImports imports){this.imports=imports;}
    @PostMapping(value="/preview",consumes="multipart/form-data")
    public PdfImports.Preview preview(@RequestParam(value="file",required=false) MultipartFile file){return imports.preview(file);}
    @PostMapping("/create")
    public DocxImports.Created create(@RequestBody Create input){return imports.create(input);}
}
