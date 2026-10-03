import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Disposable lifecycle fixture: no database, Docker, user files or model calls. */
public class RuntimeFixture {
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    public static void main(String[] args)throws Exception {
        int port=Integer.parseInt(System.getenv("PORT"));
        Path jar=Path.of(RuntimeFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        String instance=UUID.randomUUID().toString();
        String body="{\"version\":\"fixture-1\",\"pid\":"+ProcessHandle.current().pid()+",\"instanceId\":"+quote(instance)
            +",\"jarPath\":"+quote(jar.toString())+",\"jarSha256\":"+quote(digest)+",\"port\":"+port
            +",\"dataDirectory\":"+quote(System.getenv("RESUME_DATA_DIR"))+",\"logsDirectory\":"+quote(System.getenv("RESUME_RUNTIME_LOG_DIR"))+"}";
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/api/runtime",exchange->{String response=Files.exists(jar.getParent().getParent().resolve("misreport-pid"))?body.replace("\"pid\":"+ProcessHandle.current().pid(),"\"pid\":"+(ProcessHandle.current().pid()+99)):body;byte[] bytes=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.createContext("/api/health",exchange->{byte[] bytes="{\"status\":\"ok\"}".getBytes();exchange.sendResponseHeaders(Files.exists(jar.getParent().getParent().resolve("fail-ready"))?503:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});
        server.createContext("/api/fixture/detach",exchange->{exchange.sendResponseHeaders(200,-1);exchange.close();new Thread(()->{try{Thread.sleep(100);}catch(InterruptedException ignored){}server.stop(0);}).start();});
        server.start();
        new Thread(()->{try{Thread.sleep(Long.MAX_VALUE);}catch(InterruptedException ignored){}}).start();
    }
}
