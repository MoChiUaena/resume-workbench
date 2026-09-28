package dev.localresume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;

/** Schema 3 adds presentation settings; schema 2 is upgraded when read, without writing user records. */
public record ResumeDocument(@Min(3) @Max(3) int schemaVersion,
                             @NotNull @Valid Content content, @NotNull @Valid Layout layout) {
    public static final int SCHEMA_VERSION = 3;
    public ResumeDocument { if(schemaVersion==2) schemaVersion=SCHEMA_VERSION; }
    public record Content(@NotBlank @Size(max=30) String name,
                          @NotNull @Size(max=70) String headline,
                          @NotNull @Size(max=100) String email,
                          @NotNull @Size(max=30) String phone,
                          @NotNull @Size(max=40) String location,
                          @NotNull @Size(max=20) List<@NotNull @Valid Section> sections) {}
    public record Section(@NotBlank @Size(max=50) String id,
                          @NotNull @Pattern(regexp="education|experience|project|skills|custom") String type,
                          @NotBlank @Size(max=40) String title, boolean visible, boolean pageBreakBefore,
                          @NotNull @Size(max=15) List<@NotNull @Valid Entry> entries) {}
    public record Entry(@NotBlank @Size(max=50) String id,
                        @NotNull @Size(max=80) String title, @NotNull @Size(max=120) String meta,
                        boolean bulleted, @NotNull @Size(max=30) List<@NotNull @Size(max=800) String> bullets) {}
    public record Layout(@NotNull @Pattern(regexp="classic|banner") String template,
                         @NotNull @Pattern(regexp="sans|serif") String font,
                         @DecimalMin("9.0") @DecimalMax("12.0") double fontSize,
                         @DecimalMin("1.2") @DecimalMax("2.0") double lineHeight,
                         @Min(0) @Max(12) int sectionGapMm, @Min(12) @Max(22) int marginMm,
                         boolean swapImages,
                         @NotNull @Valid ResumeDraft.ImageSlot photo,
                         @NotNull @Valid ResumeDraft.ImageSlot logo,
                         @NotNull @Valid Presentation presentation) {
        public Layout { if(presentation==null) presentation=Presentation.defaults(marginMm); }
        public Layout(String template,String font,double fontSize,double lineHeight,int sectionGapMm,int marginMm,
                      boolean swapImages,ResumeDraft.ImageSlot photo,ResumeDraft.ImageSlot logo) {
            this(template,font,fontSize,lineHeight,sectionGapMm,marginMm,swapImages,photo,logo,Presentation.defaults(marginMm));
        }
        public String cssVariables() {
            return "--body-size:" + fontSize + "pt;--leading:" + lineHeight + ";--section-gap:" + sectionGapMm
                + "mm;--font-scale:"+(fontSize/9.4)+";--margin-horizontal:"+presentation.marginHorizontalMm()+"mm;--margin-top:"+presentation.marginTopMm()
                + "mm;--margin-bottom:"+presentation.marginBottomMm()+"mm;--accent:"+presentation.accentColor()
                + ";--entry-gap:"+presentation.entryGapMm()+"mm;--paragraph-gap:"+presentation.paragraphGapMm()
                + "mm;--resume-font:" + (font.equals("serif") ? "ResumeSerif" : "ResumeSans");
        }
        public String cssClasses(){return "template-"+template+" alignment-"+presentation.alignment()+" contacts-"+presentation.contactStyle()+" headings-"+presentation.headingStyle();}
    }
    public record Presentation(@NotNull @Pattern(regexp="zh|en") String language,
                               @NotNull @Pattern(regexp="#[0-9a-fA-F]{6}") String accentColor,
                               @NotNull @Pattern(regexp="left|center|justify") String alignment,
                               @NotNull @Pattern(regexp="labels|icons|plain") String contactStyle,
                               @NotNull @Pattern(regexp="template|line|bar|plain") String headingStyle,
                               @Min(8) @Max(32) int marginHorizontalMm,
                               @Min(8) @Max(32) int marginTopMm,
                               @Min(8) @Max(32) int marginBottomMm,
                               @DecimalMin("0.0") @DecimalMax("8.0") double entryGapMm,
                               @DecimalMin("0.0") @DecimalMax("4.0") double paragraphGapMm) {
        public static Presentation defaults(int margin){return new Presentation("zh","#244f63","left","plain","template",margin,margin,margin,3,.9);}
        public String label(String field){String text=switch(field){case "phone"->language.equals("en")?"Phone":"电话";case "email"->language.equals("en")?"Email":"邮箱";default->language.equals("en")?"Location":"城市";};return text+(language.equals("en")?": ":"：");}
    }
    public static ResumeDocument fromLegacy(ResumeDraft draft) {
        var sections = new ArrayList<Section>();
        var pages = Samples.pages(draft.sample());
        for (int p=0;p<pages.size();p++) {
            int n=0;
            for (var section : pages.get(p)) {
                String type = switch (section.title()) { case "教育背景" -> "education"; case "专业技能" -> "skills"; case "项目经历" -> "project"; case "实践与学习" -> "experience"; default -> "custom"; };
                var entries = section.entries().stream().map(e -> new Entry(UUID.randomUUID().toString(),e.title(),e.meta(),true,e.bullets())).toList();
                sections.add(new Section(UUID.randomUUID().toString(),type,section.title(),true,p>0 && n++==0,entries));
            }
        }
        return new ResumeDocument(SCHEMA_VERSION,new Content(draft.name(),draft.headline(),draft.email(),draft.phone(),draft.location(),List.copyOf(sections)),
            new Layout("classic","sans",9.4,1.68,4,16,draft.swapImages(),draft.photo(),draft.logo()));
    }
    public static ResumeDocument sample(String sample) {
        var photo = new ResumeDraft.ImageSlot(null,true,26,34,"cover",0,1,50,50);
        var logo = new ResumeDraft.ImageSlot(null,true,26,26,"contain",0,1,50,50);
        var result = fromLegacy(new ResumeDraft(1,sample.equals("two") ? "two" : "one","奶龙","Java 后端 / AI 应用开发实习",
            "nailong@example.invalid","138 0000 0000","杭州 · 2027 届",false,photo,logo));
        if (!sample.equals("blank")) return result;
        return new ResumeDocument(SCHEMA_VERSION,new Content("姓名","求职方向","","","",List.of()),result.layout());
    }
}
