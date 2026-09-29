package dev.localresume;

import jakarta.validation.constraints.NotNull;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.*;

/** Export-only projection. Never changes the saved document or its attachment references. */
public final class Redaction {
    private Redaction() {}
    public record Options(@NotNull Boolean name, @NotNull Boolean phone, @NotNull Boolean email,
                          @NotNull Boolean location, @NotNull Boolean photo, @NotNull Boolean logo,
                          @NotNull Boolean matchingText) {
        public static Options defaults() { return new Options(true,true,true,true,true,true,true); }
    }
    public static ResumeDocument apply(ResumeDocument original, Options options) {
        var content=original.content();var layout=original.layout();
        boolean english=layout.presentation().language().equals("en");
        String candidate=english?"Candidate":"候选人";
        var replacements=new LinkedHashMap<String,String>();
        if(options.name()) add(replacements,content.name(),candidate);
        if(options.phone()) add(replacements,content.phone(),english?"[phone hidden]":"（电话已隐藏）");
        if(options.email()) add(replacements,content.email(),english?"[email hidden]":"（邮箱已隐藏）");
        if(options.location()) add(replacements,content.location(),english?"[location hidden]":"（位置已隐藏）");
        UnaryOperator<String> text=UnaryOperator.identity();
        if(options.matchingText()&&!replacements.isEmpty()) {
            // Match overlapping originals longest-first, once; replacement text is never reprocessed.
            var pattern=Pattern.compile(replacements.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(Pattern::quote).collect(java.util.stream.Collectors.joining("|")));
            text=value->pattern.matcher(value).replaceAll(match->Matcher.quoteReplacement(replacements.get(match.group())));
        }
        var rewrite=text;
        var sections=content.sections().stream().map(section->new ResumeDocument.Section(section.id(),section.type(),
            rewrite.apply(section.title()),section.visible(),section.pageBreakBefore(),section.entries().stream().map(entry->
                new ResumeDocument.Entry(entry.id(),rewrite.apply(entry.title()),rewrite.apply(entry.meta()),entry.bulleted(),
                    entry.bullets().stream().map(rewrite).toList())).toList())).toList();
        var redacted=new ResumeDocument.Content(options.name()?candidate:content.name(),rewrite.apply(content.headline()),
            options.email()?"":content.email(),options.phone()?"":content.phone(),options.location()?"":content.location(),sections);
        return new ResumeDocument(original.schemaVersion(),redacted,new ResumeDocument.Layout(layout.template(),layout.font(),
            layout.fontSize(),layout.lineHeight(),layout.sectionGapMm(),layout.marginMm(),layout.swapImages(),
            options.photo()?withoutImage(layout.photo()):layout.photo(),options.logo()?withoutImage(layout.logo()):layout.logo(),layout.presentation()));
    }
    private static void add(Map<String,String> replacements,String original,String replacement) {
        if(!original.isBlank()) replacements.putIfAbsent(original.strip(),replacement);
    }
    private static ResumeDraft.ImageSlot withoutImage(ResumeDraft.ImageSlot slot) {
        return new ResumeDraft.ImageSlot(null,false,slot.widthMm(),slot.heightMm(),slot.fit(),slot.quarterTurns(),
            slot.zoom(),slot.positionX(),slot.positionY());
    }
}
