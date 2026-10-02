package dev.localresume;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.util.*;

/** Parses a model response as untrusted data and proves every quoted span was actually sent. */
public final class JobMatchOutput {
    public record Evidence(String sourceId,String quote) {}
    public record MatchItem(String requirement,String status,List<Evidence> evidence,String advice) {}
    public record Candidate(String sourceId,String replacement) {}
    public record Result(List<MatchItem> items,List<Candidate> suggestions) {}
    private JobMatchOutput(){}
    public static Result decode(ObjectMapper mapper,String raw,JobMatches.Preview preview){
        if(raw==null||raw.length()>12000||preview==null)throw invalid();
        try{
            var strict=mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root=strict.readTree(raw);
            fields(root,"items","suggestions");
            var itemsNode=root.path("items");var suggestionsNode=root.path("suggestions");
            if(!itemsNode.isArray()||itemsNode.isEmpty()||itemsNode.size()>12||!suggestionsNode.isArray()||suggestionsNode.size()>6)throw invalid();
            var sources=new HashMap<String,JobMatches.Source>();
            for(var source:preview.sources())sources.put(source.id(),source);
            var items=new ArrayList<MatchItem>();var requirements=new HashSet<String>();
            for(var node:itemsNode){
                fields(node,"requirement","status","evidence","advice");
                String requirement=string(node,"requirement",1,6000),status=string(node,"status",1,20),advice=string(node,"advice",0,800);
                if(requirement.isBlank()||!preview.jobDescription().contains(requirement)||!requirements.add(requirement)
                    ||!Set.of("supported","partial","missing").contains(status))throw invalid();
                var evidenceNode=node.path("evidence");
                if(!evidenceNode.isArray()||evidenceNode.size()>3||("missing".equals(status)?!evidenceNode.isEmpty():evidenceNode.isEmpty()))throw invalid();
                var evidence=new ArrayList<Evidence>();var seen=new HashSet<String>();
                for(var ref:evidenceNode){
                    fields(ref,"sourceId","quote");
                    String id=string(ref,"sourceId",1,20),quote=string(ref,"quote",1,12000);
                    var source=sources.get(id);
                    if(source==null||quote.isBlank()||!source.text().contains(quote)||!seen.add(id+"\u0000"+quote))throw invalid();
                    evidence.add(new Evidence(id,quote));
                }
                items.add(new MatchItem(requirement,status,List.copyOf(evidence),advice));
            }
            var suggestions=new ArrayList<Candidate>();var seenSuggestions=new HashSet<String>();
            for(var node:suggestionsNode){
                fields(node,"sourceId","replacement");
                String id=string(node,"sourceId",1,20),replacement=string(node,"replacement",1,800);
                var source=sources.get(id);
                if(source==null||source.paragraph()<0||!seenSuggestions.add(id)||replacement.isBlank()
                    ||replacement.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\t'))throw invalid();
                suggestions.add(new Candidate(id,replacement));
            }
            return new Result(List.copyOf(items),List.copyOf(suggestions));
        }catch(ApiException e){throw e;}catch(Exception e){throw invalid();}
    }
    private static String string(JsonNode node,String name,int min,int max){
        var field=node.path(name);
        if(!field.isTextual()||field.asText().length()<min||field.asText().length()>max||field.asText().codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\t'))throw invalid();
        return field.asText();
    }
    private static void fields(JsonNode node,String... expected){
        if(!node.isObject()||node.size()!=expected.length||!Arrays.stream(expected).allMatch(node::has))throw invalid();
    }
    private static ApiException invalid(){return new ApiException("JOB_MATCH_OUTPUT_INVALID","模型返回的岗位分析格式或引用无效，请重新生成并核对原文。",422);}
}
