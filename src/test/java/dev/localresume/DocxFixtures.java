package dev.localresume;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

final class DocxFixtures {
    static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static String paragraph(String text) { return "<w:p><w:r><w:t>"+text+"</w:t></w:r></w:p>"; }
    static Map<String,byte[]> parts(String body) {
        var parts=new LinkedHashMap<String,byte[]>();
        parts.put("[Content_Types].xml",utf8("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"));
        parts.put("_rels/.rels",utf8("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"));
        parts.put("word/document.xml",utf8("<w:document xmlns:w=\""+W+"\"><w:body>"+body+"</w:body></w:document>"));
        return parts;
    }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static byte[] zip(Map<String,byte[]> parts) {
        try {var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out)){for(var part:parts.entrySet()){zip.putNextEntry(new ZipEntry(part.getKey()));zip.write(part.getValue());zip.closeEntry();}}return out.toByteArray();}
        catch(IOException e){throw new UncheckedIOException(e);}
    }
    static byte[] document(String body) { return zip(parts(body)); }
}
