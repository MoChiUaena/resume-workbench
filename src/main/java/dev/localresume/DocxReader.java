package dev.localresume;

import java.util.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import javax.xml.stream.*;

public class DocxReader {
    public record Block(String text, boolean heading, boolean unclassified) {}
    public record Warning(String code, String message) {}
    public record Statistics(int paragraphs, int tables, int images) {}
    public record Result(List<Block> blocks, String sourceText, List<Warning> warnings, Statistics statistics) {}
    public static final int MAX_COMPRESSED=5*1024*1024;
    private static final int MAX_EXPANDED=32*1024*1024, MAX_XML=4*1024*1024;
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String STRICT_W="http://purl.oclc.org/ooxml/wordprocessingml/main";
    private static final String CT="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String MAIN="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    public Result read(byte[] bytes) {
        if(bytes.length>MAX_COMPRESSED)throw tooLarge();
        if(bytes.length<4||bytes[0]!='P'||bytes[1]!='K'||bytes[2]!=3||bytes[3]!=4)throw unsupported();
        Path temporary=null;
        try {
            temporary=Files.createTempFile("resume-docx-",".zip");Files.write(temporary,bytes);
            var xml=new LinkedHashMap<String,byte[]>();var names=new HashSet<String>();long total=0;int images=0;
            try(var zip=new ZipFile(temporary.toFile())) {
                var entries=zip.entries();
                while(entries.hasMoreElements()) {
                    var entry=entries.nextElement();String name=entry.getName();
                    if(!legalPath(name)||!names.add(name))throw invalid();
                    if(names.size()>512)throw tooLarge();
                    if(name.toLowerCase(Locale.ROOT).contains("vbaproject"))throw unsupported();
                    boolean parsed=name.equals("[Content_Types].xml")||name.equals("_rels/.rels")||name.equals("word/document.xml")||name.matches("word/(header|footer)[^/]*\\.xml");
                    var out=parsed?new ByteArrayOutputStream():null;var crc=new CRC32();long size=0;
                    try(var in=zip.getInputStream(entry)) {
                        var buffer=new byte[8192];int n;
                        while((n=in.read(buffer))!=-1) {
                            total+=n;size+=n;
                            if(total>MAX_EXPANDED||(parsed&&size>MAX_XML))throw tooLarge();
                            crc.update(buffer,0,n);if(parsed)out.write(buffer,0,n);
                        }
                    }
                    if(size!=entry.getSize()||crc.getValue()!=entry.getCrc())throw invalid();
                    if(parsed)xml.put(name,out.toByteArray());
                    if(!entry.isDirectory()&&name.startsWith("word/media/"))images++;
                }
            }
            // ZipFile validates the central directory; stream local entries as well so
            // hidden, duplicate or mismatched local names cannot bypass the package bounds.
            var localNames=new HashSet<String>();long localTotal=0;
            try(var stream=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;var buffer=new byte[8192];
                while((entry=stream.getNextEntry())!=null) {
                    if(!legalPath(entry.getName())||!localNames.add(entry.getName())||!names.contains(entry.getName()))throw invalid();
                    if(localNames.size()>512)throw tooLarge();
                    int n;while((n=stream.read(buffer))!=-1){localTotal+=n;if(localTotal>MAX_EXPANDED)throw tooLarge();}
                    stream.closeEntry(); // ZipInputStream verifies local CRC/data descriptors.
                }
            }
            if(!localNames.equals(names)||localTotal!=total)throw invalid();
            if(!xml.keySet().containsAll(List.of("[Content_Types].xml","_rels/.rels","word/document.xml")))throw unsupported();
            validateTypes(xml.get("[Content_Types].xml"));validateRelationships(xml.get("_rels/.rels"));
            var extraction=new Extraction();extraction.parse(xml.get("word/document.xml"),"document",false);
            for(var part:xml.entrySet())if(part.getKey().matches("word/(header|footer)[^/]*\\.xml"))extraction.parse(part.getValue(),part.getKey().contains("/header")?"hdr":"ftr",true);
            images=Math.max(images,extraction.imageReferences);
            if(extraction.blocks.isEmpty())throw new ApiException("DOCX_EMPTY","Word 文档没有可读取的文字，请检查内容或另存为 .docx。",422);
            var warnings=new ArrayList<Warning>();
            if(images>0)warnings.add(new Warning("DOCX_IMAGES_SKIPPED","文档图片未导入，可在编辑器中重新上传。"));
            if(extraction.objects)warnings.add(new Warning("DOCX_OBJECTS_SKIPPED","嵌入对象未导入，仅保留支持的文字。"));
            return new Result(List.copyOf(extraction.blocks),String.join("\n",extraction.blocks.stream().map(Block::text).toList()),List.copyOf(warnings),new Statistics(extraction.blocks.size(),extraction.tables,images));
        } catch(ApiException e){throw e;}
        catch(ZipException e){
            if(e.getMessage()!=null&&e.getMessage().toLowerCase(Locale.ROOT).contains("encrypt"))throw unsupported();
            throw invalid();
        }
        catch(IOException|XMLStreamException|IllegalArgumentException e){throw invalid();}
        finally {if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException e){throw invalid();}}
    }
    private static boolean legalPath(String name) {
        if(name.isEmpty()||name.startsWith("/")||name.contains("\\")||name.contains(":")||name.indexOf('\0')>=0)return false;
        String relative=name.endsWith("/")?name.substring(0,name.length()-1):name;
        for(String segment:relative.split("/",-1))if(segment.isEmpty()||segment.equals(".")||segment.equals(".."))return false;
        return true;
    }
    private static XMLStreamReader reader(byte[] bytes)throws XMLStreamException {
        var factory=XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD,false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES,false);
        factory.setXMLResolver((publicId,systemId,base,namespace)->{throw new XMLStreamException("External resolution disabled");});
        return factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
    }
    private static int next(XMLStreamReader r,int depth)throws XMLStreamException {
        int event=r.next();
        if(event==XMLStreamConstants.DTD||event==XMLStreamConstants.ENTITY_REFERENCE)throw invalid();
        if(event==XMLStreamConstants.START_ELEMENT&&++depth>128)throw invalid();
        if(event==XMLStreamConstants.END_ELEMENT)depth--;
        return depth;
    }
    private static void validateTypes(byte[] bytes)throws XMLStreamException {
        var r=reader(bytes);int depth=0;boolean main=false,root=false;
        try {while(r.hasNext()) {depth=next(r,depth);if(!r.isStartElement())continue;
            if(depth==1){if(!r.getLocalName().equals("Types")||!CT.equals(r.getNamespaceURI()))throw unsupported();root=true;}
            if(!CT.equals(r.getNamespaceURI()))continue;
            String type=r.getAttributeValue(null,"ContentType");
            if(type!=null&&(type.toLowerCase(Locale.ROOT).contains("macroenabled")||type.toLowerCase(Locale.ROOT).contains("vbaproject")))throw unsupported();
            if(r.getLocalName().equals("Override")&&"/word/document.xml".equals(r.getAttributeValue(null,"PartName"))) {
                if(!MAIN.equals(type))throw unsupported();main=true;
            }
        }}finally{r.close();}
        if(!root||!main)throw unsupported();
    }
    private static void validateRelationships(byte[] bytes)throws XMLStreamException {
        var r=reader(bytes);int depth=0,count=0;
        try {while(r.hasNext()){depth=next(r,depth);if(!r.isStartElement())continue;
            if(depth==1&&(!r.getLocalName().equals("Relationships")||!REL.equals(r.getNamespaceURI())))throw unsupported();
            if(REL.equals(r.getNamespaceURI())&&r.getLocalName().equals("Relationship")) {
                String type=r.getAttributeValue(null,"Type");
                if("http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument".equals(type)||"http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument".equals(type)) {
                    if(!"word/document.xml".equals(r.getAttributeValue(null,"Target"))||"External".equals(r.getAttributeValue(null,"TargetMode")))throw unsupported();count++;
                }
            }
        }}finally{r.close();}
        if(count!=1)throw unsupported();
    }
    private static boolean word(XMLStreamReader r) {return W.equals(r.getNamespaceURI())||STRICT_W.equals(r.getNamespaceURI());}
    private static final class Extraction {
        final List<Block> blocks=new ArrayList<>();final StringBuilder text=new StringBuilder();int units,tables,imageReferences;boolean heading,objects;
        void flush(boolean unclassified) {
            if(!text.toString().isBlank()) {
                if(!blocks.isEmpty()&&++units>40000)throw contentTooLarge();
                if(blocks.size()>=600)throw contentTooLarge();blocks.add(new Block(text.toString(),heading,unclassified));
            }
            text.setLength(0);
        }
        void append(String value) {units+=value.length();if(units>40000)throw contentTooLarge();text.append(value);}
        void parse(byte[] bytes,String root,boolean unclassified)throws XMLStreamException {
            var r=reader(bytes);int depth=0,skip=0,paragraph=0;boolean visibleText=false;
            try {while(r.hasNext()) {
                depth=next(r,depth);
                if(r.isStartElement()) {
                    if(depth==1&&(!word(r)||!root.equals(r.getLocalName())))throw unsupported();
                    if(skip>0){skip++;continue;}
                    if(("imagedata".equals(r.getLocalName())&&"urn:schemas-microsoft-com:vml".equals(r.getNamespaceURI()))
                        ||("blip".equals(r.getLocalName())&&("http://schemas.openxmlformats.org/drawingml/2006/main".equals(r.getNamespaceURI())||"http://purl.oclc.org/ooxml/drawingml/main".equals(r.getNamespaceURI()))))imageReferences++;
                    if(!word(r))continue;
                    switch(r.getLocalName()) {
                        case "del","moveFrom","instrText" -> skip=1;
                        case "p" -> {flush(unclassified);paragraph++;heading=false;}
                        case "pStyle" -> {String style=r.getAttributeValue(r.getNamespaceURI(),"val");if(style!=null&&style.toLowerCase(Locale.ROOT).matches(".*(heading|title|标题).*"))heading=true;}
                        case "t" -> visibleText=paragraph>0;
                        case "tab" -> {if(paragraph>0)append("\t");}
                        case "br","cr" -> {if(paragraph>0)append("\n");}
                        case "tbl" -> tables++;
                        case "object","altChunk" -> objects=true;
                        default -> {}
                    }
                } else if(r.isEndElement()) {
                    if(skip>0){skip--;continue;}
                    if(!word(r))continue;
                    if(r.getLocalName().equals("t"))visibleText=false;
                    if(r.getLocalName().equals("p")){flush(unclassified);paragraph--;heading=false;}
                } else if(r.isCharacters()&&skip==0&&visibleText)append(r.getText());
            }}finally{r.close();}
            flush(unclassified);
        }
    }
    static ApiException invalid(){return new ApiException("DOCX_INVALID","Word 文件损坏或包含不安全的结构，请重新另存为 .docx 后导入。",422);}
    static ApiException unsupported(){return new ApiException("DOCX_UNSUPPORTED","仅支持不含宏、未加密的 .docx Word 文档。",422);}
    static ApiException tooLarge(){return new ApiException("DOCX_TOO_LARGE","Word 文件须不超过 5 MiB，解压后的内容也须符合导入限制。",413);}
    static ApiException contentTooLarge(){return new ApiException("DOCX_CONTENT_TOO_LARGE","Word 文字或模块过多，请拆分文档后导入；本次未截断内容。",413);}
}
