package dev.localresume;

import java.util.*;
import java.io.IOException;
import java.security.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocxImports {
    public record Preview(String format,String fileName,String title,ResumeDocument document,String sourceText,List<DocxReader.Warning> warnings,DocxReader.Statistics statistics) {}
    public record Created(UUID mutationId,ResumeService.Resume resume) {}
    private final JdbcTemplate jdbc;
    private final ResumeService resumes;
    private final ObjectMapper mapper;
    private final Validator validator;
    public DocxImports(JdbcTemplate jdbc,ResumeService resumes,ObjectMapper mapper,Validator validator) {this.jdbc=jdbc;this.resumes=resumes;this.mapper=mapper;this.validator=validator;}
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
        validateDocument(mapping.document());
        return new Preview("docx",fileName,title,mapping.document(),source.sourceText(),mapping.warnings(),source.statistics());
    }
    private static String bounded(String value,int max) {
        int end=Math.min(value.length(),max);if(end<value.length()&&end>0&&Character.isHighSurrogate(value.charAt(end-1))&&Character.isLowSurrogate(value.charAt(end)))end--;
        return value.substring(0,end);
    }
    private String validateDocument(ResumeDocument document) {
        if(document==null)throw DocxReader.invalid();
        var violations=validator.validate(document);
        if(violations.stream().anyMatch(v->v.getConstraintDescriptor().getAnnotation() instanceof Size))throw DocxReader.contentTooLarge();
        if(!violations.isEmpty())throw DocxReader.invalid();
        if(document.layout().photo().id()!=null||document.layout().logo().id()!=null)throw DocxReader.invalid();
        var ids=new HashSet<String>();
        for(var section:document.content().sections()) {
            if(!ids.add(section.id()))throw DocxReader.invalid();
            for(var entry:section.entries())if(!ids.add(entry.id()))throw DocxReader.invalid();
        }
        try {String json=mapper.writeValueAsString(document);if(json.length()>200000)throw DocxReader.contentTooLarge();return json;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw DocxReader.invalid();}
    }
    @Transactional
    public Created create(DocxImportController.Create input) {
        if(input==null||input.mutationId()==null||input.title()==null||input.title().isBlank())throw DocxReader.invalid();
        if(input.title().length()>120)throw DocxReader.contentTooLarge();
        String json=validateDocument(input.document());String hash;
        try {
            // Array encoding preserves boundaries between the exact original title and document.
            String request=mapper.writeValueAsString(List.of(input.title(),mapper.readTree(json)));
            hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.getBytes(StandardCharsets.UTF_8)));
        }catch(IOException|NoSuchAlgorithmException e){throw new IllegalStateException("Import fingerprint unavailable");}
        int reserved=jdbc.update("INSERT INTO docx_imports(mutation_id,request_sha256) VALUES (?,?) ON CONFLICT (mutation_id) DO NOTHING",input.mutationId(),hash);
        var receipts=jdbc.query("SELECT request_sha256,resume_id FROM docx_imports WHERE mutation_id=? FOR UPDATE",(rs,n)->new Receipt(rs.getString(1),rs.getObject(2,UUID.class)),input.mutationId());
        if(receipts.size()!=1)throw new IllegalStateException("Import receipt unavailable");
        var receipt=receipts.getFirst();
        if(!receipt.hash().equals(hash))throw new ApiException("DOCX_IMPORT_CONFLICT","这次导入确认已用于另一份内容，请重新确认导入。",409);
        if(reserved==1) {
            var resume=resumes.create(input.title(),input.document());
            jdbc.update("UPDATE docx_imports SET resume_id=? WHERE mutation_id=?",resume.id(),input.mutationId());
            return new Created(input.mutationId(),resume);
        }
        if(receipt.resumeId()==null)throw deleted();
        try{return new Created(input.mutationId(),resumes.get(receipt.resumeId()));}
        catch(ApiException e){if(e.code.equals("RESUME_NOT_FOUND"))throw deleted();throw e;}
    }
    private record Receipt(String hash,UUID resumeId) {}
    private static ApiException deleted(){return new ApiException("DOCX_IMPORT_DELETED","此前导入创建的简历已被删除，请重新导入以创建新简历。",410);}
}
