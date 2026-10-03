package dev.localresume;

import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/resumes/{resumeId}/job-reports")
public class JobReportHistoryController {
    private final JobReportHistory history;
    public JobReportHistoryController(JobReportHistory history){this.history=history;}
    @PostMapping public Object save(@PathVariable UUID resumeId,@RequestBody JobReportHistory.Save input){return history.save(resumeId,input);}
    @GetMapping public Object list(@PathVariable UUID resumeId,@RequestParam(defaultValue="0") int page){return history.history(resumeId,page);}
    @GetMapping("/{id}") public Object get(@PathVariable UUID resumeId,@PathVariable UUID id){return history.get(resumeId,id);}
    @DeleteMapping("/{id}") public Object delete(@PathVariable UUID resumeId,@PathVariable UUID id){return history.delete(resumeId,id);}
}
