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
}
