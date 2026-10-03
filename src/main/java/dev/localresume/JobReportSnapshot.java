package dev.localresume;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;

/** Immutable archival material, with no live review/send tokens or provider credentials. */
public record JobReportSnapshot(int schemaVersion,long sourceRevision,String jobDescription,String profileName,
                                String provider,String model,String destination,List<JobMatches.Source> sources,
                                List<JobMatchOutput.MatchItem> items,List<JobMatchOutput.Candidate> suggestions) {
    public static final int MAX_BYTES=131072;
    public static final int MAX_DATABASE_BYTES=262144;

    public static JobReportSnapshot from(ObjectMapper mapper,JobMatches.ArchiveMaterial material) {
        var preview=material.preview();var report=material.report();
        var candidates=new ArrayList<JobMatchOutput.Candidate>();
        for(var suggestion:report.suggestions()) {
            var source=preview.sources().stream().filter(s->s.sectionId().equals(suggestion.sectionId())
                &&s.entryId().equals(suggestion.entryId())&&s.paragraph()==suggestion.paragraph()&&s.text().equals(suggestion.original()))
                .findFirst().orElseThrow(JobReportSnapshot::invalid);
            candidates.add(new JobMatchOutput.Candidate(source.id(),suggestion.replacement()));
        }
        var snapshot=new JobReportSnapshot(1,report.revision(),preview.jobDescription(),report.profileName(),report.provider(),
            report.model(),report.destination(),List.copyOf(preview.sources()),List.copyOf(report.items()),List.copyOf(candidates));
        validate(mapper,snapshot);return snapshot;
    }

    public static JobReportSnapshot parse(ObjectMapper mapper,byte[] bytes) {
        return parseBounded(mapper,bytes,MAX_BYTES);
    }
    /** JSONB text adds separator whitespace; its canonical snapshot remains bounded to MAX_BYTES. */
    public static JobReportSnapshot parseDatabase(ObjectMapper mapper,byte[] bytes) {
        return parseBounded(mapper,bytes,MAX_DATABASE_BYTES);
    }
    private static JobReportSnapshot parseBounded(ObjectMapper mapper,byte[] bytes,int maxRawBytes) {
        if(bytes==null||bytes.length>maxRawBytes)throw invalid();
        try {
            var strict=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root=strict.readTree(bytes);shape(root);
            var snapshot=strict.treeToValue(root,JobReportSnapshot.class);validate(mapper,snapshot);return snapshot;
        }catch(ApiException e){throw e;}catch(Exception e){throw invalid();}
    }
    private static void shape(JsonNode root) {
        fields(root,"schemaVersion","sourceRevision","jobDescription","profileName","provider","model","destination","sources","items","suggestions");
        integer(root,"schemaVersion",true);integer(root,"sourceRevision",false);
        strings(root,"jobDescription","profileName","provider","model","destination");
        array(root,"sources");array(root,"items");array(root,"suggestions");
        for(var source:root.path("sources")) {
            fields(source,"id","sectionId","entryId","paragraph","sectionTitle","entryTitle","text");
            strings(source,"id","sectionId","entryId","sectionTitle","entryTitle","text");integer(source,"paragraph",true);
        }
        for(var item:root.path("items")) {
            fields(item,"requirement","status","evidence","advice");strings(item,"requirement","status","advice");array(item,"evidence");
            for(var evidence:item.path("evidence")) {fields(evidence,"sourceId","quote");strings(evidence,"sourceId","quote");}
        }
        for(var candidate:root.path("suggestions")) {fields(candidate,"sourceId","replacement");strings(candidate,"sourceId","replacement");}
    }
    private static void fields(JsonNode node,String... names) {
        if(node==null||!node.isObject()||node.size()!=names.length||!Arrays.stream(names).allMatch(node::has))throw invalid();
    }
    private static void strings(JsonNode node,String... names) {for(var name:names)if(!node.path(name).isTextual())throw invalid();}
    private static void array(JsonNode node,String name) {if(!node.path(name).isArray())throw invalid();}
    private static void integer(JsonNode node,String name,boolean narrow) {
        var value=node.path(name);
        if(!value.isIntegralNumber()||!(narrow?value.canConvertToInt():value.canConvertToLong()))throw invalid();
    }

    public static void validate(ObjectMapper mapper,JobReportSnapshot snapshot) {
        if(snapshot==null||snapshot.schemaVersion()!=1||snapshot.sourceRevision()<1
            ||!text(snapshot.jobDescription(),1,6000)||snapshot.jobDescription().isBlank()
            ||!text(snapshot.profileName(),1,50)||snapshot.provider()==null||!Set.of("dashscope","deepseek","glm","compatible").contains(snapshot.provider())
            ||!text(snapshot.model(),1,80)||!snapshot.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,79}")
            ||!text(snapshot.destination(),1,2048)||snapshot.sources()==null||snapshot.sources().isEmpty()||snapshot.sources().size()>60
            ||snapshot.items()==null||snapshot.suggestions()==null)throw invalid();
        var seen=new HashSet<String>();var targets=new HashSet<String>();int length=0;
        for(var source:snapshot.sources()) {
            if(source==null||!text(source.id(),1,20)||!source.id().matches("s(?:[1-9]|[1-5][0-9]|60)")||!seen.add(source.id())
                ||!text(source.sectionId(),1,50)||source.sectionId().isBlank()||!text(source.entryId(),1,50)||source.entryId().isBlank()
                ||source.paragraph() < -1||source.paragraph()>29||!text(source.sectionTitle(),0,120)||!text(source.entryTitle(),0,160)
                ||!text(source.text(),1,12000)||source.text().isBlank()
                ||!targets.add(source.sectionId()+"\u0000"+source.entryId()+"\u0000"+source.paragraph()))throw invalid();
            length+=source.text().length();if(length>12000)throw invalid();
        }
        try {
            // Re-use the generation boundary's exact quote, requirement and suggestion reference checks.
            var preview=new JobMatches.Preview(UUID.randomUUID(),UUID.randomUUID(),snapshot.sourceRevision(),0,"archive",
                snapshot.profileName(),snapshot.provider(),snapshot.model(),snapshot.destination(),snapshot.jobDescription(),"",
                snapshot.sources(),Instant.EPOCH);
            JobMatchOutput.decode(mapper,mapper.writeValueAsString(Map.of("items",snapshot.items(),"suggestions",snapshot.suggestions())),preview);
            if(mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(snapshot).length>MAX_BYTES)throw invalid();
        }catch(Exception e){throw invalid();}
    }
    private static boolean text(String value,int min,int max){return value!=null&&value.length()>=min&&value.length()<=max
        &&value.codePoints().noneMatch(c->Character.isISOControl(c)&&c!='\t'&&c!='\n'&&c!='\r');}
    static ApiException invalid(){return new ApiException("JOB_REPORT_SNAPSHOT_INVALID","报告快照格式或引用无效，请保留原记录并检查备份。",422);}
}
