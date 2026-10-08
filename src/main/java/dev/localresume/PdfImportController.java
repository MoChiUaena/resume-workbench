package dev.localresume;

import java.util.UUID;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/imports/pdf")
public class PdfImportController {
    public record SelectedImage(String mimeType,String base64) {}
    public record Create(@NotNull UUID mutationId,@NotBlank @Size(max=120) String title,@NotNull ResumeDocument document,
                         SelectedImage photo,SelectedImage logo) {
        public Create(UUID mutationId,String title,ResumeDocument document){this(mutationId,title,document,null,null);}
    }
    private final PdfImports imports;
    public PdfImportController(PdfImports imports){this.imports=imports;}
    @PostMapping(value="/preview",consumes="multipart/form-data")
    public PdfImports.Preview preview(@RequestParam(value="file",required=false) MultipartFile file){return imports.preview(file);}
    @PostMapping("/create")
    public DocxImports.Created create(@RequestBody Create input){return imports.create(input);}
}
