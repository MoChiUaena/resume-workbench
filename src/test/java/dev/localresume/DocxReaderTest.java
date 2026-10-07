package dev.localresume;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DocxReaderTest {
    @Test void readsOnlyTheSelectedTextBoxRepresentation() {
        String mc="http://schemas.openxmlformats.org/markup-compatibility/2006";
        String wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape";
        String alternate="<mc:AlternateContent xmlns:mc=\""+mc+"\" xmlns:wps=\""+wps+"\">"
            +"<mc:Choice Requires=\"wps\"><w:drawing><wps:wsp><wps:txbx><w:txbxContent>"
            +DocxFixtures.paragraph("现代文本框")
            +"</w:txbxContent></wps:txbx></wps:wsp></w:drawing></mc:Choice>"
            +"<mc:Fallback><w:pict><w:txbxContent>"+DocxFixtures.paragraph("旧版备用文本")
            +"</w:txbxContent></w:pict></mc:Fallback></mc:AlternateContent>";
        var result=new DocxReader().read(DocxFixtures.document(DocxFixtures.paragraph("奶龙")+"<w:p><w:r>"+alternate+"</w:r></w:p>"));
        assertThat(result.sourceText()).isEqualTo("奶龙\n现代文本框");
        assertThat(result.blocks()).extracting(DocxReader.Block::text).containsExactly("奶龙","现代文本框");
        String unknown="<mc:AlternateContent xmlns:mc=\""+mc+"\" xmlns:future=\"urn:future-format\">"
            +"<mc:Choice Requires=\"future\">"+DocxFixtures.paragraph("不可识别的格式")+"</mc:Choice>"
            +"<mc:Fallback>"+DocxFixtures.paragraph("可读取的备用文本")+"</mc:Fallback></mc:AlternateContent>";
        var fallback=new DocxReader().read(DocxFixtures.document(unknown));
        assertThat(fallback.sourceText()).isEqualTo("可读取的备用文本");
    }
    @Test void preservesRunsTablesTextboxesAndAcceptedRevisionText() {
        var result=new DocxReader().read(DocxFixtures.document(DocxFixtures.paragraph("奶龙")+
            "<w:p><w:hyperlink><w:r><w:t>Java</w:t></w:r></w:hyperlink><w:del><w:r><w:delText>删除</w:delText></w:r></w:del><w:ins><w:r><w:t>保留</w:t></w:r></w:ins><w:r><w:instrText>秘密指令</w:instrText></w:r></w:p>"+
            "<w:tbl><w:tr><w:tc>"+DocxFixtures.paragraph("表格")+"</w:tc></w:tr></w:tbl><w:p><w:r><w:drawing><w:txbxContent>"+DocxFixtures.paragraph("文本框")+"</w:txbxContent></w:drawing></w:r></w:p>"));
        assertThat(result.sourceText()).isEqualTo("奶龙\nJava保留\n表格\n文本框");
        assertThat(result.statistics().tables()).isEqualTo(1);
    }
    static void rejects(byte[] bytes,String code) {
        assertThatThrownBy(()->new DocxReader().read(bytes)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code).isEqualTo(code));
    }
    @Test void retainsHeadersFootersAndWarnsForImagesAndObjects() {
        var parts=DocxFixtures.parts(DocxFixtures.paragraph("正文")+"<w:p><w:r><w:object/></w:r></w:p>");
        parts.put("word/header1.xml",DocxFixtures.utf8("<w:hdr xmlns:w=\""+DocxFixtures.W+"\">"+DocxFixtures.paragraph("页眉")+"</w:hdr>"));
        parts.put("word/footer1.xml",DocxFixtures.utf8("<w:ftr xmlns:w=\""+DocxFixtures.W+"\">"+DocxFixtures.paragraph("页脚")+"</w:ftr>"));
        parts.put("word/media/image.png",new byte[]{1,2,3});
        var result=new DocxReader().read(DocxFixtures.zip(parts));
        assertThat(result.sourceText()).contains("正文","页眉","页脚");
        assertThat(result.blocks()).filteredOn(DocxReader.Block::unclassified).hasSize(2);
        assertThat(result.statistics().images()).isEqualTo(1);
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("DOCX_IMAGES_SKIPPED","DOCX_OBJECTS_SKIPPED");
    }
    @Test void rejectsUnsupportedAndMalformedPackages() {
        rejects(new byte[]{(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0},"DOCX_UNSUPPORTED");
        rejects(new byte[]{1,2,3},"DOCX_UNSUPPORTED");
        var parts=DocxFixtures.parts("<w:p>");rejects(DocxFixtures.zip(parts),"DOCX_INVALID");
        parts=DocxFixtures.parts("");parts.put("word/document.xml",DocxFixtures.utf8("<bad/>"));rejects(DocxFixtures.zip(parts),"DOCX_UNSUPPORTED");
        parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));parts.put("[Content_Types].xml",DocxFixtures.utf8("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.ms-word.document.macroEnabled.main+xml\"/></Types>"));rejects(DocxFixtures.zip(parts),"DOCX_UNSUPPORTED");
        parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));parts.put("../escape",new byte[]{1});rejects(DocxFixtures.zip(parts),"DOCX_INVALID");
        var valid=DocxFixtures.document(DocxFixtures.paragraph("ok"));rejects(Arrays.copyOf(valid,valid.length-30),"DOCX_INVALID");
    }
    @Test void rejectsEntitiesWithoutReadingTheirResource() {
        var parts=DocxFixtures.parts("");
        parts.put("word/document.xml",DocxFixtures.utf8("<!DOCTYPE w:document [<!ENTITY leak SYSTEM 'file:///must-not-be-read'>]><w:document xmlns:w=\""+DocxFixtures.W+"\"><w:body>"+DocxFixtures.paragraph("&leak;")+"</w:body></w:document>"));
        rejects(DocxFixtures.zip(parts),"DOCX_INVALID");
    }
    @Test void enforcesActualExpandedXmlTextBlockDepthAndCompressedBounds() {
        var parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));parts.put("padding",new byte[32*1024*1024]);rejects(DocxFixtures.zip(parts),"DOCX_TOO_LARGE");
        parts=DocxFixtures.parts(" ".repeat(4*1024*1024));rejects(DocxFixtures.zip(parts),"DOCX_TOO_LARGE");
        rejects(DocxFixtures.document(DocxFixtures.paragraph("x".repeat(40001))),"DOCX_CONTENT_TOO_LARGE");
        rejects(DocxFixtures.document(DocxFixtures.paragraph("x").repeat(601)),"DOCX_CONTENT_TOO_LARGE");
        rejects(DocxFixtures.document("<w:sdt>".repeat(130)+DocxFixtures.paragraph("x")+"</w:sdt>".repeat(130)),"DOCX_INVALID");
        rejects(new byte[5*1024*1024+1],"DOCX_TOO_LARGE");
        parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));for(int i=0;i<510;i++)parts.put("extra/"+i,new byte[0]);rejects(DocxFixtures.zip(parts),"DOCX_TOO_LARGE");
        rejects(DocxFixtures.document(""),"DOCX_EMPTY");
    }
    @Test void verifiesStoredEntryCrcAndRejectsDuplicateNames() {
        var valid=DocxFixtures.document(DocxFixtures.paragraph("ok"));
        // Rewrite the central CRC (not compressed data) to prove every actual entry is checked.
        for(int i=0;i<valid.length-4;i++)if(valid[i]==0x50&&valid[i+1]==0x4b&&valid[i+2]==1&&valid[i+3]==2){valid[i+16]^=1;break;}
        rejects(valid,"DOCX_INVALID");
        var parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));parts.put("duplicateA",new byte[]{1});parts.put("duplicateB",new byte[]{2});
        var duplicate=DocxFixtures.zip(parts);var a=DocxFixtures.utf8("duplicateA");var b=DocxFixtures.utf8("duplicateB");
        for(int i=0;i<=duplicate.length-b.length;i++){boolean match=true;for(int j=0;j<b.length;j++)if(duplicate[i+j]!=b[j])match=false;if(match)System.arraycopy(a,0,duplicate,i,a.length);}
        rejects(duplicate,"DOCX_INVALID");
    }
    @Test void countsSourceSeparatorsWithinTextLimitAndRejectsMismatchedLocalNames() {
        rejects(DocxFixtures.document(DocxFixtures.paragraph("x".repeat(20000))+DocxFixtures.paragraph("y".repeat(20000))),"DOCX_CONTENT_TOO_LARGE");
        var mismatch=DocxFixtures.document(DocxFixtures.paragraph("ok"));
        // Change only a local name, leaving its central-directory name untouched.
        int offset=30; mismatch[offset]='X';rejects(mismatch,"DOCX_INVALID");
    }
    @Test void rejectsExternalOrWrongOfficeRelationshipAndWrongMainType() {
        for(String relation:List.of("Target=\"https://example.invalid/document.xml\" TargetMode=\"External\"","Target=\"word/other.xml\"")) {
            var parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));
            parts.put("_rels/.rels",DocxFixtures.utf8("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" "+relation+"/></Relationships>"));
            rejects(DocxFixtures.zip(parts),"DOCX_UNSUPPORTED");
        }
        var parts=DocxFixtures.parts(DocxFixtures.paragraph("ok"));parts.put("[Content_Types].xml",DocxFixtures.utf8("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/xml\"/></Types>"));rejects(DocxFixtures.zip(parts),"DOCX_UNSUPPORTED");
    }
    @Test void rejectsEncryptedZipWithUnsupportedFormatError() {
        var encrypted=DocxFixtures.document(DocxFixtures.paragraph("ok"));encrypted[6]|=1;rejects(encrypted,"DOCX_UNSUPPORTED");
    }
    @Test void warnsForExternalImagesWithoutResolvingTheirTargets() {
        var result=new DocxReader().read(DocxFixtures.document(DocxFixtures.paragraph("原文")+"<w:p><w:r><w:pict><v:imagedata xmlns:v=\"urn:schemas-microsoft-com:vml\" src=\"https://must-not-be-fetched.invalid/image\"/></w:pict></w:r></w:p>"));
        assertThat(result.sourceText()).isEqualTo("原文");assertThat(result.statistics().images()).isEqualTo(1);
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("DOCX_IMAGES_SKIPPED");
    }
}
