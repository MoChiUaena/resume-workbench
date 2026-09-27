package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Component
public class LocalFileStorage implements AttachmentStorage {
    private final Path root;
    private final ObjectMapper mapper;
    public LocalFileStorage(@Value("${resume.data-dir}") String data, ObjectMapper mapper) {
        this.root = Path.of(data).toAbsolutePath().resolve("attachments"); this.mapper = mapper;
    }
    private Path directory(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new ApiException("ASSET_NOT_FOUND", "图片不存在，请重新导入。", 404);
        return root.resolve(id);
    }
    @Override public void save(ImageService.Asset asset, byte[] original, byte[] normalized) throws IOException {
        Files.createDirectories(root);
        Path temp = root.resolve(".upload-" + UUID.randomUUID());
        try {
            Files.createDirectory(temp);
            Files.write(temp.resolve("original." + asset.format().toLowerCase()), original);
            Files.write(temp.resolve("image.png"), normalized);
            mapper.writeValue(temp.resolve("metadata.json").toFile(), asset);
            Files.move(temp, directory(asset.id()), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temp)) {
                try (var files = Files.list(temp)) { for (Path p : files.toList()) Files.deleteIfExists(p); }
                Files.deleteIfExists(temp);
            }
        }
    }
    @Override public ImageService.Asset metadata(String id) throws IOException {
        Path path = directory(id).resolve("metadata.json");
        if (!Files.isRegularFile(path)) throw new ApiException("ASSET_NOT_FOUND", "图片不存在，请重新导入。", 404);
        return mapper.readValue(path.toFile(), ImageService.Asset.class);
    }
    @Override public byte[] image(String id) throws IOException {
        metadata(id);
        return Files.readAllBytes(directory(id).resolve("image.png"));
    }
}
