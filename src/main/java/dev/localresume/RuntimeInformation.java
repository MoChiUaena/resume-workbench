package dev.localresume;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

@Component
public class RuntimeInformation {
    public record View(String version, String buildTime, long pid, String startedAt, String instanceId,
                       String javaVersion, String jarPath, String jarSha256, String dataDirectory,
                       String logsDirectory, int port) {}
    private final String version, instanceId=UUID.randomUUID().toString(), jarPath, jarSha256, dataDirectory, logsDirectory;
    private final Instant buildTime, startedAt=ProcessHandle.current().info().startInstant().orElseGet(Instant::now);

    public RuntimeInformation(ObjectProvider<BuildProperties> builds,
                              @Value("${resume.data-dir}") String data,
                              @Value("${resume.runtime-log-dir:}") String logs) {
        var build=builds.getIfAvailable();
        version=build==null?"development":build.getVersion();buildTime=build==null?null:build.getTime();
        var source=new ApplicationHome(LocalResumeApplication.class).getSource();
        var path=source==null?null:source.toPath().toAbsolutePath().normalize();
        jarPath=path!=null&&Files.isRegularFile(path)?path.toString():null;
        jarSha256=jarPath==null?null:digest(path);
        dataDirectory=Path.of(data).toAbsolutePath().normalize().toString();
        logsDirectory=logs==null||logs.isBlank()?null:Path.of(logs).toAbsolutePath().normalize().toString();
    }

    private static String digest(Path path) {
        try(InputStream stream=Files.newInputStream(path)) {
            var hash=MessageDigest.getInstance("SHA-256");var buffer=new byte[65536];int count;
            while((count=stream.read(buffer))!=-1)hash.update(buffer,0,count);
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.io.IOException|NoSuchAlgorithmException e) {return null;}
    }

    public View view(int port) {
        return new View(version,buildTime==null?null:buildTime.toString(),ProcessHandle.current().pid(),startedAt.toString(),instanceId,
            System.getProperty("java.version"),jarPath,jarSha256,dataDirectory,logsDirectory,port);
    }
}
