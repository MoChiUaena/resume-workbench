package dev.localresume;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Bounded, local-only extraction of selectable PDF text. */
public class PdfReader {
    public record Statistics(int paragraphs,int pages,int images) {}
    public record Result(List<DocxReader.Block> blocks,String sourceText,List<DocxReader.Warning> warnings,Statistics statistics) {}
    public static final int MAX_BYTES=5*1024*1024;
    private static final int MAX_PAGES=20,MAX_TEXT=40000,MAX_BLOCKS=600;

    public Result read(byte[] bytes) {
        if(bytes==null||bytes.length>MAX_BYTES)throw tooLarge();
        if(bytes.length<5||bytes[0]!='%'||bytes[1]!='P'||bytes[2]!='D'||bytes[3]!='F'||bytes[4]!='-')throw invalid();
        try(PDDocument document=Loader.loadPDF(bytes)) {
            if(document.isEncrypted())throw encrypted();
            int pages=document.getNumberOfPages();
            if(pages<1)throw invalid();
            if(pages>MAX_PAGES)throw tooLarge();
            var all=new StringBuilder();var blocks=new ArrayList<DocxReader.Block>();int images=0;
            var stripper=new LimitedStripper();
            for(int number=1;number<=pages;number++) {
                PDPage page=document.getPage(number-1);
                if(page.getResources()!=null)for(var name:page.getResources().getXObjectNames())
                    if(page.getResources().isImageXObject(name))images++;
                stripper.setStartPage(number);stripper.setEndPage(number);
                if(number>1)append(all,"\n");
                var writer=new LimitedWriter(MAX_TEXT-all.length());stripper.writeText(document,writer);
                String pageText=writer.toString().strip();
                if(!pageText.isEmpty()) {
                    append(all,pageText);
                    for(String line:pageText.split("\\R"))if(!line.isBlank()) {
                        if(blocks.size()>=MAX_BLOCKS)throw contentTooLarge();
                        blocks.add(new DocxReader.Block(line.strip(),false,false));
                    }
                }
            }
            if(blocks.isEmpty())throw new ApiException("PDF_NO_TEXT","PDF 没有可选择的文字；请使用含文字的 PDF。扫描件暂不支持。",422);
            var warnings=new ArrayList<DocxReader.Warning>();
            warnings.add(new DocxReader.Warning("PDF_READING_ORDER","PDF 的分栏和布局可能影响文字顺序，请对照原文件核对后创建。"));
            if(images>0)warnings.add(new DocxReader.Warning("PDF_IMAGES_SKIPPED","PDF 中的图片未导入，可在编辑器中重新上传。"));
            return new Result(List.copyOf(blocks),all.toString(),List.copyOf(warnings),new Statistics(blocks.size(),pages,images));
        } catch(ApiException e){throw e;}
        catch(InvalidPasswordException e){throw encrypted();}
        catch(IOException|RuntimeException e){throw invalid();}
    }
    private static void append(StringBuilder text,String part){if(text.length()+part.length()>MAX_TEXT)throw contentTooLarge();text.append(part);}
    private static final class LimitedStripper extends PDFTextStripper {
        private int glyphUnits;
        LimitedStripper() throws IOException {setSortByPosition(true);setLineSeparator("\n");setPageEnd("");}
        @Override protected void processTextPosition(TextPosition position) {
            glyphUnits+=position.getUnicode().length();
            if(glyphUnits>MAX_TEXT)throw contentTooLarge();
            super.processTextPosition(position);
        }
    }
    private static final class LimitedWriter extends Writer {
        private final int limit;private final StringBuilder value=new StringBuilder();
        LimitedWriter(int limit){this.limit=limit;}
        @Override public void write(char[] chars,int offset,int length){if(value.length()+length>limit)throw contentTooLarge();value.append(chars,offset,length);}
        @Override public void flush(){} @Override public void close(){}
        @Override public String toString(){return value.toString();}
    }
    static ApiException invalid(){return new ApiException("PDF_INVALID","PDF 文件无效或损坏，请重新导出文字 PDF 后导入。",422);}
    static ApiException encrypted(){return new ApiException("PDF_ENCRYPTED","加密或受密码保护的 PDF 暂不支持，请导入未加密的文字 PDF。",422);}
    static ApiException tooLarge(){return new ApiException("PDF_TOO_LARGE","PDF 须不超过 5 MiB 且不超过 20 页，请拆分后重试。",413);}
    static ApiException contentTooLarge(){return new ApiException("PDF_CONTENT_TOO_LARGE","PDF 文字或模块过多，请拆分文档后导入；本次未截断内容。",413);}
}
