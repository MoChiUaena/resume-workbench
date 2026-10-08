package dev.localresume;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PdfReaderTest {
    private static byte[] fixture(String name) throws Exception {
        return Files.readAllBytes(Path.of("fixtures", "pdf", name + ".pdf"));
    }

    @Test void extractsChineseTextInPageOrderAndMapsHeadingsAndFields() throws Exception {
        var source = new PdfReader().read(fixture("two-pages"));
        assertThat(source.statistics().pages()).isEqualTo(2);
        assertThat(source.sourceText()).contains("奶龙", "教育背景", "第二页的原文也应保留");
        assertThat(source.sourceText().indexOf("示例理工大学"))
            .isLessThan(source.sourceText().indexOf("第二页的原文也应保留"));
        var mapped = new DocxMapping().map(source.blocks(), source.warnings(), "PDF");
        assertThat(mapped.document().content().name()).isEqualTo("奶龙");
        assertThat(mapped.document().content().email()).isEqualTo("nailong@example.invalid");
        assertThat(mapped.document().content().sections()).anyMatch(s -> s.type().equals("education"));
        assertThat(mapped.document().content().sections()).anyMatch(s -> s.type().equals("project"));
        assertThat(mapped.warnings()).extracting(DocxReader.Warning::code).contains("PDF_READING_ORDER", "PDF_NAME_INFERRED");
    }

    private static void rejects(byte[] pdf,String code) {
        assertThatThrownBy(() -> new PdfReader().read(pdf)).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code).isEqualTo(code));
    }
    private static byte[] generated(int pages,String text) throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) {
                var page=new PDPage();doc.addPage(page);
                if(!text.isEmpty())try(var stream=new PDPageContentStream(doc,page)) {
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                    stream.newLineAtOffset(40,700);stream.showText(text);stream.endText();
                }
            }
            doc.save(bytes);return bytes.toByteArray();
        }
    }
    @Test void rejectsImageOnlyEncryptedAndMalformedInputsWithDistinctCodes() throws Exception {
        rejects(fixture("scanned"),"PDF_NO_TEXT");
        rejects(fixture("encrypted"),"PDF_ENCRYPTED");
        rejects(new byte[]{1,2,3},"PDF_INVALID");
        rejects(new byte[]{'%','P','D','F','-',1,2,3},"PDF_INVALID");
        assertThat(new PdfReader().read(fixture("text")).statistics().images()).isZero();
    }
    @Test void rejectsEncryptedPdfEvenWhenItsUserPasswordIsEmpty() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.protect(new StandardProtectionPolicy("owner-only","",new AccessPermission()));
            doc.save(bytes);
            rejects(bytes.toByteArray(),"PDF_ENCRYPTED");
        }
    }
    @Test void rejectsBytePageAndGlyphLimitsWithoutTruncation() throws Exception {
        rejects(new byte[5*1024*1024+1],"PDF_TOO_LARGE");
        rejects(generated(21,"page"),"PDF_TOO_LARGE");
        rejects(generated(1,"x".repeat(40001)),"PDF_CONTENT_TOO_LARGE");
    }
    @Test void acceptsExactlyFortyThousandUnitsWithoutCountingPdfboxPageEnd() throws Exception {
        var result=new PdfReader().read(generated(1,"x".repeat(40000)));
        assertThat(result.sourceText()).hasSize(40000);
    }
    @Test void preservesLfOnlyBetweenLinesAtTheExactTextLimit() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("x".repeat(39998));
                stream.newLineAtOffset(0,-14);stream.showText("y");stream.endText();
            }
            doc.save(bytes);
            var result=new PdfReader().read(bytes.toByteArray());
            assertThat(result.sourceText()).isEqualTo("x".repeat(39998)+"\ny").doesNotContain("\r").hasSize(40000);
        }
    }
    @Test void retainsPageBoundaryWhenAnEarlierPageHasNoText() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            var second=new PDPage();doc.addPage(second);
            try(var stream=new PDPageContentStream(doc,second)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("second page");stream.endText();
            }
            doc.save(bytes);
            assertThat(new PdfReader().read(bytes.toByteArray()).sourceText()).isEqualTo("\nsecond page");
        }
    }
    @Test void rejectsMoreThanSixHundredTextBlocks() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            for(int pageIndex=0;pageIndex<20;pageIndex++) {
                var page=new PDPage();doc.addPage(page);
                try(var stream=new PDPageContentStream(doc,page)) {
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                    stream.newLineAtOffset(40,700);
                    for(int line=0;line<31;line++) {stream.showText("line "+pageIndex+" "+line);stream.newLineAtOffset(0,-14);}
                    stream.endText();
                }
            }
            doc.save(bytes);rejects(bytes.toByteArray(),"PDF_CONTENT_TOO_LARGE");
        }
    }
    @Test void offersAnEmbeddedImageForExplicitSelectionWithoutPersistingThePdf() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            var image=LosslessFactory.createFromImage(doc,new BufferedImage(64,64,BufferedImage.TYPE_INT_RGB));
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.drawImage(image,20,20,64,64);
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("text");stream.endText();
            }
            doc.save(bytes);
            var result=new PdfReader().read(bytes.toByteArray());
            assertThat(result.statistics().images()).isEqualTo(1);
            assertThat(result.images()).hasSize(1);
            var candidate=result.images().getFirst();
            assertThat(candidate.id()).isEqualTo("p1-i1");
            assertThat(candidate.page()).isEqualTo(1);
            assertThat(candidate.width()).isEqualTo(64);
            assertThat(candidate.height()).isEqualTo(64);
            assertThat(candidate.mimeType()).isIn("image/png","image/jpeg");
            assertThat(java.util.Base64.getDecoder().decode(candidate.base64())).isNotEmpty();
            assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("PDF_IMAGES_AVAILABLE");
        }
    }
    @Test void offersSeparateSyntheticPortraitAndTransparentLogo() throws Exception {
        var result=new PdfReader().read(fixture("with-images"));
        assertThat(result.statistics().images()).isEqualTo(2);
        assertThat(result.images()).extracting(PdfReader.ImageCandidate::id).containsExactly("p1-i1","p1-i2");
        assertThat(result.images()).allSatisfy(candidate->{
            assertThat(candidate.page()).isEqualTo(1);
            assertThat(candidate.width()).isBetween(16,640);
            assertThat(candidate.height()).isBetween(16,640);
            assertThat(java.util.Base64.getDecoder().decode(candidate.base64()).length).isLessThanOrEqualTo(PdfImageCandidates.MAX_BYTES);
        });
        assertThat(result.images()).extracting(PdfReader.ImageCandidate::mimeType).contains("image/png");
        assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("PDF_IMAGES_AVAILABLE").doesNotContain("PDF_IMAGES_SKIPPED");
    }
    @Test void aTallImageNeverMakesTheTextPreviewInvalid() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            var image=LosslessFactory.createFromImage(doc,new BufferedImage(16,4000,BufferedImage.TYPE_INT_RGB));
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.drawImage(image,20,20,16,4000);
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("readable text");stream.endText();
            }
            doc.save(bytes);
            var result=new PdfReader().read(bytes.toByteArray());
            assertThat(result.sourceText()).isEqualTo("readable text");
            assertThat(result.statistics().images()).isEqualTo(1);
            assertThat(result.images().size()).isZero();
            assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("PDF_IMAGES_SKIPPED");
        }
    }
    @Test void moreThanFiveHundredTwelveImageObjectsHasAnExplicitLimitError() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                for(int index=0;index<513;index++) {
                    var image=LosslessFactory.createFromImage(doc,new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB));
                    stream.drawImage(image,20,20,16,16);
                }
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("readable text");stream.endText();
            }
            doc.save(bytes);rejects(bytes.toByteArray(),"PDF_CONTENT_TOO_LARGE");
        }
    }
    @Test void repeatedXObjectsCannotConsumeUnlimitedImageDecodeWork() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                for(int index=0;index<33;index++) {
                    var pixels=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);
                    if(index==32)for(int y=0;y<16;y++)for(int x=0;x<16;x++)pixels.setRGB(x,y,0xff0077dd);
                    stream.drawImage(LosslessFactory.createFromImage(doc,pixels),20,20,16,16);
                }
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("readable text");stream.endText();
            }
            doc.save(bytes);
            var result=new PdfReader().read(bytes.toByteArray());
            assertThat(result.statistics().images()).isEqualTo(33);
            assertThat(result.images().size()).isEqualTo(1);
            assertThat(result.warnings()).extracting(DocxReader.Warning::code).contains("PDF_IMAGES_SKIPPED");
        }
    }
    @Test void ignoresExternalLinksWithoutResolvingThem() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            var action=new PDActionURI();action.setURI("file:///must-not-be-read/private.txt");
            var link=new PDAnnotationLink();link.setAction(action);page.getAnnotations().add(link);
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("visible text");stream.endText();
            }
            doc.save(bytes);
            assertThat(new PdfReader().read(bytes.toByteArray()).sourceText()).isEqualTo("visible text");
        }
    }
    @Test void preservesBothColumnsAsEditableTextWhileWarningAboutReadingOrder() throws Exception {
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),10);
                stream.newLineAtOffset(40,700);stream.showText("LeftAlpha1");
                stream.newLineAtOffset(0,-18);stream.showText("LeftGamma2");
                stream.newLineAtOffset(260,18);stream.showText("RightBeta3");
                stream.newLineAtOffset(0,-18);stream.showText("RightDelta4");stream.endText();
            }
            doc.save(bytes);
            var source=new PdfReader().read(bytes.toByteArray());
            var mapped=new DocxMapping().map(source.blocks(),source.warnings(),"PDF");
            String editable=String.join(" ",mapped.document().content().sections().stream()
                .flatMap(s->s.entries().stream()).flatMap(e->e.bullets().stream()).toList());
            for(String word:java.util.List.of("LeftAlpha1","LeftGamma2","RightBeta3","RightDelta4")) {
                assertThat(source.sourceText()).contains(word);
                assertThat(editable).contains(word);
            }
            assertThat(mapped.warnings()).extracting(DocxReader.Warning::code).contains("PDF_READING_ORDER");
        }
    }
}
