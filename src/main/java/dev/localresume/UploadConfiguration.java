package dev.localresume;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.*;
import org.springframework.util.unit.DataSize;

@Configuration
public class UploadConfiguration {
    @Bean MultipartConfigElement multipartConfigElement(@Value("${resume.max-upload-bytes}") long maxBytes,
        @Value("${resume.max-backup-bytes}") long maxBackupBytes) {
        var factory = new MultipartConfigFactory();
        long envelope = Math.max(maxBytes, maxBackupBytes);
        factory.setMaxFileSize(DataSize.ofBytes(envelope));
        factory.setMaxRequestSize(DataSize.ofBytes(envelope + 524288));
        return factory.createMultipartConfig();
    }
}
