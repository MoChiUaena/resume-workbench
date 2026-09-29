package dev.localresume;

import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

/** Temporary reviewed suggestions; source text is not logged or written to configuration files. */
@Service
public class TextSuggestions {
    public record Request(@NotNull UUID resumeId,@Min(1) long expectedRevision,
                          @NotBlank @Size(max=50) String sectionId,@NotBlank @Size(max=50) String entryId,
                          @Min(0) @Max(29) int paragraph,@NotBlank String profileId,@Min(0) long settingsRevision,
                          @AssertTrue boolean confirmSend) {}
    public record Suggestion(UUID id,UUID resumeId,long revision,String sectionId,String entryId,int paragraph,
                             String original,String replacement,String profileName,String provider,String model,
                             String destination,Instant expiresAt,boolean addedNumbers) {}
    private final ResumeService resumes;
    private final ModelSettings settings;
    private final ModelGateway gateway;
    private final Clock clock;
    private final Map<UUID,Suggestion> suggestions=new ConcurrentHashMap<>();
    private final Semaphore generation=new Semaphore(1);
    @org.springframework.beans.factory.annotation.Autowired
    public TextSuggestions(ResumeService resumes,ModelSettings settings,ModelGateway gateway){this(resumes,settings,gateway,Clock.systemUTC());}
    TextSuggestions(ResumeService resumes,ModelSettings settings,ModelGateway gateway,Clock clock){this.resumes=resumes;this.settings=settings;this.gateway=gateway;this.clock=clock;}
    public Suggestion create(Request input){
        if(!input.confirmSend())throw new ApiException("AI_SEND_CONFIRMATION_REQUIRED","请确认要发送的段落和模型服务后，再生成建议。",422);
        if(!generation.tryAcquire())throw new ApiException("MODEL_BUSY","正在生成建议，请稍后重试。",423);
        try{
            var profile=settings.selected(input.profileId(),input.settingsRevision(),true);
            var source=resumes.savedRevision(input.resumeId(),input.expectedRevision());
            String original=ResumeService.paragraph(source.document(),input.sectionId(),input.entryId(),input.paragraph());
            if(original.isBlank()||original.length()>800)throw new ApiException("AI_TEXT_INVALID","请选择非空且不超过 800 个字符的段落。",422);
            String replacement=gateway.rewrite(profile,settings.credential(profile),original);
            resumes.savedRevision(input.resumeId(),input.expectedRevision());
            settings.selected(input.profileId(),input.settingsRevision(),true);
            var result=new Suggestion(UUID.randomUUID(),source.id(),source.revision(),input.sectionId(),input.entryId(),input.paragraph(),original,replacement,
                profile.name(),profile.provider(),profile.model(),profile.baseUrl()+"/chat/completions",clock.instant().plusSeconds(600),addsNumbers(original,replacement));
            suggestions.entrySet().removeIf(entry->!entry.getValue().expiresAt().isAfter(clock.instant()));
            if(suggestions.size()>=32){var oldest=suggestions.values().stream().min(Comparator.comparing(Suggestion::expiresAt)).orElseThrow();suggestions.remove(oldest.id());}
            suggestions.put(result.id(),result);return result;
        }finally{generation.release();}
    }
    public ResumeService.Resume apply(UUID id,UUID resumeId,long revision,boolean confirmed){
        if(!confirmed)throw new ApiException("AI_APPLY_CONFIRMATION_REQUIRED","请核对事实和表达后，再确认应用建议。",422);
        var value=suggestions.get(id);
        if(value==null||!value.expiresAt().isAfter(clock.instant())){suggestions.remove(id);throw new ApiException("AI_SUGGESTION_EXPIRED","建议已过期，请重新生成。",410);}
        if(!value.resumeId().equals(resumeId)||value.revision()!=revision)throw new ApiException("AI_SOURCE_CHANGED","建议不属于当前简历或修订，请重新生成。",409);
        return resumes.applyParagraph(resumeId,revision,value.sectionId(),value.entryId(),value.paragraph(),value.original(),value.replacement(),id);
    }
    static boolean addsNumbers(String original,String proposed){var pattern=Pattern.compile("[0-9]+(?:[.,][0-9]+)?%?");var prior=new HashSet<String>();pattern.matcher(original).results().forEach(match->prior.add(match.group()));return pattern.matcher(proposed).results().anyMatch(match->!prior.contains(match.group()));}
}
