package dev.localresume;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** File I/O boundary; QuarantineStore retains all path checks and journal ownership. */
class QuarantineJournalIo {
    void write(FileChannel channel,ByteBuffer bytes)throws IOException {channel.write(bytes);}
    void force(FileChannel channel)throws IOException {channel.force(true);}
    void replace(Path source,Path destination)throws IOException {
        Files.move(source,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
