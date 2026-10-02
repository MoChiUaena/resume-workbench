package dev.localresume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** An in-memory, revision-bound snapshot of exactly the material approved for one model call. */
@Service
public class JobMatches {
    public record Request(UUID resumeId,long expectedRevision,List<String> sectionIds,String jobDescription,String profileId,long settingsRevision) {}
    public record Generate(UUID previewId,boolean confirmSend) {}
    public record Source(String id,String sectionId,String entryId,int paragraph,String sectionTitle,String entryTitle,String text) {}
    public record Preview(UUID id,UUID resumeId,long revision,long settingsRevision,String profileId,
                          String profileName,String provider,String model,String destination,String jobDescription,
                          String payload,List<Source> sources,Instant expiresAt) {}
    public record Report(UUID id,UUID resumeId,long revision,String profileName,String provider,String model,
                         String destination,Instant expiresAt,List<JobMatchOutput.MatchItem> items,List<TextSuggestions.Suggestion> suggestions) {}
    private final ResumeService resumes;
    private final ModelSettings settings;
    private final ModelGateway gateway;
    private final TextSuggestions suggestions;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Map<UUID,Preview> previews=new ConcurrentHashMap<>();
    private final Map<UUID,Report> reports=new ConcurrentHashMap<>();
    private final Set<UUID> inFlight=ConcurrentHashMap.newKeySet();
    private final Semaphore generation=new Semaphore(2);
    @org.springframework.beans.factory.annotation.Autowired
    public JobMatches(ResumeService resumes,ModelSettings settings,ModelGateway gateway,TextSuggestions suggestions,ObjectMapper mapper){
        this(resumes,settings,gateway,suggestions,mapper,Clock.systemUTC());
    }
    JobMatches(ResumeService resumes,ModelSettings settings,ModelGateway gateway,TextSuggestions suggestions,ObjectMapper mapper,Clock clock){
        this.resumes=resumes;this.settings=settings;this.gateway=gateway;this.suggestions=suggestions;this.mapper=mapper;this.clock=clock;
    }
    public Preview preview(Request input){
        if(input==null||input.resumeId()==null||input.expectedRevision()<1||input.profileId()==null
            ||input.jobDescription()==null||input.jobDescription().isBlank()||input.jobDescription().length()>6000
            ||input.sectionIds()==null||input.sectionIds().isEmpty()||input.sectionIds().size()>12
            ||input.sectionIds().stream().anyMatch(s->s==null||s.isBlank())
            ||new HashSet<>(input.sectionIds()).size()!=input.sectionIds().size())throw invalid();
        var profile=settings.selected(input.profileId(),input.settingsRevision(),true);
        var resume=resumes.savedRevision(input.resumeId(),input.expectedRevision());
        var sources=new ArrayList<Source>();int length=0;
        for(String sectionId:input.sectionIds()){
            var section=resume.document().content().sections().stream().filter(s->s.id().equals(sectionId)&&s.visible()).findFirst()
                .orElseThrow(JobMatches::invalid);
            for(var entry:section.entries()){
                String heading=entry.title()+(entry.meta().isBlank()?"":" · "+entry.meta());
                length=append(sources,length,section,entry,-1,heading);
                for(int paragraph=0;paragraph<entry.bullets().size();paragraph++){
                    String value=entry.bullets().get(paragraph);
                    if(!value.isBlank())length=append(sources,length,section,entry,paragraph,value);
                }
            }
        }
        if(sources.isEmpty())throw invalid();
        var payloadSources=new ArrayList<Map<String,String>>();
        for(var source:sources){
            var section=resume.document().content().sections().stream().filter(s->s.id().equals(source.sectionId())).findFirst().orElseThrow();
            payloadSources.add(Map.of("id",source.id(),"type",section.type(),"text",source.text()));
        }
        String payload;
        try{payload=mapper.writeValueAsString(Map.of("jobDescription",input.jobDescription(),"sources",payloadSources));}
        catch(JsonProcessingException e){throw new IllegalStateException(e);}
        var result=new Preview(UUID.randomUUID(),resume.id(),resume.revision(),input.settingsRevision(),profile.id(),
            profile.name(),profile.provider(),profile.model(),profile.baseUrl()+"/chat/completions",input.jobDescription(),
            payload,List.copyOf(sources),clock.instant().plusSeconds(900));
        synchronized(previews){prune();evict(previews,32);previews.put(result.id(),result);}
        return result;
    }
    private static int append(List<Source> sources,int length,ResumeDocument.Section section,ResumeDocument.Entry entry,int paragraph,String text){
        if(sources.size()>=60||length+text.length()>12000)throw invalid();
        sources.add(new Source("s"+(sources.size()+1),section.id(),entry.id(),paragraph,section.title(),entry.title(),text));
        return length+text.length();
    }
    public Report generate(Generate input){
        if(input==null||!input.confirmSend())throw new ApiException("AI_SEND_CONFIRMATION_REQUIRED","请确认预览中的发送内容和模型服务。",422);
        if(input.previewId()==null)throw new ApiException("JOB_MATCH_PREVIEW_EXPIRED","发送预览已过期，请重新预览。",410);
        var preview=previews.get(input.previewId());
        if(preview==null||!preview.expiresAt().isAfter(clock.instant())){
            previews.remove(input.previewId());reports.remove(input.previewId());
            throw new ApiException("JOB_MATCH_PREVIEW_EXPIRED","发送预览已过期，请重新预览。",410);
        }
        var profile=settings.selected(preview.profileId(),preview.settingsRevision(),true);
        resumes.savedRevision(preview.resumeId(),preview.revision());
        var prior=reports.get(preview.id());
        if(prior!=null&&prior.expiresAt().isAfter(clock.instant()))return prior;
        if(!generation.tryAcquire())throw new ApiException("MODEL_BUSY","正在生成岗位匹配，请稍后重试。",423);
        if(!inFlight.add(preview.id())){generation.release();throw new ApiException("MODEL_BUSY","此预览正在生成，请稍后重试。",423);}
        try{
            String raw=gateway.matchJob(profile,settings.credential(profile),preview.payload());
            var parsed=JobMatchOutput.decode(mapper,raw,preview);
            if(!preview.expiresAt().isAfter(clock.instant()))
                throw new ApiException("JOB_MATCH_PREVIEW_EXPIRED","发送预览已过期，请重新预览。",410);
            resumes.savedRevision(preview.resumeId(),preview.revision());
            settings.selected(preview.profileId(),preview.settingsRevision(),true);
            var source=resumes.savedRevision(preview.resumeId(),preview.revision());
            var reviewed=new ArrayList<TextSuggestions.Suggestion>();
            for(var candidate:parsed.suggestions()){
                var origin=preview.sources().stream().filter(s->s.id().equals(candidate.sourceId())).findFirst().orElseThrow();
                reviewed.add(suggestions.registerReviewedCandidate(source,profile,origin.sectionId(),origin.entryId(),origin.paragraph(),candidate.replacement()));
            }
            var report=new Report(UUID.randomUUID(),preview.resumeId(),preview.revision(),preview.profileName(),preview.provider(),
                preview.model(),preview.destination(),clock.instant().plusSeconds(900),parsed.items(),List.copyOf(reviewed));
            synchronized(reports){prune();evict(reports,32);reports.put(preview.id(),report);}
            return report;
        }finally{inFlight.remove(preview.id());generation.release();}
    }
    private void prune(){Instant now=clock.instant();previews.entrySet().removeIf(e->!e.getValue().expiresAt().isAfter(now));reports.entrySet().removeIf(e->!e.getValue().expiresAt().isAfter(now));}
    private static <T> void evict(Map<UUID,T> cache,int cap){if(cache.size()>=cap)cache.remove(cache.keySet().iterator().next());}
    private static ApiException invalid(){return new ApiException("JOB_MATCH_INPUT_INVALID","岗位文本或所选模块无效，或内容超过安全上限。",422);}
}
