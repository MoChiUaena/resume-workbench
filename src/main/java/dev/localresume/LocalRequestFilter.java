package dev.localresume;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** Loopback binding + host checks + same-origin requests; no public hosting in Stage A. */
@Component
public class LocalRequestFilter extends OncePerRequestFilter {
    private final WorkspaceGate gate;
    public LocalRequestFilter() { this(new WorkspaceGate()); }
    @org.springframework.beans.factory.annotation.Autowired
    public LocalRequestFilter(WorkspaceGate gate) { this.gate = gate; }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        var hosts = Set.of("127.0.0.1", "localhost", "[::1]");
        String origin = req.getHeader("Origin");
        String expected = "http://" + req.getServerName() + ":" + req.getServerPort();
        boolean mutation = !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
        if (!hosts.contains(req.getServerName()) || "cross-site".equals(req.getHeader("Sec-Fetch-Site"))
            || (origin != null && !origin.equals(expected))
            || (mutation && !"1".equals(req.getHeader("X-Local-Resume")))) {
            res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":\"LOCAL_REQUEST_REQUIRED\",\"message\":\"请从本地工作台发起操作。\"}"); return;
        }
        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("Referrer-Policy", "no-referrer");
        res.setHeader("X-Frame-Options", "SAMEORIGIN");
        res.setHeader("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; font-src 'self'; script-src 'self'; connect-src 'self'; frame-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'; form-action 'self'");
        if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
        // Provider calls only read a source snapshot. Do not hold the workspace lock during network I/O.
        boolean providerCall=req.getRequestURI().equals("/api/ai/suggestions")||req.getRequestURI().matches("/api/models/profiles/[0-9a-f-]{36}/test");
        boolean storageCall=req.getRequestURI().equals("/api/storage")||req.getRequestURI().startsWith("/api/storage/");
        try (WorkspaceGate.Lease lease = mutation && !providerCall && !storageCall && !req.getRequestURI().startsWith("/api/backups") ? gate.mutation() : null) {
            chain.doFilter(req, res);
        } catch (ApiException e) {
            if (res.isCommitted()) throw e;
            res.setStatus(e.status); res.setContentType("application/json;charset=UTF-8");
            res.getWriter().write("{\"code\":\"WORKSPACE_BUSY\",\"message\":\"备份、恢复或文件检查正在进行，本次操作尚未完成，请稍后重试。\"}");
        }
    }
}
