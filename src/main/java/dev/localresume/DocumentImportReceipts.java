package dev.localresume;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Instance-local receipts shared by reviewed DOCX and PDF creation. */
@Service
public class DocumentImportReceipts {
    public enum Format { DOCX, PDF }
    private final JdbcTemplate jdbc;
    private final ResumeService resumes;
    private final ObjectMapper mapper;
    private final Validator validator;
    public DocumentImportReceipts(JdbcTemplate jdbc,ResumeService resumes,ObjectMapper mapper,Validator validator) {
        this.jdbc=jdbc;this.resumes=resumes;this.mapper=mapper;this.validator=validator;
    }
    String validateDocument(ResumeDocument document,Format format) {
        if(document==null)throw invalid(format);
        var violations=validator.validate(document);
        if(violations.stream().anyMatch(v->v.getConstraintDescriptor().getAnnotation() instanceof Size))throw contentTooLarge(format);
        if(!violations.isEmpty())throw invalid(format);
        if(document.layout().photo().id()!=null||document.layout().logo().id()!=null)throw invalid(format);
        var ids=new HashSet<String>();
        for(var section:document.content().sections()) {
            if(!ids.add(section.id()))throw invalid(format);
            for(var entry:section.entries())if(!ids.add(entry.id()))throw invalid(format);
        }
        try {String json=mapper.writeValueAsString(document);if(json.length()>200000)throw contentTooLarge(format);return json;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw invalid(format);}
    }
    @Transactional
    public DocxImports.Created create(UUID mutationId,String title,ResumeDocument document,Format format) {
        if(mutationId==null||title==null||title.isBlank())throw invalid(format);
        if(title.length()>120)throw contentTooLarge(format);
        String json=validateDocument(document,format);String hash;
        try {
            String request=mapper.writeValueAsString(List.of(title,mapper.readTree(json)));
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));
        }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Import fingerprint unavailable");}
        int reserved=jdbc.update("INSERT INTO document_imports(mutation_id,request_sha256) VALUES (?,?) ON CONFLICT (mutation_id) DO NOTHING",mutationId,hash);
        var receipts=jdbc.query("SELECT request_sha256,resume_id FROM document_imports WHERE mutation_id=? FOR UPDATE",(rs,n)->new Receipt(rs.getString(1),rs.getObject(2,UUID.class)),mutationId);
        if(receipts.size()!=1)throw new IllegalStateException("Import receipt unavailable");
        var receipt=receipts.getFirst();
        if(!receipt.hash().equals(hash))throw new ApiException(format+"_IMPORT_CONFLICT","这次导入确认已用于另一份内容，请重新确认导入。",409);
        if(reserved==1) {
            var resume=resumes.create(title,document);
            jdbc.update("UPDATE document_imports SET resume_id=? WHERE mutation_id=?",resume.id(),mutationId);
            return new DocxImports.Created(mutationId,resume);
        }
        if(receipt.resumeId()==null)throw deleted(format);
        try{return new DocxImports.Created(mutationId,resumes.get(receipt.resumeId()));}
        catch(ApiException e){if(e.code.equals("RESUME_NOT_FOUND"))throw deleted(format);throw e;}
    }
    private record Receipt(String hash,UUID resumeId) {}
    private static ApiException invalid(Format format){return format==Format.PDF?PdfReader.invalid():DocxReader.invalid();}
    private static ApiException contentTooLarge(Format format){return format==Format.PDF?PdfReader.contentTooLarge():DocxReader.contentTooLarge();}
    private static ApiException deleted(Format format){return new ApiException(format+"_IMPORT_DELETED","此前导入创建的简历已被删除，请重新导入以创建新简历。",410);}
}
