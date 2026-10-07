package dev.localresume;

import java.util.*;
import java.util.regex.Pattern;

public class DocxMapping {
    public record Result(ResumeDocument document,List<DocxReader.Warning> warnings) {}
    private static final Pattern FIELD=Pattern.compile("^(姓名|name|求职方向|求职意向|headline|邮箱|email|e-mail|电话|手机|phone|城市|地点|location)\\s*[:：]\\s*(.+)$",Pattern.CASE_INSENSITIVE);
    public Result map(DocxReader.Result source) {
        return map(source.blocks(),source.warnings(),"DOCX");
    }
    public Result map(List<DocxReader.Block> blocks,List<DocxReader.Warning> sourceWarnings,String prefix) {
        var fields=new HashMap<String,String>();var sections=new ArrayList<SectionBuilder>();var warnings=new ArrayList<>(sourceWarnings);
        SectionBuilder current=null;boolean split=false,unclassified=false,explicitField=false;
        boolean first=true;
        for(var block:blocks) {
            String raw=block.text(),trimmed=raw.strip();var match=FIELD.matcher(trimmed);
            if(first&&prefix.equals("PDF")&&!block.unclassified()&&!match.matches()&&plausibleName(trimmed)) {
                fields.put("name",trimmed);warnings.add(new DocxReader.Warning("PDF_NAME_INFERRED","已根据首行推测姓名，请在创建前核对。"));first=false;continue;
            }
            if(!prefix.equals("PDF")||!genericResumeTitle(trimmed))first=false;
            if(!block.unclassified()&&match.matches()) {
                String field=switch(match.group(1).toLowerCase(Locale.ROOT)){case "姓名","name"->"name";case "求职方向","求职意向","headline"->"headline";case "邮箱","email","e-mail"->"email";case "电话","手机","phone"->"phone";default->"location";};
                String value=match.group(2);int max=switch(field){case "name","phone"->30;case "headline"->70;case "email"->100;default->40;};
                boolean plausible=switch(field){case "email"->value.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");case "phone"->value.matches("[+()0-9 \\-]{5,30}");default->true;};
                if(value.length()<=max&&plausible&&!fields.containsKey(field)){fields.put(field,value);explicitField=true;continue;}
            }
            String type=headingType(trimmed);
            boolean shortHeading=raw.length()<=40&&(block.heading()||trimmed.matches("[^:：。.!?！？\\n]{1,20}[:：]"));
            if(!block.unclassified()&&raw.length()<=40&&(type!=null||shortHeading)) {
                current=new SectionBuilder(type==null?"custom":type,raw);sections.add(current);
                if(sections.size()>20)throw contentTooLarge(prefix);
                if(type==null)unclassified=true;
                continue;
            }
            if(current==null||block.unclassified()&&!current.type.equals("custom")) {
                current=new SectionBuilder("custom","未分类内容");sections.add(current);
            }
            if(current.type.equals("custom"))unclassified=true;
            for(int start=0;start<raw.length();) {
                int end=Math.min(start+800,raw.length());
                if(end<raw.length()&&Character.isHighSurrogate(raw.charAt(end-1))&&Character.isLowSurrogate(raw.charAt(end)))end--;
                if(raw.length()>800)split=true;
                if(current.paragraphs.size()==450){current=new SectionBuilder(current.type,current.title);sections.add(current);}
                if(sections.size()>20)throw contentTooLarge(prefix);
                current.paragraphs.add(raw.substring(start,end));start=end;
            }
        }
        if(explicitField)warnings.add(new DocxReader.Warning(prefix+"_FIELDS_INFERRED","已根据明确标签识别基本信息，请核对后创建。"));
        if(fields.size()<5)warnings.add(new DocxReader.Warning(prefix+"_FIELDS_MISSING","部分基本信息未识别，请在预览中补充或检查。"));
        if(unclassified)warnings.add(new DocxReader.Warning(prefix+"_UNCLASSIFIED","未识别的文字已保留在自定义模块，请检查分类。"));
        if(split)warnings.add(new DocxReader.Warning(prefix+"_PARAGRAPHS_SPLIT","长段落已按字符边界拆分，文字完整保留。"));
        var content=new ResumeDocument.Content(fields.getOrDefault("name","姓名"),fields.getOrDefault("headline",""),fields.getOrDefault("email",""),fields.getOrDefault("phone",""),fields.getOrDefault("location",""),sections.stream().map(SectionBuilder::build).toList());
        return new Result(new ResumeDocument(ResumeDocument.SCHEMA_VERSION,content,ResumeDocument.sample("blank").layout()),List.copyOf(warnings));
    }
    private static ApiException contentTooLarge(String prefix){return prefix.equals("PDF")?PdfReader.contentTooLarge():DocxReader.contentTooLarge();}
    private static boolean genericResumeTitle(String text){return text.equals("简历")||text.equals("个人简历")||text.equalsIgnoreCase("resume");}
    private static boolean plausibleName(String text) {
        return !genericResumeTitle(text)&&headingType(text)==null
            &&text.length()>=2&&text.length()<=20&&!text.matches(".*[0-9@：:。.!?！？].*")
            &&(text.matches("[\\p{IsHan}·]{2,8}")||text.matches("[A-Za-z]+(?: [A-Za-z]+){1,3}"));
    }
    private static String headingType(String heading) {
        String normalized=heading.toLowerCase(Locale.ROOT).replaceAll("[\\s:：]","");
        return switch(normalized) {
            case "教育","教育背景","教育经历","education","educationalbackground","academicbackground" -> "education";
            case "工作经历","工作经验","实习经历","实习经验","实践经历","workexperience","professionalexperience","experience","employment","internship","internshipexperience" -> "experience";
            case "项目经历","项目经验","项目","projects","project","projectexperience" -> "project";
            case "技能","专业技能","技术技能","技能特长","skills","technicalskills","professionalskills" -> "skills";
            default -> null;
        };
    }
    private static final class SectionBuilder {
        final String type,title;final List<String> paragraphs=new ArrayList<>();
        SectionBuilder(String type,String title){this.type=type;this.title=title;}
        ResumeDocument.Section build() {
            var entries=new ArrayList<ResumeDocument.Entry>();
            for(int start=0;start<paragraphs.size();start+=30)entries.add(new ResumeDocument.Entry(UUID.randomUUID().toString(),"","",false,List.copyOf(paragraphs.subList(start,Math.min(start+30,paragraphs.size())))));
            return new ResumeDocument.Section(UUID.randomUUID().toString(),type,title,true,false,List.copyOf(entries));
        }
    }
}
