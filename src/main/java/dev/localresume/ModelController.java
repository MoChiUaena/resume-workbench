package dev.localresume;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/models")
public class ModelController {
    private final ModelSettings settings;
    private final ModelGateway gateway;
    private final Semaphore tests=new Semaphore(1);
    public ModelController(ModelSettings settings,ModelGateway gateway){this.settings=settings;this.gateway=gateway;}
    @GetMapping public Object list(){return settings.view();}
    @PostMapping("/profiles") public Object create(@Valid @RequestBody ModelSettings.Edit body){return settings.save(null,body);}
    @PutMapping("/profiles/{id}") public Object edit(@PathVariable String id,@Valid @RequestBody ModelSettings.Edit body){return settings.save(id,body);}
    @DeleteMapping("/profiles/{id}") public Object remove(@PathVariable String id,@Valid @RequestBody ModelSettings.Revision body){return settings.remove(id,body.expectedRevision());}
    @PostMapping("/profiles/{id}/default") public Object select(@PathVariable String id,@Valid @RequestBody ModelSettings.Revision body){return settings.select(id,body.expectedRevision());}
    @PutMapping("/enabled") public Object enable(@Valid @RequestBody ModelSettings.Enable body){return settings.enable(body);}
    @PostMapping("/profiles/{id}/test") public Object test(@PathVariable String id,@Valid @RequestBody ModelSettings.Revision body){
        if(!tests.tryAcquire())throw new ApiException("MODEL_BUSY","正在测试连接，请稍后重试。",423);
        try{var profile=settings.selected(id,body.expectedRevision(),false);long start=System.nanoTime();gateway.test(profile,settings.credential(profile));return Map.of("connected",true,"elapsedMs",(System.nanoTime()-start)/1000000);}
        finally{tests.release();}
    }
}
