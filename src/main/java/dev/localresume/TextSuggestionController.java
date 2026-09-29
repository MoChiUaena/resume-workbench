package dev.localresume;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/ai")
public class TextSuggestionController {
    public record Apply(@NotNull UUID resumeId,@Min(1) long expectedRevision,@AssertTrue boolean confirmApply) {}
    private final TextSuggestions suggestions;
    public TextSuggestionController(TextSuggestions suggestions){this.suggestions=suggestions;}
    @PostMapping("/suggestions") public Object create(@Valid @RequestBody TextSuggestions.Request input){return suggestions.create(input);}
    @PostMapping("/suggestions/{id}/apply") public Object apply(@PathVariable UUID id,@Valid @RequestBody Apply input){return suggestions.apply(id,input.resumeId(),input.expectedRevision(),input.confirmApply());}
}
