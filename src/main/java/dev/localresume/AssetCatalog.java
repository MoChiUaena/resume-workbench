package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class AssetCatalog {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AttachmentStorage storage;
    public AssetCatalog(JdbcTemplate jdbc, ObjectMapper mapper, AttachmentStorage storage) {
        this.jdbc=jdbc; this.mapper=mapper; this.storage=storage;
    }
    public void register(ImageService.Asset asset) {
        try { jdbc.update("INSERT INTO attachments(id,metadata) VALUES (?,?::jsonb) ON CONFLICT(id) DO NOTHING", UUID.fromString(asset.id()), mapper.writeValueAsString(asset)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public void ensure(String id) {
        if (id==null) return;
        try { register(storage.metadata(id)); }
        catch (java.io.IOException e) { throw new ApiException("ASSET_READ_FAILED","本地图片读取失败，请检查数据目录。",500); }
    }
}
