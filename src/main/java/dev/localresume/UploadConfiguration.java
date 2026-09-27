package dev.localresume;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.*;
import org.springframework.util.unit.DataSize;

@Configuration
public class UploadConfiguration {
    @Bean MultipartConfigElement multipartConfigElement(@Value("${resume.max-upload-bytes}") long maxBytes) {
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(maxBytes));
        factory.setMaxRequestSize(DataSize.ofBytes(maxBytes + 524288));
        return factory.createMultipartConfig();
    }
}
