package dev.localresume;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BackupData(int schemaVersion, List<SavedResume> resumes, List<SavedVersion> versions,
                         List<ImageService.Asset> attachments, List<ExportService.Export> exports,
                         List<SavedJobReport> jobReports) {
    public static final int SCHEMA_VERSION=5;
    public static boolean supportsSchema(int version){return version>=2&&version<=SCHEMA_VERSION;}
    public BackupData {
        if(schemaVersion<SCHEMA_VERSION&&jobReports==null)jobReports=List.of();
    }
    public record SavedJobReport(UUID id,UUID resumeId,String label,long sourceRevision,Instant createdAt,String sha256) {}
    public record SavedResume(UUID id, String title, ResumeDocument document, long revision,
                              Instant createdAt, Instant updatedAt) {}
    public record SavedVersion(UUID id, UUID resumeId, String title, String label,
                               ResumeDocument document, long sourceRevision, Instant createdAt) {}
    public record Settings(int schemaVersion, long maxUploadBytes, long maxPixels) {}
}
