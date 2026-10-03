package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;

/** A small allowlisted archive format. No ZIP-provided path is trusted. */
public final class BackupArchive {
    public static final String FORMAT = "resume-workbench-backup";
    public static final int VERSION = 1;
    public static final int MAX_FILES = 10000;
    public static final long MAX_ENTRY = 134217728L;
    private static final String UUID_PATTERN = "[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private final ObjectMapper mapper;
    private final long maxBytes;
    public BackupArchive(ObjectMapper mapper, long maxBytes) { this.mapper = mapper; this.maxBytes = maxBytes; }
    public record FileEntry(String path, long bytes, String sha256) {}
    public record Manifest(String format, int formatVersion, int documentSchemaVersion, Instant createdAt, List<FileEntry> files) {}
    public record Source(Path path, byte[] bytes, String expectedSha256) {
        public static Source file(Path path, String hash) { return new Source(path, null, hash); }
        public static Source json(byte[] bytes) { return new Source(null, bytes, null); }
        InputStream open() throws IOException { return path != null ? Files.newInputStream(path) : new ByteArrayInputStream(bytes); }
    }
    public record Staged(Path root, Manifest manifest) implements AutoCloseable {
        public Path file(String name) { validatePath(name); return root.resolve(name); }
        @Override public void close() { try { removeTree(root); } catch (IOException ignored) { /* A committed restore must not be undone by temporary cleanup. */ } }
    }

    public Manifest write(Path destination, Map<String, Source> sources) throws IOException {
        if (sources.size() > MAX_FILES) throw invalid("备份文件过多，请拆分工作区。");
        var entries = new ArrayList<FileEntry>();
        long total = 0;
        try (var out = new ZipOutputStream(Files.newOutputStream(destination))) {
            out.setLevel(Deflater.BEST_SPEED);
            for (var name : new TreeSet<>(sources.keySet())) {
                validatePath(name);
                var source = sources.get(name);
                out.putNextEntry(new ZipEntry(name));
                var digest = digest();
                long size;
                try (var in = source.open()) { size = copy(in, out, digest, entryLimit(name, total)); }
                total += size;
                String hash = HexFormat.of().formatHex(digest.digest());
                if (source.expectedSha256() != null && !hash.equals(source.expectedSha256()))
                    throw invalid("本地文件与元数据校验值不一致，请保留原文件并检查数据目录。");
                entries.add(new FileEntry(name, size, hash)); out.closeEntry();
            }
            var manifest = new Manifest(FORMAT, VERSION, BackupData.SCHEMA_VERSION, Instant.now(), List.copyOf(entries));
            byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
            if (manifestBytes.length > 1048576) throw invalid("备份清单过大，请拆分工作区。");
            out.putNextEntry(new ZipEntry("manifest.json"));
            out.write(manifestBytes); out.closeEntry();
            out.finish();
            if (Files.size(destination) > maxBytes)
                throw new ApiException("BACKUP_TOO_LARGE", "备份 ZIP 大小超限，请拆分工作区。", 413);
            return manifest;
        } catch (Exception e) {
            Files.deleteIfExists(destination);
            if (e instanceof ApiException a) throw a;
            if (e instanceof IOException io) throw io;
            throw new IOException(e);
        }
    }

    public Staged read(InputStream input, Path parent) throws IOException {
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, "restore-");
        var actual = new HashMap<String, FileEntry>();
        Manifest manifest = null;
        boolean seenManifest = false;
        long total = 0;
        try (var zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) throw invalid("备份包含不支持的目录项。");
                if (name.equals("manifest.json")) {
                    if (seenManifest) throw invalid("备份清单重复。");
                    seenManifest = true;
                    byte[] bytes = zip.readNBytes(1048577);
                    if (bytes.length > 1048576) throw invalid("备份清单过大。");
                    manifest = mapper.readValue(bytes, Manifest.class);
                } else {
                    validatePath(name);
                    if (actual.containsKey(name) || actual.size() >= MAX_FILES) throw invalid("备份文件重复或过多。");
                    Path target = stage.resolve(name).normalize();
                    if (!target.startsWith(stage)) throw invalid("备份路径无效。");
                    Files.createDirectories(target.getParent());
                    var digest = digest();
                    long size;
                    try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                        size = copy(zip, out, digest, entryLimit(name, total));
                    }
                    total += size;
                    actual.put(name, new FileEntry(name, size, HexFormat.of().formatHex(digest.digest())));
                }
                zip.closeEntry();
            }
            if (manifest == null || !FORMAT.equals(manifest.format()) || manifest.formatVersion() != VERSION || !BackupData.supportsSchema(manifest.documentSchemaVersion()))
                throw new ApiException("BACKUP_VERSION_UNSUPPORTED", "备份格式或版本不受支持，请使用匹配的应用版本。", 422);
            if (manifest.files() == null || manifest.files().size() != actual.size()) throw invalid("备份清单与文件数量不一致。");
            var unique = new HashSet<String>();
            for (var file : manifest.files()) {
                if (file == null) throw invalid("备份清单无效。");
                validatePath(file.path());
                if (!unique.add(file.path()) || !file.equals(actual.get(file.path()))) throw invalid("备份文件缺失、损坏或校验失败。");
            }
            if (!actual.containsKey("workspace.json") || !actual.containsKey("settings.json")) throw invalid("备份缺少工作区或设置数据。");
            return new Staged(stage, manifest);
        } catch (Exception e) {
            removeTree(stage);
            if (e instanceof ApiException a) throw a;
            throw new ApiException("BACKUP_INVALID", "备份文件无法读取或校验失败，请重新选择完整的 ZIP 文件。", 422);
        }
    }
    static void validatePath(String name) {
        boolean allowed = name != null && (name.equals("workspace.json") || name.equals("settings.json")
            || name.matches("attachments/" + UUID_PATTERN + "/(?:image\\.png|metadata\\.json|original\\.(?:jpeg|png|webp))")
            || name.matches("exports/" + UUID_PATTERN + "\\.(?:pdf|json)")
            || name.matches("job-reports/" + UUID_PATTERN + "\\.json"));
        if (!allowed) throw invalid("备份包含不允许的文件路径。");
    }
    static long copy(InputStream in, OutputStream out, MessageDigest digest, long limit) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new ApiException("BACKUP_TOO_LARGE", "备份解压大小超限，请拆分工作区。", 413);
            out.write(buffer, 0, read); digest.update(buffer, 0, read);
        }
        return total;
    }
    private long entryLimit(String name,long written) {
        long kind=name.equals("workspace.json")?16777216L:name.equals("settings.json")?65536L:name.startsWith("job-reports/")?JobReportSnapshot.MAX_BYTES:name.endsWith(".json")?1048576L:MAX_ENTRY;
        return Math.min(kind,maxBytes-written);
    }
    static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); } }
    static ApiException invalid(String message) { return new ApiException("BACKUP_INVALID", message, 422); }
    static void removeTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
