package dev.localresume;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocxImports {
    public record Preview(String format,String fileName,String title,ResumeDocument document,String sourceText,List<DocxReader.Warning> warnings,DocxReader.Statistics statistics) {}
    public record Created(UUID mutationId,ResumeService.Resume resume) {}
    private final DocumentImportReceipts receipts;
    @Autowired public DocxImports(DocumentImportReceipts receipts){this.receipts=receipts;}
    // Pure controller tests use this constructor without a database.
    public DocxImports(JdbcTemplate jdbc,ResumeService resumes,ObjectMapper mapper,Validator validator) {
        this(new DocumentImportReceipts(jdbc,resumes,mapper,validator));
    }
    public Preview preview(MultipartFile file) {
        if(file==null)throw new ApiException("DOCX_INVALID","请选择一份 .docx Word 文件后导入。",422);
        String supplied=file.getOriginalFilename();String basename=supplied==null?"":supplied.replace('\\','/');basename=basename.substring(basename.lastIndexOf('/')+1);
        if(!basename.toLowerCase(Locale.ROOT).endsWith(".docx"))throw DocxReader.unsupported();
        if(file.getSize()>DocxReader.MAX_COMPRESSED)throw DocxReader.tooLarge();
        String fileName=bounded(basename.replaceAll("[\\p{Cntrl}<>:\"|?*]","_").strip(),120);
        String title=bounded(basename.substring(0,basename.length()-5).replaceAll("[\\p{Cntrl}<>:\"|?*]","_").strip(),120);
        if(title.isBlank())title="导入的 Word 简历";
        byte[] bytes;
        try(var in=file.getInputStream()){bytes=in.readNBytes(DocxReader.MAX_COMPRESSED+1);}
        catch(IOException e){throw DocxReader.invalid();}
        var source=new DocxReader().read(bytes);var mapping=new DocxMapping().map(source);
        receipts.validateDocument(mapping.document(),DocumentImportReceipts.Format.DOCX);
        return new Preview("docx",fileName,title,mapping.document(),source.sourceText(),mapping.warnings(),source.statistics());
    }
    static String bounded(String value,int max) {
        int end=Math.min(value.length(),max);if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
    public Created create(DocxImportController.Create input) {
        if(input==null)throw DocxReader.invalid();
        return receipts.create(input.mutationId(),input.title(),input.document(),DocumentImportReceipts.Format.DOCX);
    }
}
