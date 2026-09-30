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
                          @Min(0) @Max(29) int paragraph,@Min(0) Integer selectionStart,@Min(0) Integer selectionEnd,
                          @NotBlank String profileId,@Min(0) long settingsRevision,@AssertTrue boolean confirmSend) {
        public Request(UUID resumeId,long expectedRevision,String sectionId,String entryId,int paragraph,
                       String profileId,long settingsRevision,boolean confirmSend){
            this(resumeId,expectedRevision,sectionId,entryId,paragraph,null,null,profileId,settingsRevision,confirmSend);
        }
    }
    public record Suggestion(UUID id,UUID resumeId,long revision,String sectionId,String entryId,int paragraph,
                             String original,int selectionStart,int selectionEnd,String selectedOriginal,String replacement,
                             String profileName,String provider,String model,
                             String destination,Instant expiresAt,boolean addedNumbers) {}
    private record Selection(int start,int end,String text) {}
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
            var selection=selection(original,input.selectionStart(),input.selectionEnd());
            String replacement=gateway.rewrite(profile,settings.credential(profile),selection.text());
            resumes.savedRevision(input.resumeId(),input.expectedRevision());
            settings.selected(input.profileId(),input.settingsRevision(),true);
            var result=new Suggestion(UUID.randomUUID(),source.id(),source.revision(),input.sectionId(),input.entryId(),input.paragraph(),
                original,selection.start(),selection.end(),selection.text(),replacement,profile.name(),profile.provider(),profile.model(),
                profile.baseUrl()+"/chat/completions",clock.instant().plusSeconds(600),addsNumbers(selection.text(),replacement));
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
        String updated=value.original().substring(0,value.selectionStart())+value.replacement()+value.original().substring(value.selectionEnd());
        if(updated.length()>800)throw new ApiException("AI_RESULT_TOO_LONG","建议应用后将超过单段 800 字上限，请缩短选区或手动编辑。",422);
        return resumes.applyParagraph(resumeId,revision,value.sectionId(),value.entryId(),value.paragraph(),value.original(),updated,id);
    }
    private static Selection selection(String source,Integer start,Integer end){
        if(start==null&&end==null){start=0;end=source.length();}
        if(start==null||end==null||start<0||end<=start||end>source.length()||splitsPair(source,start)||splitsPair(source,end))
            throw new ApiException("AI_SELECTION_INVALID","请在同一段落内选择连续、完整的文字。",422);
        String text=source.substring(start,end);
        if(text.isBlank()||text.length()>800)throw new ApiException("AI_TEXT_INVALID","请选择非空且不超过 800 个字符的文字。",422);
        return new Selection(start,end,text);
    }
    private static boolean splitsPair(String text,int offset){
        return offset>0&&offset<text.length()&&Character.isHighSurrogate(text.charAt(offset-1))&&Character.isLowSurrogate(text.charAt(offset));
    }
    static boolean addsNumbers(String original,String proposed){var pattern=Pattern.compile("[0-9]+(?:[.,][0-9]+)?%?");var prior=new HashSet<String>();pattern.matcher(original).results().forEach(match->prior.add(match.group()));return pattern.matcher(proposed).results().anyMatch(match->!prior.contains(match.group()));}
}
