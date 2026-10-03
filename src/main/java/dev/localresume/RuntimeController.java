package dev.localresume;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class RuntimeController {
    private final RuntimeInformation runtime;
    public RuntimeController(RuntimeInformation runtime){this.runtime=runtime;}
    @GetMapping("/api/runtime") public ResponseEntity<RuntimeInformation.View> runtime(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(runtime.view(request.getLocalPort()));
    }
}
