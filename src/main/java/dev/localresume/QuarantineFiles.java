package dev.localresume;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
    private static final long MAX_BYTES=1024L*1024*1024;
    static void validate(QuarantineFiles.Plan plan){
        require(plan!=null&&uuid(plan.id())&&sha(plan.requestDigest())&&sha(plan.previewDigest())&&plan.createdAt()!=null&&plan.items()!=null&&!plan.items().isEmpty()&&plan.items().size()<=100);
        var targets=new HashSet<String>();long total=0;
        for(var target:plan.items()){
            require(target!=null&&target.kind()!=null&&Set.of("image","pdf").contains(target.kind())&&uuid(target.id())&&target.files()!=null&&target.bytes()>0&&target.bytes()<=MAX_BYTES&&targets.add(target.kind()+"/"+target.id()));
            var paths=new HashSet<String>();long size=0;
            for(var file:target.files()){
                require(file!=null&&file.path()!=null&&file.bytes()>0&&file.bytes()<=MAX_BYTES&&sha(file.sha256())&&file.modified()!=null&&paths.add(file.path()));
                size+=file.bytes();require(size<=MAX_BYTES);
            }
            require(size==target.bytes());
            if(target.kind().equals("pdf"))require(paths.equals(Set.of("exports/"+target.id()+".pdf","exports/"+target.id()+".json")));
            else{
                String prefix="attachments/"+target.id()+"/";require(paths.size()==3&&paths.contains(prefix+"metadata.json")&&paths.contains(prefix+"image.png")
                    &&paths.stream().filter(p->Set.of(prefix+"original.png",prefix+"original.jpeg",prefix+"original.webp").contains(p)).count()==1);
            }
            total+=size;require(total<=MAX_BYTES);
        }
    }
    static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static void require(boolean valid){if(!valid)throw new ApiException("QUARANTINE_INVALID","暂存请求或文件清单无效。",422);}
}
