package dev.localresume;

import java.io.IOException;

public interface AttachmentStorage {
    void save(ImageService.Asset asset, byte[] original, byte[] normalized) throws IOException;
    ImageService.Asset metadata(String id) throws IOException;
    byte[] image(String id) throws IOException;
    byte[] original(String id) throws IOException;
    void delete(String id) throws IOException;
}
