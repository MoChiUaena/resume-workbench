package dev.localresume;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class BackupCatalogTest {
    @TempDir Path data;
    @Test void catalogOrdersByArchiveCreationNotCopyTimeAndPaginatesWithoutDroppingItems()throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());var catalog=new BackupCatalog(data,mapper,10000);Files.createDirectories(data.resolve("backups"));
        var identifiers=new ArrayList<String>();Instant first=Instant.parse("2026-09-29T00:00:00Z");
        for(int index=0;index<23;index++){
            String id=UUID.randomUUID().toString();identifiers.add(id);Path zip=data.resolve("backups/"+id+".zip");Files.writeString(zip,"synthetic bytes");
            var created=new BackupService.Created(id,Files.size(zip),1,1,0,0,first.plusSeconds(index));catalog.save(new BackupCatalog.Stored(1,"manual",created,BackupCatalog.hash(zip),"a".repeat(64)));
        }
        Files.setLastModifiedTime(data.resolve("backups/"+identifiers.getFirst()+".zip"),java.nio.file.attribute.FileTime.from(first.plusSeconds(9999)));
        var newest=catalog.history(0);var older=catalog.history(1);assertThat(newest.items()).hasSize(20);assertThat(newest.hasMore()).isTrue();assertThat(older.items()).hasSize(3);assertThat(older.hasMore()).isFalse();
        assertThat(newest.items().getFirst().backup().id()).isEqualTo(identifiers.getLast());assertThat(older.items().getLast().backup().id()).isEqualTo(identifiers.getFirst());
        var all=new ArrayList<>(newest.items());all.addAll(older.items());assertThat(all.stream().map(item->item.backup().id()).distinct()).hasSize(23);
    }
}
