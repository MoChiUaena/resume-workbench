package dev.localresume;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PdfImports {
    public record Preview(String format,String fileName,String title,ResumeDocument document,String sourceText,List<DocxReader.Warning> warnings,PdfReader.Statistics statistics) {}
    private final DocumentImportReceipts receipts;
    public PdfImports(DocumentImportReceipts receipts){this.receipts=receipts;}
    public Preview preview(MultipartFile file) {
        if(file==null)throw new ApiException("PDF_INVALID","请选择一份 .pdf 文件后导入。",422);
        String supplied=file.getOriginalFilename();String basename=supplied==null?"":supplied.replace('\\','/');basename=basename.substring(basename.lastIndexOf('/')+1);
        if(!basename.toLowerCase(Locale.ROOT).endsWith(".pdf"))throw PdfReader.invalid();
        if(file.getSize()>PdfReader.MAX_BYTES)throw PdfReader.tooLarge();
        String fileName=DocxImports.bounded(basename.replaceAll("[\\p{Cntrl}<>:\"|?*]","_").strip(),120);
        String title=DocxImports.bounded(basename.substring(0,basename.length()-4).replaceAll("[\\p{Cntrl}<>:\"|?*]","_").strip(),120);
        if(title.isBlank())title="导入的 PDF 简历";
        byte[] bytes;
        try(var in=file.getInputStream()){bytes=in.readNBytes(PdfReader.MAX_BYTES+1);}
        catch(IOException e){throw PdfReader.invalid();}
        var source=new PdfReader().read(bytes);
        var mapping=new DocxMapping().map(source.blocks(),source.warnings(),"PDF");
        receipts.validateDocument(mapping.document(),DocumentImportReceipts.Format.PDF);
        return new Preview("pdf",fileName,title,mapping.document(),source.sourceText(),mapping.warnings(),source.statistics());
    }
    public DocxImports.Created create(PdfImportController.Create input) {
        if(input==null)throw PdfReader.invalid();
        return receipts.create(input.mutationId(),input.title(),input.document(),DocumentImportReceipts.Format.PDF);
    }
}
