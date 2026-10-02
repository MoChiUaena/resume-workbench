package dev.localresume;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/job-matches")
public class JobMatchController {
    private final JobMatches matches;
    public JobMatchController(JobMatches matches){this.matches=matches;}
    @PostMapping("/preview") public JobMatches.Preview preview(@RequestBody JobMatches.Request input){return matches.preview(input);}
    @PostMapping public JobMatches.Report generate(@RequestBody JobMatches.Generate input){return matches.generate(input);}
}
