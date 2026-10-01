package dev.localresume;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

/** Immutable file evidence produced by the trusted storage scan. */
public final class QuarantineFiles {
    private QuarantineFiles() {}
    public record Entry(String path,long bytes,String sha256,Instant modified) {}
    public record Target(String kind,String id,long bytes,List<Entry> files) {
        public Target { if(files!=null)files=Collections.unmodifiableList(new ArrayList<>(files)); }
    }
    public record Plan(String id,String requestDigest,String previewDigest,Instant createdAt,List<Target> items) {
        public Plan { if(items!=null)items=Collections.unmodifiableList(new ArrayList<>(items)); }
    }
}
