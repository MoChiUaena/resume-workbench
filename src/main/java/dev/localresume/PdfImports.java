package dev.localresume;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class PdfImports {
    public record Preview(String format,String fileName,String title,ResumeDocument document,String sourceText,List<DocxReader.Warning> warnings,PdfReader.Statistics statistics,List<PdfReader.ImageCandidate> images) {}
    private final DocumentImportReceipts receipts;
    private final ImageService images;
    private final AttachmentStorage storage;
    @Autowired public PdfImports(DocumentImportReceipts receipts,ImageService images,AttachmentStorage storage){this.receipts=receipts;this.images=images;this.storage=storage;}
    // Pure preview tests do not need attachment persistence.
    PdfImports(DocumentImportReceipts receipts){this(receipts,null,null);}
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
        return new Preview("pdf",fileName,title,mapping.document(),source.sourceText(),mapping.warnings(),source.statistics(),source.images());
    }
    public DocxImports.Created create(PdfImportController.Create input) {
        if(input==null)throw PdfReader.invalid();
        if(input.photo()==null&&input.logo()==null)
            return receipts.create(input.mutationId(),input.title(),input.document(),DocumentImportReceipts.Format.PDF);
        byte[] photo=decode(input.photo()),logo=decode(input.logo());
        String fingerprint="photo="+(photo==null?"-":ImageService.sha(photo))+";logo="+(logo==null?"-":ImageService.sha(logo));
        return receipts.createPdfWithImages(input.mutationId(),input.title(),input.document(),fingerprint,
            document->attach(document,photo,logo));
    }
    private static byte[] decode(PdfImportController.SelectedImage selected) {
        if(selected==null)return null;
        if(selected.base64()==null||selected.mimeType()==null||!List.of("image/png","image/jpeg").contains(selected.mimeType()))throw imageInvalid();
        if(selected.base64().length()>699_052)throw imageTooLarge();
        byte[] bytes;
        try{bytes=Base64.getDecoder().decode(selected.base64());}
        catch(IllegalArgumentException e){throw imageInvalid();}
        if(bytes.length>PdfImageCandidates.MAX_BYTES)throw imageTooLarge();
        boolean png=bytes.length>=8&&bytes[0]==(byte)137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71&&bytes[4]==13&&bytes[5]==10&&bytes[6]==26&&bytes[7]==10;
        boolean jpeg=bytes.length>=3&&bytes[0]==(byte)255&&bytes[1]==(byte)216&&bytes[2]==(byte)255;
        if(!(png&&selected.mimeType().equals("image/png")||jpeg&&selected.mimeType().equals("image/jpeg")))throw imageInvalid();
        return bytes;
    }
    private ResumeDocument attach(ResumeDocument document,byte[] photo,byte[] logo) {
        if(images==null||storage==null||!TransactionSynchronizationManager.isSynchronizationActive())throw imageInvalid();
        var created=new ArrayList<String>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status==STATUS_COMMITTED)return;
                for(String id:created)try{storage.delete(id);}catch(IOException e){LoggerFactory.getLogger(PdfImports.class).warn("Rolled-back PDF image could not be removed");}
            }
        });
        String photoId=photo==null?null:importImage(photo,created),logoId=logo==null?null:importImage(logo,created);
        var layout=document.layout();
        var updated=new ResumeDocument.Layout(layout.template(),layout.font(),layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),layout.swapImages(),
            withId(layout.photo(),photoId),withId(layout.logo(),logoId),layout.presentation());
        return new ResumeDocument(document.schemaVersion(),document.content(),updated);
    }
    private String importImage(byte[] bytes,List<String> created) {
        var asset=images.importImage(bytes);created.add(asset.id());return asset.id();
    }
    private static ResumeDraft.ImageSlot withId(ResumeDraft.ImageSlot slot,String id) {
        return new ResumeDraft.ImageSlot(id,slot.visible(),slot.widthMm(),slot.heightMm(),slot.fit(),slot.quarterTurns(),slot.zoom(),slot.positionX(),slot.positionY());
    }
    private static ApiException imageInvalid(){return new ApiException("PDF_IMAGE_INVALID","所选 PDF 图片无效，请重新预览并选择。",422);}
    private static ApiException imageTooLarge(){return new ApiException("PDF_IMAGE_TOO_LARGE","所选 PDF 图片超过导入限制，请换一张图片。",413);}
}
