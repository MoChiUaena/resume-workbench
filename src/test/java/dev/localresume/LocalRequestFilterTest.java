package dev.localresume;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;

class LocalRequestFilterTest {
    @Test void blocksCrossSiteAndReboundHostAndMissingMutationHeader() throws Exception {
        for (String attack : new String[]{"origin", "host", "header", "fetch-site"}) {
            var request = new MockHttpServletRequest("POST", "/api/previews");
            request.setServerName("127.0.0.1"); request.setServerPort(18765);
            if (!attack.equals("header")) request.addHeader("X-Local-Resume", "1");
            if (attack.equals("origin")) request.addHeader("Origin", "https://attacker.invalid");
            if (attack.equals("host")) request.setServerName("attacker.invalid");
            if (attack.equals("fetch-site")) request.addHeader("Sec-Fetch-Site", "cross-site");
            var response = new MockHttpServletResponse();
            new LocalRequestFilter().doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(403);
        }
    }
    @Test void allowsLocalBrowserRequest() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/previews");
        request.setServerName("127.0.0.1"); request.setServerPort(18765);
        request.addHeader("X-Local-Resume", "1"); request.addHeader("Origin", "http://127.0.0.1:18765");
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        new LocalRequestFilter().doFilter(request, response, chain);
        assertThat(chain.getRequest()).isNotNull();
    }
    @Test void readOnlyProviderCallDoesNotHoldTheWorkspaceLease()throws Exception{
        for(String route:java.util.List.of("/api/ai/suggestions","/api/ai/job-matches")){
            var gate=new WorkspaceGate();var request=new MockHttpServletRequest("POST",route);request.setServerName("127.0.0.1");request.setServerPort(18765);request.addHeader("X-Local-Resume","1");
            new LocalRequestFilter(gate).doFilter(request,new MockHttpServletResponse(),(req,res)->{
                var worker=java.util.concurrent.Executors.newSingleThreadExecutor();try{worker.submit(()->{try(var lease=gate.exclusive()){return true;}}).get(1,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}finally{worker.shutdownNow();}
            });
        }
    }
    @Test void storageMutationAcquiresItsOwnExclusiveLeaseWithoutFilterLockUpgrade()throws Exception {
        var gate=new WorkspaceGate();var request=new MockHttpServletRequest("POST","/api/storage/quarantine");request.setServerName("127.0.0.1");request.addHeader("X-Local-Resume","1");
        new LocalRequestFilter(gate).doFilter(request,new MockHttpServletResponse(),(req,res)->{
            var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
            try{assertThat(worker.submit(()->{try(var lease=gate.exclusive()){return true;}}).get(1,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
            catch(Exception e){throw new RuntimeException(e);}finally{worker.shutdownNow();}
        });
    }
}
