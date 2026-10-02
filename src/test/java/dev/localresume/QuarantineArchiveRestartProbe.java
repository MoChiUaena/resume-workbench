package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;

/** Small-budget separate-JVM fixture invoked by the final cache accounting check. */
public final class QuarantineArchiveRestartProbe {
    public static void main(String[] args)throws Exception {
        Path data=Path.of(args[0]),cache=Path.of(args[1]);String id=args[2];Instant now=Instant.parse(args[3]);
        var mapper=new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String pdf="22222222-2222-4222-8222-222222222222";var entries=new ArrayList<QuarantineFiles.Entry>();
        for(String name:List.of("exports/"+pdf+".pdf","exports/"+pdf+".json")){
            Path file=data.resolve("quarantine").resolve(id).resolve("payload").resolve(name);Files.createDirectories(file.getParent());
            Files.writeString(file,name.endsWith(".pdf")?"%PDF-probe":"{\"probe\":true}");Files.setLastModifiedTime(file,FileTime.from(now.minusSeconds(10)));
            entries.add(new QuarantineFiles.Entry(name,Files.size(file),ImageService.sha(Files.readAllBytes(file)),Files.getLastModifiedTime(file).toInstant()));
        }
        var plan=new QuarantineFiles.Plan(id,"a".repeat(64),"b".repeat(64),now,List.of(new QuarantineFiles.Target("pdf",pdf,entries.stream().mapToLong(QuarantineFiles.Entry::bytes).sum(),entries)));
        var archive=new QuarantineArchive(data,cache,mapper,Clock.fixed(Instant.parse(args[3]),ZoneOffset.UTC),Long.parseLong(args[4]),Integer.parseInt(args[5]));
        try{var result=archive.create(plan,args[6]);System.out.println("CREATED "+result.path()+" "+result.bytes());}
        catch(ApiException e){System.out.println("REJECTED "+e.code);}
        try(var paths=Files.walk(cache)){
            var files=paths.filter(Files::isRegularFile).toList();long bytes=0;for(var file:files)bytes+=Files.size(file);
            System.out.println("PHYSICAL "+bytes+" FILES "+files.size());
        }
    }
}
